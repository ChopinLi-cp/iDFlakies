package edu.illinois.cs.dt.tools.detection;

import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.dt.tools.utility.PathManager;
import edu.illinois.cs.dt.tools.utility.SootAnalysis;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.framework.TestFramework;
import edu.illinois.cs.testrunner.util.ProjectWrapper;
import edu.illinois.starts.data.ZLCFormat;
import edu.illinois.starts.enums.DependencyFormat;
import edu.illinois.starts.helpers.*;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.maven.AgentLoader;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import edu.illinois.yasgl.DirectedGraph;
import edu.illinois.yasgl.DirectedGraphBuilder;
import edu.illinois.yasgl.Edge;
import edu.illinois.yasgl.GraphUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import soot.SootMethod;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static edu.illinois.starts.constants.StartsConstants.*;

public class IncDetectorPlugin extends DetectorPlugin {

    /**
     * The directory in which to store STARTS artifacts that are needed between runs.
     */
    protected String artifactsDir;

    protected ClassLoader loader;

    /**
     * Set this to "false" to disable smart hashing, i.e., to *not* strip
     * Bytecode files of debug info prior to computing checksums. See the "Smart
     * Checksums" Sections in the Ekstazi paper:
     * http://dl.acm.org/citation.cfm?id=2771784
     */
    @Parameter(property = "cleanBytes", defaultValue = TRUE)
    protected boolean cleanBytes;

    /**
     * Allows to switch the format in which we want to store the test dependencies.
     * A full list of what we currently support can be found in
     * @see edu.illinois.starts.enums.DependencyFormat
     */
    @Parameter(property = "depFormat", defaultValue = "ZLC")
    protected DependencyFormat depFormat;

    /**
     * Set this to "false" to not filter out "sun.*" and "java.*" classes from jdeps parsing.
     */
    @Parameter(property = "filterLib", defaultValue = TRUE)
    protected boolean filterLib;

    /**
     * Path to directory that contains the result of running jdeps on third-party
     * and standard library jars that an application may need, e.g., those in M2_REPO.
     */
    @Parameter(property = "gCache", defaultValue = "${basedir}${file.separator}jdeps-cache")
    protected String graphCache;

    /**
     * Output filename for the graph, if printGraph == true.
     */
    @Parameter(defaultValue = "graph", readonly = true, required = true)
    protected String graphFile;

    protected List<Pair> jarCheckSums = null;

    protected Set<String> nonAffectedTests;

    /**
     * Set this to "false" to not print the graph obtained from jdeps parsing.
     * When "true" the graph is written to file after the run.
     */
    @Parameter(property = "printGraph", defaultValue = TRUE)
    protected boolean printGraph;

    private Classpath sureFireClassPath;

    private static final String TARGET = "target";
    /**
     * Set this to "false" to not add jdeps edges from 3rd party-libraries.
     */
    @Parameter(property = "useThirdParty", defaultValue = FALSE)
    protected boolean useThirdParty;

    /**
     * Set this to "true" to update test dependencies on disk. The default value of
     * "false" is useful for "dry runs" where one may want to see the affected
     * tests, without updating test dependencies.
     */
    @Parameter(property = "updateChecksums", defaultValue = FALSE)
    private boolean updateChecksums;

    /**
     * Format of the ZLC dependency file deps.zlc
     * Set to "INDEXED" to store indices of tests
     * Set to "PLAIN_TEXT" to store full URLs of tests
     */
    @Parameter(property = "zlcFormat", defaultValue = "PLAIN_TEXT")
    protected ZLCFormat zlcFormat;

    protected boolean selectMore;

    protected boolean selectBasedOnMethodsCall;

    protected boolean selectBasedOnMethodsCallUpgrade;

    protected boolean removeBasedOnMethodsCall;

    protected boolean fineGranularity;

    protected boolean detectOrNot;

    protected int distance;

    protected String ekstaziSelectedTestsFile;

    protected String ekstaziDependenciesFile;

    protected boolean ekstaziOrNot;

    private Set<String> affectedTestClasses;

    protected List<String> selectedTests;

    private static Set<String> immutableList;

    @Override
    public void execute(final ProjectWrapper project) {
        final ErrorLogger logger = new ErrorLogger(project);
        logger.runAndLogError(() -> defineSettings(logger, project));
        if(this.runner == null) {
            return;
        }
        this.coordinates = logger.coordinates();

        long startTime = System.currentTimeMillis();
        try {
            affectedTestClasses = computeAffectedTests(project);
            if (!detectOrNot) {
                System.out.println("Not detect flaky tests at the first run");
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        timing(startTime);

        startTime = System.currentTimeMillis();
        logger.runAndLogError(() -> detectorExecute(logger, project, moduleRounds(coordinates)));
        timing(startTime);
    }

    // from SelectMojo
    private Set<String> computeAffectedTests(ProjectWrapper project) throws IOException, MojoExecutionException, ClassNotFoundException {
        if (ekstaziOrNot) {
            return computeEkstaziAffectedTests(project);
        }

        String cpString = Writer.pathToString(sureFireClassPath.getClassPath());
        List<String> sfPathElements = getCleanClassPath(cpString);

        // setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(project, this.runner.framework()));
        List<String> allTestsForLoadables = new LinkedList<>();
        allTests.addAll(allTests);
        Set<String> affectedTests = new HashSet<>(allTests);

        // System.out.println("SAME?: " + isSameClassPath(sfPathElements) + " " + hasSameJarChecksum(sfPathElements));
        // System.out.println(sfPathElements);
        boolean selectAll = false;
        if (!isSameClassPath(sfPathElements) || !hasSameJarChecksum(sfPathElements)) {
            // Force retestAll because classpath changed since last run
            // don't compute changed and non-affected classes
            dynamicallyUpdateExcludes(new ArrayList<String>());
            // Make nonAffected empty so dependencies can be updated
            nonAffectedTests = new HashSet<>();
            Writer.writeClassPath(cpString, artifactsDir);
            Writer.writeJarChecksums(sfPathElements, artifactsDir, jarCheckSums);
            // System.out.println("SELECTALL");
            selectAll = true;
        }

        nonAffectedTests = new HashSet<>();
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        // System.out.println("CHANGEDATA: " + data);
        nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        // System.out.println("NONAFFECTEDTESTS: " + nonAffectedTests.size() + " " + nonAffectedTests);
        List<String> excludePaths = Writer.fqnsToExcludePath(nonAffectedTests);
        dynamicallyUpdateExcludes(excludePaths);
        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            Logger.getGlobal().log(Level.INFO, STARS_RUN_STARS);
            Logger.getGlobal().log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        long startUpdate = System.currentTimeMillis();
        Loadables loadables = updateForNextRun(project, nonAffectedTests);
        long endUpdate = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        if ( selectAll || affectedTests.size() == allTests.size() ) {
            // System.out.println("SELECTALL");
            /* if (!removeBasedOnMethodsCall) {
                return allTests;
            }
            else {
                affectedTests = allTests;
            } */
            affectedTests = allTests;
        }

        if (!selectMore) {
            return affectedTests;
        }
        if (loadables == null) {
            // deal with cases like wildfly whose loadables is null and we could not deal with it more
            try {
                String sfPathString = Writer.pathToString(sureFireClassPath.getClassPath());
                loadables = prepareForNextRun(sfPathString, sureFireClassPath, allTestsForLoadables, new HashSet<>(), true);
            } catch (Exception e) {
                e.printStackTrace();
                return affectedTests;
            }
            // return affectedTests;
        }


        Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();

        // the dependency map from classes to their dependencies
        Map<String, Set<String>> reverseTransitiveClosure = getReverseClosure(transitiveClosure);

        Set<String> additionalTests = new HashSet<>();

        // iter through the affected tests and find what depends on
        Set<String> processedClasses = new HashSet<>();
        Set<String> affectedClasses = new HashSet<>();

        if (selectBasedOnMethodsCall) {
            DirectedGraphBuilder directedGraphBuilder = new DirectedGraphBuilder();
            DirectedGraph directedGraph = directedGraphBuilder.build();
            // this.affectedTestClasses = affectedTests;
            Map<String, List<String>> testClassToMethod = new HashMap<>();
            List<String> currentTests = super.getTests(project, this.runner.framework());

            // Using fine granularity (methods level)
            this.selectedTests = new ArrayList<>();

            String delimiter = this.runner.framework().getDelimiter();
            for (String test : currentTests) {
                String className = test.substring(0, test.lastIndexOf(delimiter));
                if (!testClassToMethod.containsKey(className)) {
                    testClassToMethod.put(className, new ArrayList<>());
                }
                testClassToMethod.get(className).add(test);
            }

            Map<String, Set<String>> SootAnalysisTestClassesToClassesSet = new HashMap<>();
            Map<String, Set<String>> SootAnalysisFieldsToAffectedClassesSet = new HashMap<>();
            for (String testClass : testClassToMethod.keySet()) {
                // System.out.println("GOING TO RUN SOOT ANALYSIS FOR TC: " + testClass);
                // long startTime = System.currentTimeMillis();
                // if (affectedTests.contains(testClass)) {
                //     continue;
                // }
                // System.out.println("CONTAIN OR NOT" + affectedTests.contains(testClass));
                if(affectedTests.contains(testClass)) {
                    Set<String> sootNewAffectedClasses = new HashSet<>();
                    Map<String, Set<String>> sootNewAffectedClassesToFields = new HashMap<>();
                    DirectedGraphBuilder directedGraphBuilderItem = new DirectedGraphBuilder();
                    // Combine these two methods to one method ...
                    // analysis(String cpString, String testClass, Map<> testClassToMethod) {
                    //    return analysisOnMethods(cpString, testClass, testClassToMethod, new ArrayList<>());
                    sootNewAffectedClassesToFields = SootAnalysis.analysis(cpString, testClass, testClassToMethod, fineGranularity, this.selectedTests, directedGraphBuilderItem);
                    sootNewAffectedClasses = sootNewAffectedClassesToFields.keySet();

                    DirectedGraph directedGraphItem = directedGraphBuilderItem.build();
                    Collection<Edge> edgeSet = directedGraphItem.getEdges();

                    for (Edge edge: edgeSet) {
                        directedGraphBuilder.addEdge(edge.getSource(), edge.getDestination());
                    }

                    for (String sootNewAffectedClass: sootNewAffectedClasses) {
                        Set<String> fieldValues = sootNewAffectedClassesToFields.get(sootNewAffectedClass);
                        for (String combinedfieldValue : fieldValues) {
                            // System.out.println("combinedfieldValue: " + combinedfieldValue);
                            String fieldValue = combinedfieldValue.substring(0, combinedfieldValue.indexOf("+"));
                            int length = Integer.valueOf(combinedfieldValue.substring(combinedfieldValue.indexOf("+"))).intValue();
                            String key = sootNewAffectedClass + "." + fieldValue;
                            Set<String> values = new HashSet<>();
                            if (SootAnalysisFieldsToAffectedClassesSet.containsKey(key)) {
                                values = SootAnalysisFieldsToAffectedClassesSet.get(key);
                            }
                            values.add(testClass + "+" + length);
                            SootAnalysisFieldsToAffectedClassesSet.put(key, values);
                        }
                    }

                    // System.out.println("END TIME: " + (System.currentTimeMillis() - startTime));
                    // System.out.println("THE SIZE of sootNewAffectedClasses: " + sootNewAffectedClasses.size());
                    if (sootNewAffectedClasses.isEmpty() && removeBasedOnMethodsCall) {
                        // System.out.println("REMOVE OPTIONS1");
                        affectedTests.remove(testClass);
                    } else {
                        affectedClasses.addAll(sootNewAffectedClasses);
                        SootAnalysisTestClassesToClassesSet.put(testClass, sootNewAffectedClasses);
                    }
                }
            }

            record_classes_stats(affectedClasses.size());
            // Map<String, Set<String>> additionalAffectedTestClassesSet = new HashMap<>();
            int filteredClassesCount = 0;
            for (String affectedClass : affectedClasses) {
                if (reverseTransitiveClosure.containsKey(affectedClass)) {
                    Set<String> additionalAffectedTestClasses = reverseTransitiveClosure.get(affectedClass);
                    // System.out.println("aATC: " + affectedClass + " SET: " + additionalAffectedTestClasses);
                    boolean additionalAffectedTestClassesFlag = false;
                    for (String additionalAffectedTestClass : additionalAffectedTestClasses) {
                        if(selectBasedOnMethodsCallUpgrade) {
                            Set<String> reachableClassesFromAdditionalAffectedTestClass = new HashSet<>();
                            Map<String, Set<String>> reachableClassesFromAdditionalAffectedTestClassToFields = new HashMap<>();
                            DirectedGraphBuilder directedGraphBuilderItem = new DirectedGraphBuilder();

                            if(SootAnalysisTestClassesToClassesSet.containsKey(additionalAffectedTestClass)) {
                                reachableClassesFromAdditionalAffectedTestClass = SootAnalysisTestClassesToClassesSet.get(additionalAffectedTestClass);
                            }
                            else {
                                // System.out.println("additionalAffectedTestClass: " + additionalAffectedTestClass);
                                reachableClassesFromAdditionalAffectedTestClassToFields = SootAnalysis.analysis(cpString, additionalAffectedTestClass, testClassToMethod, fineGranularity, this.selectedTests, directedGraphBuilderItem);
                                reachableClassesFromAdditionalAffectedTestClass = reachableClassesFromAdditionalAffectedTestClassToFields.keySet();

                                DirectedGraph directedGraphItem = directedGraphBuilderItem.build();
                                Collection<Edge> edgeSet = directedGraphItem.getEdges();
                                for (Edge edge: edgeSet) {
                                    directedGraphBuilder.addEdge(edge.getSource(), edge.getDestination());
                                }

                                for (String reachableClassFromAdditionalAffectedTestClass: reachableClassesFromAdditionalAffectedTestClass) {
                                    Set<String> fieldValues = reachableClassesFromAdditionalAffectedTestClassToFields.get(reachableClassFromAdditionalAffectedTestClass);
                                    for (String combinedfieldValue : fieldValues) {
                                        String fieldValue = combinedfieldValue.substring(0, combinedfieldValue.indexOf("+"));
                                        int length = Integer.valueOf(combinedfieldValue.substring(combinedfieldValue.indexOf("+"))).intValue();
                                        String key = reachableClassFromAdditionalAffectedTestClass + "." + fieldValue;
                                        Set<String> values = new HashSet<>();
                                        if (SootAnalysisFieldsToAffectedClassesSet.containsKey(key)) {
                                            values = SootAnalysisFieldsToAffectedClassesSet.get(key);
                                        }
                                        values.add(additionalAffectedTestClass + "+" + length);
                                        SootAnalysisFieldsToAffectedClassesSet.put(key, values);
                                    }
                                }

                                // System.out.println("reachableClassesFromAdditionalAffectedTestClass: " + reachableClassesFromAdditionalAffectedTestClass);
                                // remove the test class that could not reach any classes that contain static fields
                                // System.out.println("THE SIZE of sootNewAffectedClasses: " + reachableClassesFromAdditionalAffectedTestClass.size());
                                if (reachableClassesFromAdditionalAffectedTestClass.isEmpty() && removeBasedOnMethodsCall) {
                                    // System.out.println("REMOVE OPTIONS2");
                                    additionalTests.remove(additionalAffectedTestClass);
                                    affectedTests.remove(additionalAffectedTestClass);
                                }
                                SootAnalysisTestClassesToClassesSet.put(additionalAffectedTestClass, reachableClassesFromAdditionalAffectedTestClass);
                            }
                            if (reachableClassesFromAdditionalAffectedTestClass.contains(affectedClass)) {
                                // System.out.println("additionalAffectedTestClass: " + additionalAffectedTestClass);
                                additionalTests.add(additionalAffectedTestClass);
                                additionalAffectedTestClassesFlag = true;
                            }
                        }
                        else {
                            // System.out.println("additionalAffectedTestClass: " + additionalAffectedTestClass);
                            additionalTests.add(additionalAffectedTestClass);
                            additionalAffectedTestClassesFlag = true;
                        }
                    }
                    if (!additionalAffectedTestClassesFlag) {
                        filteredClassesCount += 1;
                    }
                } else {
                    filteredClassesCount += 1;
                }
            }
            affectedTests.addAll(additionalTests);

            if (detectOrNot) {
                record_classes_stats(filteredClassesCount);

                directedGraph = directedGraphBuilder.build();
                customizedPrintGraph(getArtifactsDir(), directedGraph, true, "soot-graph");

                record_classes_deps(SootAnalysisFieldsToAffectedClassesSet);
            }
            return affectedTests;
        }

        // add class count for basic version ...
        getImmutableList();
        DirectedGraph<String> graph = loadables.getGraph();
        for (String affectedTest : affectedTests) {
            Set<String> dependencies = transitiveClosure.get(affectedTest);
            for (String dependency : dependencies) {
                if (processedClasses.contains(dependency)) {
                    continue;
                }
                Set<String> dest = new HashSet<>();
                dest.add(dependency);
                int length = GraphUtils.computeShortestPath(graph, affectedTest, dest).size();
                if (length > distance) {
                    continue;
                }
                try {
                    Class clazz = loader.loadClass(dependency);
                    for (Field field : clazz.getDeclaredFields()) {
                        if (inImmutableList(field)) {
                            continue;
                        }
                        if (Modifier.isStatic(field.getModifiers())) {
                            String upperLevelAffectedClass = clazz.getName();
                            Set<String> upperLevelAffectedClassDest = new HashSet<>();
                            upperLevelAffectedClassDest.add(upperLevelAffectedClass);
                            Set<String> upperLevelAffectedTestClasses = reverseTransitiveClosure.get(upperLevelAffectedClass);
                            if (upperLevelAffectedTestClasses != null) {
                                for (String upperLevelAffectedTestClass: upperLevelAffectedTestClasses) {
                                    int reverseLength = GraphUtils.computeShortestPath(graph, upperLevelAffectedTestClass, upperLevelAffectedClassDest).size();
                                    if (reverseLength <= distance) {
                                        additionalTests.add(upperLevelAffectedTestClass);
                                    }
                                }
                            }
                            break;
                        }
                    }
                    processedClasses.add(dependency);
                } catch (ClassNotFoundException CNFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                } catch (NoClassDefFoundError NCDFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                }

            }
        }

        // Set<String> tmpAffectedTests = affectedTests;
        // tmpAffectedTests.addAll(additionalTests);

        // update the classes-deps file according to the distance
        /* Set<String> processedDependencies = new HashSet<>();
        Map<String, Set<String>> startsFieldsToAffectedClassesSet = new HashMap<>();
        Map<String, Boolean> classContainsStaticFieldsOrNot = new HashMap<>();

        for (String affectedTest : tmpAffectedTests) {
            Set<String> dependencies = transitiveClosure.get(affectedTest);
            for (String dependency : dependencies) {
                if (processedDependencies.contains(dependency)) {
                    continue;
                }
                processedDependencies.add(dependency);
                try {
                    Class clazz = loader.loadClass(dependency);
                    boolean containsStaticField = false;
                    for (Field field : clazz.getDeclaredFields()) {
                        if (inImmutableList(field)) {
                            continue;
                        }
                        if (Modifier.isStatic(field.getModifiers())) {
                            containsStaticField = true;
                            String upperLevelAffectedClass = clazz.getName();
                            String fieldFullName = upperLevelAffectedClass + "." + field.getName();
                            Set<String> valuesString = new HashSet<>();
                            startsFieldsToAffectedClassesSet.put(fieldFullName, valuesString);
                        }
                    }
                    if (containsStaticField) {
                        classContainsStaticFieldsOrNot.put(dependency, true);
                    }
                } catch (ClassNotFoundException CNFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                } catch (NoClassDefFoundError NCDFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                }

            }
        } */

        /* Set<String> finalAffectedTests = new HashSet<>();
        // System.out.println("DISTANCE: " + distance);

        for (String processedDependency : processedDependencies) {
            if (classContainsStaticFieldsOrNot.containsKey(processedDependency)) {
                Set<String> affectedTestClassesByDependency = reverseTransitiveClosure.get(processedDependency);
                for (String affectedTestClassByDependency : affectedTestClassesByDependency) {
                    Set<String> dest = new HashSet<>();
                    dest.add(processedDependency);
                    int length = GraphUtils.computeShortestPath(graph, affectedTestClassByDependency, dest).size();
                    if (length <= distance) {
                        if (affectedTests.contains(affectedTestClassByDependency)) {
                            finalAffectedTests.add(affectedTestClassByDependency);
                        }
                        String valueString = affectedTestClassByDependency + "+" + length;
                        for (String startsField: startsFieldsToAffectedClassesSet.keySet()) {
                            if (startsField.startsWith(processedDependency)) {
                                Set<String> valuesString = startsFieldsToAffectedClassesSet.get(startsField);
                                valuesString.add(valueString);
                                startsFieldsToAffectedClassesSet.put(startsField, valuesString);
                            }
                        }
                    }
                }
            }
        } */


        /* if(detectOrNot) {
            record_classes_stats(processedDependencies.size());
            record_classes_deps(startsFieldsToAffectedClassesSet);
            customizedPrintGraph(getArtifactsDir(), graph, true, "starts-graph");
        } */

        /* for (String startsField : startsFieldsToAffectedClassesSet.keySet()) {
            Set<String> valueString = startsFieldsToAffectedClassesSet.get(startsField);
            if (valueString.size() <= 1) {
                continue;
            } else {
                for (String value : valueString) {
                    String finalAdditionalTest = value.substring(0, value.indexOf("+"));
                    int length = Integer.valueOf(value.substring(value.indexOf("+"))).intValue();
                    if (length <= distance) {
                        finalAdditionalTests.add(finalAdditionalTest);
                    }
                }
            }
        } */
        // additionalTests.retainAll(finalAdditionalTests);
        affectedTests.addAll(additionalTests);
        return affectedTests;
    }

    private Set<String> computeEkstaziAffectedTests(ProjectWrapper project) throws IOException, MojoExecutionException, ClassNotFoundException {
        /* String cpString = Writer.pathToString(sureFireClassPath.getClassPath());
        List<String> sfPathElements = getCleanClassPath(cpString);

        // setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(project, this.runner.framework()));
        List<String> allTestsForLoadables = new LinkedList<>();
        allTests.addAll(allTests);
        Set<String> affectedTests = new HashSet<>(allTests);

        // System.out.println("SAME?: " + isSameClassPath(sfPathElements) + " " + hasSameJarChecksum(sfPathElements));
        boolean selectAll = false;
        if (!isSameClassPath(sfPathElements) || !hasSameJarChecksum(sfPathElements)) {
            // Force retestAll because classpath changed since last run
            // don't compute changed and non-affected classes
            dynamicallyUpdateExcludes(new ArrayList<String>());
            // Make nonAffected empty so dependencies can be updated
            nonAffectedTests = new HashSet<>();
            Writer.writeClassPath(cpString, artifactsDir);
            Writer.writeJarChecksums(sfPathElements, artifactsDir, jarCheckSums);
            selectAll = true;
        }

        nonAffectedTests = new HashSet<>();
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        // System.out.println("CHANGEDATA: " + data);
        nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        // System.out.println("NONAFFECTEDTESTS: " + nonAffectedTests.size() + " " + nonAffectedTests);
        List<String> excludePaths = Writer.fqnsToExcludePath(nonAffectedTests);
        dynamicallyUpdateExcludes(excludePaths);
        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            Logger.getGlobal().log(Level.INFO, STARS_RUN_STARS);
            Logger.getGlobal().log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        long startUpdate = System.currentTimeMillis();
        Loadables loadables = updateForNextRun(project, nonAffectedTests);
        long endUpdate = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        if ( selectAll || affectedTests.size() == allTests.size() ) {
            affectedTests = allTests;
        } */

        Set<String> affectedTests = new HashSet<>();

        try {
            // System.out.println("ekstaziSelectedTestsFile: " + ekstaziSelectedTestsFile);
            BufferedReader in = new BufferedReader(new FileReader(ekstaziSelectedTestsFile));
            String str;
            while ((str = in.readLine()) != null) {
                affectedTests.add(str);
            }
        } catch (IOException e) {
        }


        if (!selectMore) {
            return affectedTests;
        }


        Map<String, Set<String>> transitiveClosure = new HashMap<>();
        DirectedGraphBuilder graphBuilder = new DirectedGraphBuilder();


        try {
            BufferedReader in = new BufferedReader(new FileReader(ekstaziDependenciesFile));
            String str;
            while ((str = in.readLine()) != null) {
                if (!str.contains(",")) {
                    continue;
                }
                String transitiveClosureKey = str.substring(0, str.indexOf(","));
                String transitiveClosureValues = str.substring(str.indexOf(","));
                // System.out.println(transitiveClosureKey + ": " + transitiveClosureValues);
                String[] transitiveClosureValueArray = transitiveClosureValues.split(",");
                Set<String> transitiveClosureValue = new HashSet<>();
                for (String transitiveClosureValueArrayItem: transitiveClosureValueArray) {
                    transitiveClosureValue.add(transitiveClosureValueArrayItem);
                    // System.out.println("EDGE: " + transitiveClosureKey + "->" + transitiveClosureValueArrayItem);
                    graphBuilder.addEdge(transitiveClosureKey, transitiveClosureValueArrayItem);
                }
                transitiveClosure.put(transitiveClosureKey, transitiveClosureValue);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // the dependency map from classes to their dependencies
        Map<String, Set<String>> reverseTransitiveClosure = getReverseClosure(transitiveClosure);

        Set<String> additionalTests = new HashSet<>();

        // iter through the affected tests and find what depends on
        Set<String> processedClasses = new HashSet<>();
        Set<String> affectedClasses = new HashSet<>();

        // add class count for basic version ...
        DirectedGraph<String> graph = graphBuilder.build();
        getImmutableList();
        for (String affectedTest : affectedTests) {
            Set<String> dependencies = transitiveClosure.get(affectedTest);
            if (dependencies == null) {
                continue;
            }
            for (String dependency : dependencies) {
                if (processedClasses.contains(dependency)) {
                    continue;
                }
                Set<String> dest = new HashSet<>();
                dest.add(dependency);
                int length = GraphUtils.computeShortestPath(graph, affectedTest, dest).size();
                if (length > distance) {
                    continue;
                }
                try {
                    Class clazz = loader.loadClass(dependency);
                    for (Field field : clazz.getDeclaredFields()) {
                        if (inImmutableList(field)) {
                            continue;
                        }
                        if (Modifier.isStatic(field.getModifiers())) {
                            String upperLevelAffectedClass = clazz.getName();
                            Set<String> upperLevelAffectedClassDest = new HashSet<>();
                            upperLevelAffectedClassDest.add(upperLevelAffectedClass);
                            Set<String> upperLevelAffectedTestClasses = reverseTransitiveClosure.get(upperLevelAffectedClass);
                            if (upperLevelAffectedTestClasses != null) {
                                for (String upperLevelAffectedTestClass: upperLevelAffectedTestClasses) {
                                    int reverseLength = GraphUtils.computeShortestPath(graph, upperLevelAffectedTestClass, upperLevelAffectedClassDest).size();
                                    if (reverseLength <= distance) {
                                        additionalTests.add(upperLevelAffectedTestClass);
                                    }
                                }
                            }
                            break;
                        }
                    }
                    processedClasses.add(dependency);
                } catch (ClassNotFoundException CNFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                } catch (NoClassDefFoundError NCDFE)  {
                    // System.out.println("Can not load class. Test dependency skipping: " + dependency);
                }

            }
        }

        affectedTests.addAll(additionalTests);
        return affectedTests;
    }


    protected Pair<Set<String>, Set<String>> computeChangeData(boolean writeChanged) throws FileNotFoundException {
        long start = System.currentTimeMillis();
        Pair<Set<String>, Set<String>> data = null;
        if (depFormat == DependencyFormat.ZLC) {
            ZLCHelper zlcHelper = new ZLCHelper();
            data = zlcHelper.getChangedData(getArtifactsDir(), cleanBytes);
        } else if (depFormat == DependencyFormat.CLZ) {
            data = EkstaziHelper.getNonAffectedTests(getArtifactsDir());
        }
        Set<String> changed = data == null ? new HashSet<String>() : data.getValue();
        if (writeChanged || Logger.getGlobal().getLoggingLevel().intValue() <= Level.FINEST.intValue()) {
            Writer.writeToFile(changed, CHANGED_CLASSES, getArtifactsDir());
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] COMPUTING CHANGES: " + Writer.millsToSeconds(end - start));
        return data;
    }

    public ClassLoader createClassLoader(Classpath sfClassPath) {
        long start = System.currentTimeMillis();
        ClassLoader loader = null;
        try {
            loader = sfClassPath.createClassLoader(false, false, "MyRole");
        } catch (SurefireExecutionException see) {
            see.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] IncDetectorPlugin(createClassLoader): "
                + Writer.millsToSeconds(end - start));
        return loader;
    }

    @Override
    protected Void defineSettings(final ErrorLogger logger, final ProjectWrapper project) throws IOException {
        super.defineSettings(logger, project);

        artifactsDir = getArtifactsDir();
        cleanBytes = true;
        depFormat = DependencyFormat.ZLC;
        filterLib = true;
        graphFile = "graph";
        graphCache = "jdeps-cache";
        printGraph = true;
        updateChecksums = true;
        useThirdParty = false;
        zlcFormat = ZLCFormat.PLAIN_TEXT;
        selectMore = Configuration.config().getProperty("dt.incdetector.selectmore", false);
        selectBasedOnMethodsCall = Configuration.config().getProperty("dt.incdetector.selectonmethods", false);
        selectBasedOnMethodsCallUpgrade = Configuration.config().getProperty("dt.incdetector.selectonmethodsupgrade", false);
        removeBasedOnMethodsCall = Configuration.config().getProperty("dt.incdetector.removeonmethods", false);
        detectOrNot = Configuration.config().getProperty("dt.incdetector.detectornot", true);
        fineGranularity = Configuration.config().getProperty("dt.incdetector.finegranularity", false);
        distance = Configuration.config().getProperty("dt.incdetector.distance", Integer.MAX_VALUE);
        ekstaziOrNot = Configuration.config().getProperty("dt.incdetector.ekstazi", false);
        ekstaziSelectedTestsFile = Configuration.config().getProperty("dt.incdetector.ekstaziselectedtests", "");
        ekstaziDependenciesFile = Configuration.config().getProperty("dt.incdetector.ekstazidependencies", "");;

        getSureFireClassPath(project);
        loader = createClassLoader(sureFireClassPath);


        return null;
    }

    private void dynamicallyUpdateExcludes(List<String> excludePaths) throws MojoExecutionException {
        if (AgentLoader.loadDynamicAgent()) {
            System.setProperty(STARTS_EXCLUDE_PROPERTY, Arrays.toString(excludePaths.toArray(new String[0])));
        } else {
            throw new MojoExecutionException("I COULD NOT ATTACH THE AGENT");
        }
    }

    public String getArtifactsDir() throws FileNotFoundException {
        if (artifactsDir == null) {
            artifactsDir = PathManager.cachePath().toString();
            File file = new File(artifactsDir);
            if (!file.mkdirs() && !file.exists()) {
                throw new FileNotFoundException("I could not create artifacts dir: " + artifactsDir);
            }
        }
        return artifactsDir;
    }

    private List<String> getCleanClassPath(String cp) {
        List<String> cpPaths = new ArrayList<>();
        String[] paths = cp.split(File.pathSeparator);
        String classes = File.separator + TARGET + File.separator + CLASSES;
        String testClasses = File.separator + TARGET + File.separator + TEST_CLASSES;
        Set<String> classPathSet = new HashSet<>();
        for (int i = 0; i < paths.length; i++) {
            paths[i].replaceAll(" ", "");
            // TODO: should we also exclude SNAPSHOTS from same project?
            if (paths[i].contains(classes) || paths[i].contains(testClasses)) {
                continue;
            }
            classPathSet.add(paths[i]);
        }
        for(String classPath: classPathSet) {
            cpPaths.add(classPath);
        }
        return cpPaths;
    }

    public Map<String, Set<String>> getReverseClosure(Map<String, Set<String>> transitiveClosure) {
        Map<String, Set<String>> reverseTransitiveClosure = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : transitiveClosure.entrySet()) {
            for (String dep : entry.getValue()) {
                Set<String> reverseDeps = new HashSet<>();
                if (reverseTransitiveClosure.containsKey(dep)) {
                    reverseDeps = reverseTransitiveClosure.get(dep);
                    reverseDeps.add(entry.getKey());
                    reverseTransitiveClosure.replace(dep, reverseDeps);
                }
                else {
                    reverseDeps.add(entry.getKey());
                    reverseTransitiveClosure.putIfAbsent(dep, reverseDeps);
                }
            }
        }
        return reverseTransitiveClosure;
    }

    public Classpath getSureFireClassPath(final ProjectWrapper project) {
        long start = System.currentTimeMillis();
        if (sureFireClassPath == null) {
            sureFireClassPath = new Classpath(project.getClasspathElements()); // contains not just getTestClasspathElements()
            // sureFireClassPath = new Classpath(getProject().getTestClasspathElements()); // AbstractSurefireMojo
        }
        Logger.getGlobal().log(Level.FINEST, "SF-CLASSPATH: " + sureFireClassPath.getClassPath());
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] IncDetectorPlugin(getSureFireClassPath): "
                + Writer.millsToSeconds(end - start));
        return sureFireClassPath;
    }

    @Override
    protected List<String> getTests(
            final ProjectWrapper project,
            TestFramework testFramework) throws IOException {
        // System.out.println("SELECTED TESTS SIZE: " + this.selectedTests.size());
        List<String> tests;
        if (fineGranularity && this.selectedTests != null) {
            tests = this.selectedTests;
        } else {
            tests = getOriginalOrder(project, testFramework, true);
        }
        List<String> affectedTests = new ArrayList<>();

        String delimiter = testFramework.getDelimiter();
        for (String test: tests) {
            String clazz = test.substring(0, test.lastIndexOf(delimiter));
            if (affectedTestClasses.contains(clazz)) {
                affectedTests.add(test);
            }
        }
        return affectedTests;
    }

    public static List<String> getTestClasses (
            final ProjectWrapper project,
            TestFramework testFramework) throws IOException {
        List<String> tests = getOriginalOrder(project, testFramework, true);

        String delimiter = testFramework.getDelimiter();
        List<String> classes = new ArrayList<>();
        for(String test : tests){
            String clazz = test.substring(0, test.lastIndexOf(delimiter));
            if(!classes.contains(clazz)) {
                classes.add(clazz);
            }
        }

        return classes;
    }

    private boolean hasSameJarChecksum(List<String> cleanSfClassPath) throws FileNotFoundException {
        if (cleanSfClassPath.isEmpty()) {
            return true;
        }
        String oldChecksumPathFileName = Paths.get(getArtifactsDir(), JAR_CHECKSUMS).toString();
        if (!new File(oldChecksumPathFileName).exists()) {
            return false;
        }
        boolean noException = true;
        try {
            List<String> lines = Files.readAllLines(Paths.get(oldChecksumPathFileName));
            Map<String, String> checksumMap = new HashMap<>();
            for (String line : lines) {
                String[] elems = line.split(COMMA);
                checksumMap.put(elems[0], elems[1]);
            }
            jarCheckSums = new ArrayList<>();
            for (String path : cleanSfClassPath) {
                Pair<String, String> pair = Writer.getJarToChecksumMapping(path);
                jarCheckSums.add(pair);
                String oldCS = checksumMap.get(pair.getKey());
                noException &= pair.getValue().equals(oldCS);
            }
        } catch (IOException ioe) {
            noException = false;
            // reset to null because we don't know what/when exception happened
            jarCheckSums = null;
            ioe.printStackTrace();
        }
        return noException;
    }

    private boolean isSameClassPath(List<String> sfPathString) throws MojoExecutionException, FileNotFoundException {
        if (sfPathString.isEmpty()) {
            return true;
        }
        String oldSfPathFileName = Paths.get(getArtifactsDir(), SF_CLASSPATH).toString();
        if (!new File(oldSfPathFileName).exists()) {
            return false;
        }
        try {
            List<String> oldClassPathLines = Files.readAllLines(Paths.get(oldSfPathFileName));
            if (oldClassPathLines.size() != 1) {
                throw new MojoExecutionException(SF_CLASSPATH + " is corrupt! Expected only 1 line.");
                // This exception is not correct and need to be modified.
            }
            List<String> oldClassPathelements = getCleanClassPath(oldClassPathLines.get(0));
            // comparing lists and not sets in case order changes
            if (sfPathString.equals(oldClassPathelements)) {
                return true;
            }
            Set<String> sfPathStringSet = new HashSet<>();
            sfPathStringSet.addAll(sfPathString);
            Set<String> oldClassPathelementsSet = new HashSet<>();
            oldClassPathelementsSet.addAll(sfPathString);
            if (sfPathStringSet.equals(oldClassPathelementsSet)) {
                return true;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return false;
    }

    public Loadables prepareForNextRun(String sfPathString, Classpath sfClassPath, List<String> classesToAnalyze,
                                             Set<String> nonAffected, boolean computeUnreached) {
        File jdepsCache = new File(graphCache);
        String m2Repo = "tmp"; // getLocalRepository().getBasedir(); // AbstractSurefireMojo

        // Create the Loadables object early so we can use its helpers
        Loadables loadables = new Loadables(classesToAnalyze, artifactsDir, sfPathString,
                useThirdParty, filterLib, jdepsCache);
        // Surefire Classpath object is easier to iterate over without de-constructing
        // sfPathString (which we use in a number of other places)
        loadables.setSurefireClasspath(sfClassPath);

        Cache cache = new Cache(jdepsCache, m2Repo);
        // 1. Load non-reflection edges from third-party libraries in the classpath
        List<String> moreEdges = new ArrayList<>();
        if (useThirdParty) {
            moreEdges = cache.loadM2EdgesFromCache(sfPathString);
        }

        // 2. Get non-reflection edges from CUT and SDK; use (1) to build graph
        loadables.create(new ArrayList<>(moreEdges), sfClassPath, computeUnreached);

        return loadables;
    }

    public void printToTerminal(List<String> testClasses, Set<String> affectedTests) {
        Logger.getGlobal().log(Level.INFO, STARTS_AFFECTED_TESTS + affectedTests.size() + affectedTests);
        Logger.getGlobal().log(Level.INFO, "STARTS:TotalTests: " + testClasses.size() + testClasses);
    }

    public void save(String artifactsDir, Set<String> affectedTests, List<String> testClasses,
                     String sfPathString, DirectedGraph<String> graph) {
        int globalLogLevel = Logger.getGlobal().getLoggingLevel().intValue();
        if (globalLogLevel <= Level.FINER.intValue()) {
            Writer.writeToFile(testClasses, "all-tests", artifactsDir);
            Writer.writeToFile(affectedTests, "selected-tests", artifactsDir);
        }
        if (globalLogLevel <= Level.FINEST.intValue()) {
            RTSUtil.saveForNextRun(artifactsDir, graph, printGraph, graphFile);
            Writer.writeClassPath(sfPathString, artifactsDir);
        }
    }

    public static void customizedPrintGraph(String artifactsDir, DirectedGraph<String> graph, boolean printGraph, String graphFile) {
        RTSUtil.saveForNextRun(artifactsDir, graph, printGraph, graphFile);
    }

    public Loadables updateForNextRun(final ProjectWrapper project, Set<String> nonAffected) throws IOException, MojoExecutionException {
        long start = System.currentTimeMillis();

        String sfPathString = Writer.pathToString(sureFireClassPath.getClassPath());

        /* try {
            setIncludesExcludes();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        } */

        List<String> allTests = getTestClasses(project, this.runner.framework());  // may contain IO Exception
        List<String> classesToAnalyze = allTests;
        Set<String> affectedTests = new HashSet<>(allTests);
        affectedTests.removeAll(nonAffected);
        DirectedGraph<String> graph = null;
        Loadables loadables = null;

        if (!affectedTests.isEmpty()) {
            //TODO: set this boolean to true only for static reflectionAnalyses with * (border, string, naive)?
            boolean computeUnreached = true;
            loadables = prepareForNextRun(sfPathString, sureFireClassPath, allTests, nonAffected, computeUnreached);
            Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();
            Map<String, Set<String>> testDeps = transitiveClosure;
            graph = loadables.getGraph();
            Set<String> unreached = computeUnreached ? loadables.getUnreached() : new HashSet<String>();

            Set<String> affected = depFormat == DependencyFormat.ZLC ? null
                    : RTSUtil.computeAffectedTests(new HashSet<>(classesToAnalyze),
                    nonAffected, transitiveClosure);

            if (depFormat == DependencyFormat.ZLC) {
                ZLCHelper zlcHelper = new ZLCHelper();
                zlcHelper.updateZLCFile(testDeps, loader, getArtifactsDir(), unreached, useThirdParty, zlcFormat);
            } else if (depFormat == DependencyFormat.CLZ) {
                // The next line is not needed with ZLC because '*' is explicitly tracked in ZLC
                affectedTests = affected;
                if (affectedTests == null) {
                    System.out.println("Affected tests should not be null with CLZ format!"); // Should be an exception
                }
                RTSUtil.computeAndSaveNewCheckSums(getArtifactsDir(), affectedTests, testDeps, loader);
            }
        }
        save(getArtifactsDir(), affectedTests, allTests, sfPathString, graph);
        printToTerminal(allTests, affectedTests);
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_UPDATE_FOR_NEXT_RUN_TOTAL + Writer.millsToSeconds(end - start));

        return loadables;
    }

    public void record_classes_deps(Map<String, Set<String>> testClassToClassesMap) {
        if(!Files.exists(DetectorPathManager.classesDepsPath())) {
            try {
                Files.createFile(DetectorPathManager.classesDepsPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        List<Map.Entry<String, Set<String>>> list = testClassToClassesMap.entrySet().stream()
                .sorted((entry1, entry2) -> ((Integer) entry2.getValue().size()).compareTo((Integer) entry1.getValue().size()))
                .collect(Collectors.toList());

        for(Map.Entry<String, Set<String>> entry: list) {
            String deps = entry.getKey() + ",";
            Set<String> values = entry.getValue();
            Map<String, Integer> distanceMap = new HashMap<>();
            for (String value : values) {
                String distanceKey = value.substring(0, value.indexOf("+"));
                int length = Integer.valueOf(value.substring(value.indexOf("+"))).intValue();
                distanceMap.put(distanceKey, length);
            }

            List<Map.Entry<String, Integer>> distanceList = distanceMap.entrySet().stream()
                    .sorted((entry1, entry2) -> (entry1.getValue()).compareTo(entry2.getValue()))
                    .collect(Collectors.toList());
            for (Map.Entry<String, Integer> dep: distanceList) {
                deps += dep.getKey() + "+" + dep.getValue() + ",";
            }
            deps += " " + entry.getValue().size() + "\n";
            try {
                Files.write(DetectorPathManager.classesDepsPath(), deps.getBytes(),
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static Set<String> getImmutableList() {
        if (immutableList == null) {
            immutableList = new HashSet<>();

            immutableList.add("java.lang.String");
            immutableList.add("java.lang.Enum");
            immutableList.add("java.lang.StackTraceElement");
            immutableList.add("java.math.BigInteger");
            immutableList.add("java.math.BigDecimal");
            immutableList.add("java.io.File");
            immutableList.add("java.awt.Font");
            immutableList.add("java.awt.BasicStroke");
            immutableList.add("java.awt.Color");
            immutableList.add("java.awt.GradientPaint");
            immutableList.add("java.awt.LinearGradientPaint");
            immutableList.add("java.awt.RadialGradientPaint");
            immutableList.add("java.awt.Cursor");
            immutableList.add("java.util.Locale");
            immutableList.add("java.util.UUID");
            immutableList.add("java.util.Collections");
            immutableList.add("java.net.URL");
            immutableList.add("java.net.URI");
            immutableList.add("java.net.Inet4Address");
            immutableList.add("java.net.Inet6Address");
            immutableList.add("java.net.InetSocketAddress");
            immutableList.add("java.awt.BasicStroke");
            immutableList.add("java.awt.Color");
            immutableList.add("java.awt.GradientPaint");
            immutableList.add("java.awt.LinearGradientPaint");
            immutableList.add("java.awt.RadialGradientPaint");
            immutableList.add("java.awt.Cursor");
            immutableList.add("java.util.regex.Pattern");
        }
        return immutableList;
    }

    private static boolean inImmutableList(Field field) {
        boolean isFinal = false;
        if (Modifier.isFinal(field.getModifiers())) {
            isFinal = true;
        }

        if ((field.getType().isPrimitive() || field.getDeclaringClass().isEnum()) && isFinal) {
            // System.out.println("FIELDNAME(P|E): " + field.getType().getName());
            return true;
        }

        for (String immutableTypeName: immutableList) {
            if ((field.getType().getName().equals(immutableTypeName)) && isFinal) {
                // System.out.println("FIELDNAME: " + field.getType().getName());
                return true;
            }
        }
        return false;
    }
}
