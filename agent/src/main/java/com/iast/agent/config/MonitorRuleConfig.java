package com.iast.agent.config;

import java.util.List;

/**
 * 单条监控规则实体
 */
public class MonitorRuleConfig {
    private String className;
    private List<String> methods;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }
}
