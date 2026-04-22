package com.iast.agent;

import com.iast.agent.config.MonitorRuleConfig;
import com.iast.agent.config.OutputConfig;
import com.iast.agent.config.YamlRootConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 监控配置管理类
 * 支持从外部配置文件加载监控规则
 */
public class MonitorConfig {
    private static final Map<String, List<MethodRule>> monitorRules = new HashMap<>();
    // 类名 -> 插件名称列表（支持同一个方法挂多个插件，按YAML声明顺序依次调用）
    private static final Map<String, List<String>> classPluginMap = new HashMap<>();
    // 插件名 -> 该插件的所有规则配置块（已附带className/methods信息）
    private static final Map<String, List<Map<String, Object>>> pluginConfigs = new HashMap<>();
    // internal className -> matchType（"exact" 或 "interface"），默认缺省视为 "exact"
    private static final Map<String, String> classMatchType = new HashMap<>();
    // internal className -> 是否启用 ServletBody 包装（仅对 HttpServlet.service 规则有意义）
    private static final Map<String, Boolean> classWrapServletRequest = new HashMap<>();
    // 全局开关：matchType=interface 时，是否把安装后新加载的实现类也纳入监控
    private static volatile boolean includeFutureClasses = false;
    // premain 模式下字节码 install 的延迟毫秒数，默认 1 分钟
    private static volatile long premainDelayMs = 60_000L;
    // 外部插件目录（存放 plugin jar）。空字符串 = 不加载外部插件
    private static volatile String pluginsDir = "";
    // 规则目录（每个 yaml 文件 multi-doc 规则）。空字符串 = 不从目录加载
    private static volatile String rulesDir = "";
    // internal className -> 该类下所有规则的 id 列表（id 缺省时该 rule 不计入；CLI rules 命令展示用）
    private static final Map<String, List<String>> classRuleIds = new HashMap<>();
    private static final String DEFAULT_YAML_CONFIG_PATH = "iast-monitor.yaml";
    private static final String DEFAULT_PROPERTIES_CONFIG_PATH = "iast-monitor.properties";
    private static String configFilePath = DEFAULT_YAML_CONFIG_PATH;

    /** CLI 命令要用（status 打印当前生效的配置文件路径）；init() 执行过后才有意义。 */
    public static String getConfigFilePath() {
        return configFilePath;
    }

    // 输出控制选项
    private static boolean outputArgs = true;
    private static boolean outputReturn = true;
    private static boolean outputStacktrace = true;
    private static int stacktraceDepth = 8;

    static {
        // 默认兜底配置
        addDefaultRule();
    }

    /**
     * 初始化配置，支持从agent参数指定配置文件路径
     */
    public static void init(String agentArgs) {
        // 解析agent参数，支持指定config路径
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                if (arg.startsWith("config=")) {
                    configFilePath = arg.substring("config=".length()).trim();
                    break;
                }
            }
        }
        
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    private static void loadConfig() {
        File configFile = new File(configFilePath);
        // 如果是默认配置路径，yaml不存在则尝试找properties配置
        if (!configFile.exists() && DEFAULT_YAML_CONFIG_PATH.equals(configFilePath)) {
            File propertiesConfig = new File(DEFAULT_PROPERTIES_CONFIG_PATH);
            if (propertiesConfig.exists()) {
                configFilePath = DEFAULT_PROPERTIES_CONFIG_PATH;
                configFile = propertiesConfig;
                LogWriter.getInstance().info("[IAST Agent] Use default properties config: " + DEFAULT_PROPERTIES_CONFIG_PATH);
            }
        }
        if (!configFile.exists()) {
            LogWriter.getInstance().info("[IAST Agent] Config file not found at " + configFilePath + ", using default rules");
            return;
        }

        try (InputStream is = new FileInputStream(configFile)) {
            monitorRules.clear();
            
            // 判断配置文件类型：yaml/yml还是properties
            if (configFilePath.toLowerCase().endsWith(".yaml") || configFilePath.toLowerCase().endsWith(".yml")) {
                // 加载YAML格式配置
                Yaml yaml = new Yaml();
                YamlRootConfig rootConfig = yaml.loadAs(is, YamlRootConfig.class);
                processYamlConfig(rootConfig);
            } else {
                // 加载Properties格式配置（兼容旧版本）
                Properties props = new Properties();
                props.load(is);
                processPropertiesConfig(props);
            }
            
            if (monitorRules.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] No valid monitor rules found in config, using default rules");
                addDefaultRule();
            }
        } catch (Exception e) {
            LogWriter.getInstance().info("[IAST Agent] Failed to load config file: " + e.getMessage());
            LogWriter.getInstance().info("[IAST Agent] Exception: " + e.toString());
            // 加载失败用默认配置
            addDefaultRule();
        }
    }

    /**
     * 处理YAML格式配置
     */
    private static void processYamlConfig(YamlRootConfig rootConfig) {
        if (rootConfig == null) {
            return;
        }

        // 解析输出配置
        OutputConfig outputConfig = rootConfig.getOutput();
        if (outputConfig != null) {
            outputArgs = outputConfig.isArgs();
            outputReturn = outputConfig.isReturn();
            outputStacktrace = outputConfig.isStacktrace();
            stacktraceDepth = outputConfig.getStacktraceDepth();
            if (outputConfig.getEventsPath() != null && !outputConfig.getEventsPath().isEmpty()) {
                com.iast.agent.plugin.event.EventWriter.getInstance().setEventsPath(outputConfig.getEventsPath());
            }
            // 日志级别从 yaml 应用——本调用要早于"Loaded monitor rule"等后续 info 日志，
            // 这样若用户配 logLevel: debug，整个 init 阶段的 debug 日志也能被记录。
            if (outputConfig.getLogLevel() != null && !outputConfig.getLogLevel().isEmpty()) {
                LogWriter.getInstance().setLevel(outputConfig.getLogLevel());
            }
        }

        // 解析全局 default 配置
        if (rootConfig.getMonitor() != null && rootConfig.getMonitor().getDefault() != null) {
            com.iast.agent.config.MonitorDefaultConfig defCfg = rootConfig.getMonitor().getDefault();
            includeFutureClasses = defCfg.isIncludeFutureClasses();
            if (includeFutureClasses) {
                LogWriter.getInstance().info("[IAST Agent] includeFutureClasses=true: interface rules will also match classes loaded after agent install");
            }
            long d = defCfg.getPremainDelayMs();
            premainDelayMs = Math.max(0L, d);
            if (premainDelayMs != 60_000L) {
                LogWriter.getInstance().info("[IAST Agent] premainDelayMs override: " + premainDelayMs + "ms");
            }
            String pd = defCfg.getPluginsDir();
            pluginsDir = pd == null ? "" : pd.trim();
            // 相对路径按**本 yaml 文件所在目录**解析，不是 JVM 的 CWD——避免被业务进程启动目录
            // 坑了（尤其是 agentmain attach 场景下调用方的 CWD 常常是控制台当前目录）
            if (!pluginsDir.isEmpty() && !new File(pluginsDir).isAbsolute()) {
                File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                if (cfgParent != null) {
                    pluginsDir = new File(cfgParent, pluginsDir).getAbsolutePath();
                }
            }
            if (!pluginsDir.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] pluginsDir: " + pluginsDir);
            }

            // rulesDir：和 pluginsDir 同一套相对路径解析
            String rd = defCfg.getRulesDir();
            rulesDir = rd == null ? "" : rd.trim();
            if (!rulesDir.isEmpty() && !new File(rulesDir).isAbsolute()) {
                File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                if (cfgParent != null) {
                    rulesDir = new File(cfgParent, rulesDir).getAbsolutePath();
                }
            }
            if (!rulesDir.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] rulesDir: " + rulesDir);
            }
        }

        // inline monitor.rules: 已废弃。检测到老 yaml 仍有该节就 WARN，但不解析。
        if (rootConfig.getMonitor() != null && rootConfig.getMonitor().getRules() != null
                && !rootConfig.getMonitor().getRules().isEmpty()) {
            LogWriter.getInstance().warn("[IAST Agent] inline 'monitor.rules:' is no longer supported "
                    + "(found " + rootConfig.getMonitor().getRules().size() + " rule(s)); "
                    + "move them to monitor.default.rulesDir directory (one rule per yaml doc, multi-doc with '---')");
        }

        // 从 rulesDir 加载所有规则
        if (!rulesDir.isEmpty()) {
            loadRulesDir(rulesDir);
        }
    }

    /**
     * 扫一个目录，加载所有 *.yaml/*.yml 里的规则（multi-doc，用 --- 分割每条规则）。
     * 文件按文件名字典序处理；某个文件 IO/解析失败会 WARN+继续。
     */
    private static void loadRulesDir(String absDirPath) {
        File dir = new File(absDirPath);
        if (!dir.exists()) {
            LogWriter.getInstance().warn("[IAST Agent] rulesDir does not exist: " + absDirPath);
            return;
        }
        if (!dir.isDirectory()) {
            LogWriter.getInstance().warn("[IAST Agent] rulesDir is not a directory: " + absDirPath);
            return;
        }
        File[] files = dir.listFiles((d, name) -> {
            String low = name.toLowerCase();
            return low.endsWith(".yaml") || low.endsWith(".yml");
        });
        if (files == null || files.length == 0) {
            LogWriter.getInstance().info("[IAST Agent] rulesDir is empty (no *.yaml/*.yml): " + absDirPath);
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            int loaded = 0;
            try (InputStream in = new FileInputStream(f)) {
                // 用 typed Constructor —— 关键：避免 SnakeYAML 把 `on:`/`yes:`/`no:` 这类 YAML 1.1 关键字
                // 在 Map key 位置自动 resolve 成 Boolean。typed 路径下 pluginConfig 的 key 保持 String。
                Yaml y = new Yaml(new org.yaml.snakeyaml.constructor.Constructor(
                        MonitorRuleConfig.class, new org.yaml.snakeyaml.LoaderOptions()));
                for (Object doc : y.loadAll(in)) {
                    if (doc == null) continue;  // 空 doc（典型为文件头/尾的空 ---）
                    if (!(doc instanceof MonitorRuleConfig)) {
                        LogWriter.getInstance().warn("[IAST Agent] rule doc in " + f.getName()
                                + " is not a rule (got " + doc.getClass().getSimpleName() + "); skip");
                        continue;
                    }
                    MonitorRuleConfig rule = (MonitorRuleConfig) doc;
                    if (rule.getClassName() == null || rule.getClassName().isEmpty()
                            || rule.getMethods() == null || rule.getMethods().isEmpty()) {
                        LogWriter.getInstance().warn("[IAST Agent] rule [id=" + rule.getId() + "] in "
                                + f.getName() + " missing required fields (className/methods); skip");
                        continue;
                    }
                    applyRule(rule, f.getName());
                    loaded++;
                }
            } catch (Exception e) {
                LogWriter.getInstance().warn("[IAST Agent] failed to load rules from " + f.getName()
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            LogWriter.getInstance().info("[IAST Agent] Loaded " + loaded + " rule(s) from " + f.getName());
        }
    }

    /**
     * 把一条 rule 合到全局状态里（monitorRules / classPluginMap / classMatchType /
     * classWrapServletRequest / pluginConfigs / classRuleIds）。所有规则源（inline 已废弃，
     * 当前只剩 rulesDir）共用本路径，保证语义一致。
     */
    private static void applyRule(MonitorRuleConfig rule, String origin) {
        String className = rule.getClassName();
        String methodRuleStr = String.join(",", rule.getMethods());
        List<MethodRule> methods = parseMethodRules(methodRuleStr);
        if (methods.isEmpty()) {
            LogWriter.getInstance().warn("[IAST Agent] rule [id=" + rule.getId() + "] in " + origin
                    + " has no parseable methods; skip");
            return;
        }
        String internalClassName = className.replace('.', '/');

        // 同一个类多条规则，合并方法列表（去重）
        List<MethodRule> existing = monitorRules.computeIfAbsent(internalClassName, k -> new ArrayList<>());
        for (MethodRule m : methods) {
            boolean dup = false;
            for (MethodRule e : existing) {
                if (e.getMethodName().equals(m.getMethodName())
                        && e.getDescriptor().equals(m.getDescriptor())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) existing.add(m);
        }

        // 记录 matchType（先声明胜出 + warn 冲突）
        String ruleMatchType = normalizeMatchType(rule.getMatchType());
        String existingMatchType = classMatchType.get(internalClassName);
        if (existingMatchType == null) {
            classMatchType.put(internalClassName, ruleMatchType);
        } else if (!existingMatchType.equals(ruleMatchType)) {
            LogWriter.getInstance().warn("[IAST Agent] conflicting matchType for " + className
                    + " (existing=" + existingMatchType + ", new=" + ruleMatchType + " from " + origin + "), keeping existing");
        }

        // wrapServletRequest 任一为 true → 整个 className 走包装
        if (rule.isWrapServletRequest()) {
            classWrapServletRequest.put(internalClassName, Boolean.TRUE);
        }

        // 插件名（缺省 LogPlugin），按声明顺序累加
        String pluginName = rule.getPlugin();
        if (pluginName == null || pluginName.isEmpty()) {
            pluginName = "LogPlugin";
        }
        List<String> pluginList = classPluginMap.computeIfAbsent(internalClassName, k -> new ArrayList<>());
        if (!pluginList.contains(pluginName)) {
            pluginList.add(pluginName);
        }

        // pluginConfig 块（附带 className/methods，给插件 init 用）
        if (rule.getPluginConfig() != null && !rule.getPluginConfig().isEmpty()) {
            Map<String, Object> block = new HashMap<>(rule.getPluginConfig());
            block.putIfAbsent("className", className);
            block.putIfAbsent("methods", rule.getMethods());
            if (rule.getId() != null) block.putIfAbsent("ruleId", rule.getId());
            pluginConfigs.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(block);
        }

        // id（可选）记到 classRuleIds，给 CLI / 日志展示
        if (rule.getId() != null && !rule.getId().isEmpty()) {
            List<String> ids = classRuleIds.computeIfAbsent(internalClassName, k -> new ArrayList<>());
            if (!ids.contains(rule.getId())) ids.add(rule.getId());
        }

        String idTag = rule.getId() == null ? "" : "[id=" + rule.getId() + "] ";
        LogWriter.getInstance().info("[IAST Agent] Loaded monitor rule: " + idTag
                + className + " -> " + methods + " (plugin: " + pluginName + ", from: " + origin + ")");
    }

    /**
     * 处理Properties格式配置（兼容旧版本）
     */
    private static void processPropertiesConfig(Properties props) {
        // 解析日志路径配置
        String customLogPath = props.getProperty("log.path");
        if (customLogPath != null && !customLogPath.trim().isEmpty()) {
            LogWriter.getInstance().setLogPath(customLogPath.trim());
        }

        // 解析输出控制选项
        outputArgs = getBooleanProperty(props, "output.args", true);
        outputReturn = getBooleanProperty(props, "output.return", true);
        outputStacktrace = getBooleanProperty(props, "output.stacktrace", true);
        stacktraceDepth = getIntProperty(props, "output.stacktrace.depth", 8);

        // 与 yaml 对齐：output.logLevel = debug|info|warn|error
        String level = props.getProperty("output.logLevel");
        if (level != null && !level.trim().isEmpty()) {
            LogWriter.getInstance().setLevel(level.trim());
        }

        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            
            if (key.startsWith("monitor.")) {
                String className = key.substring("monitor.".length()).trim();
                if (className.isEmpty() || value.isEmpty()) {
                    continue;
                }
                
                List<MethodRule> methods = parseMethodRules(value);
                if (!methods.isEmpty()) {
                    monitorRules.put(className.replace('.', '/'), methods); // 转为内部类名格式
                    LogWriter.getInstance().info("[IAST Agent] Loaded monitor rule: " + className + " -> " + methods);
                }
            }
        }
    }

    /**
     * 解析方法规则
     * 格式：方法1名#方法1描述符,方法2名#方法2描述符
     */
    private static List<MethodRule> parseMethodRules(String value) {
        List<MethodRule> rules = new ArrayList<>();
        String[] methodDefs = value.split(",");
        
        for (String methodDef : methodDefs) {
            methodDef = methodDef.trim();
            if (methodDef.isEmpty()) {
                continue;
            }
            
            String[] parts = methodDef.split("#");
            if (parts.length >= 2) {
                String methodName = parts[0].trim();
                String descriptor = parts[1].trim();
                if (!methodName.isEmpty() && !descriptor.isEmpty()) {
                    rules.add(new MethodRule(methodName, descriptor));
                }
            }
        }
        
        return rules;
    }

    /**
     * 添加默认监控规则
     */
    private static void addDefaultRule() {
        List<MethodRule> defaultMethods = new ArrayList<>();
        defaultMethods.add(new MethodRule("exists", "()Z"));
        monitorRules.put("java/io/File", defaultMethods);
        LogWriter.getInstance().info("[IAST Agent] Using default monitor rule: java.io.File.exists()");
    }

    /**
     * 获取所有需要监控的类名（内部格式）
     */
    public static List<String> getMonitoredClasses() {
        return new ArrayList<>(monitorRules.keySet());
    }

    /**
     * 获取指定类的监控方法规则
     */
    public static List<MethodRule> getMethodRules(String internalClassName) {
        return monitorRules.getOrDefault(internalClassName, new ArrayList<>());
    }

    private static final List<String> DEFAULT_PLUGINS = java.util.Collections.singletonList("LogPlugin");

    /**
     * 获取指定类对应的所有插件名称列表
     */
    public static List<String> getPluginNames(String internalClassName) {
        return classPluginMap.getOrDefault(internalClassName, DEFAULT_PLUGINS);
    }

    /**
     * 按声明顺序把方法调用事件分发给该类对应的所有插件
     */
    public static void dispatchToPlugins(String internalClassName, com.iast.agent.plugin.MethodContext context) {
        List<String> names = getPluginNames(internalClassName);
        com.iast.agent.plugin.PluginManager pm = com.iast.agent.plugin.PluginManager.getInstance();
        for (int i = 0; i < names.size(); i++) {
            pm.handleMethodCall(names.get(i), context);
        }
    }

    /**
     * 获取各插件聚合的规则配置列表
     * 用于IastAgent.initPlugins()时传入plugin.init(config)
     */
    public static Map<String, List<Map<String, Object>>> getPluginConfigs() {
        return pluginConfigs;
    }

    /**
     * 全局开关：matchType=interface 时是否也监控安装后新加载的实现类
     */
    public static boolean isIncludeFutureClasses() {
        return includeFutureClasses;
    }

    /**
     * premain 模式下字节码 install 的延迟毫秒数。默认 60_000（1 分钟）；0 表示立即 install。
     * agentmain（attach）模式不使用该值。
     */
    public static long getPremainDelayMs() {
        return premainDelayMs;
    }

    /**
     * 外部插件目录；空字符串表示不加载外部插件。
     */
    public static String getPluginsDir() {
        return pluginsDir;
    }

    /**
     * 查询某个 className（internal 格式）配置的 matchType
     * 未显式配置时返回 "exact"，兼容老规则
     */
    public static String getMatchType(String internalClassName) {
        return classMatchType.getOrDefault(internalClassName, "exact");
    }

    /**
     * 是否对该规则启用 ServletBody 包装（改写 service 入参为缓冲 wrapper）
     */
    public static boolean isWrapServletRequest(String internalClassName) {
        return classWrapServletRequest.getOrDefault(internalClassName, Boolean.FALSE);
    }

    /**
     * 该 className 上配的所有规则 id（缺省 id 的不计入）。给 CLI rules 命令展示用。
     */
    public static List<String> getRuleIds(String internalClassName) {
        return classRuleIds.getOrDefault(internalClassName, java.util.Collections.emptyList());
    }

    /**
     * 把接口规则命中到的具体类路由到接口规则声明的插件列表。
     * 在 ByteBuddy transform 阶段调用：advice 的 dispatchToPlugins 以具体类的 internal name 查 classPluginMap，
     * 若接口规则没把具体类挂到 map 里，运行期就会查不到插件导致事件丢失。
     *
     * @param concreteInternalName  具体实现类的 internal name（斜杠形式）
     * @param interfaceInternalName 声明规则的接口/父类 internal name
     */
    public static void linkConcreteToPlugins(String concreteInternalName, String interfaceInternalName) {
        if (concreteInternalName == null || interfaceInternalName == null) {
            return;
        }
        if (concreteInternalName.equals(interfaceInternalName)) {
            return; // 接口/抽象类本身已经在 map 里，无需自链
        }
        List<String> interfacePlugins = classPluginMap.get(interfaceInternalName);
        if (interfacePlugins == null || interfacePlugins.isEmpty()) {
            return;
        }
        List<String> concretePlugins = classPluginMap.computeIfAbsent(concreteInternalName, k -> new ArrayList<>());
        for (String p : interfacePlugins) {
            if (!concretePlugins.contains(p)) {
                concretePlugins.add(p);
            }
        }
    }

    private static String normalizeMatchType(String raw) {
        if (raw == null) return "exact";
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) return "exact";
        if ("interface".equals(v) || "exact".equals(v)) return v;
        LogWriter.getInstance().info("[IAST Agent] Warning: unknown matchType '" + raw + "', falling back to 'exact'");
        return "exact";
    }

    /**
     * 方法规则实体
     */
    public static class MethodRule {
        private final String methodName;
        private final String descriptor;
        private final boolean wildcardDescriptor;

        public MethodRule(String methodName, String descriptor) {
            this.methodName = methodName;
            this.wildcardDescriptor = "*".equals(descriptor);
            this.descriptor = descriptor;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public boolean isWildcardDescriptor() {
            return wildcardDescriptor;
        }

        @Override
        public String toString() {
            return methodName + "#" + descriptor;
        }
    }

    // --- 输出控制 getter ---

    public static boolean isOutputArgs() {
        return outputArgs;
    }

    public static boolean isOutputReturn() {
        return outputReturn;
    }

    public static boolean isOutputStacktrace() {
        return outputStacktrace;
    }

    public static int getStacktraceDepth() {
        return stacktraceDepth;
    }

    // --- 属性解析辅助 ---

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val.trim());
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
