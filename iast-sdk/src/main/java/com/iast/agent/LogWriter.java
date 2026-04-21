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

    private static volatile LogWriter instance;
    private BufferedWriter writer;
    private String logPath;
    private static volatile LogLevel currentLevel = LogLevel.INFO;

    private LogWriter() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        this.logPath = "/tmp/iast-agent-" + pid + ".log";
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

    /** 设置自定义日志路径，必须在 {@link #init} 之前调用。 */
    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    /** 初始化日志文件流，注册 JVM 退出钩子自动关闭。 */
    public void init() {
        try {
            FileOutputStream fos = new FileOutputStream(logPath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer = new BufferedWriter(osw);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (writer != null) {
                        writer.flush();
                        writer.close();
                    }
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }));
        } catch (Exception e) {
            // 初始化失败静默处理，不影响源进程
        }
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
                writer.write(msg);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            // 写入失败静默处理，不影响源进程
        }
    }
}
