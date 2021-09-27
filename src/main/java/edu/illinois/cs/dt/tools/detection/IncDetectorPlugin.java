package edu.illinois.cs.dt.tools.detection;

import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.dt.tools.utility.PathManager;
import edu.illinois.cs.testrunner.coreplugin.TestPluginUtil;
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
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

import static edu.illinois.starts.constants.StartsConstants.*;

public class IncDetectorPlugin extends DetectorPlugin {

    /**
     * The directory in which to store STARTS artifacts that are needed between runs.
     */
    protected String artifactsDir;

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

    @Override
    public void execute(final ProjectWrapper project) {
        final ErrorLogger logger = new ErrorLogger(project);
        logger.runAndLogError(() -> defineSettings(logger, project));
        if(this.runner == null) {
            return;
        }

        try {
            computeAffectedTests(project);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        }

        // logger.runAndLogError(() -> incDetectorExecute(logger, project));
    }

    // from SelectMojo
    private Set<String> computeAffectedTests(ProjectWrapper project) throws IOException, MojoExecutionException {
        // setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(project, this.runner.framework()));
        Set<String> affectedTests = new HashSet<>(allTests);
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        Set<String> nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            Logger.getGlobal().log(Level.INFO, STARS_RUN_STARS);
            Logger.getGlobal().log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        long startUpdate = System.currentTimeMillis();
        if (updateChecksums) {
            updateForNextRun(project, nonAffectedTests);
        }
        long endUpdate = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
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

    public Void defineSettings(final ErrorLogger logger, final ProjectWrapper project) throws IOException {
        Files.deleteIfExists(DetectorPathManager.errorPath());
        Files.createDirectories(DetectorPathManager.cachePath());
        Files.createDirectories(DetectorPathManager.detectionResults());

        artifactsDir = getArtifactsDir();
        depFormat = DependencyFormat.ZLC;
        graphFile = "graph";
        printGraph = true;
        updateChecksums = true;
        useThirdParty = false;
        zlcFormat = ZLCFormat.PLAIN_TEXT;

        loadTestRunners(logger, project); // may contain IO Exception
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

    public Void incDetectorExecute(final ErrorLogger logger, final ProjectWrapper project) throws IOException {
        Files.deleteIfExists(DetectorPathManager.errorPath());
        Files.createDirectories(DetectorPathManager.cachePath());
        Files.createDirectories(DetectorPathManager.detectionResults());

        loadTestRunners(logger, project); // may contain IO Exception
        if(this.runner == null) {
            return null;
        }

        /* try {
            setIncludesExcludes();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        } */

        List<String> classesToAnalyze = null;
        classesToAnalyze = getTestClasses(project, this.runner.framework()); // may contain IO Exception

        String artifactsDir = getArtifactsDir();

        Classpath sfClassPath = null;
        sfClassPath = getSureFireClassPath(project);
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());

        useThirdParty = false;
        boolean filterLib = true;
        boolean computeUnreached = true;

        String m2Repo = "tmp"; // getLocalRepository().getBasedir(); // AbstractSurefireMojo
        String graphCache = "jdeps-cache";
        File jdepsCache = new File(graphCache);

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

        Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();
        Map<String, Set<String>> testDeps = transitiveClosure;
        ClassLoader loader = createClassLoader(sfClassPath);
        Set<String> unreached = loadables.getUnreached();
        depFormat = DependencyFormat.ZLC;
        zlcFormat = ZLCFormat.PLAIN_TEXT;

        if (depFormat == DependencyFormat.ZLC) {
            ZLCHelper zlcHelper = new ZLCHelper();
            try {
                zlcHelper.updateZLCFile(testDeps, loader, artifactsDir, unreached, useThirdParty, zlcFormat);
            } catch (NullPointerException n) {
                n.printStackTrace();
            }
        }

        return null;
    }

    private void loadTestRunners(final ErrorLogger logger, final ProjectWrapper project) throws IOException {
        // Currently there could two runners, one for JUnit 4 and one for JUnit 5
        // If the maven project has both JUnit 4 and JUnit 5 tests, two runners will
        // be returned
        List<Runner> runners = RunnerFactory.allFrom(project);
        runners = removeZombieRunners(runners, project);

        if (runners.size() != 1) {
            if (forceJUnit4) {
                Runner nrunner = null;
                for (Runner runner : runners) {
                    if (runner.framework().toString() == "JUnit") {
                        nrunner = runner;
                        break;
                    }
                }
                if (nrunner != null) {
                    runners = new ArrayList<>(Arrays.asList(nrunner));
                } else {
                    String errorMsg;
                    if (runners.size() == 0) {
                        errorMsg =
                                "Module is not using a supported test framework (probably not JUnit), " +
                                        "or there is no test.";
                    } else {
                        errorMsg = "dt.detector.forceJUnit4 is true but no JUnit 4 runners found. Perhaps the project only contains JUnit 5 tests.";
                    }
                    TestPluginUtil.project.info(errorMsg);
                    logger.writeError(errorMsg);
                    return;
                }
            } else {
                String errorMsg;
                if (runners.size() == 0) {
                    errorMsg =
                            "Module is not using a supported test framework (probably not JUnit), " +
                                    "or there is no test.";
                } else {
                    // more than one runner, currently is not supported.
                    errorMsg =
                            "This project contains both JUnit 4 and JUnit 5 tests, which currently"
                                    + " is not supported by iDFlakies";
                }
                TestPluginUtil.project.info(errorMsg);
                logger.writeError(errorMsg);
                return;
            }
        }

        if (this.runner == null) {
            this.runner = InstrumentingSmartRunner.fromRunner(runners.get(0));
        }
    }

    public Loadables prepareForNextRun(String sfPathString, Classpath sfClassPath, List<String> classesToAnalyze,
                                             Set<String> nonAffected, boolean computeUnreached) {
        boolean filterLib = true;
        String graphCache = "jdeps-cache";
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
        Logger.getGlobal().log(Level.INFO, STARTS_AFFECTED_TESTS + affectedTests.size());
        Logger.getGlobal().log(Level.INFO, "STARTS:TotalTests: " + testClasses.size());
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

    public void updateForNextRun(final ProjectWrapper project, Set<String> nonAffected) throws IOException, MojoExecutionException {
        long start = System.currentTimeMillis();

        /* try {
            setIncludesExcludes();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        } */

        Classpath sfClassPath = getSureFireClassPath(project);
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());

        List<String> allTests = getTestClasses(project, this.runner.framework());  // may contain IO Exception
        List<String> classesToAnalyze = allTests;
        Set<String> affectedTests = new HashSet<>(allTests);
        affectedTests.removeAll(nonAffected);
        DirectedGraph<String> graph = null;

        if (!affectedTests.isEmpty()) {
            ClassLoader loader = createClassLoader(sfClassPath);
            //TODO: set this boolean to true only for static reflectionAnalyses with * (border, string, naive)?
            boolean computeUnreached = true;
            Loadables loadables = prepareForNextRun(sfPathString, sfClassPath, allTests, nonAffected, computeUnreached);
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
    }

}
