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
    /** 请求和响应头都用同一个名字；上游传入 / 响应回写都围绕这个 key。
     *  和 x-seeker-forward-* / xseeker 同前缀，避免和业务自己的 X-Request-Id 撞名。 */
    private static final String HEADER_NAME = "X-Seeker-Request-Id";
    private static final String INCOMING_HEADER = HEADER_NAME;

    // ===== 链路 attr 键名（HttpForwardPlugin 出口侧会读这几个） =====
    /** 主调方 IP，优先取 x-real-client-addr 头，否则 request.getRemoteAddr() */
    public static final String ATTR_CLIENT_IP       = "iast.client_ip";
    /** 上游 forward_req_id 链（服务端入口收到时的值，出口拼接时往后追加本机 reqId） */
    public static final String ATTR_FORWARD_REQ_ID  = "iast.forward_req_id";
    /** 已经聚合好的 forward_ip 链：incoming x-seeker-forward-ip + "," + 当前 client_ip */
    public static final String ATTR_FORWARD_IP      = "iast.forward_ip";
    /** 透传字段，原样进出，不做任何加工 */
    public static final String ATTR_XSEEKER         = "iast.xseeker";

    // 入口取上下文用到的请求头（参考项目同名）
    private static final String IN_HEADER_REAL_CLIENT_ADDR  = "x-real-client-addr";
    private static final String IN_HEADER_FORWARD_REQ_ID    = "x-seeker-forward-req-id";
    private static final String IN_HEADER_FORWARD_IP        = "x-seeker-forward-ip";
    private static final String IN_HEADER_XSEEKER           = "xseeker";

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
            // 优先采用调用方已经带进来的 X-Seeker-Request-Id 头；这样跨服务链路的 id 能直接串起来，
            // 前端可以用自己种的 id 追溯。上游没传再 fallback 到本地 UUID。
            Object req = (context.getArgs() != null && context.getArgs().length > 0) ? context.getArgs()[0] : null;
            String incoming = extractIncomingId(req);
            requestId = (incoming != null) ? incoming : UUID.randomUUID().toString().replace("-", "");
            RequestIdHolder.set(requestId);
        }

        context.setRequestId(requestId);

        // 强制保证响应头一定带 X-Seeker-Request-Id：每次 enter 都尝试一次，用 setHeader（替换）而不是
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

        // 链路上下文采集（仅最外层入口做一次；嵌套层 attr 已存好，不重复覆盖）。出口侧
        // HttpForwardPlugin 从这几个 attr 读取后注入到下游请求里。
        if (depth == 1 && context.getArgs() != null && context.getArgs().length > 0) {
            captureLinkContext(context.getArgs()[0], context.getCallId());
        }

        logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] === Request Started (depth=" + depth + ") ===");
    }

    /**
     * 入口从 ServletRequest 上读链路上下文，存到 IastContext attribute。后续同线程上的
     * 出口（如 HttpRest.sendHttpRequest）就能从同一组 key 取到。
     *
     * <p>所有访问都走反射——agent 不能编译期依赖 Servlet API（bootstrap CL 看不见 jakarta/javax.servlet）。
     * 任一字段抓不到都不致命，debug 日志一句、继续；不影响 requestId 主流程。
     */
    private void captureLinkContext(Object req, long callId) {
        if (req == null) return;

        String clientIp = extractStringHeader(req, IN_HEADER_REAL_CLIENT_ADDR);
        if (clientIp == null) {
            clientIp = extractRemoteAddr(req);
        }
        if (clientIp != null) {
            IastContext.putAttribute(ATTR_CLIENT_IP, clientIp);
        }

        String incomingFwdId = extractStringHeader(req, IN_HEADER_FORWARD_REQ_ID);
        if (incomingFwdId != null) {
            IastContext.putAttribute(ATTR_FORWARD_REQ_ID, incomingFwdId);
        }

        // forward_ip = incoming chain ++ "," ++ client_ip；缺哪一段就退化用另一段
        String incomingFwdIp = extractStringHeader(req, IN_HEADER_FORWARD_IP);
        String forwardIp;
        if (incomingFwdIp != null && clientIp != null) {
            forwardIp = incomingFwdIp + "," + clientIp;
        } else if (incomingFwdIp != null) {
            forwardIp = incomingFwdIp;
        } else {
            forwardIp = clientIp;  // 可能仍为 null，这种就什么都不存
        }
        if (forwardIp != null) {
            IastContext.putAttribute(ATTR_FORWARD_IP, forwardIp);
        }

        String xseeker = extractStringHeader(req, IN_HEADER_XSEEKER);
        if (xseeker != null) {
            IastContext.putAttribute(ATTR_XSEEKER, xseeker);
        }

        if (logWriter.isDebugEnabled()) {
            logWriter.debug("[IAST RequestId] [callId=" + callId + "] link captured: client_ip="
                    + clientIp + ", forward_req_id=" + incomingFwdId
                    + ", forward_ip=" + forwardIp + ", xseeker=" + (xseeker == null ? "null" : "<set>"));
        }
    }

    /**
     * 幂等地把 X-Seeker-Request-Id 写到响应上。
     * <ul>
     *   <li>response 已有 X-Seeker-Request-Id（任意值）→ 不覆盖，尊重上游/业务已有设置</li>
     *   <li>否则调 setHeader（注意不是 addHeader）—— setHeader 会替换同名值，嵌套调用多次
     *       也只会留一份头，不会出现多个 X-Seeker-Request-Id 行</li>
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

    /** 反射调 request.getHeader(name)；上游 X-Seeker-Request-Id 复用、链路 attr 采集都走它。 */
    private static String extractIncomingId(Object req) {
        return extractStringHeader(req, INCOMING_HEADER);
    }

    /**
     * 反射读取一个请求头。req 可能是 ServletRequest（无 getHeader）或 HttpServletRequest，
     * 两种 namespace（javax/jakarta）也都在；统一用反射规避编译期依赖。
     * 失败返回 null，debug 日志一句；不抛异常打断主流程。
     */
    private static String extractStringHeader(Object req, String name) {
        if (req == null) return null;
        try {
            Method getHeader = req.getClass().getMethod("getHeader", String.class);
            Object v = getHeader.invoke(req, name);
            return (v instanceof String) ? (String) v : null;
        } catch (NoSuchMethodException nsme) {
            // 不是 HttpServletRequest（可能是 ServletRequest 接口直接来的）
            LogWriter lw = LogWriter.getInstance();
            if (lw.isDebugEnabled()) {
                lw.debug("[IAST RequestId] req has no getHeader (class=" + req.getClass().getName() + "); skip header '" + name + "'");
            }
            return null;
        } catch (Throwable t) {
            LogWriter lw = LogWriter.getInstance();
            if (lw.isDebugEnabled()) {
                lw.debug("[IAST RequestId] getHeader('" + name + "') failed on " + req.getClass().getName()
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /** 反射调 request.getRemoteAddr()，作为 client_ip 的兜底来源。 */
    private static String extractRemoteAddr(Object req) {
        if (req == null) return null;
        try {
            Method m = req.getClass().getMethod("getRemoteAddr");
            Object v = m.invoke(req);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            LogWriter lw = LogWriter.getInstance();
            if (lw.isDebugEnabled()) {
                lw.debug("[IAST RequestId] getRemoteAddr() failed on " + req.getClass().getName()
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }
}