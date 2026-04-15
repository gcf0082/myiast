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
            System.out.println("[IAST Agent] Config file not found at " + configFilePath + ", using default rules");
            return;
        }

        try (InputStream is = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.load(is);
            
            monitorRules.clear();
            
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
                        System.out.println("[IAST Agent] Loaded monitor rule: " + className + " -> " + methods);
                    }
                }
            }
            
            if (monitorRules.isEmpty()) {
                System.out.println("[IAST Agent] No valid monitor rules found in config, using default rules");
                addDefaultRule();
            }
        } catch (IOException e) {
            System.err.println("[IAST Agent] Failed to load config file: " + e.getMessage());
            e.printStackTrace();
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
        System.out.println("[IAST Agent] Using default monitor rule: java.io.File.exists()");
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

        public MethodRule(String methodName, String descriptor) {
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getDescriptor() {
            return descriptor;
        }

        @Override
        public String toString() {
            return methodName + descriptor;
        }
    }
}
