package edu.illinois.cs.dt.tools.utility;

import soot.*;
import soot.Hierarchy;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.jimple.spark.ondemand.pautil.SootUtil;

import soot.options.Options;
import soot.tagkit.AnnotationTag;
import soot.tagkit.VisibilityAnnotationTag;
import soot.util.queue.QueueReader;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static soot.SootClass.BODIES;

public class SootAnalysis {

    private static String sourceDirectory;
    private static String clzName;
    private static String methodName;
    private static LinkedList<String> excludeList;


    private static LinkedList<String> getExcludeList() {
        if (excludeList == null) {
            excludeList = new LinkedList<String>();

            // explicitly include packages for shorter runtime:
            excludeList.add("java.*");
            excludeList.add("javax.*");
            excludeList.add("jdk.*");
            excludeList.add("soot.*");
            excludeList.add("sun.*");
            excludeList.add("sunw.*");
            excludeList.add("com.sun.*");
            excludeList.add("com.ibm.*");
            excludeList.add("com.apple.*");
            excludeList.add("android.*");
            excludeList.add("apple.awt.*");
            excludeList.add("org.apache.*");
            excludeList.add("org.xml.*");
            excludeList.add("org.codehaus.*");
        }
        return excludeList;
    }

    private static boolean inExcludeList(String className) {
        for (int i = 0; i < excludeList.size(); i++) {
            String libPackage = excludeList.get(i).substring(0, excludeList.get(i).length()-1);
            if (className.startsWith(libPackage)) {
                return true;
            }

        }
        return false;
    }

    private static void excludeJDKLibrary() {
        // exclude jdk classes
        Options.v().set_exclude(getExcludeList());
        // this option must be disabled for a sound call graph
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_allow_phantom_refs(true);
    }

    private static void setupSoot() {
        G.reset();

        // System.out.println("EXCLUDE: " + Options.v().exclude());
        excludeJDKLibrary();
        // System.out.println("EXCLUDE: " + Options.v().exclude());

        Options.v().set_prepend_classpath(true);
        Options.v().set_app(true);
        Options.v().set_soot_classpath(sourceDirectory);
        Options.v().set_output_format(Options.output_format_jimple);
        // Options.v().set_process_dir(Collections.singletonList(sourceDirectory));
        Options.v().set_whole_program(true);
        // Options.v().set_allow_phantom_refs(true); // especially for wildfly

    }

    private static boolean reportFieldRefInfo(Stmt stmt, final Map<String, Set<String>> affectedClassesToFields) {
        final boolean[] reachStaticFields = {false};
        FieldRef fieldRef = stmt.getFieldRef();
        // System.out.println("FIELDREF: " + fieldRef);
        fieldRef.apply(new AbstractRefSwitch() {
            @Override
            public void caseStaticFieldRef(StaticFieldRef v) {
                // A static field reference
                // System.out.println("A static field reference: " + v.getFieldRef() + " " + v.getFieldRef().isStatic());
                if (SootUtil.inLibrary(v.getFieldRef().declaringClass().getName()) || inExcludeList(v.getFieldRef().declaringClass().getName())) {
                    return;
                }
                String className = v.getFieldRef().declaringClass().getName();
                String fieldName = v.getField().getName();
                Set<String> fieldNames = new HashSet<>();
                if (!affectedClassesToFields.containsKey(className)) {
                    fieldNames.add(fieldName);
                } else {
                    fieldNames = affectedClassesToFields.get(className);
                    fieldNames.add(fieldName);
                }
                affectedClassesToFields.put(className, fieldNames);
                reachStaticFields[0] = true;
                // affectedClasses.add(v.getField().getName());
            }

            @Override
            public void caseInstanceFieldRef(InstanceFieldRef v) {
                // System.out.println("A instance field reference: " + v.getFieldRef() + " " + v.toString());
                // affectedClasses.add(v.getFieldRef().declaringClass().getName());
            }
        });
        return reachStaticFields[0];
    }

    private static boolean hasBeforeOrAfterAnnotation(SootMethod sootMethod) {
        boolean hasAnnotation = false;
        VisibilityAnnotationTag tag = (VisibilityAnnotationTag) sootMethod.getTag("VisibilityAnnotationTag");
        if (tag != null) {
            for (AnnotationTag annotation : tag.getAnnotations()) {
                // System.out.println("annotation.getType(): " + annotation.getType());
                if (annotation.getType().equals("Lorg/junit/Before;") || annotation.getType().equals("Lorg/junit/After;")
                        || annotation.getType().equals("Lorg/junit/BeforeClass;") || annotation.getType().equals("Lorg/junit/AfterClass;")
                        || annotation.getType().equals("Lorg/junit/BeforeEach;") || annotation.getType().equals("Lorg/junit/AfterEach;")
                        || annotation.getType().equals("Lorg/junit/BeforeAll;") || annotation.getType().equals("Lorg/junit/AfterAll;")) {
                    // System.out.println("annotation.getType(): " + annotation.getType() + " " + sootMethod.getDeclaringClass() + "." + sootMethod.getName());
                    hasAnnotation = true;
                    break;
                }
            }
        }
        return hasAnnotation;
    }

    public static boolean detectAffectedClasses(CallGraph callGraph, List<SootMethod> tmpEntryPoints, Map<String, Set<String>> affectedClassesToFields) {
        boolean flag = false;

        ReachableMethods rm = new ReachableMethods(callGraph, tmpEntryPoints);
        rm.update();
        QueueReader qr = rm.listener();

        // System.out.println("-------------------");
        // System.out.println("-----Reachable Methods-----");

        // qr = rm.listener();
        for(Iterator<SootMethod> it = qr; it.hasNext(); ) {
            try {
                SootMethod reachableMethod = it.next();
                /// System.out.println("REACH: " + reachableMethod.getName());
                if (SootUtil.inLibrary(reachableMethod.getDeclaringClass().getName()) || inExcludeList(reachableMethod.getDeclaringClass().getName())) {
                    continue;
                }
                if(reachableMethod.isPhantom()) {
                    continue;
                }
                JimpleBody reachableMethodBody = (JimpleBody) reachableMethod.retrieveActiveBody();
                for (Unit u : reachableMethodBody.getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (stmt.containsFieldRef()) {
                        boolean reachStaticFields = reportFieldRefInfo(stmt, affectedClassesToFields);
                        if (reachStaticFields) {
                            flag = true;
                        }
                    }
                }
            } catch (Exception e) {
                // System.out.println("LIKELY ERROR: cannot get resident body for phantom method");
                e.printStackTrace();
            }
        }
        return flag;
    }

    public static void putEntryPoints(SootClass sc, List<SootMethod> ep) {
        try {
            // Get clinits
            for (SootMethod sm : EntryPoints.v().clinitsOf(sc)) {
                ep.add(sm);
            }
        } catch (Exception e) {
            // System.out.println("CLINIT METHOD MAY NOT EXIST!");
            e.printStackTrace();
        }
        try {
            SootMethod init = sc.getMethod("<init>", new ArrayList<>());
            ep.add(init);
        } catch (Exception e) {
            // System.out.println("INIT METHOD MAY NOT EXIST!");
            e.printStackTrace();
        }
        for (SootMethod sootMethod : sc.getMethods()) {
            try {
                if (hasBeforeOrAfterAnnotation(sootMethod)) {
                    ep.add(sootMethod);
                }
            } catch (Exception e){
                // System.out.println("BUG EXISTS WHEN DETECTING @BEFORE ANNOTATIONS!");
                e.printStackTrace();
            }
        }
    }

    public static Map<String, Set<String>> analysis(String srcDir, String clsName, Map<String, List<String>> testClassToMethod, boolean fineGranularity, List<String> selectedTests) {
        // Set<String> affectedClasses = new HashSet<>();
        Map<String, Set<String>> affectedClassesToFields = new HashMap<>();

        List<SootMethod> entryPoints = new ArrayList();
        List<SootMethod> tmpEntryPoints = new ArrayList();

        sourceDirectory = srcDir;
        clzName = clsName;

        setupSoot();
        SootClass sc = Scene.v().forceResolve(clzName, BODIES);// Scene.v().loadClassAndSupport(clsName);
        sc.setApplicationClass();
        Scene.v().loadNecessaryClasses();

        putEntryPoints(sc, entryPoints);
        putEntryPoints(sc, tmpEntryPoints);

        Map<String, SootMethod> testNameToSootMethod = new HashMap<>();
        // Add the tests
        for (String test : testClassToMethod.get(clzName)) {
            String testName = test.substring(test.lastIndexOf(".") + 1);
            try {
                SootMethod sm = sc.getMethodByName(testName);
                entryPoints.add(sm);
                testNameToSootMethod.put(test, sm);
            } catch (Exception e) {
                // e.printStackTrace();
                if (sc.hasSuperclass()) {
                    Hierarchy hierarchy = Scene.v().getActiveHierarchy();
                    for (SootClass upperSootClass: hierarchy.getSuperclassesOf(sc)) {
                        // System.out.println("sc.hasSuperclass()");
                        // SootClass upperSootClass = sc.getSuperclass();
                        try {
                            SootMethod sm = upperSootClass.getMethodByName(testName);
                            entryPoints.add(sm);
                            testNameToSootMethod.put(test, sm);
                            // Do a loop on super classes ...
                            if (!Scene.v().containsClass(upperSootClass.getName())) {
                                Scene.v().addClass(upperSootClass);
                                Scene.v().forceResolve(upperSootClass.getName(), BODIES);
                                upperSootClass.setApplicationClass();
                                putEntryPoints(upperSootClass, entryPoints);
                                putEntryPoints(upperSootClass, tmpEntryPoints);
                            }
                            break;
                        } catch (Exception methodNotFoundException) {
                            methodNotFoundException.printStackTrace();
                        }
                    }
                }
            }
        }
        Scene.v().setEntryPoints(entryPoints);
        PackManager.v().runPacks();

        // Call graph
        CallGraph callGraph = Scene.v().getCallGraph();
        if (!fineGranularity) {
            detectAffectedClasses(callGraph, entryPoints, affectedClassesToFields);
            return affectedClassesToFields;
        }
        detectAffectedClasses(callGraph, tmpEntryPoints, affectedClassesToFields);

        if (!affectedClassesToFields.keySet().isEmpty()) {
            selectedTests.addAll(testClassToMethod.get(clzName));
            return affectedClassesToFields; // affectedClasses also contain the classes reachable from the test methods
        }
        else {
            for (String test : testClassToMethod.get(clzName)) {
                tmpEntryPoints.clear();
                // remove the tmpEntryPoints from entryPoints to get the Soot Method ...
                try {
                    tmpEntryPoints.add(testNameToSootMethod.get(test));
                } catch (Exception e) {
                    // e.printStackTrace();
                }
                boolean reachStaticFields = detectAffectedClasses(callGraph, tmpEntryPoints, affectedClassesToFields);
                // tmpEntryPoints.remove(sm);
                if (reachStaticFields) {
                    // System.out.println("CLASSTOTEST2: " + clzName + "; " + test );
                    selectedTests.add(test);
                }
            }
        }

        return affectedClassesToFields;
    }

}

