package com.iast.agent.config;

/**
 * 输出配置实体
 */
public class OutputConfig {
    private boolean args = true;
    private boolean returnVal = true;
    private boolean stacktrace = true;
    private int stacktraceDepth = 8;
    private String eventsPath;
    /** 日志级别：debug / info / warn / error，默认 info。MonitorConfig 加载完后会传给 LogWriter。 */
    private String logLevel;

    public boolean isArgs() {
        return args;
    }

    public void setArgs(boolean args) {
        this.args = args;
    }

    public boolean isReturn() {
        return returnVal;
    }

    public void setReturn(boolean returnVal) {
        this.returnVal = returnVal;
    }

    public boolean isStacktrace() {
        return stacktrace;
    }

    public void setStacktrace(boolean stacktrace) {
        this.stacktrace = stacktrace;
    }

    public int getStacktraceDepth() {
        return stacktraceDepth;
    }

    public void setStacktraceDepth(int stacktraceDepth) {
        this.stacktraceDepth = stacktraceDepth;
    }

    public String getEventsPath() {
        return eventsPath;
    }

    public void setEventsPath(String eventsPath) {
        this.eventsPath = eventsPath;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }
}
