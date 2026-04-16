package com.iast.agent.config;

import java.util.List;

/**
 * 全局默认监控配置实体
 */
public class MonitorDefaultConfig {
    private List<String> events = List.of("enter", "return", "exception");

    public List<String> getEvents() {
        return events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }
}
