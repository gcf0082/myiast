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
    private static final String DEFAULT_YAML_CONFIG_PATH = "iast-monitor.yaml";
    private static final String DEFAULT_PROPERTIES_CONFIG_PATH = "iast-monitor.properties";
    private static String configFilePath = DEFAULT_YAML_CONFIG_PATH;

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
        }

        // 解析监控规则
        if (rootConfig.getMonitor() != null && rootConfig.getMonitor().getRules() != null) {
            for (MonitorRuleConfig rule : rootConfig.getMonitor().getRules()) {
                String className = rule.getClassName();
                if (className == null || className.isEmpty() || rule.getMethods() == null || rule.getMethods().isEmpty()) {
                    continue;
                }
                // 方法规则转为逗号分隔字符串，复用原有解析逻辑
                String methodRuleStr = String.join(",", rule.getMethods());
                List<MethodRule> methods = parseMethodRules(methodRuleStr);
                if (!methods.isEmpty()) {
                    String internalClassName = className.replace('.', '/');
                    // 同一个类可能有多条规则，合并方法列表（去重）
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

                    // 存储插件名称，默认使用LogPlugin；同一个类可以挂多个插件，按YAML声明顺序排列
                    String pluginName = rule.getPlugin();
                    if (pluginName == null || pluginName.isEmpty()) {
                        pluginName = "LogPlugin";
                    }
                    List<String> pluginList = classPluginMap.computeIfAbsent(internalClassName, k -> new ArrayList<>());
                    if (!pluginList.contains(pluginName)) {
                        pluginList.add(pluginName);
                    }

                    // 聚合该规则的pluginConfig，附带className/methods，后续传给插件init()
                    if (rule.getPluginConfig() != null && !rule.getPluginConfig().isEmpty()) {
                        Map<String, Object> block = new HashMap<>(rule.getPluginConfig());
                        block.putIfAbsent("className", className);
                        block.putIfAbsent("methods", rule.getMethods());
                        pluginConfigs.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(block);
                    }

                    LogWriter.getInstance().info("[IAST Agent] Loaded monitor rule: " + className + " -> " + methods + " (plugin: " + pluginName + ")");
                }
            }
        }
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
