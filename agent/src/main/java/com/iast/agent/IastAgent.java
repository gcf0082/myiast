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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;

public class IastAgent {
    public static final AtomicInteger globalCallCount = new AtomicInteger(0);
    // 监控全局开关，volatile保证多线程立即可见
    public static volatile boolean MONITOR_ENABLED = true;
    // 初始化标记，避免重复执行初始化逻辑
    private static volatile boolean INITIALIZED = false;

    // Agent 启动的 wall-clock 毫秒，CLI status 用
    public static final long START_TIME = System.currentTimeMillis();
    // 保存 Instrumentation 以便 CLI 运行期调用 getAllLoadedClasses（buildAndInstall 之后就没其他地方用）
    public static volatile Instrumentation INSTRUMENTATION;

    // 当前方法上下文（用于onExit时获取类名）
    // 必须public：Advice字节码会被内联到被监控的类中（如HttpServlet），
    // 这些类与IastAgent处于不同ClassLoader，访问private字段会触发IllegalAccessError
    public static final ThreadLocal<com.iast.agent.plugin.MethodContext> currentContext = new ThreadLocal<>();

    /**
     * Pre-agent模式入口：JVM启动时挂载
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LogWriter.getInstance().init();
        com.iast.agent.plugin.event.EventWriter.getInstance().init();
        LogWriter.getInstance().info("[IAST Agent] Starting IAST Agent in pre-agent mode...");
        startAgent(agentArgs, inst, true);
    }

    /**
     * Attach模式入口：动态挂载到运行中的JVM
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LogWriter.getInstance().init();
        com.iast.agent.plugin.event.EventWriter.getInstance().init();
        LogWriter.getInstance().info("[IAST Agent] Starting IAST Agent in attach mode...");
        startAgent(agentArgs, inst, false);
    }

    /**
     * 公共Agent启动逻辑，供两种模式复用
     * @param isPremain true=premain（JVM 启动时），false=agentmain（attach 到运行中进程）
     */
    private static void startAgent(String agentArgs, Instrumentation inst, boolean isPremain) {
        // 如果已经初始化，仅处理开关/CLI 指令
        if (INITIALIZED) {
            LogWriter.getInstance().info("[IAST Agent] Already initialized, processing control command: " + agentArgs);
            if ("stop".equals(agentArgs)) {
                MONITOR_ENABLED = false;
                LogWriter.getInstance().info("[IAST Agent] Monitor disabled successfully, target process restored to normal");
            } else if ("start".equals(agentArgs)) {
                MONITOR_ENABLED = true;
                LogWriter.getInstance().info("[IAST Agent] Monitor enabled successfully");
            } else if ("cli".equals(agentArgs)) {
                com.iast.agent.cli.CliServer.ensureStarted();
            }
            return;
        }
        // 首次初始化，正常执行流程
        LogWriter.getInstance().info("[IAST Agent] Java version: " + System.getProperty("java.version"));

        // 保存全局 Instrumentation 引用供 CLI / 未来工具使用（install 完成后 inst 不会被持有）
        INSTRUMENTATION = inst;

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

        // 先加载配置，再初始化插件（插件init需要MonitorConfig.getPluginConfigs()返回的聚合配置）
        MonitorConfig.init(agentArgs);
        initPlugins();

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

        // 决定字节码 install 的时机：premain 模式下默认延迟 1 分钟，避免 retransform + 逐类拦截拖慢业务启动；
        // agentmain 模式总是立即 install（典型场景是对已经跑起来的进程做 hook，没有启动期开销顾虑）。
        long delayMs = isPremain ? MonitorConfig.getPremainDelayMs() : 0L;

        Runnable installTask = () -> buildAndInstall(inst, adviceLocator);

        if (delayMs > 0L) {
            final long delayMsFinal = delayMs;
            Thread installer = new Thread(() -> {
                try {
                    Thread.sleep(delayMsFinal);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LogWriter.getInstance().info("[IAST Agent] Delayed install thread interrupted, skipping install");
                    return;
                }
                try {
                    installTask.run();
                } catch (Throwable t) {
                    LogWriter.getInstance().info("[IAST Agent] Delayed install failed: " + t);
                }
            }, "iast-agent-delayed-install");
            installer.setDaemon(true);
            installer.start();
            LogWriter.getInstance().info("[IAST Agent] Bytecode install deferred by " + delayMs
                    + "ms to protect app startup (premain mode). Set monitor.default.premainDelayMs: 0 to disable.");
        } else {
            installTask.run();
        }

        // 标记初始化完成：后续 agentmain("start"/"stop"/"cli") 能进入开关/命令处理分支。
        // 这里先于实际 install 标记是安全的——advice 的 MONITOR_ENABLED 检查独立于 INITIALIZED。
        INITIALIZED = true;

        // 首次 attach 时若直接带 "cli"，一并把 CLI server 起来（"一步到位"场景）
        if ("cli".equals(agentArgs)) {
            com.iast.agent.cli.CliServer.ensureStarted();
        }
    }

    /**
     * 真正构建 AgentBuilder 并 installOn(inst)。
     * 在 premain 延迟模式下被异步 daemon 线程调用。
     */
    private static void buildAndInstall(Instrumentation inst, ClassFileLocator adviceLocator) {
        LogWriter.getInstance().info("[IAST Agent] Building AgentBuilder and installing transformers...");

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

        // 若存在任何 matchType=interface 且全局开关为 OFF 的规则，预先取已加载类名快照，
        // 用 NameInSetMatcher 过滤掉"Agent 安装后新加载"的实现类
        Set<String> preInstallLoadedNames = null;

        // 为每个监控类添加规则
        List<String> monitoredClasses = MonitorConfig.getMonitoredClasses();
        for (String internalClassName : monitoredClasses) {
            String className = internalClassName.replace('/', '.');
            List<MonitorConfig.MethodRule> methodRules = MonitorConfig.getMethodRules(internalClassName);
            String matchType = MonitorConfig.getMatchType(internalClassName);
            boolean isInterfaceRule = "interface".equals(matchType);

            ElementMatcher.Junction<TypeDescription> typeMatcher;
            if (isInterfaceRule) {
                // 接口/父类规则：匹配所有实现类 + 抽象父类（保留抽象类是为了 hook 那些"声明在抽象基类里、
                // 具体子类只靠继承不 override"的方法，典型如 HttpServlet.service）。只排除接口本身和规则类自身。
                typeMatcher = ElementMatchers.hasSuperType(ElementMatchers.named(className))
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.named(className)));
                if (!MonitorConfig.isIncludeFutureClasses()) {
                    if (preInstallLoadedNames == null) {
                        preInstallLoadedNames = snapshotLoadedClassNames(inst);
                        LogWriter.getInstance().info("[IAST Agent] Snapshot of loaded classes taken ("
                                + preInstallLoadedNames.size() + " classes); future-loaded classes won't match interface rules");
                    }
                    typeMatcher = typeMatcher.and(new com.iast.agent.matcher.NameInSetMatcher<>(preInstallLoadedNames));
                }
                LogWriter.getInstance().info("[IAST Agent] Interface rule: match all implementations of " + className
                        + " (includeFutureClasses=" + MonitorConfig.isIncludeFutureClasses() + ")");
            } else {
                typeMatcher = ElementMatchers.named(className);
            }

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

            final boolean linkConcrete = isInterfaceRule;
            final String interfaceInternalName = internalClassName;
            final boolean wrapServletRequest = MonitorConfig.isWrapServletRequest(internalClassName);

            // 应用普通方法Advice
            if (methodMatcher != null) {
                final ElementMatcher.Junction<MethodDescription> fm = methodMatcher;
                final Class<?> adviceClass = wrapServletRequest ? ServletBodyAdvice.class : MethodMonitorAdvice.class;
                if (wrapServletRequest) {
                    LogWriter.getInstance().info("[IAST Agent] Using ServletBodyAdvice (wrapServletRequest=true) for " + className);
                }
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                            if (linkConcrete) {
                                MonitorConfig.linkConcreteToPlugins(typeDescription.getInternalName(), interfaceInternalName);
                            }
                            return builder.visit(Advice.to(adviceClass, adviceLocator).on(fm));
                        });
            }

            // 应用构造函数Advice
            if (ctorMatcher != null) {
                final ElementMatcher.Junction<MethodDescription> fc = ctorMatcher;
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                            if (linkConcrete) {
                                MonitorConfig.linkConcreteToPlugins(typeDescription.getInternalName(), interfaceInternalName);
                            }
                            return builder.visit(Advice.to(ConstructorMonitorAdvice.class, adviceLocator).on(fc));
                        });
            }
        }

        // 安装Agent
        agentBuilder.installOn(inst);

        LogWriter.getInstance().info("[IAST Agent] Agent installed successfully, monitoring " + monitoredClasses.size() + " classes");
    }

    /**
     * 初始化插件
     */
    private static void initPlugins() {
        com.iast.agent.plugin.PluginManager pm = com.iast.agent.plugin.PluginManager.getInstance();
        java.util.Map<String, java.util.List<java.util.Map<String, Object>>> allCfgs = MonitorConfig.getPluginConfigs();
        registerPlugin(pm, "LogPlugin", new com.iast.agent.plugin.LogPlugin(), allCfgs);
        registerPlugin(pm, "RequestIdPlugin", new com.iast.agent.plugin.RequestIdPlugin(), allCfgs);
        registerPlugin(pm, "CustomEventPlugin", new com.iast.agent.plugin.CustomEventPlugin(), allCfgs);
        registerPlugin(pm, "ServletBodyPlugin", new com.iast.agent.plugin.ServletBodyPlugin(), allCfgs);

        // 外部插件：按 monitor.default.pluginsDir 指向的目录，通过 ServiceLoader 发现
        // 冲突策略：外部插件 getName() 如果和内置撞名，保守跳过——不允许外部覆盖内置实现
        String dir = MonitorConfig.getPluginsDir();
        java.util.List<com.iast.agent.plugin.IastPlugin> externals =
                com.iast.agent.plugin.PluginDiscovery.discover(dir);
        for (com.iast.agent.plugin.IastPlugin ext : externals) {
            String name = ext.getName();
            if (name == null || name.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] External plugin missing getName(), skipped: " + ext.getClass().getName());
                continue;
            }
            if (pm.getPlugin(name) != null) {
                LogWriter.getInstance().info("[IAST Agent] WARN external plugin name '" + name
                        + "' conflicts with a built-in plugin; keeping built-in, skipping external "
                        + ext.getClass().getName());
                continue;
            }
            registerPlugin(pm, name, ext, allCfgs);
        }
    }

    private static void registerPlugin(com.iast.agent.plugin.PluginManager pm, String name,
                                       com.iast.agent.plugin.IastPlugin plugin,
                                       java.util.Map<String, java.util.List<java.util.Map<String, Object>>> allCfgs) {
        try {
            java.util.List<java.util.Map<String, Object>> defs = allCfgs.getOrDefault(name, java.util.Collections.emptyList());
            java.util.Map<String, Object> cfg = new java.util.HashMap<>();
            cfg.put("definitions", defs);
            plugin.init(cfg);
            pm.registerPlugin(name, plugin);
            LogWriter.getInstance().info("[IAST Agent] Initialized plugin: " + name + " with " + defs.size() + " rule(s)");
        } catch (Exception e) {
            LogWriter.getInstance().info("[IAST Agent] Failed to initialize plugin " + name + ": " + e.getMessage());
        }
    }

    /**
     * 取当前所有已加载类的 FQCN 快照。
     * 用于 matchType=interface 且 includeFutureClasses=false 时做 "安装前已加载" 过滤。
     */
    private static Set<String> snapshotLoadedClassNames(Instrumentation inst) {
        Class<?>[] all = inst.getAllLoadedClasses();
        Set<String> names = new HashSet<>(all.length);
        for (Class<?> c : all) {
            names.add(c.getName());
        }
        return names;
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
            // fullMethodName格式：com.example.MyClass.method(arg.Type1,arg.Type2)RetType
            // 参数类型中可能含"."，因此需要在"("之前查找最后一个"."
            int parenIndex = fullMethodName.indexOf('(');
            int searchEnd = (parenIndex > 0) ? parenIndex : fullMethodName.length();
            int lastDotIndex = fullMethodName.lastIndexOf('.', searchEnd - 1);
            if (lastDotIndex > 0) {
                context.setClassName(fullMethodName.substring(0, lastDotIndex));
                context.setMethodName(fullMethodName.substring(lastDotIndex + 1, searchEnd));
            } else {
                context.setClassName(fullMethodName);
                context.setMethodName("unknown");
            }

            // 获取调用栈
            context.setStackTrace(Thread.currentThread().getStackTrace());

            // 保存当前上下文到ThreadLocal（供onExit使用）
            currentContext.set(context);
            
            // 调用插件处理（支持同一方法挂多个插件）
            String internalClassName = context.getClassName().replace('.', '/');
            MonitorConfig.dispatchToPlugins(internalClassName, context);

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
            
            // 从ThreadLocal获取上下文
            com.iast.agent.plugin.MethodContext context = currentContext.get();
            currentContext.remove();
            
            if (context == null) {
                return;
            }
            
            context.setExitTime(System.currentTimeMillis());
            context.setDuration(context.getExitTime() - context.getEnterTime());
            
            if (throwable != null) {
                context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXCEPTION);
                context.setThrowable(throwable);
            } else {
                context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXIT);
                context.setResult(result);
            }
            
            // 调用插件处理（与onEnter保持一致，支持多插件分发）
            String internalClassName = context.getClassName() != null
                    ? context.getClassName().replace('.', '/')
                    : "";
            MonitorConfig.dispatchToPlugins(internalClassName, context);
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
            // fullMethodName格式：com.example.MyClass.<init>(arg.Type1,arg.Type2)V
            // 参数类型中可能含"."，因此需要在"("之前查找最后一个"."
            int parenIndex = fullMethodName.indexOf('(');
            int searchEnd = (parenIndex > 0) ? parenIndex : fullMethodName.length();
            int lastDotIndex = fullMethodName.lastIndexOf('.', searchEnd - 1);
            if (lastDotIndex > 0) {
                context.setClassName(fullMethodName.substring(0, lastDotIndex));
                context.setMethodName(fullMethodName.substring(lastDotIndex + 1, searchEnd));
            } else {
                context.setClassName(fullMethodName);
                context.setMethodName("unknown");
            }
            
            // 获取调用栈
            context.setStackTrace(Thread.currentThread().getStackTrace());

            // 保存当前上下文到ThreadLocal（供onExit获取className等信息）
            currentContext.set(context);

            // 调用插件处理（构造函数也支持多插件分发）
            String internalClassName = context.getClassName().replace('.', '/');
            MonitorConfig.dispatchToPlugins(internalClassName, context);

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

            // 从ThreadLocal取回onEnter保存的上下文，复用className等信息
            com.iast.agent.plugin.MethodContext context = currentContext.get();
            currentContext.remove();

            if (context == null) {
                return;
            }

            context.setExitTime(System.currentTimeMillis());
            context.setDuration(context.getExitTime() - context.getEnterTime());
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXIT);
            context.setTarget(self);  // 构造函数完成后可以访问this

            // 调用插件处理（与onEnter保持一致，支持多插件分发）
            String internalClassName = context.getClassName() != null
                    ? context.getClassName().replace('.', '/')
                    : "";
            MonitorConfig.dispatchToPlugins(internalClassName, context);
        }
    }
}