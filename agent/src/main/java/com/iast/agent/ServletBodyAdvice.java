package com.iast.agent;

import com.iast.agent.inject.WrapperInjector;
import com.iast.agent.plugin.RequestIdHolder;
import com.iast.agent.plugin.ServletBodyPlugin;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * 专用 Advice：hook {@code HttpServlet.service(ServletRequest, ServletResponse)}，
 * 在 onEnter 用 {@link WrapperInjector#wrap} 构造一个缓冲包装，**改写** arg[0] 为包装后的对象
 * （{@code @Advice.Argument(readOnly = false, typing = DYNAMIC)}），业务此后读到的是缓冲流——
 * 多次读取都能复原、不会让插件把 body 吃空。onExit 再反射从包装取出 cachedBody 交 Plugin 打日志。
 */
public class ServletBodyAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object onEnter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request) {
        if (!IastAgent.MONITOR_ENABLED) return null;
        if (request == null) return null;

        // 已经是我们自己的 wrapper 了（嵌套 service 调用）——跳过，不要无限包。
        String cn = request.getClass().getName();
        if (cn.endsWith(".BufferingHttpServletRequestWrapper")) return null;

        // Content-Type / Content-Length 决定是否包装。反射失败就保守放行（仍尝试包装，交给 Wrapper 的 hardLimit 兜）
        String contentType = null;
        long declaredLen = -1L;
        try {
            Object ct = request.getClass().getMethod("getContentType").invoke(request);
            if (ct instanceof String) contentType = (String) ct;
        } catch (Throwable ignore) {}
        try {
            Object cl = request.getClass().getMethod("getContentLengthLong").invoke(request);
            if (cl instanceof Long) declaredLen = (Long) cl;
        } catch (Throwable ignore) {}

        if (!ServletBodyPlugin.shouldWrap(contentType, declaredLen)) {
            // 不包装但仍然可记一笔"非文本 / 超大"的 summary
            ServletBodyPlugin.emit(RequestIdHolder.get(), contentType, null, null,
                    declaredLen < 0 ? 0 : declaredLen, false);
            return null;
        }

        Object wrapped = WrapperInjector.wrap(request, ServletBodyPlugin.hardLimitBytes());
        if (wrapped == null) return null;
        request = wrapped;   // 关键：改写入参
        return wrapped;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter Object wrapped) {
        if (wrapped == null) return;
        try {
            byte[] body = (byte[]) wrapped.getClass().getMethod("peekCachedBody").invoke(wrapped);
            Object totalObj = wrapped.getClass().getMethod("getTotalLength").invoke(wrapped);
            Object trObj = wrapped.getClass().getMethod("isTruncated").invoke(wrapped);
            Object ctObj = wrapped.getClass().getMethod("getContentType").invoke(wrapped);
            Object encObj = wrapped.getClass().getMethod("getCharacterEncoding").invoke(wrapped);

            long total = (totalObj instanceof Long) ? (Long) totalObj : 0L;
            boolean tr = (trObj instanceof Boolean) && (Boolean) trObj;
            String ct = (ctObj instanceof String) ? (String) ctObj : null;
            String enc = (encObj instanceof String) ? (String) encObj : null;

            ServletBodyPlugin.emit(RequestIdHolder.get(), ct, enc, body, total, tr);
        } catch (Throwable ignore) {}
    }
}
