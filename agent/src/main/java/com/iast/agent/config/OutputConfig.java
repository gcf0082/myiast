package com.iast.agent.config;

/**
 * 输出配置实体
 */
public class OutputConfig {
    private boolean args = true;
    private boolean returnVal = true;
    private boolean stacktrace = true;
    private int stacktraceDepth = 8;

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
}
