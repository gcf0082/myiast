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
}
