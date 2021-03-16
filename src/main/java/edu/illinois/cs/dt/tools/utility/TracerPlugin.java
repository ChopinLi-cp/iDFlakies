package edu.illinois.cs.dt.tools.utility;

import com.google.gson.Gson;
import com.reedoei.eunomia.io.files.FileUtil;
import edu.illinois.cs.diaper.StateCapture;
import edu.illinois.cs.diaper.agent.MainAgent;
import edu.illinois.cs.dt.tools.minimizer.PolluterData;
import edu.illinois.cs.dt.tools.runner.data.DependentTest;
import edu.illinois.cs.dt.tools.runner.data.DependentTestList;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import edu.illinois.cs.testrunner.execution.JUnitTestExecutor;
import edu.illinois.cs.testrunner.mavenplugin.TestPlugin;
import edu.illinois.cs.testrunner.mavenplugin.TestPluginPlugin;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import org.apache.commons.io.FileUtils;
import org.apache.maven.project.MavenProject;
import scala.Option;
import scala.util.Try;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;
import edu.illinois.cs.dt.tools.minimizer.MinimizeTestsResult;

public class TracerPlugin extends TestPlugin {
    private Path replayPath;
    private Path replayPath2;
    private String dtname;
    private String module;
    private String output;
    private String testorder;

    @Override
    public void execute(final MavenProject mavenProject) {
        long startTime = System.currentTimeMillis();
        final Option<Runner> runnerOption = RunnerFactory.from(mavenProject);

        replayPath = Paths.get(Configuration.config().getProperty("replay.path"));
        replayPath2 = Paths.get(Configuration.config().getProperty("replay.path2"));
        dtname= Configuration.config().getProperty("replay.dtname");
        output= Configuration.config().getProperty("replay.output");
        module = Configuration.config().getProperty("replay.module");

        testorder = Configuration.config().getProperty("replay.testorder");

        if (runnerOption.isDefined() && module.equals(PathManager.modulePath().toString())) {
            System.out.println("replyPath: " + replayPath);
            System.out.println("module: " + module);

            try {
                final Runner runner = runnerOption.get(); // safe because we checked above
                System.out.println("tests!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

                if(testorder.equals("doublevictim")) {
                    System.out.println("doublevictim");
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e){
                        System.out.println("error in phase 1: " + e);
                    }
                }
                else if(testorder.equals("passorder")){
                    System.out.println("passing order");
                    try{
                        runner.runList(testPassOrder_full());
                    }
                    catch(Exception e) {
                        System.out.println("error in running passing order!");
                    }
                }
                else if(testorder.equals("failing")) {
                    System.out.println("failing order");
                    try {
                        runner.runList(testFailOrder());
                    }
                    catch(Exception e) {
                        System.out.println("error in phase 4 before!! " + e);

                    }
                }

            } catch (Exception e) {
                TestPluginPlugin.mojo().getLog().error(e);
            }
        } else {
            TestPluginPlugin.mojo().getLog().info("Module is not using a supported test framework (probably not JUnit).");
        }
        timing(startTime);
    }

    private void timing(long startTime) {
        long endTime = System.currentTimeMillis();
        double duration = (endTime - startTime)/1000.0;

        String time = duration + "\n";
        try {
            Files.write(Paths.get(output), time.getBytes(),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<String> victim() {
        List<String> partialOrder = new ArrayList<>();
        partialOrder.add(dtname);
        return partialOrder;
    }


    private List<String> testPassOrder_full() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$testPassOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if(dt.name().equals(dtname)) {
                    for(String s: dt.intended().order()) {
                        partialOrder.add(s);
                        if(s.equals(dt.name()))
                            break;
                    }
                    if(!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    System.out.println("testFailOrder_full1 : " + dtname);
                    return partialOrder;
                }
            }
            System.out.println("testFailOrder_full2: " + dtname);
            return null;

        } catch (Exception e) {
            System.out.println("expection in reading json!!!!!");
            return null;
        }
    }

    private List<String> testFailOrder() throws IOException {
        if(replayPath2.toString().equals("")) {
            return testFailOrder_full();
        }
        else {
            return testFailOrder_minimized();
        }
    }

    private List<String> testFailOrder_full() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$testFailOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if(dt.name().equals(dtname)) {
                    for(String s: dt.revealed().order()) {
                        partialOrder.add(s);
                        if(s.equals(dt.name()))
                            break;
                    }
                    if(!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    System.out.println("testFailOrder_full1 : " + dtname);
                    return partialOrder;
                }
            }
            System.out.println("testFailOrder_full2: " + dtname);
            return null;

        } catch (Exception e) {
            System.out.println("expection in reading json!!!!!");
            return null;
        }
    }

    private List<String> testFailOrder_minimized() throws IOException {
        List<String> failingTests = new ArrayList<String>();
        try {
            System.out.println("$$$$$$$$$$$replayPath2: " + replayPath2);
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            System.out.println("polluters!!!!!!!!!!!");
            for(PolluterData pd: polluters) {
                if(pd.deps().size() >=1) {
                    failingTests.addAll(pd.deps());
                    failingTests.add(dtname);
                    return failingTests;
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("expection in reading json for failing order!!!!!");
            return null;
        }
    }

}
