package com.iast.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;

public class IastAgent {
    public static final AtomicInteger globalCallCount = new AtomicInteger(0);

    /**
     * Pre-agent模式入口：JVM启动时挂载
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[IAST Agent] Starting IAST Agent in pre-agent mode...");
        startAgent(agentArgs, inst);
    }

    /**
     * Attach模式入口：动态挂载到运行中的JVM
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[IAST Agent] Starting IAST Agent in attach mode...");
        startAgent(agentArgs, inst);
    }

    /**
     * 公共Agent启动逻辑，供两种模式复用
     */
    private static void startAgent(String agentArgs, Instrumentation inst) {
        System.out.println("[IAST Agent] Java version: " + System.getProperty("java.version"));

        // 定位Agent jar路径并添加到bootstrap classpath
        File agentJarFile = findAgentJar();
        if (agentJarFile != null) {
            try {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJarFile));
                System.out.println("[IAST Agent] Appended agent jar to bootstrap classpath: " + agentJarFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("[IAST Agent] Warning: Failed to append to bootstrap classpath: " + e.getMessage());
            }
        }

        // 初始化配置，支持agent参数指定配置文件路径
        MonitorConfig.init(agentArgs);

        // 创建ClassFileLocator，确保ByteBuddy能找到Advice类的字节码
        ClassFileLocator locator;
        try {
            if (agentJarFile != null) {
                locator = new ClassFileLocator.Compound(
                        ClassFileLocator.ForJarFile.of(agentJarFile),
                        ClassFileLocator.ForClassLoader.ofSystemLoader()
                );
            } else {
                locator = ClassFileLocator.ForClassLoader.ofSystemLoader();
            }
        } catch (Exception e) {
            locator = ClassFileLocator.ForClassLoader.ofSystemLoader();
        }
        final ClassFileLocator adviceLocator = locator;

        // 构建ByteBuddy Agent
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .ignore(ElementMatchers.nameStartsWith("com.iast.agent."))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges()
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                 net.bytebuddy.utility.JavaModule module,
                                                 boolean loaded, net.bytebuddy.dynamic.DynamicType dynamicType) {
                        System.out.println("[IAST Agent] Transformed: " + typeDescription.getName() + " (loaded=" + loaded + ")");
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        System.err.println("[IAST Agent] Error transforming " + typeName + ": " + throwable.getMessage());
                    }
                });

        // 为每个监控类添加规则
        List<String> monitoredClasses = MonitorConfig.getMonitoredClasses();
        for (String internalClassName : monitoredClasses) {
            String className = internalClassName.replace('/', '.');
            List<MonitorConfig.MethodRule> methodRules = MonitorConfig.getMethodRules(internalClassName);
            
            ElementMatcher.Junction<TypeDescription> typeMatcher = ElementMatchers.named(className);
            ElementMatcher.Junction<MethodDescription> methodMatcher = ElementMatchers.none();
            for (MonitorConfig.MethodRule rule : methodRules) {
                methodMatcher = methodMatcher.or(
                        ElementMatchers.named(rule.getMethodName())
                                .and(ElementMatchers.hasDescriptor(rule.getDescriptor()))
                );
                System.out.println("[IAST Agent] Adding monitor to method: " + className + "." + rule.getMethodName() + rule.getDescriptor());
            }
            final ElementMatcher.Junction<MethodDescription> finalMethodMatcher = methodMatcher;

            agentBuilder = agentBuilder
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(MethodMonitorAdvice.class, adviceLocator).on(finalMethodMatcher))
                    );
        }

        // 安装Agent
        agentBuilder.installOn(inst);
            
        System.out.println("[IAST Agent] Agent installed successfully, monitoring " + monitoredClasses.size() + " classes");
    }

    /**
     * 定位Agent jar文件路径
     */
    private static File findAgentJar() {
        try {
            String classResourcePath = IastAgent.class.getName().replace('.', '/') + ".class";
            ClassLoader cl = IastAgent.class.getClassLoader();
            java.net.URL resourceUrl = (cl != null) ? cl.getResource(classResourcePath)
                    : ClassLoader.getSystemResource(classResourcePath);
            if (resourceUrl != null) {
                String url = resourceUrl.toString();
                if (url.startsWith("jar:file:")) {
                    String jarPath = url.substring("jar:file:".length(), url.indexOf("!"));
                    File f = new File(jarPath);
                    if (f.exists()) return f;
                }
            }
            // 备选：通过CodeSource获取
            java.security.CodeSource cs = IastAgent.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.exists() && f.getName().endsWith(".jar")) return f;
            }
        } catch (Exception e) {
            System.err.println("[IAST Agent] Warning: Could not locate agent jar: " + e.getMessage());
        }
        return null;
    }

    /**
     * 方法监控Advice
     */
    public static class MethodMonitorAdvice {
        @Advice.OnMethodEnter
        public static int onEnter(@Advice.Origin("#m#d") String fullMethodName) {
            int callId = globalCallCount.incrementAndGet();
            System.out.println("[IAST Agent] [" + callId + "] === Intercepted method call ===");
            System.out.println("[IAST Agent] [" + callId + "] Method: " + fullMethodName);
            
            // 打印调用栈（跳过前2层：本方法和调用点）
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            if (stackTrace.length > 2) {
                System.out.println("[IAST Agent] [" + callId + "] Caller: " + stackTrace[2]);
            }
            return callId;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter int callId,
                                  @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable throwable) {
            if (throwable != null) {
                System.out.println("[IAST Agent] [" + callId + "] Thrown: " + throwable.getClass().getName() + ": " + throwable.getMessage());
            } else {
                if (result == null) {
                    System.out.println("[IAST Agent] [" + callId + "] Returned: void/null");
                } else {
                    System.out.println("[IAST Agent] [" + callId + "] Returned: " + result);
                }
            }
            System.out.println("[IAST Agent] [" + callId + "] ========================================");
        }
    }
}