package edu.illinois.cs.dt.tools.utility;

import edu.illinois.cs.dt.tools.utility.deltadebug.DeltaDebugger;
import edu.illinois.cs.testrunner.data.results.Result;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import edu.illinois.cs.testrunner.runner.Runner;

import java.util.ArrayList;
import java.util.List;
import java.io.*;

public class ReflectionDeltaDebugger extends DeltaDebugger<String> {

    private final Runner runner;
    private final String dependentTest;
    private final List<String> failingOrder;
    private final List<String> diffFields;
    private final String prefix;
    private final String tmpFile;

    public ReflectionDeltaDebugger(Runner runner, String dependentTest, List<String> failingOrder, List<String> diffFields, String prefix, String tmpFile) {
        this.runner = runner;
        this.dependentTest = dependentTest;
        this.failingOrder = failingOrder;
        this.diffFields = diffFields;
        this.prefix = prefix;
        this.tmpFile = tmpFile;
    }

    @Override
    public boolean checkValid(List<String> diffFields) {
        String s = this.prefix;
        for(int i = 0 ; i < diffFields.size() ; i++) {
            if (i == 0) {
                s += diffFields.get(i);
            }
            else {
                s += " " + diffFields.get(i);
            }
        }
        try{
            write2tmp(s);
        } catch (FileNotFoundException fileNotFoundException){
            System.out.println("WRTING EXCEPTION: fileNotFoundException.");
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            System.out.println("WRTING EXCEPTION: unsupportedEncodingException.");
        }
        return result(failingOrder).toString().equals("PASS");
    }

    private Result result(final List<String> tests) {
        try {
            return runResult(tests).results().get(this.dependentTest).result();
        } catch (java.lang.IllegalThreadStateException e) {
            // indicates timeout
            return Result.SKIPPED;
        }
    }

    private TestRunResult runResult(final List<String> tests) {
        final List<String> actualOrder = new ArrayList<>(tests);

        if (!actualOrder.contains(this.dependentTest)) {
            actualOrder.add(this.dependentTest);
        }

        return this.runner.runList(actualOrder).get();
    }

    private void write2tmp(String s) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(this.tmpFile, "UTF-8");
        writer.print(s);
        writer.close();
    }
}
