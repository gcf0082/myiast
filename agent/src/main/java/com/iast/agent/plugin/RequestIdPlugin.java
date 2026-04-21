package com.iast.agent.plugin;

import com.iast.agent.LogWriter;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * 请求跟踪插件
 * 拦截Servlet请求，生成UUID作为requestId，记录日志并设置响应头
 */
public class RequestIdPlugin implements IastPlugin {
    /** 请求和响应头都用同一个名字；上游传入 / 响应回写都围绕这个 key */
    private static final String HEADER_NAME = "X-Request-Id";
    private static final String INCOMING_HEADER = HEADER_NAME;

    private LogWriter logWriter;

    @Override
    public void init(Map<String, Object> config) {
        logWriter = LogWriter.getInstance();
    }
    
    @Override
    public void handleMethodCall(MethodContext context) {
        if (context.getPhase() == MethodContext.CallPhase.ENTER) {
            handleEnter(context);
        } else if (context.getPhase() == MethodContext.CallPhase.EXIT) {
            handleExit(context);
        } else if (context.getPhase() == MethodContext.CallPhase.EXCEPTION) {
            handleException(context);
        }
    }
    
    private void handleEnter(MethodContext context) {
        // 嵌套调用（如 service(ServletRequest) 内部再调用 service(HttpServletRequest)）复用同一个 requestId
        int depth = RequestIdHolder.enter();
        String requestId = RequestIdHolder.get();
        boolean newId = (requestId == null);
        if (newId) {
            // 优先采用调用方已经带进来的 X-Request-Id 头；这样跨服务链路的 id 能直接串起来，
            // 前端可以用自己种的 id 追溯。上游没传再 fallback 到本地 UUID。
            Object req = (context.getArgs() != null && context.getArgs().length > 0) ? context.getArgs()[0] : null;
            String incoming = extractIncomingId(req);
            requestId = (incoming != null) ? incoming : UUID.randomUUID().toString().replace("-", "");
            RequestIdHolder.set(requestId);
        }

        context.setRequestId(requestId);

        // 强制保证响应头一定带 X-Request-Id：每次 enter 都尝试一次，用 setHeader（替换）而不是
        // addHeader（追加），这样嵌套调用多次尝试也不会产生重复头；已经有同值则是幂等的。
        // containsHeader 先探查：如果上游过滤器已经设过（相同或不同值），就不再覆盖，尊重既有值。
        int argsLen = context.getArgs() == null ? 0 : context.getArgs().length;
        if (argsLen >= 2) {
            Object response = context.getArgs()[1];
            ensureResponseHeader(response, requestId, context.getCallId(), depth);
        } else if (logWriter.isDebugEnabled()) {
            logWriter.debug("[IAST RequestId] [callId=" + context.getCallId()
                    + "] skip setHeader: args.length=" + argsLen
                    + " (<2, expected req+res — service overload?) (requestId=" + requestId + ")");
        }

        logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] === Request Started (depth=" + depth + ") ===");
    }

    /**
     * 幂等地把 X-Request-Id 写到响应上。
     * <ul>
     *   <li>response 已有 X-Request-Id（任意值）→ 不覆盖，尊重上游/业务已有设置</li>
     *   <li>否则调 setHeader（注意不是 addHeader）—— setHeader 会替换同名值，嵌套调用多次
     *       也只会留一份头，不会出现多个 X-Request-Id 行</li>
     *   <li>setHeader 不存在时（极少见的 response 包装）退回 addHeader，总比没有好</li>
     * </ul>
     */
    private void ensureResponseHeader(Object response, String requestId, long callId, int depth) {
        if (response == null) {
            logWriter.warn("[IAST RequestId] [callId=" + callId + "] response arg is null; header NOT set (requestId=" + requestId + ")");
            return;
        }
        String responseClass = response.getClass().getName();
        Class<?> cls = response.getClass();
        try {
            // 1) containsHeader：上游已有就尊重，不覆盖
            try {
                Method contains = cls.getMethod("containsHeader", String.class);
                Object r = contains.invoke(response, HEADER_NAME);
                if (Boolean.TRUE.equals(r)) {
                    if (logWriter.isDebugEnabled()) {
                        logWriter.debug("[IAST RequestId] [callId=" + callId + "] header already present on "
                                + responseClass + ", skip (requestId=" + requestId + ", depth=" + depth + ")");
                    }
                    return;
                }
            } catch (NoSuchMethodException ignore) {
                // 没 containsHeader 就直接进 setHeader 路径；不是致命
            }

            // 2) setHeader：幂等替换
            try {
                Method setHeader = cls.getMethod("setHeader", String.class, String.class);
                setHeader.invoke(response, HEADER_NAME, requestId);
                if (logWriter.isDebugEnabled()) {
                    logWriter.debug("[IAST RequestId] [callId=" + callId + "] setHeader OK on "
                            + responseClass + " (requestId=" + requestId + ", depth=" + depth + ")");
                }
                return;
            } catch (NoSuchMethodException nsme) {
                // 3) 极端兜底：setHeader 不存在就用 addHeader
                Method addHeader = cls.getMethod("addHeader", String.class, String.class);
                addHeader.invoke(response, HEADER_NAME, requestId);
                if (logWriter.isDebugEnabled()) {
                    logWriter.debug("[IAST RequestId] [callId=" + callId + "] addHeader fallback on "
                            + responseClass + " (no setHeader; requestId=" + requestId + ", depth=" + depth + ")");
                }
            }
        } catch (NoSuchMethodException nsme) {
            logWriter.warn("[IAST RequestId] [callId=" + callId + "] response has neither setHeader nor addHeader on "
                    + responseClass + "; header NOT set (requestId=" + requestId + ")");
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            logWriter.warn("[IAST RequestId] [callId=" + callId + "] setHeader threw on "
                    + responseClass + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + " (requestId=" + requestId + ", header NOT set)");
        }
    }

    private void handleExit(MethodContext context) {
        String requestId = RequestIdHolder.get();
        int remaining = RequestIdHolder.exit();

        if (requestId != null) {
            context.setRequestId(requestId);
            logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] Request Completed, duration=" + context.getDuration() + "ms");
        } else if (logWriter.isDebugEnabled()) {
            // 没拿到 requestId 但 exit 跑到了——通常意味着 enter 没跑（MONITOR_ENABLED 在请求中途被切回，
            // 或者 ThreadLocal 在更外层 plugin 提前被清了）。打 debug 帮排查。
            logWriter.debug("[IAST RequestId] [callId=" + context.getCallId() + "] exit without active requestId (remaining=" + remaining + ")");
        }

        // 只有最外层退出时才清理，避免嵌套调用中途把requestId抹掉
        if (remaining == 0) {
            RequestIdHolder.clear();
        }
    }

    private void handleException(MethodContext context) {
        String requestId = RequestIdHolder.get();
        int remaining = RequestIdHolder.exit();

        if (requestId != null) {
            context.setRequestId(requestId);
            logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] Request Failed: " +
                          (context.getThrowable() != null ? context.getThrowable().getMessage() : "Unknown error"));
        }

        if (remaining == 0) {
            RequestIdHolder.clear();
        }
    }
    
    @Override
    public void destroy() {
    }

    @Override
    public String getName() {
        return "RequestIdPlugin";
    }

    /**
     * 反射读 request.getHeader("X-Request-Id")（ServletRequest 接口在 Agent 所在 bootstrap CL
     * 不可见，不能编译时依赖）。拿到什么用什么，不对值做任何改动——上游传啥就用啥。
     * 返回 null 代表上游没传这个头，由调用方 fallback 到 UUID。
     */
    private static String extractIncomingId(Object req) {
        if (req == null) return null;
        try {
            Method getHeader = req.getClass().getMethod("getHeader", String.class);
            Object v = getHeader.invoke(req, INCOMING_HEADER);
            return (v instanceof String) ? (String) v : null;
        } catch (NoSuchMethodException nsme) {
            // 不是 HttpServletRequest（可能是 ServletRequest 接口直接来的）；正常情况，debug 即可
            LogWriter lw = LogWriter.getInstance();
            if (lw.isDebugEnabled()) {
                lw.debug("[IAST RequestId] req has no getHeader (class=" + req.getClass().getName() + "); will fall back to UUID");
            }
            return null;
        } catch (Throwable t) {
            LogWriter lw = LogWriter.getInstance();
            if (lw.isDebugEnabled()) {
                lw.debug("[IAST RequestId] extractIncomingId failed on " + req.getClass().getName()
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }
}