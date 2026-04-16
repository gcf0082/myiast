package com.iast.agent.config;

import java.util.List;

/**
 * YAML配置根实体
 */
public class YamlRootConfig {
    private OutputConfig output;
    private MonitorConfig monitor;

    public OutputConfig getOutput() {
        return output;
    }

    public void setOutput(OutputConfig output) {
        this.output = output;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public static class MonitorConfig {
        private MonitorDefaultConfig defaultConfig;
        private List<MonitorRuleConfig> rules;

        public MonitorDefaultConfig getDefault() {
            return defaultConfig;
        }

        // snakeyaml需要set方法，和字段名一致
        public void setDefault(MonitorDefaultConfig defaultConfig) {
            this.defaultConfig = defaultConfig;
        }

        public List<MonitorRuleConfig> getRules() {
            return rules;
        }

        public void setRules(List<MonitorRuleConfig> rules) {
            this.rules = rules;
        }
    }
}
