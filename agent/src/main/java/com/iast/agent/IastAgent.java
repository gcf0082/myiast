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
    // 监控全局开关，volatile保证多线程立即可见
    public static volatile boolean MONITOR_ENABLED = true;
    // 初始化标记，避免重复执行初始化逻辑
    private static volatile boolean INITIALIZED = false;

    /**
     * Pre-agent模式入口：JVM启动时挂载
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LogWriter.getInstance().init();
        LogWriter.getInstance().info("[IAST Agent] Starting IAST Agent in pre-agent mode...");
        startAgent(agentArgs, inst);
    }

    /**
     * Attach模式入口：动态挂载到运行中的JVM
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LogWriter.getInstance().init();
        LogWriter.getInstance().info("[IAST Agent] Starting IAST Agent in attach mode...");
        startAgent(agentArgs, inst);
    }

    /**
     * 公共Agent启动逻辑，供两种模式复用
     */
    private static void startAgent(String agentArgs, Instrumentation inst) {
        // 如果已经初始化，仅处理开关指令
        if (INITIALIZED) {
            LogWriter.getInstance().info("[IAST Agent] Already initialized, processing control command: " + agentArgs);
            if ("stop".equals(agentArgs)) {
                MONITOR_ENABLED = false;
                LogWriter.getInstance().info("[IAST Agent] Monitor disabled successfully, target process restored to normal");
            } else if ("start".equals(agentArgs)) {
                MONITOR_ENABLED = true;
                LogWriter.getInstance().info("[IAST Agent] Monitor enabled successfully");
            }
            return;
        }
        // 首次初始化，正常执行流程
        LogWriter.getInstance().info("[IAST Agent] Java version: " + System.getProperty("java.version"));

        // 初始化插件
        initPlugins();

        // 定位Agent jar路径并添加到bootstrap classpath
        File agentJarFile = findAgentJar();
        if (agentJarFile != null) {
            try {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJarFile));
                LogWriter.getInstance().info("[IAST Agent] Appended agent jar to bootstrap classpath: " + agentJarFile.getAbsolutePath());
            } catch (Exception e) {
                LogWriter.getInstance().info("[IAST Agent] Warning: Failed to append to bootstrap classpath: " + e.getMessage());
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
                        LogWriter.getInstance().info("[IAST Agent] Transformed: " + typeDescription.getName() + " (loaded=" + loaded + ")");
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        LogWriter.getInstance().info("[IAST Agent] Error transforming " + typeName + ": " + throwable.getMessage());
                    }
                });

        // 为每个监控类添加规则
        List<String> monitoredClasses = MonitorConfig.getMonitoredClasses();
        for (String internalClassName : monitoredClasses) {
            String className = internalClassName.replace('/', '.');
            List<MonitorConfig.MethodRule> methodRules = MonitorConfig.getMethodRules(internalClassName);

            ElementMatcher.Junction<TypeDescription> typeMatcher = ElementMatchers.named(className);

            // 分离构造函数规则和普通方法规则
            ElementMatcher.Junction<MethodDescription> ctorMatcher = null;
            ElementMatcher.Junction<MethodDescription> methodMatcher = null;

            for (MonitorConfig.MethodRule rule : methodRules) {
                if ("<init>".equals(rule.getMethodName())) {
                    // 构造函数匹配
                    ElementMatcher.Junction<MethodDescription> m;
                    if (rule.isWildcardDescriptor()) {
                        m = ElementMatchers.isConstructor();
                    } else {
                        m = ElementMatchers.isConstructor()
                                .and(ElementMatchers.hasDescriptor(rule.getDescriptor()));
                    }
                    ctorMatcher = (ctorMatcher == null) ? m : ctorMatcher.or(m);
                } else {
                    // 普通方法匹配
                    ElementMatcher.Junction<MethodDescription> m;
                    if (rule.isWildcardDescriptor()) {
                        m = ElementMatchers.named(rule.getMethodName());
                    } else {
                        m = ElementMatchers.named(rule.getMethodName())
                                .and(ElementMatchers.hasDescriptor(rule.getDescriptor()));
                    }
                    methodMatcher = (methodMatcher == null) ? m : methodMatcher.or(m);
                 }
                 LogWriter.getInstance().info("[IAST Agent] Adding monitor: " + className + "." + rule.getMethodName() + "#" + rule.getDescriptor());
             }

            // 应用普通方法Advice
            if (methodMatcher != null) {
                final ElementMatcher.Junction<MethodDescription> fm = methodMatcher;
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(MethodMonitorAdvice.class, adviceLocator).on(fm))
                        );
            }

            // 应用构造函数Advice
            if (ctorMatcher != null) {
                final ElementMatcher.Junction<MethodDescription> fc = ctorMatcher;
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(ConstructorMonitorAdvice.class, adviceLocator).on(fc))
                        );
            }
        }

        // 安装Agent
        agentBuilder.installOn(inst);
            
        LogWriter.getInstance().info("[IAST Agent] Agent installed successfully, monitoring " + monitoredClasses.size() + " classes");
        // 标记初始化完成
        INITIALIZED = true;
    }

    /**
     * 初始化插件
     */
    private static void initPlugins() {
        try {
            // 注册默认日志插件
            com.iast.agent.plugin.LogPlugin logPlugin = new com.iast.agent.plugin.LogPlugin();
            logPlugin.init(new java.util.HashMap<>());
            com.iast.agent.plugin.PluginManager.getInstance().registerPlugin("LogPlugin", logPlugin);
            LogWriter.getInstance().info("[IAST Agent] Initialized default plugin: LogPlugin");
        } catch (Exception e) {
            LogWriter.getInstance().info("[IAST Agent] Failed to initialize plugins: " + e.getMessage());
        }
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
            LogWriter.getInstance().info("[IAST Agent] Warning: Could not locate agent jar: " + e.getMessage());
        }
        return null;
    }

    /**
     * 方法监控Advice
     */
    public static class MethodMonitorAdvice {
        @Advice.OnMethodEnter
        public static int onEnter(
                @Advice.Origin("#t.#m#s") String fullMethodName,
                @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
                @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            // 全局开关关闭时，直接跳过所有拦截逻辑
            if (!MONITOR_ENABLED) {
                return 0;
            }
            int callId = globalCallCount.incrementAndGet();
            
            // 构建方法上下文
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(callId);
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.ENTER);
            context.setTarget(self);
            context.setArgs(args);
            context.setEnterTime(System.currentTimeMillis());
            context.setThreadId(Thread.currentThread().getId());
            context.setThreadName(Thread.currentThread().getName());
            
            // 解析类名和方法名
            // fullMethodName格式：java.io.File.exists()Z
            int lastDotIndex = fullMethodName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                context.setClassName(fullMethodName.substring(0, lastDotIndex));
                context.setMethodName(fullMethodName.substring(lastDotIndex + 1));
            } else {
                context.setClassName(fullMethodName);
                context.setMethodName("unknown");
            }
            
            // 获取调用栈
            context.setStackTrace(Thread.currentThread().getStackTrace());
            
            // 调用插件处理
            String internalClassName = context.getClassName().replace('.', '/');
            String pluginName = MonitorConfig.getPluginName(internalClassName);
            com.iast.agent.plugin.PluginManager.getInstance().handleMethodCall(pluginName, context);
            
            return callId;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter int callId,
                                  @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable throwable) {
            // callId为0说明开关关闭，直接跳过
            if (callId == 0) {
                return;
            }
            
            // 构建方法上下文（调用后）
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(callId);
            context.setExitTime(System.currentTimeMillis());
            context.setDuration(context.getExitTime() - context.getEnterTime());
            
            if (throwable != null) {
                context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXCEPTION);
                context.setThrowable(throwable);
            } else {
                context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXIT);
                context.setResult(result);
            }
            
            // 调用插件处理
            // 注意：这里无法获取类名，需要从其他地方传递，或者使用默认插件
            // 暂时使用默认插件
            com.iast.agent.plugin.PluginManager.getInstance().handleMethodCall("LogPlugin", context);
        }
    }

    /**
     * 构造函数监控Advice
     * 与MethodMonitorAdvice的区别：onEnter不能访问this（对象尚未构造），onExit无返回值
     */
    public static class ConstructorMonitorAdvice {
        @Advice.OnMethodEnter
        public static int onEnter(
                @Advice.Origin("#t.<init>#s") String fullMethodName,
                @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            // 全局开关关闭时，直接跳过所有拦截逻辑
            if (!MONITOR_ENABLED) {
                return 0;
            }
            int callId = globalCallCount.incrementAndGet();
            
            // 构建方法上下文
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(callId);
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.ENTER);
            context.setTarget(null);  // 构造函数中this不可用
            context.setArgs(args);
            context.setEnterTime(System.currentTimeMillis());
            context.setThreadId(Thread.currentThread().getId());
            context.setThreadName(Thread.currentThread().getName());
            
            // 解析类名和方法名
            // fullMethodName格式：java.io.File.<init>()Z
            int lastDotIndex = fullMethodName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                context.setClassName(fullMethodName.substring(0, lastDotIndex));
                context.setMethodName(fullMethodName.substring(lastDotIndex + 1));
            } else {
                context.setClassName(fullMethodName);
                context.setMethodName("unknown");
            }
            
            // 获取调用栈
            context.setStackTrace(Thread.currentThread().getStackTrace());
            
            // 调用插件处理
            String internalClassName = context.getClassName().replace('.', '/');
            String pluginName = MonitorConfig.getPluginName(internalClassName);
            com.iast.agent.plugin.PluginManager.getInstance().handleMethodCall(pluginName, context);
            
            return callId;
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Enter int callId,
                @Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
            // callId为0说明开关关闭，直接跳过
            if (callId == 0) {
                return;
            }
            
            // 构建方法上下文（调用后）
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(callId);
            context.setExitTime(System.currentTimeMillis());
            context.setDuration(context.getExitTime() - context.getEnterTime());
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXIT);
            context.setTarget(self);  // 构造函数完成后可以访问this
            
            // 调用插件处理
            // 注意：这里无法获取类名，需要从其他地方传递，或者使用默认插件
            // 暂时使用默认插件
            com.iast.agent.plugin.PluginManager.getInstance().handleMethodCall("LogPlugin", context);
        }
    }
}