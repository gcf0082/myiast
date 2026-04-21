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
    private static final String INCOMING_HEADER = "X-Request-Id";

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
        // 嵌套调用（如service(ServletRequest)内部再调用service(HttpServletRequest)）复用同一个requestId
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

        // 只有最外层入口设置响应头，此时响应还未提交，Tomcat 才会把 header 带出去。
        // 排查"响应头消失"的常见原因都靠下面这几条 debug 日志锁定：depth!=1（嵌套）/ args 不够 /
        // response 是包装类没有 addHeader / addHeader 真抛异常
        int argsLen = context.getArgs() == null ? 0 : context.getArgs().length;
        if (depth == 1 && argsLen >= 2) {
            Object response = context.getArgs()[1];
            String responseClass = response == null ? "null" : response.getClass().getName();
            try {
                Method addHeaderMethod = response.getClass().getMethod("addHeader", String.class, String.class);
                addHeaderMethod.invoke(response, "X-Request-Id", requestId);
                if (logWriter.isDebugEnabled()) {
                    logWriter.debug("[IAST RequestId] [callId=" + context.getCallId() + "] addHeader OK on "
                            + responseClass + " (requestId=" + requestId + ", incoming=" + (newId ? "no" : "yes") + ")");
                }
            } catch (NoSuchMethodException e) {
                // response 包装类没暴露 addHeader（少见——典型 HttpServletResponse / ResponseFacade 都有）。
                // 用 WARN：业务能跑、但本插件的核心承诺 X-Request-Id 头丢了
                logWriter.warn("[IAST RequestId] [callId=" + context.getCallId() + "] addHeader missing on "
                        + responseClass + ": header NOT set (requestId=" + requestId + ")");
            } catch (Exception e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                logWriter.warn("[IAST RequestId] [callId=" + context.getCallId() + "] addHeader threw on "
                        + responseClass + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        + " (requestId=" + requestId + ", header NOT set)");
            }
        } else if (logWriter.isDebugEnabled()) {
            // 跳过 addHeader 的两种"正常"原因；启 debug 时显式标出来
            String reason = (depth != 1) ? "nested call (depth=" + depth + ")"
                                         : "args.length=" + argsLen + " (<2, expected req+res — service overload?)";
            logWriter.debug("[IAST RequestId] [callId=" + context.getCallId()
                    + "] skip addHeader: " + reason + " (requestId=" + requestId + ")");
        }

        logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] === Request Started (depth=" + depth + ") ===");
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