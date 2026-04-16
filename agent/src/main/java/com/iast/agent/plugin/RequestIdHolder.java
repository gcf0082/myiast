package com.iast.agent.plugin;

/**
 * 请求ID持有者
 * 使用ThreadLocal存储当前线程的请求跟踪ID
 * 同一个线程在HttpServlet.service嵌套调用（如generic->typed分派）时共享同一个requestId，
 * 通过depth计数保证只有最外层的enter分配ID、最外层的exit清理ID
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