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
    /** 输出目录：iast-agent-&lt;pid&gt;.log 和 iast-events-&lt;pid&gt;.jsonl 共用。空 = 默认 /tmp。
     *  相对路径按主 yaml 所在目录解析；不存在自动 mkdirs。
     *  与 eventsPath 同时配 → eventsPath 优先（仅作用 events 文件，log 仍走 outputDir）。 */
    private String outputDir;

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

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }
}
