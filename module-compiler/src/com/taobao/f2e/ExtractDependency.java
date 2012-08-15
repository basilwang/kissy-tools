package com.taobao.f2e;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import org.apache.commons.cli.*;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Extract dependencies from code files.
 *
 * @author yiminghe@gmail.com
 * @since 2012-08-07
 */
public class ExtractDependency {

    /**
     * packages.
     */
    private Packages packages = new Packages();

    /**
     * exclude pattern for modules.
     */
    private Pattern excludePattern;

    /**
     * include pattern for modules.
     */
    private Pattern includePattern;

    /**
     * dependency output file.
     */
    private String output;

    /**
     * dependency 's output file encoding.
     */
    private String outputEncoding = "utf-8";

    /**
     * whether overwrite module's file with module name added.
     */
    private boolean fixModuleName = false;

    private HashMap<String, ArrayList<String>> dependencyCode = new HashMap<String, ArrayList<String>>();

    static String CODE_PREFIX = "/*Generated by KISSY Module Compiler*/\n" +
            "if(KISSY.Loader){\nKISSY.config('modules', {\n";
    static String CODE_SUFFIX = "\n});\n}";

    /**
     * dom/src -> dom
     * event/src -> event
     */
    private HashMap<String, Pattern> nameMap = new HashMap<String, Pattern>();

    public Packages getPackages() {
        return packages;
    }

    public void setExcludePattern(Pattern excludePattern) {
        this.excludePattern = excludePattern;
    }

    public void setIncludePattern(Pattern includePattern) {
        this.includePattern = includePattern;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void setOutputEncoding(String outputEncoding) {
        this.outputEncoding = outputEncoding;
    }

    public void setFixModuleName(boolean fixModuleName) {
        this.fixModuleName = fixModuleName;
    }

    private boolean isModuleTobeProcessed(Node root) {
        Node t;
        if (root == null) {
            return false;
        }
        if (root.getType() != Token.SCRIPT) {
            return false;
        }
        t = root.getFirstChild();
        if (t == null) {
            return false;
        }
        if (t.getType() != Token.EXPR_RESULT) {
            return false;
        }
        t = t.getFirstChild();
        if (t == null) {
            return false;
        }
        if (t.getType() != Token.CALL) {
            return false;
        }
        t = t.getFirstChild();
        if (t == null) {
            return false;
        }
        if (t.getType() != Token.GETPROP) {
            return false;
        }

        // t.getNext(); => module name . str,type==STRING

        t = t.getFirstChild();

        if (t == null) {
            return false;
        }
        if (t.getType() != Token.NAME) {
            return false;
        }
        if (!t.getString().equals("KISSY")) {
            return false;
        }


        t = t.getNext();

        if (t == null) {
            return false;
        }
        if (t.getType() != Token.STRING) {
            return false;
        }

        if (!t.getString().equals("add")) {
            return false;
        }

        return true;
    }

    private String getModuleNameFromAst(Node root) {
        Node t;
        if (root == null) {
            return null;
        }
        if (root.getType() != Token.SCRIPT) {
            return null;
        }
        t = root.getFirstChild();
        if (t == null) {
            return null;
        }
        if (t.getType() != Token.EXPR_RESULT) {
            return null;
        }
        t = t.getFirstChild();
        if (t == null) {
            return null;
        }
        if (t.getType() != Token.CALL) {
            return null;
        }
        t = t.getFirstChild();
        if (t == null) {
            return null;
        }
        if (t.getType() != Token.GETPROP) {
            return null;
        }

        t = t.getNext();

        if (t == null) {
            return null;
        }
        if (t.getType() != Token.STRING) {
            return null;
        }

        return t.getString();
    }


    private String getModuleNameFromPath(String path) {
        path = FileUtils.escapePath(path);
        String[] baseUrls = packages.getBaseUrls();
        int finalIndex = -1, curIndex = -1;
        String finalBase = "";
        for (String baseUrl : baseUrls) {
            curIndex = path.indexOf(baseUrl, 0);
            if (curIndex > finalIndex) {
                finalIndex = curIndex;
                finalBase = baseUrl;
            }
        }
        if (curIndex != -1) {
            return FileUtils.removeSuffix(path.substring(finalBase.length()));
        }

        return null;

    }

    private void processSingle(String path, String encoding) {
        String content = FileUtils.getFileContent(path, encoding);
        Node root = null;
        try {
            root = AstUtils.parse(content, path);
        } catch (Exception e) {
            System.out.println("invalid js file: " + path);
            e.printStackTrace();
            System.exit(1);
        }

        if (!isModuleTobeProcessed(root)) {
            return;
        }

        String name;

        name = getModuleNameFromAst(root);

        if (name == null) {
            name = getModuleNameFromPath(path);
            if (fixModuleName && name != null) {
                Module m = new Module();
                m.setFullpath(path);
                m.setName(name);
                m.completeModuleName();
                m.updateCodeToFile();
            }
        }

        if (name == null) {
            System.out.println("can not get " +
                    "module name from js file: " + path);
            return;
        }

        if (excludePattern != null &&
                excludePattern.matcher(name).matches()) {
            return;
        }

        if (includePattern != null &&
                !includePattern.matcher(name).matches()) {
            return;
        }

        String[] requires = ModuleUtils.getRequiresFromAst(root, name);
        if (requires.length > 0) {

            dependencyCode.put(name, new ArrayList<String>(Arrays.asList(requires)));

        }
    }

    /**
     * generate code list by module dependency map
     */
    private ArrayList<String> formCodeList() {
        ArrayList<String> codes = new ArrayList<String>();
        Set<String> keys = dependencyCode.keySet();
        for (String name : keys) {
            ArrayList<String> requires = dependencyCode.get(name);
            if (requires.size() > 0) {
                String allRs = "";
                for (String r : requires) {
                    if (!r.startsWith("#")) {
                        allRs += ",'" + r + "'";
                    } else {
                        allRs += "," + r.substring(1);
                    }
                }
                codes.add("'" + name + "': {requires: [" + allRs.substring(1) + "]}");
            }
        }
        return codes;
    }

    private void putToDependency(HashMap<String, ArrayList<String>> dependencyCode2,
                                 String name, ArrayList<String> requires) {
        if (requires.contains(name)) {
            requires.remove(name);
        }

        ArrayList<String> old = dependencyCode2.get(name);

        if (old == null) {
            old = new ArrayList<String>();
        }

        for (String require : old) {
            if (require.equals(name)) {
                old.remove(require);
            }
        }

        for (String require : requires) {
            if (!old.contains(require) && !require.equals(name)) {
                old.add(require);
            }
        }

        dependencyCode2.put(name, old);
    }

    private String transformByNameMap(String name) {
        Set<String> mapKeys = nameMap.keySet();
        for (String mapKey : mapKeys) {
            Pattern mapReg = nameMap.get(mapKey);
            if (mapReg.matcher(name).matches()) {
                mapKey = mapReg.matcher(name).replaceAll(mapKey);
                return mapKey;
            }
        }
        return name;
    }

    private ArrayList<String> transformByNameMap(ArrayList<String> requires) {
        for (int i = 0; i < requires.size(); i++) {
            requires.set(i, transformByNameMap(requires.get(i)));
        }
        return requires;
    }

    /**
     * merge dependency with in nameMap
     */
    private void mergeNameMap() {
        if (nameMap != null) {
            HashMap<String, ArrayList<String>> dependencyCode2 = new HashMap<String, ArrayList<String>>();
            Set<String> keys = dependencyCode.keySet();
            for (String name : keys) {
                putToDependency(dependencyCode2, transformByNameMap(name),
                        transformByNameMap(dependencyCode.get(name)));
            }

            this.dependencyCode = dependencyCode2;
        }
    }

    public static void commandRunnerCLI(String[] args) throws Exception {

        Options options = new Options();
        options.addOption("encodings", true, "baseUrls 's encodings");
        options.addOption("baseUrls", true, "baseUrls");
        options.addOption("excludeReg", true, "excludeReg");
        options.addOption("includeReg", true, "includeReg");
        options.addOption("nameMap", true, "nameMap");
        options.addOption("output", true, "output");
        options.addOption("v", "version", false, "version");
        options.addOption("outputEncoding", true, "outputEncoding");
        options.addOption("fixModuleName", true, "fixModuleName");
        // create the command line parser
        CommandLineParser parser = new GnuParser();
        CommandLine line;

        try {
            // parse the command line arguments
            line = parser.parse(options, args);
        } catch (ParseException exp) {
            System.out.println("Unexpected exception:" + exp.getMessage());
            return;
        }

        if (line.hasOption("v")) {
            System.out.println("KISSY Dependency Extractor 2.0");
            return;
        }

        ExtractDependency m = new ExtractDependency();

        Packages packages = m.getPackages();

        String encodingStr = line.getOptionValue("encodings");
        if (encodingStr != null) {
            packages.setEncodings(encodingStr.split(","));
        }

        String baseUrlStr = line.getOptionValue("baseUrls");
        if (baseUrlStr != null) {
            packages.setBaseUrls(baseUrlStr.split(","));
        }

        String fixModuleName = line.getOptionValue("fixModuleName");
        if (fixModuleName != null) {
            m.setFixModuleName(true);
        }

        String excludeReg = line.getOptionValue("excludeReg");
        if (excludeReg != null) {
            excludeReg = excludeReg.replaceAll("\\s", "");
            m.setExcludePattern(Pattern.compile(excludeReg));
        }

        String includeReg = line.getOptionValue("includeReg");
        if (includeReg != null) {
            includeReg = includeReg.replaceAll("\\s", "");
            m.setIncludePattern(Pattern.compile(includeReg));
        }

        m.setOutput(line.getOptionValue("output"));

        String outputEncoding = line.getOptionValue("outputEncoding");
        if (outputEncoding != null) {
            m.setOutputEncoding(outputEncoding);
        }

        String nameMapStr = line.getOptionValue("nameMap");

        if (nameMapStr != null) {
            constructNameMapFromString(nameMapStr, m.nameMap);
        }

        m.run();

    }

    static void constructNameMapFromString(String nameMapStr, HashMap<String, Pattern> nameMap) {
        String[] names = nameMapStr.split(",,");
        for (String n : names) {
            String[] ns = n.split("\\|\\|");
            nameMap.put(ns[1], Pattern.compile(ns[0]));
        }
    }

    public void run() {
        long start = System.currentTimeMillis();
        String[] baseUrls = packages.getBaseUrls();
        String[] encodings = packages.getEncodings();

        int index = 0;

        for (String baseUrl : baseUrls) {
            Collection<File> files = org.apache.commons.io.FileUtils.listFiles(new File(baseUrl),
                    new String[]{"js"}, true);

            for (File f : files) {
                processSingle(f.getPath(), encodings.length > index ? encodings[index] : "utf-8");

            }

            index++;
        }

        // merge by name map
        mergeNameMap();

        // get code list
        ArrayList<String> codes = formCodeList();

        // serialize to file
        FileUtils.outputContent(
                CODE_PREFIX +
                        ArrayUtils.join(codes.toArray(new String[codes.size()]), ",\n")
                        + CODE_SUFFIX
                , output, outputEncoding);


        System.out.println("dependency file saved to: " + output);
        System.out.println("duration: " + (System.currentTimeMillis() - start));
    }

    public static void testMain() throws Exception {

        ExtractDependency m = new ExtractDependency();
        String path;
        path = ExtractDependency.class.getResource("/").getFile() + "../../../tests/kissy_combo/";
        System.out.println(new File(path).getCanonicalPath());
        m.getPackages().setBaseUrls(new String[]{
                FileUtils.escapePath(new File(path).getCanonicalPath())
        });
        m.setOutput(path + "deps.js");
        m.setOutputEncoding("utf-8");

        String deps = "([\\w-]+)(?:/.*)?||$1";


        constructNameMapFromString(deps, m.nameMap);

        m.run();
    }


    public static void testKISSY() throws Exception {

        ExtractDependency m = new ExtractDependency();
        String path;

        path = "d:\\code\\kissy_git\\kissy\\src\\";
        m.getPackages().setBaseUrls(new String[]{
                path
        });
        m.setOutput(path + "seed/src/dependency.js");
        m.setOutputEncoding("utf-8");
        m.getPackages().setEncodings(new String[]{
                "utf-8"
        });

        String deps = "([\\w-]+)(?:/.*)?||$1";

        constructNameMapFromString(deps, m.nameMap);

        m.setIncludePattern(Pattern.compile("dom(/.*)?"));

        m.run();
    }

    public static void main(String[] args) throws Exception {
        commandRunnerCLI(args);

        //testMain();

        //testKISSY();
    }
}
