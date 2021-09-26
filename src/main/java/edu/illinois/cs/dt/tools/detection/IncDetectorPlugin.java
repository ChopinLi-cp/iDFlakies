package edu.illinois.cs.dt.tools.detection;

import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.dt.tools.utility.PathManager;
import edu.illinois.cs.testrunner.coreplugin.TestPluginUtil;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import edu.illinois.cs.testrunner.util.ProjectWrapper;
import edu.illinois.starts.data.ZLCFormat;
import edu.illinois.starts.enums.DependencyFormat;
import edu.illinois.starts.helpers.*;
import edu.illinois.starts.util.Logger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.SurefireExecutionException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public class IncDetectorPlugin extends DetectorPlugin {

    private Classpath sureFireClassPath;

    @Override
    public void execute(final ProjectWrapper project) {
        final ErrorLogger logger = new ErrorLogger(project);

        logger.runAndLogError(() -> incDetectorExecute(logger, project));
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

        String artifactsDir = PathManager.cachePath().toString();

        Classpath sfClassPath = null;
        sfClassPath = getSureFireClassPath(project);
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());

        boolean useThirdParty = false;
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
        DependencyFormat depFormat = DependencyFormat.ZLC;
        ZLCFormat zlcFormat = ZLCFormat.PLAIN_TEXT;

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
}
