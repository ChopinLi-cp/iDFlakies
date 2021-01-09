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

public class iFixPlusPlugin extends TestPlugin {
    private Path replayPath;
    private Path replayPath2;
    private String dtname;
    private String dtjavapPath = "";
    private String xmlFold;
    private String module;
    private String output;
    private String slug;
    private String tmpfile;
    private String diffFieldsFold;
    private String reflectionFold;
    //private String lognum;
    @Override
    public void execute(final MavenProject mavenProject) {
        long startTime = System.currentTimeMillis();
        final Option<Runner> runnerOption = RunnerFactory.from(mavenProject);

        replayPath = Paths.get(Configuration.config().getProperty("replay.path"));
        replayPath2 = Paths.get(Configuration.config().getProperty("replay.path2"));
        dtname= Configuration.config().getProperty("replay.dtname");
        output= Configuration.config().getProperty("replay.output");
        slug= Configuration.config().getProperty("replay.slug");
        xmlFold = Configuration.config().getProperty("replay.xmlFold");
        tmpfile = Configuration.config().getProperty("replay.tmpfile");
        module = Configuration.config().getProperty("replay.module");
        diffFieldsFold = Configuration.config().getProperty("replay.diffFieldsFold");
        reflectionFold = Configuration.config().getProperty("replay.reflectionFold");

        //lognum = Configuration.config().getProperty("replay.lognum");

        int xmlFileNum = new File(xmlFold).listFiles().length;
        System.out.println("xmlFileName: " + xmlFileNum);

        if (runnerOption.isDefined() && module.equals(PathManager.modulePath().toString())) {
            System.out.println("replyPath: " + replayPath);
            System.out.println("module: " + module);

            try {
                final Runner runner = runnerOption.get(); // safe because we checked above
                System.out.println("tests!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

                 //phase 1
                Try<TestRunResult> phase1Result = null;
                try{
                    System.out.println("phase 1!!!");
                    write2tmp("1");

                    edu.illinois.cs.testrunner.configuration.Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "2");
                    phase1Result = runner.runList(victim());
                    System.out.println(phase1Result.get().results().get(dtname+":1").result());
                    edu.illinois.cs.testrunner.configuration.Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "-1");
                    System.out.println("phase 1 results: " + phase1Result.get().results());
                }
                catch(Exception e) {
                    System.out.println("error in phase 1: " + e);
                }
                System.out.println("finished phase 1!!");

                if(phase1Result.get().results().get(dtname+":1").result().toString().equals("PASS")) {
                    System.out.println("enter phase 2!!!");
                    write2tmp("2");
                    Files.write(Paths.get(output),
                            "doublevictim,".getBytes(),
                            StandardOpenOption.APPEND);
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e){
                        System.out.println("error in phase 1: " + e);
                    }
                }
                else {
                    System.out.println("enter phase 3!!!");
                    write2tmp("3");
                    Files.write(Paths.get(output),
                            "passorder,".getBytes(),
                            StandardOpenOption.APPEND);
                    try{
                        runner.runList(testPassOrder());
                    }
                    catch(Exception e) {
                        System.out.println("error in running passing order!");
                    }
                    System.out.println("finished passing order state capturing!!");
                    System.out.println("passOrder: " + testPassOrder());
                }

                System.out.println("enter phase 4!!");
                write2tmp("4");

                if(testFailOrder()==null) {
                    System.out.println("Something wrong in reading the failing order file!!");
                    return;
                }
                try {
                    runner.runList(testFailOrder());
                }
                catch(Exception e) {
                    System.out.println("error in phase 4!! " + e);
                }

                System.out.println("finish phase 4!!");
                System.out.println("FailOrder: " + testFailOrder());

                System.out.println("enter phase 5!!!");
                write2tmp("5");

                xmlFileNum = new File(xmlFold).listFiles().length;
                System.out.println("xmlFileNum: " + xmlFileNum);
                if(xmlFileNum == 2) {
                    System.out.println("begining diff!!!!!!!!!!");
                    try {
                        System.out.println("doing diff%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                        runner.runList(testFailOrder());
                        // runner.wait();
                    } catch (Exception e) {
                        System.out.println("error in failing failing order!" + e);
                    }
                }
                else {
                    System.out.println("cannot do diff, the number of xml files is not 2!!");
                }

                String diffFile = diffFieldsFold + "/0.txt";
                //create the reflection file
                File reflectionFile = new File(reflectionFold+"/0.txt");
                reflectionFile.createNewFile();

                String prefix = "diffFieldBefore ";
                boolean reflectBeforeSuccess = reflectEachField(diffFile, reflectionFile, runner, prefix);
                if(reflectBeforeSuccess) {
                    Files.write(Paths.get(output), "BeforeSuccess,".getBytes(),
                            StandardOpenOption.APPEND);
                }
                else {
                    Files.write(Paths.get(output), "BeforeFail,".getBytes(),
                            StandardOpenOption.APPEND);
                }

                prefix = "diffFieldAfter " + lastPolluter() + " ";
                boolean reflectAfterSuccess = reflectEachField(diffFile, reflectionFile, runner, prefix);
                if(reflectAfterSuccess) {
                    Files.write(Paths.get(output), "AfterSuccess,".getBytes(),
                            StandardOpenOption.APPEND);
                }
                else {
                    Files.write(Paths.get(output), "AfterFail,".getBytes(),
                            StandardOpenOption.APPEND);
                }

                /*boolean reflectionSuccess = false;
                System.out.println("enter phase 6!!!");
                write2tmp("6");

                File reflectionFile = new File(reflectionFold+"/0.txt");
                reflectionFile.createNewFile();

                String header = "*************************reflection on all fields at before state************************\n";
                Files.write(Paths.get(reflectionFile.getAbsolutePath()), header.getBytes(),
                        StandardOpenOption.APPEND);

                xmlFileNum = new File(xmlFold).listFiles().length;
                System.out.println("xmlFileNum: " + xmlFileNum);
                if(xmlFileNum == 2) {
                    System.out.println("begining reflection all!!!!!!!!!!");
                    try {
                        System.out.println("doing reflection%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                        Try<TestRunResult> result = runner.runList(testFailOrder());
                        if(result.get().results().get(dtname).result().toString().equals("PASS")) {
                            Files.write(Paths.get(output),
                                    "reflectBeforeSuccess,".getBytes(),
                                    StandardOpenOption.APPEND);
                            reflectionSuccess = true;
                        }
                        else {
                            Files.write(Paths.get(output),
                                    "reflectBeforeFail,".getBytes(),
                                    StandardOpenOption.APPEND);
                        }
                    } catch (Exception e) {
                        System.out.println("error in failing reflection!" + e);
                    }
                }
                else {
                    System.out.println("cannot do reflection, the number of xml files is not 2!!");
                }

                System.out.println("enter phase 6!!!");
                write2tmp("7 " + lastPolluter() );
                header = "*************************reflection on all fields at after state************************\n";
                Files.write(Paths.get(reflectionFile.getAbsolutePath()), header.getBytes(),
                        StandardOpenOption.APPEND);
                if(xmlFileNum == 2) {
                    System.out.println("begining reflection all as phase 7!!!!!!!!!!");
                    try {
                        System.out.println("doing reflection%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                        Try<TestRunResult> result = runner.runList(testFailOrder());
                        if(result.get().results().get(dtname).result().toString().equals("PASS")) {
                            Files.write(Paths.get(output),
                                    "reflectAfterSuccess,".getBytes(),
                                    StandardOpenOption.APPEND);
                            reflectionSuccess = true;
                        }
                        else {
                            Files.write(Paths.get(output),
                                    "reflectAfterFail,".getBytes(),
                                    StandardOpenOption.APPEND);
                        }
                    } catch (Exception e) {
                        System.out.println("error in failing reflection at phase 7!" + e);
                    }
                }
                else {
                    System.out.println("cannot do reflection on phase 7, the number of xml files is not 2!!");
                }

                if(reflectionSuccess)
                    Files.write(Paths.get(output),
                        "reflectSuccess,".getBytes(),
                        StandardOpenOption.APPEND);
                else
                    Files.write(Paths.get(output),
                            "reflectFail,".getBytes(),
                            StandardOpenOption.APPEND);*/

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

        String time = duration + ",";
        try {
            Files.write(Paths.get(output), time.getBytes(),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean reflectEachField(String diffFile, File reflectionFile, Runner runner, String prefix) throws IOException {
        boolean reflectSuccess = false;
        String header = "*************************reflection on " + prefix.split(" ")[0] + "************************\n";
        Files.write(Paths.get(reflectionFile.getAbsolutePath()), header.getBytes(),
                StandardOpenOption.APPEND);

        try (BufferedReader br = new BufferedReader(new FileReader(diffFile))) {
            String diffField;
            while ((diffField = br.readLine()) != null) {
                System.out.println(prefix + diffField);
                String s = prefix + diffField;
                write2tmp(s);
                try {
                    System.out.println("doing reflection%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                    Try<TestRunResult> result = runner.runList(testFailOrder());
                    if(result.get().results().get(dtname).result().toString().equals("PASS")) {
                        System.out.println("reflection on diffField: " + diffField + " is success!!");
                        String output = "########" + diffField + " made test success#######\n";
                        Files.write(Paths.get(reflectionFile.getAbsolutePath()), output.getBytes(),
                                StandardOpenOption.APPEND);
                        reflectSuccess = true;
                    }
                    else {
                        String output = "########" + diffField + " made test fail######\n";
                        Files.write(Paths.get(reflectionFile.getAbsolutePath()), output.getBytes(),
                                StandardOpenOption.APPEND);
                    }
                } catch (Exception e) {
                    System.out.println("error in reflection for field: "
                            + diffField + " " + e);
                }
            }
        }

        return reflectSuccess;

    }

    private void write2tmp(String s) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(tmpfile, "UTF-8");
        writer.print(s);
        writer.close();
    }

    private List<String> victim() {
        List<String> partialOrder = new ArrayList<>();
        partialOrder.add(dtname);
        return partialOrder;
    }

    private List<String> testPassOrder() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$modeluePath: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            DependentTest dt = dtl.get(0);

            List<String> partialOrder = new ArrayList<String>();
            // intended => passing order
            for(String s: dt.intended().order()) {
                partialOrder.add(s);
                if(s.equals(dt.name()))
                    break;
            }
            return partialOrder;
        } catch (Exception e) {
            System.out.println("expection in reading json!!!!!");
            return Files.readAllLines(replayPath);
        }
    }

    private List<String> testFailOrder() throws IOException {
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

    private String lastPolluter() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$replayPath2: " + replayPath2);
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            System.out.println("polluters!!!!!!!!!!!");
            for(PolluterData pd: polluters) {
                if(pd.deps().size() >=1) {
                    //failingTests.addAll(pd.deps());
                    //failingTests.add(dtname);
                    return pd.deps().get(pd.deps().size()-1);
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("expection in reading json for failing order!!!!!");
            return null;
        }
    }

}
