package com.iast.agent.config;

import java.util.List;

/**
 * 全局默认监控配置实体
 */
public class MonitorDefaultConfig {
    private List<String> events = List.of("enter", "return", "exception");
    // 全局开关：matchType=interface 规则是否也覆盖 Agent 安装后新加载的实现类。
    // 默认 false —— 仅监控安装瞬间已加载的实现类，避免意外扩大拦截面。
    private boolean includeFutureClasses = false;
    // premain 模式下延迟多少毫秒再做字节码 install，避免 retransform + 逐类拦截拖慢业务启动。
    // 默认 60000（1 分钟）；设为 0 表示不延迟（立即 install）。
    // agentmain（attach）模式不受此开关影响，始终立即 install。
    private long premainDelayMs = 60_000L;
    // 外部插件目录：非空时 agent 会扫该目录下所有 *.jar，用 ServiceLoader 加载其中的
    // com.iast.agent.plugin.IastPlugin 实现。空字符串 = 不加载任何外部插件（默认）。
    private String pluginsDir = "";

    public List<String> getEvents() {
        return events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }

    public boolean isIncludeFutureClasses() {
        return includeFutureClasses;
    }

    public void setIncludeFutureClasses(boolean includeFutureClasses) {
        this.includeFutureClasses = includeFutureClasses;
    }

    public long getPremainDelayMs() {
        return premainDelayMs;
    }

    public void setPremainDelayMs(long premainDelayMs) {
        this.premainDelayMs = premainDelayMs;
    }

    public String getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(String pluginsDir) {
        this.pluginsDir = pluginsDir == null ? "" : pluginsDir;
    }
}
