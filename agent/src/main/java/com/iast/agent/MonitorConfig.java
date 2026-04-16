package com.iast.agent;

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
    private static final String DEFAULT_CONFIG_PATH = "iast-monitor.properties";
    private static String configFilePath = DEFAULT_CONFIG_PATH;

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
        if (!configFile.exists()) {
            LogWriter.getInstance().info("[IAST Agent] Config file not found at " + configFilePath + ", using default rules");
            return;
        }

        try (InputStream is = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.load(is);
            
            monitorRules.clear();

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
            
            if (monitorRules.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] No valid monitor rules found in config, using default rules");
                addDefaultRule();
            }
        } catch (IOException e) {
            LogWriter.getInstance().info("[IAST Agent] Failed to load config file: " + e.getMessage());
            LogWriter.getInstance().info("[IAST Agent] Exception: " + e.toString());
            // 加载失败用默认配置
            addDefaultRule();
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
