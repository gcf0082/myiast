package com.iast.agent.config;

import java.util.List;
import java.util.Map;

/**
 * 单条监控规则实体
 */
public class MonitorRuleConfig {
    private String className;
    private List<String> methods;
    private String plugin;              // 插件名称
    private Map<String, Object> pluginConfig;  // 插件配置
    private String matchType = "exact"; // 匹配模式：exact（精确类名）| interface（接口，覆盖所有具体实现类）
    private boolean wrapServletRequest = false; // 仅对 HttpServlet.service 规则有效：启用后用 ServletBodyAdvice 代替 MethodMonitorAdvice，改写入参 0 为缓冲 wrapper

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

    public String getPlugin() {
        return plugin;
    }

    public void setPlugin(String plugin) {
        this.plugin = plugin;
    }

    public Map<String, Object> getPluginConfig() {
        return pluginConfig;
    }

    public void setPluginConfig(Map<String, Object> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    public String getMatchType() {
        return matchType;
    }

    public void setMatchType(String matchType) {
        this.matchType = matchType;
    }

    public boolean isWrapServletRequest() {
        return wrapServletRequest;
    }

    public void setWrapServletRequest(boolean wrapServletRequest) {
        this.wrapServletRequest = wrapServletRequest;
    }
}
