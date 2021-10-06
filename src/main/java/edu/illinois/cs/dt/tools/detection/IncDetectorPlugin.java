package edu.illinois.cs.dt.tools.detection;

import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.dt.tools.utility.PathManager;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.coreplugin.TestPluginUtil;
import edu.illinois.cs.testrunner.data.framework.TestFramework;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import edu.illinois.cs.testrunner.util.ProjectWrapper;
import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.data.ZLCFormat;
import edu.illinois.starts.enums.DependencyFormat;
import edu.illinois.starts.helpers.*;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import edu.illinois.yasgl.DirectedGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.SurefireExecutionException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

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

    /**
     * Set this to "false" to not print the graph obtained from jdeps parsing.
     * When "true" the graph is written to file after the run.
     */
    @Parameter(property = "printGraph", defaultValue = TRUE)
    protected boolean printGraph;

    private Classpath sureFireClassPath;

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

    protected boolean detectOrNot;

    private Set<String> affectedTestClasses;

    @Override
    public void execute(final ProjectWrapper project) {
        final ErrorLogger logger = new ErrorLogger(project);
        logger.runAndLogError(() -> defineSettings(logger, project));
        if(this.runner == null) {
            return;
        }
        this.coordinates = logger.coordinates();

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

        logger.runAndLogError(() -> detectorExecute(logger, project, moduleRounds(coordinates)));
    }

    // from SelectMojo
    private Set<String> computeAffectedTests(ProjectWrapper project) throws IOException, MojoExecutionException, ClassNotFoundException {
        // setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(project, this.runner.framework()));
        Set<String> affectedTests = new HashSet<>(allTests);
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        System.out.println("CHANGEDATA: " + data);
        Set<String> nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        System.out.println("NONAFFECTEDTESTS: " + nonAffectedTests.size() + " " + nonAffectedTests);
        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            Logger.getGlobal().log(Level.INFO, STARS_RUN_STARS);
            Logger.getGlobal().log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        long startUpdate = System.currentTimeMillis();
        Loadables loadables = updateForNextRun(project, nonAffectedTests);
        long endUpdate = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        if (!selectMore || loadables == null) {
            return affectedTests;
        }

        Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();

        Map<String, Set<String>> reverseTransitiveClosure = getReverseClosure(transitiveClosure);

        Set<String> additionalTests = new HashSet<>();

        // iter through the affected tests and find what depends on
        Set<String> processedClasses = new HashSet<>();
        for (String affectedTest : affectedTests) {
            Set<String> dependencies = transitiveClosure.get(affectedTest);
            for (String dependency : dependencies) {
                if (processedClasses.contains(dependency)) {
                    continue;
                }
                processedClasses.add(dependency);
                Class clazz = loader.loadClass(dependency);

                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        String upperLevelAffectedClass = clazz.getName();
                        Set<String> upperLevelAffectedTestClasses = reverseTransitiveClosure.get(upperLevelAffectedClass);
                        if (upperLevelAffectedTestClasses != null) {
                            additionalTests.addAll(upperLevelAffectedTestClasses);
                        }
                        break;
                    }
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
        selectMore = Configuration.config().getProperty("dt.incdetector.selectmore", true);
        detectOrNot = Configuration.config().getProperty("dt.incdetector.detectornot", true);

        getSureFireClassPath(project);
        loader = createClassLoader(sureFireClassPath);


        return null;
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
        List<String> tests = getOriginalOrder(project, testFramework, true);
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

}
