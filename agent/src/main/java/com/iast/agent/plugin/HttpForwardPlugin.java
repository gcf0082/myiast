package com.iast.agent.plugin;

import com.iast.agent.LogWriter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 出口链路头透传插件。
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
 * <h3>YAML 配置（可选）</h3>
 * <pre>
 * - className: com.huawei.bsp.roa.util.restclient.HttpRest
 *   methods: ["sendHttpRequest#*"]
 *   plugin: HttpForwardPlugin
 *   pluginConfig:
 *     requestArgIndex: 2          # 默认 2，对齐 HttpRest 的入参顺序
 *     headerSetterMethod: putHttpContextHeader   # 默认就是这个，对齐 HttpRest API
 * </pre>
 *
 * <p>不同业务的 HTTP 客户端 API 不同，{@code requestArgIndex} 和 {@code headerSetterMethod}
 * 都可以在 yaml 里改。方法签名固定 {@code (String, String)}。
 *
 * <h3>编译解耦</h3>
 * 目标类（HttpRest 之类）不在 agent 模块的 classpath 上，本类**全程反射**，不 import。
 * 部署到不同环境时，目标类不存在的话 Byte Buddy 规则就是不命中、本插件不会被调用，
 * 不会因此报错。
 */
public class HttpForwardPlugin implements IastPlugin {

    // 出口写到下游请求上的头名（与参考项目对齐）
    private static final String OUT_FORWARD_REQ_ID = "x-seeker-forward-req-id";
    private static final String OUT_FORWARD_IP     = "x-seeker-forward-ip";
    private static final String OUT_XSEEKER        = "xseeker";

    private static final int    DEFAULT_ARG_INDEX = 2;
    private static final String DEFAULT_SETTER    = "putHttpContextHeader";

    private LogWriter logWriter;
    private volatile int     requestArgIndex   = DEFAULT_ARG_INDEX;
    private volatile String  headerSetterMethod = DEFAULT_SETTER;

    @Override
    public void init(Map<String, Object> config) {
        logWriter = LogWriter.getInstance();
        // 多条 rule 都用本插件时，最后一条 pluginConfig 的设置生效（YAML 声明顺序）。
        // v1 不区分按 className 取对应配置——少见冲突，需要时再扩。
        if (config == null) return;
        Object defs = config.get("definitions");
        if (!(defs instanceof List)) return;
        for (Object raw : (List<?>) defs) {
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) raw;
            applyOne(def);
        }
        logWriter.info("[IAST HttpForward] plugin initialized: requestArgIndex=" + requestArgIndex
                + ", headerSetterMethod=" + headerSetterMethod);
    }

    private void applyOne(Map<String, Object> def) {
        Object v = def.get("requestArgIndex");
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= 0) requestArgIndex = n;
        }
        v = def.get("headerSetterMethod");
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (!s.isEmpty()) headerSetterMethod = s;
        }
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
        if (args == null || requestArgIndex >= args.length) {
            if (logWriter.isDebugEnabled()) {
                int len = args == null ? 0 : args.length;
                logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                        + "] arg index " + requestArgIndex + " out of range (args.length=" + len + ")");
            }
            return;
        }
        Object target = args[requestArgIndex];
        if (target == null) {
            if (logWriter.isDebugEnabled()) {
                logWriter.debug("[IAST HttpForward] [callId=" + context.getCallId()
                        + "] args[" + requestArgIndex + "] is null, skip");
            }
            return;
        }

        Method setter;
        try {
            setter = target.getClass().getMethod(headerSetterMethod, String.class, String.class);
        } catch (NoSuchMethodException nsme) {
            // 出口端的「能写头的对象」上没找到约定的 setter——配置错或对象类型不匹配
            logWriter.warn("[IAST HttpForward] [callId=" + context.getCallId() + "] "
                    + target.getClass().getName() + " has no " + headerSetterMethod
                    + "(String,String); skip forwarding (check pluginConfig.headerSetterMethod / requestArgIndex)");
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
                    + "] forwarded headers via " + target.getClass().getName() + "." + headerSetterMethod
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
