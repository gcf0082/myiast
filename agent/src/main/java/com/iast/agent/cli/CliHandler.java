package com.iast.agent.cli;

import com.iast.agent.IastAgent;
import com.iast.agent.MonitorConfig;
import com.iast.agent.plugin.PluginManager;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
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
     * server 应该关连接。
     */
    static String execute(String line) {
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
                "  enable               turn monitor ON (MONITOR_ENABLED=true)",
                "  disable              turn monitor OFF (MONITOR_ENABLED=false)",
                "  loglevel [<level>]   show or set log level (debug/info/warn/error)",
                "  quit / exit          close this session");
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
            sb.append(pad("ClassName", 60))
              .append(pad("matchType", 12))
              .append(pad("wrapBody", 10))
              .append("methods").append('\n');
            for (String internal : monitored) {
                String display = internal.replace('/', '.');
                String mt = MonitorConfig.getMatchType(internal);
                boolean wrap = MonitorConfig.isWrapServletRequest(internal);
                int n = MonitorConfig.getMethodRules(internal).size();
                sb.append(pad(display, 60))
                  .append(pad(mt, 12))
                  .append(pad(String.valueOf(wrap), 10))
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
        StringBuilder sb = new StringBuilder();
        sb.append("class:     ").append(internal.replace('/', '.')).append('\n');
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
