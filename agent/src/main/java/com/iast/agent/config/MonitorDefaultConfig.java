package com.iast.agent.config;

import java.util.List;

/**
 * 全局默认监控配置实体
 */
public class MonitorDefaultConfig {
    private List<String> events = List.of("enter", "return", "exception");
    private int sampleRate = 100;

    public List<String> getEvents() {
        return events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }
}
