package com.iast.agent.plugin.event;

import com.iast.agent.plugin.IastContext;
import com.iast.agent.plugin.MethodContext;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/**
 * Expression 的 {@code context} 顶层变量对应的视图对象。
 *
 * <p>目的：把 filter / {@link com.iast.agent.plugin.MethodContext} 已有但现有
 * Expression root 没暴露的字段（调用栈、线程、phase 等）以及进程级信息（pid、hostname、
 * instanceName、agent 启动时间）统一通过**无参 getter**暴露给 Expression 引擎。
 * Expression 本身文法只支持无参方法调用，所以这里所有 getter 都是零参。
 *
 * <h3>典型用法</h3>
 * <pre>
 * # filter：drop 掉所有栈里经过 logback 的事件
 * unless:
 *   - expr: "context.stackTraceClasses"
 *     op:   contains
 *     value: "ch.qos.logback."
 *
 * # rule my_params：把 pid / hostname 打进事件里
 * my_params:
 *   pid:  "context.pid"
 *   host: "context.hostname"
 * </pre>
 *
 * <h3>性能提示</h3>
 * {@link #getStackTrace()} 会把整条 stackTrace 拼成多行 String，高频 hook 上开销不小。
 * 真实 filter 场景用 {@link #getStackTraceClasses()} 替代：只拼 className、用 {@code |}
 * 分隔，长度短 60%~80%，contains 也照样命中。拼好后缓存到本 EvalContext 实例，同次求值
 * 内不会重复算。
 */
public final class EvalContext {

    // ======== 进程级静态字段（所有 EvalContext 共享）========

    /** JVM PID，首次 class-init 时解析一次。从 RuntimeMXBean name 拆 @ 之前的部分拿 */
    private static final String PID = resolvePid();
    /** hostname 懒加载缓存。InetAddress.getLocalHost() 在某些容器里会抛或阻塞，失败记 "" */
    private static volatile String hostnameCache;
    private static volatile boolean hostnameResolved;
    /** agent 启动后由 IastAgent 调 bindInstanceName 注入（reload 时也会重新 bind） */
    private static volatile String instanceName = "";
    /** agent 启动时刻 wall-clock ms，由 IastAgent bind */
    private static volatile long agentStartTimeMs = 0L;

    public static void bindInstanceName(String name) {
        instanceName = (name == null) ? "" : name;
    }

    public static void bindAgentStartTime(long t) {
        agentStartTimeMs = t;
    }

    // ======== 实例字段 ========

    private final MethodContext mc;
    private volatile String cachedStackTrace;         // 懒拼，首次访问 getStackTrace 时填
    private volatile String cachedStackTraceClasses;  // 同上，给 getStackTraceClasses 用

    public EvalContext(MethodContext mc) {
        this.mc = mc;
    }

    // ======== 进程级 getter ========

    public String getPid()          { return PID; }
    public String getHostname()     { return resolveHostnameLazy(); }
    public String getInstanceName() { return instanceName; }
    public long getAgentStartTime() { return agentStartTimeMs; }

    // ======== MethodContext 透传 ========

    public long getCallId()       { return mc == null ? 0L : mc.getCallId(); }
    public String getClassName()  { return mc == null ? null : mc.getClassName(); }
    public String getMethodName() { return mc == null ? null : mc.getMethodName(); }
    public String getDescriptor() { return mc == null ? null : mc.getDescriptor(); }
    public long getThreadId()     { return mc == null ? 0L : mc.getThreadId(); }
    public String getThreadName() { return mc == null ? null : mc.getThreadName(); }
    public long getEnterTime()    { return mc == null ? 0L : mc.getEnterTime(); }
    public long getExitTime()     { return mc == null ? 0L : mc.getExitTime(); }
    public long getDuration()     { return mc == null ? 0L : mc.getDuration(); }

    public String getPhase() {
        if (mc == null || mc.getPhase() == null) return null;
        return mc.getPhase().name();
    }

    /**
     * requestId。与 Expression 原 {@code requestId} root 同语义：优先从
     * {@link IastContext} 取（含最外层 exit 后的 last-snapshot 兜底），没有再回退
     * MethodContext 自带字段。
     */
    public String getRequestId() {
        String id = IastContext.getRequestId();
        if (id != null) return id;
        return mc == null ? null : mc.getRequestId();
    }

    /**
     * 整条调用栈拼成多行字符串（每行 {@code StackTraceElement.toString()} 结果）。
     * 懒拼 + 实例内缓存，同一 EvalContext 上多次读不重复算。
     */
    public String getStackTrace() {
        if (cachedStackTrace != null) return cachedStackTrace;
        StackTraceElement[] st = (mc == null) ? null : mc.getStackTrace();
        if (st == null || st.length == 0) {
            cachedStackTrace = "";
            return cachedStackTrace;
        }
        StringBuilder sb = new StringBuilder(st.length * 64);
        for (int i = 0; i < st.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(st[i].toString());
        }
        cachedStackTrace = sb.toString();
        return cachedStackTrace;
    }

    /**
     * 只拼 className、用 {@code |} 分隔的紧凑形式，供 filter 的 contains/matches 命中。
     * 通常比 getStackTrace 短 60%~80%，高频路径上优先用这个。
     */
    public String getStackTraceClasses() {
        if (cachedStackTraceClasses != null) return cachedStackTraceClasses;
        StackTraceElement[] st = (mc == null) ? null : mc.getStackTrace();
        if (st == null || st.length == 0) {
            cachedStackTraceClasses = "";
            return cachedStackTraceClasses;
        }
        StringBuilder sb = new StringBuilder(st.length * 24);
        for (int i = 0; i < st.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(st[i].getClassName());
        }
        cachedStackTraceClasses = sb.toString();
        return cachedStackTraceClasses;
    }

    // ======== helpers ========

    private static String resolvePid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String resolveHostnameLazy() {
        if (hostnameResolved) {
            return hostnameCache == null ? "" : hostnameCache;
        }
        try {
            String h = InetAddress.getLocalHost().getHostName();
            hostnameCache = (h == null) ? "" : h;
        } catch (Throwable t) {
            hostnameCache = "";
        } finally {
            hostnameResolved = true;
        }
        return hostnameCache;
    }
}
