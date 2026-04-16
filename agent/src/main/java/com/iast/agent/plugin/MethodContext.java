package com.iast.agent.plugin;

/**
 * 方法调用上下文
 * 包含被监控方法的所有上下文信息
 */
public class MethodContext {
    // 基本信息
    private String className;
    private String methodName;
    private String descriptor;
    private long callId;
    
    // 调用上下文
    private Object target;              // 被调用的对象（this）
    private Object[] args;              // 所有参数
    private Object result;              // 返回值（仅调用后可用）
    private Throwable throwable;        // 异常对象（仅异常时可用）
    
    // 调用阶段
    private CallPhase phase;            // ENTER/EXIT/EXCEPTION
    
    // 时间信息
    private long enterTime;
    private long exitTime;
    private long duration;
    
    // 线程信息
    private long threadId;
    private String threadName;
    
    // 调用栈
    private StackTraceElement[] stackTrace;
    
    public enum CallPhase {
        ENTER, EXIT, EXCEPTION
    }
    
    // Getters and Setters
    public String getClassName() {
        return className;
    }
    
    public void setClassName(String className) {
        this.className = className;
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }
    
    public String getDescriptor() {
        return descriptor;
    }
    
    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
    }
    
    public long getCallId() {
        return callId;
    }
    
    public void setCallId(long callId) {
        this.callId = callId;
    }
    
    public Object getTarget() {
        return target;
    }
    
    public void setTarget(Object target) {
        this.target = target;
    }
    
    public Object[] getArgs() {
        return args;
    }
    
    public void setArgs(Object[] args) {
        this.args = args;
    }
    
    public Object getResult() {
        return result;
    }
    
    public void setResult(Object result) {
        this.result = result;
    }
    
    public Throwable getThrowable() {
        return throwable;
    }
    
    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }
    
    public CallPhase getPhase() {
        return phase;
    }
    
    public void setPhase(CallPhase phase) {
        this.phase = phase;
    }
    
    public long getEnterTime() {
        return enterTime;
    }
    
    public void setEnterTime(long enterTime) {
        this.enterTime = enterTime;
    }
    
    public long getExitTime() {
        return exitTime;
    }
    
    public void setExitTime(long exitTime) {
        this.exitTime = exitTime;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    public long getThreadId() {
        return threadId;
    }
    
    public void setThreadId(long threadId) {
        this.threadId = threadId;
    }
    
    public String getThreadName() {
        return threadName;
    }
    
    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }
    
    public StackTraceElement[] getStackTrace() {
        return stackTrace;
    }
    
    public void setStackTrace(StackTraceElement[] stackTrace) {
        this.stackTrace = stackTrace;
    }
}
