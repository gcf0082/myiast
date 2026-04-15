package com.iast.agent;

import java.util.concurrent.atomic.AtomicInteger;

public class MethodInterceptor {
    private static final AtomicInteger globalCallCount = new AtomicInteger(0);

    public static void logMethodCall(String className, String methodName, Object target, Object[] args) {
        int callId = globalCallCount.incrementAndGet();
        
        System.out.println("[IAST Agent] [" + callId + "] === Method Call Intercepted ===");
        System.out.println("[IAST Agent] [" + callId + "] Class: " + className);
        System.out.println("[IAST Agent] [" + callId + "] Method: " + methodName);
        System.out.println("[IAST Agent] [" + callId + "] Target: " + target);
        
        if (args != null && args.length > 0) {
            System.out.println("[IAST Agent] [" + callId + "] Arguments:");
            for (int i = 0; i < args.length; i++) {
                System.out.println("[IAST Agent] [" + callId + "]   [" + i + "]: " + args[i]);
            }
        }
        
        printStackTrace(callId);
    }

    public static void logMethodReturn(String className, String methodName, Object result) {
        int callId = globalCallCount.get();
        
        System.out.println("[IAST Agent] [" + callId + "] Method returned: " + result);
        System.out.println("[IAST Agent] [" + callId + "] ======================================");
    }

    public static void logMethodException(String className, String methodName, Throwable throwable) {
        int callId = globalCallCount.get();
        
        System.out.println("[IAST Agent] [" + callId + "] Method threw exception: " + throwable.getClass().getName());
        System.out.println("[IAST Agent] [" + callId + "] Exception message: " + throwable.getMessage());
        System.out.println("[IAST Agent] [" + callId + "] ======================================");
    }

    private static void printStackTrace(int callId) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        System.out.println("[IAST Agent] [" + callId + "] Call stack:");
        for (int i = 3; i < Math.min(stackTrace.length, 10); i++) {
            System.out.println("[IAST Agent] [" + callId + "]   at " + stackTrace[i]);
        }
    }

    public static int getGlobalCallCount() {
        return globalCallCount.get();
    }

    public static void resetCallCount() {
        globalCallCount.set(0);
    }
}
