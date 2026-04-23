package com.iast.agent.cli;

import com.iast.agent.IastAgent;
import com.iast.agent.LogWriter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CLI {@code monitor} 命令的全局会话注册表。
 *
 * <p>每个 CLI 会话进入 {@link CliServer#sessionLoop} 时调 {@link #openSession} 拿一个
 * {@link SessionContext}；退出 finally 调 {@link #closeSession} 兜底撤销该会话装的 monitor +
 * 关闭输出器。{@link MonitorAdvice} 在业务线程命中时调 {@link #report}，registry 找到
 * 该方法当前归属的会话，用它的 {@link SessionWriter} 推一行。
 *
 * <h3>v1 限制</h3>
 * <ul>
 *   <li>每个会话最多 1 个活跃 monitor —— 简化数据模型，匹配"一次 --command = 一次 monitor"的用法</li>
 *   <li>类必须已加载（{@link Instrumentation#getAllLoadedClasses()} 找不到 → 报错）</li>
 *   <li>不支持 {@code <init>} 构造方法（advice 字段集合不同，v1 留出口）</li>
 *   <li>命中数超 {@link #MAX_HITS_PER_MONITOR} 后停推帧、保留计数</li>
 * </ul>
 */
public final class MonitorRegistry {

    /** 每个 monitor 最多推多少行；超过后静默并发一行 throttle 通知。避免热点方法把 CLI 刷垮。 */
    private static final long MAX_HITS_PER_MONITOR = 100_000L;

    private static final ConcurrentHashMap<Long, SessionContext> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(1);

    /** 防 advice→writeText→advice→... 死循环。同线程正在 report 时直接跳过。 */
    private static final ThreadLocal<Boolean> IN_REPORT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(java.time.ZoneId.systemDefault());

    private MonitorRegistry() {}

    // ============= 会话生命周期 =============

    public static SessionContext openSession(OutputStream out) {
        long id = NEXT_SESSION_ID.getAndIncrement();
        SessionContext sc = new SessionContext(id, new SessionWriter(out));
        SESSIONS.put(id, sc);
        return sc;
    }

    public static void closeSession(SessionContext sc) {
        if (sc == null) return;
        SESSIONS.remove(sc.sessionId);
        ActiveMonitor m = sc.monitor;
        sc.monitor = null;
        if (m != null) {
            try {
                m.transformer.reset(IastAgent.INSTRUMENTATION,
                        AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                LogWriter.getInstance().info("[IAST CLI] monitor uninstalled: "
                        + m.dottedFqcn + "." + m.methodName
                        + (m.descriptor == null ? "" : "#" + m.descriptor)
                        + " (hits=" + m.hits.get() + ")");
            } catch (Throwable t) {
                LogWriter.getInstance().warn("[IAST CLI] monitor reset failed for "
                        + m.dottedFqcn + "." + m.methodName + ": " + t);
            }
        }
        sc.writer.close();
    }

    // ============= 装 / 卸 monitor =============

    /** 安装一个 monitor。失败 / 已存在 → 返回 ERROR 字符串；成功 → ack 字符串。 */
    public static String installMonitor(SessionContext sc, String fqcn,
                                        String methodName, String descriptor) {
        if (sc == null) return "ERROR: no active session";
        if (sc.monitor != null) {
            return "ERROR: this session already has an active monitor on "
                    + sc.monitor.dottedFqcn + "." + sc.monitor.methodName
                    + " (run a fresh `iast-cli ... --command \"monitor ...\"` for another)";
        }
        Instrumentation inst = IastAgent.INSTRUMENTATION;
        if (inst == null) return "ERROR: instrumentation not available";

        // 已加载类筛选（多 classloader 同 FQN 都拿）
        List<Class<?>> targets = new ArrayList<>(1);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (fqcn.equals(c.getName())) targets.add(c);
        }
        if (targets.isEmpty()) {
            return "ERROR: class not loaded: " + fqcn
                    + " (trigger the code path first, then re-run monitor)";
        }

        ClassFileLocator advLocator = IastAgent.ADVICE_LOCATOR;
        if (advLocator == null) advLocator = ClassFileLocator.ForClassLoader.ofSystemLoader();
        final ClassFileLocator finalLocator = advLocator;

        ElementMatcher.Junction<MethodDescription> mm = ElementMatchers.named(methodName)
                .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()));
        if (descriptor != null && !descriptor.isEmpty()) {
            mm = mm.and(ElementMatchers.hasDescriptor(descriptor));
        }
        final ElementMatcher.Junction<MethodDescription> finalMM = mm;

        ResettableClassFileTransformer t;
        try {
            AgentBuilder ab = new AgentBuilder.Default()
                    .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                    .ignore(ElementMatchers.nameStartsWith("com.iast.agent."))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .disableClassFormatChanges()
                    .type(ElementMatchers.named(fqcn))
                    .transform((builder, td, cl, mod, pd) ->
                            builder.visit(Advice.to(MonitorAdvice.class, finalLocator).on(finalMM)));
            t = ab.installOn(inst);
        } catch (Throwable err) {
            return "ERROR: install failed: " + err.getClass().getSimpleName()
                    + ": " + err.getMessage();
        }

        ActiveMonitor m = new ActiveMonitor(fqcn, methodName, descriptor, t);
        sc.monitor = m;
        LogWriter.getInstance().info("[IAST CLI] monitor installed: " + fqcn + "." + methodName
                + (descriptor == null ? "" : "#" + descriptor)
                + " (matched " + targets.size() + " loaded class instance"
                + (targets.size() == 1 ? "" : "s") + ")");

        return "monitor installed on " + fqcn + "." + methodName
                + (descriptor == null ? "" : "#" + descriptor)
                + " (matched " + targets.size() + " loaded class instance"
                + (targets.size() == 1 ? "" : "s") + "). Press Ctrl-C to stop.";
    }

    // ============= advice 回调 =============

    /** Advice 唯一回调点。业务线程命中 → 找会话 → 写帧。同线程已在 report 中 → 跳过防自激。 */
    public static void report(String dottedClassName, String methodName, String descriptor,
                              boolean success, long durationNanos, Throwable thrown) {
        if (Boolean.TRUE.equals(IN_REPORT.get())) return;
        if (SESSIONS.isEmpty()) return;
        IN_REPORT.set(Boolean.TRUE);
        try {
            String line = null;
            for (SessionContext sc : SESSIONS.values()) {
                ActiveMonitor m = sc.monitor;
                if (m == null) continue;
                if (!m.dottedFqcn.equals(dottedClassName)) continue;
                if (!m.methodName.equals(methodName)) continue;
                if (m.descriptor != null && !m.descriptor.equals(descriptor)) continue;

                long hit = m.hits.incrementAndGet();
                if (hit > MAX_HITS_PER_MONITOR) continue;

                if (line == null) {
                    line = formatLine(dottedClassName, methodName, success, durationNanos, thrown);
                }
                sc.writer.writeText(line);
                if (hit == MAX_HITS_PER_MONITOR) {
                    sc.writer.writeText("[... reached " + MAX_HITS_PER_MONITOR
                            + " hits, further events suppressed; Ctrl-C to stop ...]");
                }
            }
        } finally {
            IN_REPORT.set(Boolean.FALSE);
        }
    }

    private static String formatLine(String cls, String method,
                                     boolean success, long durNanos, Throwable t) {
        long durMs = durNanos / 1_000_000L;
        long callId = IastAgent.globalCallCount.incrementAndGet();
        String ts = TS_FMT.format(java.time.Instant.now());
        StringBuilder sb = new StringBuilder(160);
        sb.append('[').append(ts).append("] callId=").append(callId)
                .append(' ').append(cls).append('.').append(method).append("  ");
        if (success) {
            sb.append("SUCCESS  rt=").append(durMs).append("ms");
        } else {
            sb.append("FAIL     rt=").append(durMs).append("ms");
            if (t != null) {
                sb.append("   ").append(t.getClass().getName());
                String msg = t.getMessage();
                if (msg != null) {
                    if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
                    sb.append(": ").append(msg);
                }
            }
        }
        return sb.toString();
    }

    // ============= 数据结构 =============

    public static final class SessionContext {
        final long sessionId;
        final SessionWriter writer;
        volatile ActiveMonitor monitor;

        SessionContext(long sessionId, SessionWriter writer) {
            this.sessionId = sessionId;
            this.writer = writer;
        }

        public SessionWriter getWriter() { return writer; }
        public long getSessionId() { return sessionId; }
        public ActiveMonitor getMonitor() { return monitor; }
    }

    public static final class ActiveMonitor {
        final String dottedFqcn;
        final String methodName;
        final String descriptor;            // null = 不限 overload
        final ResettableClassFileTransformer transformer;
        final long startWallMs = System.currentTimeMillis();
        final AtomicLong hits = new AtomicLong();

        ActiveMonitor(String dottedFqcn, String methodName, String descriptor,
                      ResettableClassFileTransformer transformer) {
            this.dottedFqcn = dottedFqcn;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.transformer = transformer;
        }
    }
}
