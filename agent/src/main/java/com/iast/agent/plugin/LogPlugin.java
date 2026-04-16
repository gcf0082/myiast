package com.iast.agent.plugin;

import com.iast.agent.LogWriter;
import java.util.Map;

/**
 * 默认日志插件
 * 将方法调用信息记录到日志文件
 */
public class LogPlugin implements IastPlugin {
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
        String requestId = RequestIdHolder.get();
        String prefix = "[IAST Agent] [callId=" + context.getCallId() + "]" + (requestId != null ? " [requestId=" + requestId + "]" : "");
        logWriter.info(prefix + " === Method Call Intercepted: " + context.getClassName() + "." + context.getMethodName() + " ===");
        logWriter.info(prefix + " Method: " + context.getClassName() + "." + context.getMethodName());
        
        if (context.getTarget() != null) {
            logWriter.info(prefix + " this: " + context.getTarget());
        }
        
        if (context.getArgs() != null && context.getArgs().length > 0) {
            for (int i = 0; i < context.getArgs().length; i++) {
                logWriter.info(prefix + " Arg[" + i + "]: " + context.getArgs()[i]);
            }
        }
    }
    
    private void handleExit(MethodContext context) {
        String requestId = RequestIdHolder.get();
        String prefix = "[IAST Agent] [callId=" + context.getCallId() + "]" + (requestId != null ? " [requestId=" + requestId + "]" : "");
        if (context.getResult() == null) {
            logWriter.info(prefix + " Returned: void/null");
        } else {
            logWriter.info(prefix + " Returned: " + context.getResult());
        }
        logWriter.info(prefix + " Duration: " + context.getDuration() + "ms");
        logWriter.info(prefix + " ========================================");
    }
    
    private void handleException(MethodContext context) {
        String requestId = RequestIdHolder.get();
        String prefix = "[IAST Agent] [callId=" + context.getCallId() + "]" + (requestId != null ? " [requestId=" + requestId + "]" : "");
        logWriter.info(prefix + " Thrown: " + context.getThrowable().getClass().getName() + ": " + context.getThrowable().getMessage());
        logWriter.info(prefix + " Duration: " + context.getDuration() + "ms");
        logWriter.info(prefix + " ========================================");
    }
    
    @Override
    public void destroy() {
        // 日志写入器由LogWriter管理，无需额外处理
    }
    
    @Override
    public String getName() {
        return "LogPlugin";
    }
}
