package com.iast.agent.plugin;

import com.iast.agent.LogWriter;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 出口链路头透传插件（专用于 {@code HttpRest.sendHttpRequest} 形态的 HTTP 客户端）。
 *
 * <p>典型业务场景：本进程作为 HTTP 服务被外部调用 → {@link RequestIdPlugin} 在
 * Servlet 入口把 requestId、client_ip、forward 链等存到 {@link IastContext}；本进程后续
 * 作为 client 发起出口 HTTP 调用时（参考实现：{@code HttpRest.sendHttpRequest}），
 * 本插件钩到出口方法上、把上面那些上下文翻译成 forward 头注入到下游请求里：
 *
 * <pre>
 *   x-seeker-forward-req-id   = (incoming-chain ?: "") + "," + 本机 reqId
 *   x-seeker-forward-ip       = 入口算好的 forward_ip 链
 *   xseeker                   = 透传字段，原样进出
 * </pre>
 *
 * <p>下游服务再 hook 同套 servlet 入口规则，就能把链路接上。
 *
 * <h3>YAML 用法（无 pluginConfig）</h3>
 * <pre>
 * - className: com.huawei.bsp.roa.util.restclient.HttpRest
 *   methods: ["sendHttpRequest#*"]
 *   plugin: HttpForwardPlugin
 * </pre>
 *
 * <p>本插件不接受 pluginConfig：行为和参数都对齐 {@code HttpRest.sendHttpRequest} 的固定签名
 * （{@code params[2].putHttpContextHeader(String, String)}），不参数化。需要适配其他出口
 * 客户端时直接写一个新插件，而不是把通用配置塞进来。
 *
 * <h3>编译解耦</h3>
 * 目标类（HttpRest 之类）不在 agent 模块的 classpath 上，本类**全程反射**，不 import。
 * 部署到不同环境时，目标类不存在的话 Byte Buddy 规则就是不命中、本插件不会被调用，
 * 不会因此报错。
 */
public class HttpForwardPlugin implements IastPlugin {

    // 下游请求要写入的头名（与参考体系对齐）
    private static final String OUT_FORWARD_REQ_ID = "x-seeker-forward-req-id";
    private static final String OUT_FORWARD_IP     = "x-seeker-forward-ip";
    private static final String OUT_XSEEKER        = "xseeker";

    // HttpRest.sendHttpRequest 的固定签名约定：
    //   params[2] = HttpContext，上面有 putHttpContextHeader(String, String) 方法
    private static final int    REQUEST_ARG_INDEX   = 2;
    private static final String HEADER_SETTER_NAME  = "putHttpContextHeader";

    private LogWriter logWriter;

    @Override
    public void init(Map<String, Object> config) {
        logWriter = LogWriter.getInstance();
        logWriter.info("[IAST HttpForward] plugin initialized (target: HttpRest.sendHttpRequest, "
                + "args[" + REQUEST_ARG_INDEX + "]." + HEADER_SETTER_NAME + "(String,String))");
    }

    @Override
    public void handleMethodCall(MethodContext context) {
        // 只在 ENTER 处理：那时下游请求还没真正发，加头有效。EXIT/EXCEPTION 不需要。
        if (context.getPhase() != MethodContext.CallPhase.ENTER) return;

        // 没走过入口（比如纯 client 进程，从未挂过 RequestIdPlugin），不污染下游头
        String requestId = RequestIdHolder.get();
        if (requestId == null) {
            if (logWriter.isDebugEnabled()) {
                logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                        + "] no active requestId on this thread, skip forwarding");
            }
            return;
        }

        Object[] args = context.getArgs();
        if (args == null || REQUEST_ARG_INDEX >= args.length) {
            if (logWriter.isDebugEnabled()) {
                int len = args == null ? 0 : args.length;
                logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                        + "] arg index " + REQUEST_ARG_INDEX + " out of range (args.length=" + len + ")");
            }
            return;
        }
        Object target = args[REQUEST_ARG_INDEX];
        if (target == null) {
            if (logWriter.isDebugEnabled()) {
                logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                        + "] args[" + REQUEST_ARG_INDEX + "] is null, skip");
            }
            return;
        }

        Method setter;
        try {
            setter = target.getClass().getMethod(HEADER_SETTER_NAME, String.class, String.class);
        } catch (NoSuchMethodException nsme) {
            // params[2] 不是预期的 HttpContext 类型——签名变了或挂错方法上
            logWriter.warn("[IAST HttpForward] [callId=" + context.getCallId() + "] "
                    + target.getClass().getName() + " has no " + HEADER_SETTER_NAME
                    + "(String,String); skip forwarding (signature mismatch?)");
            return;
        }

        // 拼 forward_req_id：incoming chain ++ "," ++ 本机 reqId（empty 时退化成单个 reqId）
        Object existing = IastContext.getAttribute(RequestIdPlugin.ATTR_FORWARD_REQ_ID);
        String forwardReqId = (existing instanceof String && !((String) existing).isEmpty())
                ? existing + "," + requestId
                : requestId;

        invokeSetter(setter, target, OUT_FORWARD_REQ_ID, forwardReqId, context.getCallId());

        Object forwardIp = IastContext.getAttribute(RequestIdPlugin.ATTR_FORWARD_IP);
        if (forwardIp instanceof String && !((String) forwardIp).isEmpty()) {
            invokeSetter(setter, target, OUT_FORWARD_IP, (String) forwardIp, context.getCallId());
        }

        Object xseeker = IastContext.getAttribute(RequestIdPlugin.ATTR_XSEEKER);
        if (xseeker instanceof String && !((String) xseeker).isEmpty()) {
            invokeSetter(setter, target, OUT_XSEEKER, (String) xseeker, context.getCallId());
        }

        if (logWriter.isDebugEnabled()) {
            logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                    + "] forwarded headers via " + target.getClass().getName() + "." + HEADER_SETTER_NAME
                    + " (req_id=" + requestId + ")");
        }
    }

    private void invokeSetter(Method setter, Object target, String name, String value, long callId) {
        try {
            setter.invoke(target, name, value);
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            // 业务调用本身不能炸——只警告 + 继续
            logWriter.warn("[IAST HttpForward] [callId=" + callId + "] " + setter.getName()
                    + "('" + name + "', ...) threw on " + target.getClass().getName()
                    + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    @Override
    public void destroy() {
    }

    @Override
    public String getName() {
        return "HttpForwardPlugin";
    }
}
