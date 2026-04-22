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
    /** 输出根目录。最终文件落在 outputDir/&lt;instanceName&gt;/iast.{log,jsonl}。
     *  相对路径按主 yaml 所在目录解析；不存在自动 mkdirs。空 = 默认 /tmp。
     *  与 eventsPath 同时配 → eventsPath 优先（仅作用 events 文件，log 仍走 outputDir/instanceName）。 */
    private String outputDir;
    /** 实例标识，作为 outputDir 下的子目录名。支持 ${VAR} 引环境变量
     *  （env 未设 → WARN + 替换为空串；解析后整串为空 → 兜底 iast_&lt;pid&gt;）。
     *  未配 / 空 → 默认 iast_&lt;pid&gt;（前缀避免 /tmp 下出现纯数字目录）。 */
    private String instanceName;
    /** 实例目录下最多保留多少个文件（含活跃文件）；≤0 不限。log + jsonl 共用。默认 5。 */
    private int maxFiles = 5;
    /** 单个文件最大 MB；超过后滚动；≤0 禁用。log + jsonl 共用。默认 20。 */
    private int maxFileSizeMb = 20;

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

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }
}
