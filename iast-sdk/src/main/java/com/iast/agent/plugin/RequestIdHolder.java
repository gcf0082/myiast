package com.iast.agent.plugin;

/**
 * 请求ID持有者（历史 API，现在是 {@link IastContext} 的薄壳）。
 *
 * <p>新代码请直接调 {@link IastContext#getRequestId()} —— 它带 clear 兜底，
 * 对外层 Advice / 后跑的插件更友好。这个类保留是为了让已有的 RequestIdPlugin
 * 等历史调用点不必改动；它的 get() 保留 "只看进行中" 语义，要"清理后还能拿"
 * 的语义请换到 IastContext.getRequestId()。
 */
public class RequestIdHolder {

    /** 只返回本请求进行中的 id，清理后为 null。跨 Advice 场景用 {@link IastContext#getRequestId()}。 */
    public static String get() {
        return IastContext.getCurrentRequestId();
    }

    public static void set(String id) {
        IastContext.setRequestId(id);
    }

    public static int enter() {
        return IastContext.enter();
    }

    public static int exit() {
        return IastContext.exit();
    }

    public static void clear() {
        IastContext.clear();
    }
}
