package com.iast.agent.cli;

import net.bytebuddy.asm.Advice;

/**
 * CLI {@code monitor} 命令专用的 stats-only advice。
 *
 * <p>跟 {@link com.iast.agent.IastAgent.MethodMonitorAdvice} 完全独立——不走
 * {@code MonitorConfig.dispatchToPlugins}，不查 {@code MONITOR_ENABLED} 总开关；
 * 只测耗时 + 成功/失败，把结果转给 {@link MonitorRegistry#report}，由 registry 决定
 * 推到哪个 CLI 会话。
 *
 * <p>{@code @Advice.Origin("#t" / "#m" / "#d")} 拿到的分别是 dotted FQN / 方法名 /
 * JVM 描述符（如 "(Ljava/lang/String;)Ljava/lang/String;"）。{@code #s} 是 Java 风格签名，
 * 不能用于和 hasDescriptor 输入对比。MonitorRegistry 内部统一 dotted FQN + 原始 JVM 描述符。
 */
public final class MonitorAdvice {

    private MonitorAdvice() {}

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName,
                              @Advice.Origin("#d") String descriptor,
                              @Advice.Enter long enterNanos,
                              @Advice.Thrown Throwable thrown) {
        try {
            MonitorRegistry.report(className, methodName, descriptor,
                    thrown == null, System.nanoTime() - enterNanos, thrown);
        } catch (Throwable ignore) {
            // advice 内任何异常都不能抛回业务方法
        }
    }
}
