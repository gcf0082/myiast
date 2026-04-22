package com.iast.agent;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * 纯原生文件日志写入工具类。
 *
 * <p>避免使用任何被监控的 IO 类，防止 Agent 自身的写入触发 IAST 拦截造成无限递归。
 * 完全无第三方依赖，线程安全。
 *
 * <h3>日志级别</h3>
 * 支持 DEBUG / INFO / WARN / ERROR 四档。每行输出按 {@code [LEVEL] msg} 形式打头，
 * 当前级别低于阈值的调用直接 return。可由 YAML {@code output.logLevel}、CLI {@code loglevel}
 * 命令、或 SDK 用户的 {@link #setLevel} 调用修改。默认 INFO。
 */
public class LogWriter {

    /** 日志级别由低到高。weight 数值越小输出越多。 */
    public enum LogLevel {
        DEBUG(10),
        INFO(20),
        WARN(30),
        ERROR(40);

        final int weight;
        LogLevel(int weight) { this.weight = weight; }
    }

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(java.time.ZoneOffset.UTC);

    private static volatile LogWriter instance;
    private BufferedWriter writer;
    private String logPath;
    private boolean shutdownHookRegistered;
    private static volatile LogLevel currentLevel = LogLevel.INFO;

    // 滚动配置：maxFileSizeMb ≤ 0 禁用；maxFiles 含活跃文件
    private int maxFiles = 5;
    private long maxFileBytes = 20L * 1024 * 1024;
    private long currentFileBytes = 0;

    private LogWriter() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        // 默认布局：/tmp/iast_<pid>/iast.log（前缀 iast_ 避免在 /tmp 下出现纯数字目录）。
        // yaml 里配 outputDir / instanceName 会通过 setLogPath 切走。
        this.logPath = "/tmp/iast_" + pid + "/iast.log";
    }

    public static LogWriter getInstance() {
        if (instance == null) {
            synchronized (LogWriter.class) {
                if (instance == null) {
                    instance = new LogWriter();
                }
            }
        }
        return instance;
    }

    /**
     * 设置自定义日志路径。{@link #init} 之前调 → 之后 init 走新路径；之后调 → 立刻 reopen
     * （关旧 writer + 开新 writer），便于运行期切换（如 yaml 配 outputDir 后 MonitorConfig
     * 调过来切目录）。空 / 未变化 → no-op。
     */
    public synchronized void setLogPath(String newLogPath) {
        if (newLogPath == null || newLogPath.isEmpty() || newLogPath.equals(this.logPath)) return;
        if (writer == null) {
            // 未 init 过 → 仅记下新路径，留给后面的 init() 用
            this.logPath = newLogPath;
            return;
        }
        // 已 init → reopen
        String old = this.logPath;
        closeWriter();
        // 旧文件没写过任何字节就清掉，避免在 /tmp 留一个 0 字节空文件
        try {
            java.io.File of = new java.io.File(old);
            if (of.exists() && of.length() == 0L) of.delete();
        } catch (Throwable ignore) {}
        this.logPath = newLogPath;
        openWriter();
        info("[IAST Agent] log path changed: " + old + " -> " + newLogPath);
    }

    /** 初始化日志文件流，注册 JVM 退出钩子自动关闭。幂等：多次调用只开一次。 */
    public synchronized void init() {
        if (writer != null) return;
        openWriter();
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (this) {
                    closeWriter();
                }
            }));
            shutdownHookRegistered = true;
        }
    }

    /** 滚动当前文件：close → rename（加时间戳）→ 清理旧文件 → 打开新文件。须在锁内调用。 */
    private void rollFile() {
        closeWriter();
        java.io.File cur = new java.io.File(logPath);
        if (cur.exists() && cur.length() > 0) {
            String ts = TS_FMT.format(java.time.Instant.now());
            String name = cur.getName();
            int dot = name.lastIndexOf('.');
            String rolled = (dot < 0)
                    ? name + "_" + ts
                    : name.substring(0, dot) + "_" + ts + name.substring(dot);
            cur.renameTo(new java.io.File(cur.getParent(), rolled));
        }
        pruneOldFiles();
        openWriter();
        if (writer != null) {
            write("[INFO] [IAST] log rolled at " + TS_FMT.format(java.time.Instant.now()));
        }
    }

    private void pruneOldFiles() {
        java.io.File dir = new java.io.File(logPath).getParentFile();
        if (dir == null || !dir.isDirectory()) return;
        String name = new java.io.File(logPath).getName();
        int dot = name.lastIndexOf('.');
        final String prefix = (dot < 0 ? name : name.substring(0, dot)) + "_";
        final String suffix = (dot < 0 ? "" : name.substring(dot));
        java.io.File[] old = dir.listFiles((d, n) ->
                n.startsWith(prefix) && n.endsWith(suffix) && n.length() > prefix.length() + suffix.length());
        if (old == null) return;
        int maxRotated = Math.max(0, maxFiles - 1);
        if (old.length <= maxRotated) return;
        java.util.Arrays.sort(old, java.util.Comparator.comparing(java.io.File::getName));
        for (int i = 0; i < old.length - maxRotated; i++) old[i].delete();
    }

    private void openWriter() {
        try {
            java.io.File f = new java.io.File(logPath);
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            currentFileBytes = f.exists() ? f.length() : 0;
            FileOutputStream fos = new FileOutputStream(logPath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer = new BufferedWriter(osw);
        } catch (Exception e) {
            // 打开失败静默——agent 不能因日志写不了就废业务
            writer = null;
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignore) {
            // 关闭异常忽略
        }
        writer = null;
    }

    /** 设置滚动参数。≤0 的值保留默认。可在 init 前后调用。 */
    public synchronized void configure(int maxFiles, int maxFileSizeMb) {
        if (maxFiles > 0) this.maxFiles = maxFiles;
        if (maxFileSizeMb > 0) this.maxFileBytes = (long) maxFileSizeMb * 1024 * 1024;
        else if (maxFileSizeMb == 0) this.maxFileBytes = 0; // 0 = 禁用滚动
    }

    // ============= 级别控制 =============

    /** 当前级别（getter，主要给 CLI 的 loglevel 命令显示）。 */
    public static LogLevel getCurrentLevel() {
        return currentLevel;
    }

    /** 当前级别名（DEBUG/INFO/WARN/ERROR）。 */
    public static String getCurrentLevelName() {
        return currentLevel.name();
    }

    /** 设置级别。低于此级别的日志会被丢弃。 */
    public void setLevel(LogLevel level) {
        if (level == null) return;
        LogLevel prev = currentLevel;
        currentLevel = level;
        // 用 info() 而不是直接 write，方便不输出（如果新级别 > INFO 就不打印切换日志）
        info("[IAST Agent] Log level changed: " + prev.name() + " -> " + level.name());
    }

    /** 按字符串名设置级别（debug/info/warn/error，大小写不敏感）。无效值打 WARN，不抛异常。 */
    public void setLevel(String name) {
        if (name == null) return;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return;
        try {
            setLevel(LogLevel.valueOf(trimmed.toUpperCase()));
        } catch (IllegalArgumentException e) {
            // 用 warn 不用 error，因为这是用户输入错误，不是系统错
            warn("[IAST Agent] Unknown log level: '" + name + "' (expected debug/info/warn/error)");
        }
    }

    public boolean isDebugEnabled() { return currentLevel.weight <= LogLevel.DEBUG.weight; }
    public boolean isInfoEnabled()  { return currentLevel.weight <= LogLevel.INFO.weight; }
    public boolean isWarnEnabled()  { return currentLevel.weight <= LogLevel.WARN.weight; }
    public boolean isErrorEnabled() { return currentLevel.weight <= LogLevel.ERROR.weight; }

    // ============= 写日志 =============

    /** DEBUG 级别。生产默认不输出；排查问题时通过 yaml 或 CLI loglevel 命令打开。 */
    public void debug(String msg) {
        if (currentLevel.weight > LogLevel.DEBUG.weight) return;
        write("[DEBUG] " + msg);
    }

    /** INFO 级别（默认）。 */
    public void info(String msg) {
        if (currentLevel.weight > LogLevel.INFO.weight) return;
        write("[INFO] " + msg);
    }

    /** WARN 级别：插件 / Agent 跑成功了但行为异常，应提醒人看一眼但不影响业务。 */
    public void warn(String msg) {
        if (currentLevel.weight > LogLevel.WARN.weight) return;
        write("[WARN] " + msg);
    }

    /** ERROR 级别：插件 / Agent 出了真错，需要上线人介入。 */
    public void error(String msg) {
        if (currentLevel.weight > LogLevel.ERROR.weight) return;
        write("[ERROR] " + msg);
    }

    /** ERROR + 异常：把 throwable 的类名 / message / 栈追加到一行 log（多行）。 */
    public void error(String msg, Throwable t) {
        if (currentLevel.weight > LogLevel.ERROR.weight) return;
        if (t == null) {
            write("[ERROR] " + msg);
            return;
        }
        StringWriter sw = new StringWriter(256);
        sw.append("[ERROR] ").append(msg).append('\n');
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        write(sw.toString());
    }

    private void write(String msg) {
        if (writer == null) return;
        try {
            synchronized (this) {
                if (maxFileBytes > 0) {
                    if (currentFileBytes + msg.length() + 1 > maxFileBytes) {
                        rollFile();
                    }
                }
                if (writer == null) return;  // openWriter 失败时安全降级
                writer.write(msg);
                writer.newLine();
                writer.flush();
                currentFileBytes += msg.length() + 1;
            }
        } catch (IOException e) {
            // 写入失败静默处理，不影响源进程
        }
    }
}
