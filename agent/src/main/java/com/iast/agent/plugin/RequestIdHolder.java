package com.iast.agent.plugin;

/**
 * 请求ID持有者（全局 API）
 *
 * 使用 ThreadLocal 存储当前线程的请求跟踪 ID，由 RequestIdPlugin 在最外层
 * HttpServlet.service 入口分配。任何插件都可以通过 {@link #get()} 静态方法
 * 直接访问当前线程的 requestId，无需依赖 MethodContext——这是跨插件共享请求
 * 上下文的默认通道。
 *
 * 同一个线程在 HttpServlet.service 嵌套调用（如 generic->typed 分派）时共享
 * 同一个 requestId，通过 depth 计数保证只有最外层的 enter 分配 ID、最外层的
 * exit 清理 ID。
 */
public class RequestIdHolder {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static String get() {
        return REQUEST_ID.get();
    }

    public static void set(String id) {
        REQUEST_ID.set(id);
    }

    public static int enter() {
        int d = DEPTH.get() + 1;
        DEPTH.set(d);
        return d;
    }

    public static int exit() {
        int d = DEPTH.get() - 1;
        if (d <= 0) {
            DEPTH.remove();
            return 0;
        }
        DEPTH.set(d);
        return d;
    }

    public static void clear() {
        REQUEST_ID.remove();
        DEPTH.remove();
    }
}