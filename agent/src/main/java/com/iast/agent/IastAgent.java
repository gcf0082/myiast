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
import java.util.ArrayList;
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

    // 已安装的 Byte Buddy transformer 引用——attach start 补偿路径需要 reset 后再装。install 失败时留 null。
    private static volatile net.bytebuddy.agent.builder.ResettableClassFileTransformer INSTALLED_TRANSFORMER;
    // install 整体成功标记：区分"premain 延迟中还没跑"、"跑了但抛了"、"装成功了"三种状态。
    private static volatile boolean INSTALL_SUCCEEDED = false;
    // Advice 字节码定位器——attach start 触发重装时需要复用，不必重新找 agent jar。
    private static volatile ClassFileLocator ADVICE_LOCATOR;

    // 注：历史上这里有 ThreadLocal<MethodContext> currentContext 作为 onEnter→onExit 的上下文通道。
    // 但嵌套 hook 场景（如 Servlet.service 内部再调到子类 service）会让内层 set 覆盖外层，
    // 外层 onExit 就取不到自己的 context，RequestIdPlugin 的 exit 也就不跑，导致 depth 累加泄漏。
    // 现在改由 @Advice.Enter 直接把 MethodContext 传给 onExit——每一层调用各自栈帧独立，不互踩。

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
                boolean prev = MONITOR_ENABLED;
                MONITOR_ENABLED = false;
                LogWriter.getInstance().info("[IAST Agent] MONITOR_ENABLED " + prev + " -> false (stop). "
                        + "Bytecode advice stays installed; advice early-returns when disabled.");
            } else if ("start".equals(agentArgs)) {
                boolean prev = MONITOR_ENABLED;
                MONITOR_ENABLED = true;
                LogWriter.getInstance().info("[IAST Agent] MONITOR_ENABLED " + prev + " -> true (start). "
                        + "Subsequent intercepted calls will dispatch to plugins again.");
                // attach start 进一步做：重载配置 + 卸旧 transformer 重装 + 补 retransform 漏网类。
                // 每一步独立 try/catch，best-effort 语义——任何一环失败只 warn，不中断后续。
                // 注意：必须复用 INSTRUMENTATION（首次 agentmain 时保存），不能用本次调用的 inst 参数——
                // HotSpot 每次 attach 会 new 出一个 Instrumentation 实现实例，
                // 用新 inst 调 removeTransformer 找不到老的 ClassFileTransformer。
                reloadAndRestart(INSTRUMENTATION != null ? INSTRUMENTATION : inst);
            } else {
                handleCliArg(agentArgs);
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
        // Bind EvalContext 进程级字段给 Expression 的 context.* 读；instanceName 依赖 MonitorConfig 解析完
        com.iast.agent.plugin.event.EvalContext.bindInstanceName(MonitorConfig.getResolvedInstanceName());
        com.iast.agent.plugin.event.EvalContext.bindAgentStartTime(START_TIME);
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
        // 存一份给 attach start 补偿路径复用
        ADVICE_LOCATOR = adviceLocator;

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

        // 首次 attach 时若直接带 "cli=host:port"，一并把 CLI dialer 拉起来（"一步到位"场景）
        handleCliArg(agentArgs);
    }

    /**
     * 解析并处理 {@code cli=host:port} 形式的 agentArg，触发 CLI dialer 连接。
     * 非 cli 参数（如 config=...、start/stop）直接忽略。bare {@code "cli"} 提示新语法。
     */
    private static void handleCliArg(String agentArgs) {
        if (agentArgs == null) return;
        if ("cli".equals(agentArgs)) {
            LogWriter.getInstance().info("[IAST Agent] 'cli' alone is no longer supported; use cli=host:port (CLI listens, agent dials)");
            return;
        }
        if (!agentArgs.startsWith("cli=")) return;
        String target = agentArgs.substring("cli=".length()).trim();
        int colon = target.lastIndexOf(':');  // lastIndexOf 兼容 IPv6 host 字面量
        if (colon <= 0 || colon == target.length() - 1) {
            LogWriter.getInstance().info("[IAST Agent] cli agentArg must be cli=host:port, got: " + agentArgs);
            return;
        }
        String host = target.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(target.substring(colon + 1));
        } catch (NumberFormatException nfe) {
            LogWriter.getInstance().info("[IAST Agent] cli port not a number: " + agentArgs);
            return;
        }
        com.iast.agent.cli.CliServer.ensureConnected(host, port);
    }

    /**
     * 真正构建 AgentBuilder 并 installOn(inst)。
     * 在 premain 延迟模式下被异步 daemon 线程调用。
     */
    private static void buildAndInstall(Instrumentation inst, ClassFileLocator adviceLocator) {
        LogWriter.getInstance().info("[IAST Agent] Building AgentBuilder and installing transformers...");
        try {
            doBuildAndInstall(inst, adviceLocator);
            INSTALL_SUCCEEDED = true;
        } catch (Throwable t) {
            // install 异常整体不上抛，给后续 attach start 留补救机会（此时 INSTALL_SUCCEEDED=false，
            // reinstallTransformerBestEffort 会识别出"从没装成功过"走直接 buildAndInstall 路径）
            INSTALL_SUCCEEDED = false;
            LogWriter.getInstance().error("[IAST Agent] buildAndInstall failed; agent left in uninstalled state. "
                    + "Use 'attach start' to retry. cause: " + t, t);
        }
    }

    private static void doBuildAndInstall(Instrumentation inst, ClassFileLocator adviceLocator) {
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
                        TransformedClasses.getInstance().recordTransform(typeDescription, classLoader);
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        LogWriter.getInstance().info("[IAST Agent] Error transforming " + typeName + ": " + throwable.getMessage());
                        TransformedClasses.getInstance().recordError(typeName, classLoader, throwable);
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

        // 安装Agent（保留 transformer 引用以便后续 reset + 重装）
        INSTALLED_TRANSFORMER = agentBuilder.installOn(inst);

        LogWriter.getInstance().info("[IAST Agent] Agent installed successfully, monitoring " + monitoredClasses.size() + " classes");
    }

    /**
     * attach start 的总入口。重载配置 + 卸旧 transformer 重装 + 补 retransform 漏网类。
     * 每一步独立 try/catch，best-effort 语义：任何一环失败都只 warn 不中断，不能因为 reload 失败反而把已工作的 agent 搞坏。
     */
    private static void reloadAndRestart(Instrumentation inst) {
        if (inst == null) {
            LogWriter.getInstance().warn("[IAST Agent] reloadAndRestart: instrumentation is null, skipped");
            return;
        }
        reloadConfigBestEffort();
        reinstallTransformerBestEffort(inst);
        retransformMissingClasses(inst);
    }

    /**
     * 重新解析配置文件 + 重新注册所有插件。
     * 复用首次 init 时保留的 {@link MonitorConfig#getConfigFilePath()}；注意 MonitorConfig 的 static 集合
     * 在 {@code init()} 里会 clear + 重填，等价于 in-place reload。
     * TODO: 现有 5 个内置插件都是纯内存，重入 init 没问题；若将来有插件持有 I/O 资源，这里需要先 close 老实例。
     */
    private static void reloadConfigBestEffort() {
        try {
            String path = MonitorConfig.getConfigFilePath();
            if (path == null || path.isEmpty()) {
                LogWriter.getInstance().warn("[IAST Agent] reload: configFilePath is empty, skipping config reload");
                return;
            }
            LogWriter.getInstance().info("[IAST Agent] reload: re-loading config from " + path);
            MonitorConfig.init("config=" + path);
            // 可能改了 instanceName，重 bind 一次
            com.iast.agent.plugin.event.EvalContext.bindInstanceName(MonitorConfig.getResolvedInstanceName());
            initPlugins();
            LogWriter.getInstance().info("[IAST Agent] reload: config + plugins re-initialized");
        } catch (Throwable t) {
            // MonitorConfig.loadFromYaml 异常会让 static 集合处于半清空状态（已知问题），但不在本次范围内修
            LogWriter.getInstance().error("[IAST Agent] reload: config reload failed (agent may be in partial state): " + t, t);
        }
    }

    /**
     * 卸掉老 transformer、按最新配置重新 buildAndInstall。
     * - INSTALL_SUCCEEDED=false（从未装成功，或 premain 延迟中还没跑）：直接 buildAndInstall，不需要 reset。
     * - INSTALL_SUCCEEDED=true：先 reset 撤销已注入的 advice（Byte Buddy 会 retransform 所有原记录类回到原字节码
     *   并从 JVM 摘下 transformer），再 buildAndInstall 重建。这样 reload 后新增的 rule 能生效、删掉的 rule 也能彻底下线。
     */
    private static void reinstallTransformerBestEffort(Instrumentation inst) {
        try {
            if (ADVICE_LOCATOR == null) {
                LogWriter.getInstance().warn("[IAST Agent] reinstall: ADVICE_LOCATOR is null (startAgent never completed), skipping");
                return;
            }
            if (INSTALL_SUCCEEDED && INSTALLED_TRANSFORMER != null) {
                int prevSize = TransformedClasses.getInstance().snapshotTransformed().size();
                LogWriter.getInstance().info("[IAST Agent] reinstall: resetting old transformer (~" + prevSize + " classes to revert)");
                try {
                    boolean resetOk = INSTALLED_TRANSFORMER.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                    if (!resetOk) {
                        // 兜底：直接调 Instrumentation.removeTransformer 摘掉老 transformer
                        boolean removed = inst.removeTransformer(INSTALLED_TRANSFORMER);
                        LogWriter.getInstance().warn("[IAST Agent] reinstall: reset returned false; fallback removeTransformer=" + removed);
                    }
                } catch (Throwable rt) {
                    LogWriter.getInstance().warn("[IAST Agent] reinstall: reset threw, continuing with re-install: " + rt);
                }
                INSTALLED_TRANSFORMER = null;
                INSTALL_SUCCEEDED = false;
            } else {
                LogWriter.getInstance().info("[IAST Agent] reinstall: no prior successful install to reset (INSTALL_SUCCEEDED=" + INSTALL_SUCCEEDED + ")");
            }
            buildAndInstall(inst, ADVICE_LOCATOR);
        } catch (Throwable t) {
            LogWriter.getInstance().error("[IAST Agent] reinstall: failed: " + t, t);
        }
    }

    /**
     * safety net：遍历当前所有已加载类，对"规则覆盖但未出现在 TransformedClasses 里"的类调 retransformClasses。
     * 正常情况下 {@link #reinstallTransformerBestEffort} 里新 transformer 的 RETRANSFORMATION 策略已经覆盖了；
     * 这里只在 install 过程中个别类被 onError 吞掉时起作用。
     */
    private static void retransformMissingClasses(Instrumentation inst) {
        try {
            java.util.Set<String> alreadyTransformed = TransformedClasses.getInstance().snapshotTransformed().keySet();
            List<String> monitoredInternal = MonitorConfig.getMonitoredClasses();
            if (monitoredInternal.isEmpty()) {
                return;
            }
            // exact 规则的目标 FQCN 集合
            java.util.Set<String> exactTargets = new HashSet<>();
            // interface 规则的目标 FQCN 集合——对每个 loaded class 要走 supertype 判断
            java.util.Set<String> interfaceTargets = new HashSet<>();
            for (String internal : monitoredInternal) {
                String fqcn = internal.replace('/', '.');
                if ("interface".equals(MonitorConfig.getMatchType(internal))) {
                    interfaceTargets.add(fqcn);
                } else {
                    exactTargets.add(fqcn);
                }
            }

            List<Class<?>> candidates = new ArrayList<>();
            Class<?>[] all = inst.getAllLoadedClasses();
            for (Class<?> c : all) {
                if (c == null) continue;
                String name = c.getName();
                if (alreadyTransformed.contains(name)) continue;
                if (c.isInterface()) continue;  // 接口本身不 hook
                if (!inst.isModifiableClass(c)) continue;
                boolean match = exactTargets.contains(name);
                if (!match && !interfaceTargets.isEmpty()) {
                    // 走 hasSuperType 语义：遍历 supertype 名称链
                    if (hasSupertypeInSet(c, interfaceTargets)) {
                        match = true;
                    }
                }
                if (match) {
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] retransform missing: none found");
                return;
            }
            LogWriter.getInstance().info("[IAST Agent] retransform missing: " + candidates.size() + " candidate class(es)");
            // 分批 retransform，单 batch 失败不影响其他 batch
            final int BATCH = 100;
            int okBatches = 0, failBatches = 0;
            for (int i = 0; i < candidates.size(); i += BATCH) {
                int end = Math.min(i + BATCH, candidates.size());
                Class<?>[] batch = candidates.subList(i, end).toArray(new Class<?>[0]);
                try {
                    inst.retransformClasses(batch);
                    okBatches++;
                } catch (Throwable t) {
                    failBatches++;
                    LogWriter.getInstance().warn("[IAST Agent] retransform batch [" + i + "," + end + ") failed: " + t);
                }
            }
            LogWriter.getInstance().info("[IAST Agent] retransform missing done: ok=" + okBatches + " fail=" + failBatches);
        } catch (Throwable t) {
            LogWriter.getInstance().error("[IAST Agent] retransform missing: unexpected failure: " + t, t);
        }
    }

    /**
     * 判断 c 的 supertype 链（superclass + interfaces，递归）里是否有 FQCN 在 set 里。
     * 不走 Class.forName / isAssignableFrom 避免 classloader 跨层加载——按 name 字符串匹配足够。
     */
    private static boolean hasSupertypeInSet(Class<?> c, java.util.Set<String> targetNames) {
        Class<?> sc = c.getSuperclass();
        while (sc != null) {
            if (targetNames.contains(sc.getName())) return true;
            sc = sc.getSuperclass();
        }
        // 广度优先遍历所有直接 + 间接接口
        java.util.Deque<Class<?>> stack = new java.util.ArrayDeque<>();
        for (Class<?> i : c.getInterfaces()) stack.push(i);
        Class<?> scIter = c.getSuperclass();
        while (scIter != null) {
            for (Class<?> i : scIter.getInterfaces()) stack.push(i);
            scIter = scIter.getSuperclass();
        }
        java.util.Set<Class<?>> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            Class<?> iface = stack.pop();
            if (!visited.add(iface)) continue;
            if (targetNames.contains(iface.getName())) return true;
            for (Class<?> parent : iface.getInterfaces()) stack.push(parent);
        }
        return false;
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
        registerPlugin(pm, "HttpForwardPlugin", new com.iast.agent.plugin.HttpForwardPlugin(), allCfgs);

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
            // 把 filtersDir 加载来的 FilterConfig 列表透给所有插件（占位字段，目前只有
            // CustomEventPlugin 会消费；其它插件忽略）。每条 filter 自带 target=ruleId，
            // 由 CustomEventPlugin.init 在自己 EventDef 里查 def.id == target 来挂载。
            cfg.put("filters", MonitorConfig.getFilterDefs());
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
     * 方法监控 Advice。
     *
     * <p>{@code onEnter} 直接把 {@link com.iast.agent.plugin.MethodContext} 当返回值交给
     * {@code @Advice.Enter} —— Byte Buddy 把它编成被 hook 方法的局部变量，天然随栈帧独立。
     * 嵌套调用（典型如 Servlet.service 调用子类 service）各层各自持有自己的 context，不会被
     * 内层覆盖；修复了旧版单槽 ThreadLocal 下 {@code RequestIdHolder} depth 泄漏的问题。
     */
    public static class MethodMonitorAdvice {
        @Advice.OnMethodEnter
        public static com.iast.agent.plugin.MethodContext onEnter(
                @Advice.Origin("#t.#m#s") String fullMethodName,
                @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
                @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            // 全局开关关闭时返回 null，onExit 识别 null 直接跳过
            if (!MONITOR_ENABLED) {
                return null;
            }
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(globalCallCount.incrementAndGet());
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.ENTER);
            context.setTarget(self);
            context.setArgs(args);
            context.setEnterTime(System.currentTimeMillis());
            context.setThreadId(Thread.currentThread().getId());
            context.setThreadName(Thread.currentThread().getName());

            // 解析类名和方法名
            // fullMethodName 格式：com.example.MyClass.method(arg.Type1,arg.Type2)RetType
            // 参数类型中可能含 "."，因此要在 "(" 之前查找最后一个 "."
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

            context.setStackTrace(Thread.currentThread().getStackTrace());

            // 调用插件处理（支持同一方法挂多个插件）
            String internalClassName = context.getClassName().replace('.', '/');
            MonitorConfig.dispatchToPlugins(internalClassName, context);

            return context;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter com.iast.agent.plugin.MethodContext context,
                                  @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable throwable) {
            // context 为 null 说明 onEnter 被 MONITOR_ENABLED=false 短路了，直接跳过
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

            String internalClassName = context.getClassName() != null
                    ? context.getClassName().replace('.', '/')
                    : "";
            MonitorConfig.dispatchToPlugins(internalClassName, context);
        }
    }

    /**
     * 构造函数监控 Advice。
     * 与 {@link MethodMonitorAdvice} 的区别：onEnter 不能访问 this（对象尚未构造），onExit 无返回值。
     * 同样通过 {@code @Advice.Enter} 把 context 传给 onExit（参见 MethodMonitorAdvice 的说明）。
     */
    public static class ConstructorMonitorAdvice {
        @Advice.OnMethodEnter
        public static com.iast.agent.plugin.MethodContext onEnter(
                @Advice.Origin("#t.<init>#s") String fullMethodName,
                @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            if (!MONITOR_ENABLED) {
                return null;
            }
            com.iast.agent.plugin.MethodContext context = new com.iast.agent.plugin.MethodContext();
            context.setCallId(globalCallCount.incrementAndGet());
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.ENTER);
            context.setTarget(null);  // 构造函数中 this 不可用
            context.setArgs(args);
            context.setEnterTime(System.currentTimeMillis());
            context.setThreadId(Thread.currentThread().getId());
            context.setThreadName(Thread.currentThread().getName());

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

            context.setStackTrace(Thread.currentThread().getStackTrace());

            String internalClassName = context.getClassName().replace('.', '/');
            MonitorConfig.dispatchToPlugins(internalClassName, context);

            return context;
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Enter com.iast.agent.plugin.MethodContext context,
                @Advice.This(typing = Assigner.Typing.DYNAMIC) Object self) {
            if (context == null) {
                return;
            }

            context.setExitTime(System.currentTimeMillis());
            context.setDuration(context.getExitTime() - context.getEnterTime());
            context.setPhase(com.iast.agent.plugin.MethodContext.CallPhase.EXIT);
            context.setTarget(self);  // 构造函数完成后可以访问 this

            String internalClassName = context.getClassName() != null
                    ? context.getClassName().replace('.', '/')
                    : "";
            MonitorConfig.dispatchToPlugins(internalClassName, context);
        }
    }
}