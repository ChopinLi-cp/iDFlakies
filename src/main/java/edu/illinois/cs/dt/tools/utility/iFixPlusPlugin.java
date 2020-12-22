package edu.illinois.cs.dt.tools.utility;

import com.google.gson.Gson;
import com.reedoei.eunomia.io.files.FileUtil;
import edu.illinois.cs.diaper.StateCapture;
import edu.illinois.cs.dt.tools.runner.data.DependentTest;
import edu.illinois.cs.dt.tools.runner.data.DependentTestList;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
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

public class iFixPlusPlugin extends TestPlugin {
    private Path replayPath;
    private String dtname;
    private String dtjavapPath = "";
    private String xmlFold;
    private String module;
    private String output;
    private String slug;
    //private String lognum;
    @Override
    public void execute(final MavenProject mavenProject) {
        final Option<Runner> runnerOption = RunnerFactory.from(mavenProject);

        replayPath = Paths.get(Configuration.config().getProperty("replay.path"));
        dtname= Configuration.config().getProperty("replay.dtname");
        output= Configuration.config().getProperty("replay.output");
        slug= Configuration.config().getProperty("replay.slug");
        xmlFold = Configuration.config().getProperty("replay.xmlFold");
        module = Configuration.config().getProperty("replay.module");
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

                System.out.println("begin passing order state capturing!!");
                boolean passOrder = true;
                try{
                    //final Try<TestRunResult> PassRunResult =
                    runner.runList(testOrder(passOrder));
                }
                catch(Exception e) {
                    System.out.println("error in running passing order!");
                }
                System.out.println("finished passing order state capturing!!");
                System.out.println("passOrder: " + testOrder(passOrder));

                System.out.println("begin failing order state capturing!!");

                passOrder = false;
                try {
                    final Try<TestRunResult> FailRunResult =
                            runner.runList(testOrder(passOrder));
                }
                catch(Exception e) {
                    System.out.println("error in failing passing order! " + e);
                }

                System.out.println("finished failing order state capturing!!");
                System.out.println("FailOrder: " + testOrder(passOrder));


                xmlFileNum = new File(xmlFold).listFiles().length;

                System.out.println("xmlFileNum: " + xmlFileNum);
                if(xmlFileNum == 2) {
                    System.out.println("begining diff!!!!!!!!!!");
                    try {
                        passOrder = false;
                        System.out.println("doing diff%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                        runner.runList(testOrder(passOrder));
                        // runner.wait();
                    } catch (Exception e) {
                        System.out.println("error in failing failing order!" + e);
                    }
                }
                else {
                    String statistics = slug + "," + dtname + ",0,0,0,0\n";
                    Files.write(Paths.get(output), statistics.getBytes(),
                            StandardOpenOption.APPEND);
                    System.out.println("cannot do diff, the number of xml files is not 2!!");
                }


                //System.out.println("PassRunResult: " + PassRunResult.get().results().get(dtname));
                //System.out.println("FailRunResult: " + FailRunResult.get().results().get(dtname));
                //System.out.println("FixFailRunResult: " + FixFailRunResult.get().results().get(dtname));

            } catch (Exception e) {
                TestPluginPlugin.mojo().getLog().error(e);
            }

            //return;
        } else {
            TestPluginPlugin.mojo().getLog().info("Module is not using a supported test framework (probably not JUnit).");
        }
    }

    private String getdtjava() {
        String[] dts = dtname.split("\\.");
        System.out.println(dts);
        String dtjavaFile = dts[dts.length-2] + ".java";
        System.out.println("dtjavaFile: " + dtjavaFile);
        return dtjavaFile;
    }

    private boolean searchFile(File file, String dtjava) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (File f : files) {
                boolean found = searchFile(f, dtjava);
                if (found) {
                    return true;
                }
            }
        } else {
            if (dtjava.equals(file.getName())) {
                System.out.println("dt java file found!!!!");
                dtjavapPath = file.getParent();
                return true;
            }
        }
        return false;
    }

    private List<String> testOrder(boolean passOrder) throws IOException {
        try {
            System.out.println("$$$$$$$$$$$modeluePath: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            DependentTest dt = dtl.get(0);

            List<String> partialOrder = new ArrayList<String>();
            if(passOrder)
                for(String s: dt.intended().order()) {
                    partialOrder.add(s);
                    if(s.equals(dt.name()))
                        break;
                }
            else
                for(String s: dt.revealed().order()) {
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

}
