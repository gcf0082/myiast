package com.iast.agent.plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 级别的线程请求上下文门面。
 *
 * <p>所有插件 / Advice 想查询当前线程正在处理的请求上下文，**一律走这个类**。内部封装了
 * ThreadLocal 细节和 clear 时的快照兜底，调用方不需要关心 RequestIdPlugin 什么时候清理、
 * 外层 Advice 是否在清理之后才跑这种栈序问题。
 *
 * <h3>requestId 的生命周期</h3>
 * <ul>
 *   <li>{@link #setRequestId(String)} 由 RequestIdPlugin 在 HttpServlet.service 最外层 enter 调用</li>
 *   <li>同线程嵌套调用通过 {@link #enter()} / {@link #exit()} 维护深度计数</li>
 *   <li>最外层 exit 时 {@link #clear()} 会把当前 id **快照**到 {@code LAST_REQUEST_ID}，
 *       然后移除 current TL</li>
 *   <li>外层 Advice（例如 ServletBodyAdvice）在 RequestIdPlugin 清理之后才跑 onExit，这时
 *       {@link #getRequestId()} 自动回退到快照，仍能拿到刚结束那次请求的 id</li>
 * </ul>
 *
 * <p>线程池复用安全：下一次请求进来时 {@link #setRequestId} 会直接覆盖 current；快照被
 * 下一轮 clear 时覆写。两个 TL 都不会泄漏跨请求的错误 id。
 *
 * <h3>扩展点</h3>
 * 除 requestId 外还暴露通用的 {@link #putAttribute} / {@link #getAttribute}，后续需要挂
 * 线程级上下文（用户、tenant、子请求元信息等）时无需再新增 Holder 类。属性 map 也会
 * 在 {@link #clear()} 时清空，避免线程池串味。
 */
public final class IastContext {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Map<String, Object>> ATTRIBUTES = new ThreadLocal<>();
    /** clear() 时把 ATTRIBUTES 拷贝过来；外层 Advice（如 ServletBodyAdvice）的 onExit 在 clear 之后才跑，
     *  这时通过 {@link #getAttribute} 自动从这里回退取，仍能拿到刚结束那次请求的 attrs。 */
    private static final ThreadLocal<Map<String, Object>> LAST_ATTRIBUTES = new ThreadLocal<>();

    private IastContext() {}

    // ========= requestId 公共 API =========

    /**
     * 取当前线程的 requestId。优先返回"本请求进行中"的值；若请求已出栈（RequestIdPlugin
     * 已 clear），回退到本线程最近一次 clear 前的快照。若两者都为空返回 null。
     *
     * <p>这是 **所有跨插件/跨 Advice 获取 requestId 的统一入口**，替代直接调
     * {@code RequestIdHolder.get()}。
     */
    public static String getRequestId() {
        String cur = REQUEST_ID.get();
        if (cur != null) return cur;
        return LAST_REQUEST_ID.get();
    }

    /**
     * 只返回"进行中"的 requestId，不走快照兜底。RequestIdPlugin 自己在 enter 时判断
     * 是否已有 id 用这个。一般插件代码不该用这个，用 {@link #getRequestId()}。
     */
    public static String getCurrentRequestId() {
        return REQUEST_ID.get();
    }

    /** 由 RequestIdPlugin 在最外层 enter 调用 */
    public static void setRequestId(String id) {
        REQUEST_ID.set(id);
    }

    // ========= depth 计数（供 RequestIdPlugin 管理嵌套）=========

    public static int enter() {
        int d = DEPTH.get() + 1;
        DEPTH.set(d);
        if (d == 1) {
            // 新请求开始：清掉上一个请求遗留的 attrs 快照，避免 getAttribute 跨请求误回退
            LAST_ATTRIBUTES.remove();
        }
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

    // ========= 清理（由 RequestIdPlugin 在最外层 exit 调用）=========

    /**
     * 最外层请求退栈时调用：先把 current requestId 快照到 lastCleared，再移除 current TL
     * 和 depth。外层 Advice 此后仍然能通过 {@link #getRequestId()} 拿到刚结束的 id。
     *
     * <p>属性 map 也清空（同请求内才有效，不跨请求）。
     */
    public static void clear() {
        String cur = REQUEST_ID.get();
        if (cur != null) {
            LAST_REQUEST_ID.set(cur);
        }
        REQUEST_ID.remove();
        DEPTH.remove();
        Map<String, Object> attrs = ATTRIBUTES.get();
        if (attrs != null && !attrs.isEmpty()) {
            // 快照供外层 Advice 的 onExit 读取（同 LAST_REQUEST_ID 路径）
            LAST_ATTRIBUTES.set(new HashMap<>(attrs));
            attrs.clear();
        }
    }

    // ========= 通用属性槽（供未来插件透传任意线程级数据）=========

    public static void putAttribute(String key, Object value) {
        if (key == null) return;
        Map<String, Object> attrs = ATTRIBUTES.get();
        if (attrs == null) {
            attrs = new HashMap<>();
            ATTRIBUTES.set(attrs);
        }
        attrs.put(key, value);
    }

    public static Object getAttribute(String key) {
        if (key == null) return null;
        Map<String, Object> attrs = ATTRIBUTES.get();
        if (attrs != null) {
            Object v = attrs.get(key);
            if (v != null) return v;
        }
        // 回退到 clear() 时的快照——给外层 Advice 的 onExit / Plugin 跨清理读用
        Map<String, Object> last = LAST_ATTRIBUTES.get();
        return last == null ? null : last.get(key);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getAttribute(String key, Class<T> type) {
        Object v = getAttribute(key);
        if (v == null || !type.isInstance(v)) return null;
        return (T) v;
    }
}
