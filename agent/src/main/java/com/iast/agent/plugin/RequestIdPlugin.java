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
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
            RequestIdHolder.set(requestId);
        }

        context.setRequestId(requestId);

        // 只有最外层入口设置响应头，此时响应还未提交，Tomcat才会把header带出去
        if (depth == 1 && context.getArgs() != null && context.getArgs().length >= 2) {
            Object response = context.getArgs()[1];
            try {
                Method addHeaderMethod = response.getClass().getMethod("addHeader", String.class, String.class);
                addHeaderMethod.invoke(response, "X-Request-Id", requestId);
            } catch (Exception e) {
                // 忽略异常，不影响主流程
            }
        }

        logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] === Request Started (depth=" + depth + ") ===");
    }

    private void handleExit(MethodContext context) {
        String requestId = RequestIdHolder.get();
        int remaining = RequestIdHolder.exit();

        if (requestId != null) {
            context.setRequestId(requestId);
            logWriter.info("[IAST RequestId] [callId=" + context.getCallId() + "] [requestId=" + requestId + "] Request Completed, duration=" + context.getDuration() + "ms");
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
}