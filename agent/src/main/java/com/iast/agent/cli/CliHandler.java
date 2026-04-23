package com.iast.agent.cli;

import com.iast.agent.IastAgent;
import com.iast.agent.MonitorConfig;
import com.iast.agent.plugin.PluginManager;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CLI 命令派发：纯文本进 / 纯文本出，不接触网络。{@link CliServer} 读一帧 → 调
 * {@link #execute(String)} → 把结果串写回一帧。
 *
 * <p>所有命令都有一个关键不变量：不 throws；内部异常捕获成错误字符串。这样 WS server 只需
 * 关心传输层故障，不会被上层命令 bug 搞断。
 */
final class CliHandler {

    /** 单次响应最多行数（classes 命令命中过多时截断，防止把 64K 单帧挤爆）。 */
    private static final int MAX_RESPONSE_LINES = 500;

    private CliHandler() {}

    /**
     * 执行一条命令。返回要回显给客户端的整段文本（可能多行）；若返回 "__QUIT__" 则表示
     * server 应该关连接。{@code sc} 由 CliServer 注入，仅 {@code monitor} 命令用到（拿
     * 当前会话的 SessionWriter 推帧）。
     */
    static String execute(String line, com.iast.agent.cli.MonitorRegistry.SessionContext sc) {
        if (line == null) return "";
        String cmd = line.trim();
        if (cmd.isEmpty()) return "";
        String[] parts = cmd.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (verb) {
                case "help":    return help();
                case "status":  return status();
                case "plugins": return plugins();
                case "rules":   return rules(arg);
                case "classes": return classes(arg);
                case "transformed": return transformed(arg);
                case "methods": return methods(arg);
                case "monitor": return monitor(arg, sc);
                case "enable":  return toggleMonitor(true);
                case "disable": return toggleMonitor(false);
                case "loglevel": return loglevel(arg);
                case "quit":
                case "exit":    return "__QUIT__";
                default:        return "unknown command: " + verb + " (try 'help')";
            }
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String help() {
        return String.join("\n",
                "IAST Agent CLI commands:",
                "  help                 show this help",
                "  status               agent runtime status",
                "  plugins              list registered plugins",
                "  rules [class]        list all monitored classes, or detail for one",
                "  classes <pattern>    grep loaded classes. use 're:<regex>' for regex",
                "  transformed [<pat>]  list classes Byte Buddy has woven advice into (use 're:<regex>')",
                "  methods <class> [name] [all]  print JVM descriptors of a class's methods (YAML-ready)",
                "  monitor <fqcn> <method>[#<desc>]  ad-hoc per-call trace, independent of YAML rules",
                "                       (recommended via: iast-cli-jattach.sh <pid> --command \"monitor X Y\")",
                "  enable               turn monitor ON (MONITOR_ENABLED=true)",
                "  disable              turn monitor OFF (MONITOR_ENABLED=false)",
                "  loglevel [<level>]   show or set log level (debug/info/warn/error)",
                "  quit / exit          close this session");
    }

    /**
     * {@code monitor <fqcn> <methodName>[#<descriptor>]} —— 给指定方法装一段 stats-only advice，
     * 每次拦截推一行到本会话 stdout。一次会话最多 1 个 monitor；CLI 断开自动撤销。
     *
     * <p>命令本身只是 ack（"installed" 或 "ERROR ..."）；后续命中的真正流式输出由
     * {@link MonitorRegistry#report} 直接调本会话的 {@link SessionWriter} 推帧。
     */
    private static String monitor(String arg, com.iast.agent.cli.MonitorRegistry.SessionContext sc) {
        if (sc == null) {
            return "ERROR: monitor requires an active session";
        }
        if (arg.isEmpty()) {
            return "usage: monitor <fqcn> <methodName>[#<descriptor>]\n"
                    + "  e.g. monitor com.foo.Bar doSomething\n"
                    + "       monitor com.foo.Bar doSomething#(Ljava/lang/String;)V";
        }
        String[] tokens = arg.split("\\s+", 2);
        if (tokens.length < 2) {
            return "usage: monitor <fqcn> <methodName>[#<descriptor>]";
        }
        String fqcn = tokens[0].replace('/', '.');
        String methodSpec = tokens[1].trim();
        String methodName;
        String descriptor = null;
        int hash = methodSpec.indexOf('#');
        if (hash < 0) {
            methodName = methodSpec;
        } else {
            methodName = methodSpec.substring(0, hash);
            descriptor = methodSpec.substring(hash + 1);
            if (descriptor.isEmpty()) descriptor = null;
        }
        if (methodName.isEmpty()) {
            return "ERROR: empty method name";
        }
        if ("<init>".equals(methodName)) {
            return "ERROR: constructor monitoring (<init>) not supported in v1";
        }
        return MonitorRegistry.installMonitor(sc, fqcn, methodName, descriptor);
    }

    /**
     * 翻 MONITOR_ENABLED 并打日志，回显里也带前后状态——比单条 "OK: monitor enabled" 更便于
     * 用户/脚本判断"是不是真切换了"（已经是该状态时显示 already 而非误以为切换成功）。
     */
    private static String toggleMonitor(boolean target) {
        boolean prev = IastAgent.MONITOR_ENABLED;
        IastAgent.MONITOR_ENABLED = target;
        com.iast.agent.LogWriter.getInstance().info("[IAST CLI] MONITOR_ENABLED " + prev + " -> " + target + " (via cli)");
        if (prev == target) {
            return "OK: monitor already " + (target ? "enabled" : "disabled") + " (no change)";
        }
        return "OK: monitor " + (target ? "enabled" : "disabled") + " (was " + prev + ")";
    }

    private static String loglevel(String arg) {
        com.iast.agent.LogWriter lw = com.iast.agent.LogWriter.getInstance();
        if (arg.isEmpty()) {
            return "current loglevel: " + com.iast.agent.LogWriter.getCurrentLevelName().toLowerCase()
                    + "  (use: loglevel <debug|info|warn|error>)";
        }
        String prev = com.iast.agent.LogWriter.getCurrentLevelName();
        lw.setLevel(arg);
        String now = com.iast.agent.LogWriter.getCurrentLevelName();
        if (prev.equals(now)) {
            // setLevel 对未知名静默 warn 但不变；告诉用户没换
            return "no change: still " + now.toLowerCase() + " (unknown level '" + arg + "'?)";
        }
        return "OK: loglevel " + prev.toLowerCase() + " -> " + now.toLowerCase();
    }

    private static String status() {
        long upSec = (System.currentTimeMillis() - IastAgent.START_TIME) / 1000L;
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int atIdx = pid.indexOf('@');
        if (atIdx > 0) pid = pid.substring(0, atIdx);
        StringBuilder sb = new StringBuilder();
        sb.append("pid:            ").append(pid).append('\n');
        sb.append("monitorEnabled: ").append(IastAgent.MONITOR_ENABLED).append('\n');
        sb.append("callCount:      ").append(IastAgent.globalCallCount.get()).append('\n');
        sb.append("configPath:     ").append(MonitorConfig.getConfigFilePath()).append('\n');
        sb.append("uptimeSec:      ").append(upSec).append('\n');
        sb.append("logLevel:       ").append(com.iast.agent.LogWriter.getCurrentLevelName().toLowerCase()).append('\n');
        sb.append("javaVersion:    ").append(System.getProperty("java.version"));
        return sb.toString();
    }

    private static String plugins() {
        Map<String, String> all = PluginManager.getInstance().listPluginClasses();
        if (all.isEmpty()) return "(no plugins registered)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            sb.append(pad(e.getKey(), 24)).append(e.getValue()).append('\n');
        }
        stripTrailingNewline(sb);
        return sb.toString();
    }

    private static String rules(String arg) {
        List<String> monitored = MonitorConfig.getMonitoredClasses();
        if (arg.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(pad("ClassName", 56))
              .append(pad("matchType", 12))
              .append(pad("wrapBody", 10))
              .append(pad("ids", 30))
              .append("methods").append('\n');
            for (String internal : monitored) {
                String display = internal.replace('/', '.');
                String mt = MonitorConfig.getMatchType(internal);
                boolean wrap = MonitorConfig.isWrapServletRequest(internal);
                int n = MonitorConfig.getMethodRules(internal).size();
                List<String> ids = MonitorConfig.getRuleIds(internal);
                String idsCell = ids.isEmpty() ? "-" : ids.toString();
                sb.append(pad(display, 56))
                  .append(pad(mt, 12))
                  .append(pad(String.valueOf(wrap), 10))
                  .append(pad(idsCell, 30))
                  .append(n).append('\n');
            }
            sb.append("total: ").append(monitored.size());
            return sb.toString();
        }
        // 单类详情：用户既可写 FQ（java.io.File）也可写 internal（java/io/File）
        String internal = arg.replace('.', '/');
        List<MonitorConfig.MethodRule> methods = MonitorConfig.getMethodRules(internal);
        if (methods.isEmpty()) {
            return "no rule found for: " + arg;
        }
        List<String> ids = MonitorConfig.getRuleIds(internal);
        StringBuilder sb = new StringBuilder();
        sb.append("class:     ").append(internal.replace('/', '.')).append('\n');
        sb.append("ids:       ").append(ids.isEmpty() ? "-" : ids).append('\n');
        sb.append("matchType: ").append(MonitorConfig.getMatchType(internal)).append('\n');
        sb.append("wrapBody:  ").append(MonitorConfig.isWrapServletRequest(internal)).append('\n');
        sb.append("plugins:   ").append(MonitorConfig.getPluginNames(internal)).append('\n');
        sb.append("methods:\n");
        for (MonitorConfig.MethodRule r : methods) {
            sb.append("  ").append(r.getMethodName()).append(r.getDescriptor()).append('\n');
        }
        stripTrailingNewline(sb);
        return sb.toString();
    }

    private static String classes(String arg) {
        if (arg.isEmpty()) return "usage: classes <substring>  OR  classes re:<regex>";
        java.lang.instrument.Instrumentation inst = IastAgent.INSTRUMENTATION;
        if (inst == null) return "ERROR: Instrumentation not available (agent not initialized?)";

        Pattern re = null;
        String needle = null;
        if (arg.startsWith("re:")) {
            try {
                re = Pattern.compile(arg.substring(3));
            } catch (PatternSyntaxException e) {
                return "ERROR: invalid regex: " + e.getMessage();
            }
        } else {
            needle = arg.toLowerCase();
        }

        Class<?>[] all = inst.getAllLoadedClasses();
        int total = 0;
        StringBuilder sb = new StringBuilder();
        int printed = 0;
        for (Class<?> c : all) {
            String name = c.getName();
            boolean match = (re != null) ? re.matcher(name).find()
                    : name.toLowerCase().contains(needle);
            if (!match) continue;
            total++;
            if (printed < MAX_RESPONSE_LINES) {
                sb.append(name).append('\n');
                printed++;
            }
        }
        if (total == 0) return "no loaded class matches: " + arg;
        stripTrailingNewline(sb);
        if (total > printed) {
            sb.append("\n... (").append(total - printed).append(" more truncated; total ").append(total).append(")");
        } else {
            sb.append("\ntotal: ").append(total);
        }
        return sb.toString();
    }

    /**
     * 列出 Byte Buddy 已经织入 advice 的类。和 {@code rules}（配置）/ {@code classes}（已加载）
     * 是不同视角——本命令展示「配置真的被命中、advice 真的织进去」的中间状态。数据来自
     * {@link com.iast.agent.TransformedClasses}，由 {@link IastAgent} 的 AgentBuilder.Listener
     * 在每次 onTransformation 时记录。
     */
    private static String transformed(String arg) {
        Map<String, com.iast.agent.TransformedClasses.Entry> all =
                com.iast.agent.TransformedClasses.getInstance().snapshotTransformed();
        if (all.isEmpty()) {
            return "no class transformed yet (agent installed? rules loaded? interface rule too narrow?)";
        }

        // 解析 pattern：substring（lowercase contains）/ "re:<regex>" / 空（全部）
        String filterDesc = null;
        Pattern re = null;
        String needle = null;
        if (!arg.isEmpty()) {
            if (arg.startsWith("re:")) {
                try {
                    re = Pattern.compile(arg.substring(3));
                    filterDesc = "regex='" + arg.substring(3) + "'";
                } catch (PatternSyntaxException e) {
                    return "ERROR: invalid regex: " + e.getMessage();
                }
            } else {
                needle = arg.toLowerCase();
                filterDesc = "filter='" + arg + "'";
            }
        }

        // 表头 + 列宽（className 70、loader 38、count 8、ageSec 10）
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(pad("className", 70))
          .append(pad("loader", 38))
          .append(pad("count", 8))
          .append("ageSec").append('\n');

        int total = 0;
        int printed = 0;
        for (Map.Entry<String, com.iast.agent.TransformedClasses.Entry> e : all.entrySet()) {
            String name = e.getKey();
            boolean match = (re != null) ? re.matcher(name).find()
                    : (needle == null ? true : name.toLowerCase().contains(needle));
            if (!match) continue;
            total++;
            if (printed >= MAX_RESPONSE_LINES) continue;
            com.iast.agent.TransformedClasses.Entry entry = e.getValue();
            // loader 集合一般 1 个；多 CL 时逗号拼，避免行太宽用 28 左右截断
            String loader = String.join(",", entry.loaders);
            if (loader.length() > 36) loader = loader.substring(0, 33) + "...";
            long ageSec = (now - entry.firstAtMs) / 1000L;
            sb.append(pad(name, 70))
              .append(pad(loader, 38))
              .append(pad(String.valueOf(entry.count.get()), 8))
              .append(ageSec).append('\n');
            printed++;
        }

        if (total == 0) {
            return "no transformed class matches: " + arg;
        }

        if (total > printed) {
            sb.append("... (").append(total - printed).append(" more truncated; total ").append(total).append(")");
        } else {
            sb.append("total: ").append(total).append(" transformed classes");
            if (filterDesc != null) sb.append("  (").append(filterDesc).append(": ").append(printed).append(" shown)");
        }
        return sb.toString();
    }

    /**
     * 列出一个类声明的方法（含构造函数）及 JVM 描述符。输出对 YAML 可直接粘贴：
     * <pre>
     *   - "exists#()Z"                           # public boolean exists()
     * </pre>
     * 默认只列 declared；加尾缀 {@code all} 走 superclass 链（到 Object 前停），按
     * name+descriptor dedup；另可选一个 name 子串过滤。排查"YAML 该填啥 descriptor"的场景。
     */
    private static String methods(String arg) {
        if (arg.isEmpty()) {
            return "usage: methods <class> [name-filter] [all]";
        }
        java.lang.instrument.Instrumentation inst = IastAgent.INSTRUMENTATION;
        if (inst == null) {
            return "ERROR: Instrumentation not available (agent not initialized?)";
        }

        // 解析位置参数。只认字面 'all' 作为展开继承开关；其他 token 当 nameFilter
        String[] tokens = arg.split("\\s+");
        String className = tokens[0].replace('/', '.');
        String nameFilter = null;
        boolean includeInherited = false;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if ("all".equalsIgnoreCase(t)) {
                includeInherited = true;
            } else if (nameFilter == null) {
                nameFilter = t.toLowerCase();
            }
        }

        // 从已加载类里找同名的（可能跨多个 CL）
        List<Class<?>> targets = new ArrayList<>(1);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (className.equals(c.getName())) {
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            return "no loaded class matches: " + tokens[0];
        }

        StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < targets.size(); idx++) {
            if (idx > 0) out.append("\n");
            appendClassMethods(out, targets.get(idx), nameFilter, includeInherited);
        }
        return out.toString();
    }

    /** 收集一个 Class 的方法 / ctor，排序、过滤、渲染到 sb 上。 */
    private static void appendClassMethods(StringBuilder sb, Class<?> cls, String nameFilter, boolean includeInherited) {
        sb.append("class: ").append(cls.getName())
          .append("  (loader=").append(describeLoader(cls.getClassLoader())).append(")").append('\n');

        // 收集成 (entry.key = name+descriptor) → entry；保序方便后续排序
        LinkedHashMap<String, MethodEntry> entries = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        collectDeclared(cls, entries, seen, null);
        int declaredCount = entries.size();
        int inheritedCount = 0;
        if (includeInherited) {
            Class<?> p = cls.getSuperclass();
            while (p != null && p != Object.class) {
                int before = entries.size();
                collectDeclared(p, entries, seen, p.getSimpleName());
                inheritedCount += entries.size() - before;
                p = p.getSuperclass();
            }
        }

        // 过滤 + 排序：构造器优先，然后 name 升序，同名按 descriptor
        List<MethodEntry> list = new ArrayList<>(entries.values());
        if (nameFilter != null) {
            String f = nameFilter;
            list.removeIf(e -> !e.name.toLowerCase().contains(f));
        }
        list.sort(Comparator.<MethodEntry, Integer>comparing(e -> "<init>".equals(e.name) ? 0 : 1)
                .thenComparing(e -> e.name)
                .thenComparing(e -> e.descriptor));

        if (list.isEmpty()) {
            if (nameFilter != null) {
                sb.append("no methods matching '").append(nameFilter).append("' in ").append(cls.getName());
            } else {
                sb.append("no methods declared in ").append(cls.getName());
            }
            return;
        }

        // 按本块最大 `"name#desc"` 宽度对齐 `#` 注释
        int col = 0;
        int printable = Math.min(list.size(), MAX_RESPONSE_LINES);
        for (int i = 0; i < printable; i++) {
            int w = list.get(i).yamlLine().length();
            if (w > col) col = w;
        }
        col += 2;

        for (int i = 0; i < printable; i++) {
            MethodEntry e = list.get(i);
            String yaml = e.yamlLine();
            sb.append("  - ").append(yaml);
            for (int j = yaml.length(); j < col; j++) sb.append(' ');
            sb.append("# ").append(e.javaSig);
            if (e.inheritedFrom != null) {
                sb.append("   [inherited from ").append(e.inheritedFrom).append("]");
            }
            sb.append('\n');
        }

        if (list.size() > printable) {
            sb.append("... (").append(list.size() - printable).append(" more truncated; total ").append(list.size()).append(")");
            return;
        }

        // 尾行：统计 + 提示
        sb.append("total: ").append(list.size());
        if (nameFilter != null) {
            sb.append(" methods matching '").append(nameFilter).append("'");
            if (includeInherited && inheritedCount > 0) sb.append(" (").append(inheritedCount).append(" inherited)");
        } else if (includeInherited) {
            sb.append(" methods (").append(declaredCount).append(" declared, ").append(inheritedCount).append(" inherited)");
        } else {
            sb.append(" declared methods  (use 'methods <class> all' to include inherited)");
        }
    }

    /** 把 cls 上直接声明的 method + ctor 塞进 entries；inheritedFrom 非 null 表示这一轮是父类递归。 */
    private static void collectDeclared(Class<?> cls,
                                        LinkedHashMap<String, MethodEntry> entries,
                                        Set<String> seen,
                                        String inheritedFrom) {
        // 构造器（父类递归时跳过——子类实例化不会调父类的 <init>，列出来会误导）
        if (inheritedFrom == null) {
            try {
                for (Constructor<?> c : cls.getDeclaredConstructors()) {
                    if (c.isSynthetic()) continue;
                    String desc = methodDescriptor(void.class, c.getParameterTypes());
                    String key = "<init>#" + desc;
                    if (seen.add(key)) {
                        entries.put(key, new MethodEntry(
                                "<init>", desc,
                                formatJavaCtor(c),
                                null));
                    }
                }
            } catch (Throwable ignore) {}
        }

        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                String desc;
                try {
                    desc = methodDescriptor(m.getReturnType(), m.getParameterTypes());
                } catch (TypeNotPresentException tnpe) {
                    com.iast.agent.LogWriter.getInstance().warn(
                            "[IAST CLI] methods: skip " + cls.getName() + "." + m.getName()
                                    + " (TypeNotPresent: " + tnpe.typeName() + ")");
                    continue;
                }
                String key = m.getName() + "#" + desc;
                if (seen.add(key)) {
                    entries.put(key, new MethodEntry(
                            m.getName(), desc,
                            formatJavaMethod(m),
                            inheritedFrom));
                }
            }
        } catch (Throwable t) {
            com.iast.agent.LogWriter.getInstance().warn(
                    "[IAST CLI] methods: reflect failed on " + cls.getName() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** `(params)ret` JVM descriptor。 */
    private static String methodDescriptor(Class<?> ret, Class<?>[] params) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('(');
        for (Class<?> p : params) sb.append(typeDescriptor(p));
        sb.append(')').append(typeDescriptor(ret));
        return sb.toString();
    }

    /** JVM 单类型描述符。primitive→字母；array→`[L...;`（Class.getName 已是该形式，只换分隔符）；其他→`Lfq/name;`。 */
    private static String typeDescriptor(Class<?> t) {
        if (t.isPrimitive()) {
            if (t == void.class)    return "V";
            if (t == boolean.class) return "Z";
            if (t == byte.class)    return "B";
            if (t == char.class)    return "C";
            if (t == short.class)   return "S";
            if (t == int.class)     return "I";
            if (t == long.class)    return "J";
            if (t == float.class)   return "F";
            if (t == double.class)  return "D";
        }
        if (t.isArray()) {
            // getName() 已是 "[L...;" 或 "[I" 等形式，只需把引用元素里的 '.' 换成 '/'
            return t.getName().replace('.', '/');
        }
        return "L" + t.getName().replace('.', '/') + ";";
    }

    private static String formatJavaMethod(Method m) {
        StringBuilder sb = new StringBuilder();
        String mods = Modifier.toString(m.getModifiers() & Modifier.methodModifiers());
        if (!mods.isEmpty()) sb.append(mods).append(' ');
        sb.append(shortTypeName(m.getReturnType())).append(' ');
        sb.append(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(shortTypeName(params[i]));
        }
        sb.append(')');
        Class<?>[] thrown = m.getExceptionTypes();
        if (thrown.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < thrown.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(shortTypeName(thrown[i]));
            }
        }
        return sb.toString();
    }

    private static String formatJavaCtor(Constructor<?> c) {
        StringBuilder sb = new StringBuilder();
        String mods = Modifier.toString(c.getModifiers() & Modifier.constructorModifiers());
        if (!mods.isEmpty()) sb.append(mods).append(' ');
        sb.append(c.getDeclaringClass().getSimpleName()).append('(');
        Class<?>[] params = c.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(shortTypeName(params[i]));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String shortTypeName(Class<?> t) {
        if (t == null) return "?";
        // getSimpleName 对 String[][] 自动返回 "String[][]"，对匿名类返回 ""；兜底 getName
        String sn = t.getSimpleName();
        return sn.isEmpty() ? t.getName() : sn;
    }

    private static String describeLoader(ClassLoader cl) {
        if (cl == null) return "bootstrap";
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    /** 方法条目的渲染承载：name + descriptor + 人读 java 签名（+ 可选 inheritedFrom 标签）。 */
    private static final class MethodEntry {
        final String name;
        final String descriptor;
        final String javaSig;
        final String inheritedFrom;

        MethodEntry(String name, String descriptor, String javaSig, String inheritedFrom) {
            this.name = name;
            this.descriptor = descriptor;
            this.javaSig = javaSig;
            this.inheritedFrom = inheritedFrom;
        }

        /** YAML 一行的左半边：`"name#descriptor"`（带引号，可直接粘到 methods: 下）。 */
        String yamlLine() {
            return "\"" + name + "#" + descriptor + "\"";
        }
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static void stripTrailingNewline(StringBuilder sb) {
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
    }
}
