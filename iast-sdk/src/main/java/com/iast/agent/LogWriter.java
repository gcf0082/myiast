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
        // 默认日志文件——"早期阶段日志"落点：yaml 解析尚未完成前的所有 info/warn/error 都在这里。
        // MonitorConfig.init 成功之后 setLogPath 会把后续日志切到 outputDir/<instanceName>/iast.log，
        // 但本早期文件作为 boot 过程审计记录**始终保留**，不做自动清理。
        this.logPath = resolveDefaultLogPath();
    }

    /**
     * 默认日志路径 resolve，按优先级降级。
     * <ol>
     *   <li>agent jar 同目录下的 {@code logs/iast_bootstrap_<pid>.log}
     *       —— tarball 布局下 jar 与脚本同目录，这是运维最容易翻到的位置</li>
     *   <li>{@code <cwd>/logs/iast_bootstrap_<pid>.log}
     *       —— jar 从 bootstrap CL 加载、CodeSource 返回 null 时的兜底</li>
     *   <li>{@code /tmp/iast_bootstrap_<pid>.log}（flat，不建子目录）
     *       —— 前两个路径都无写权限时的最后兜底，即使 /tmp 上 mkdir 权限不够也能写</li>
     * </ol>
     */
    private static String resolveDefaultLogPath() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String fileName = "iast_bootstrap_" + pid + ".log";
        java.io.File jarDir = resolveJarDir();
        if (jarDir != null) {
            java.io.File logs = new java.io.File(jarDir, "logs");
            if (ensureDirWritable(logs)) {
                return new java.io.File(logs, fileName).getAbsolutePath();
            }
        }
        java.io.File cwdLogs = new java.io.File("logs");
        if (ensureDirWritable(cwdLogs)) {
            return new java.io.File(cwdLogs, fileName).getAbsolutePath();
        }
        return "/tmp/" + fileName;
    }

    /**
     * 查找本类所在 jar 的目录。iast-sdk shade 进 iast-agent.jar 的场景下会返回 iast-agent.jar
     * 的父目录；iast-cli / iast-sdk 独立运行时返回各自 jar 的父目录。
     *
     * <p>返回 null 的 case：CodeSource 为 null（少数 JDK 在 bootstrap CL 下的返回）、URI 解析失败。
     */
    private static java.io.File resolveJarDir() {
        // 优先：从本类自身的 resource URL 反推 jar 路径。attach 模式下
        // ProtectionDomain.getCodeSource() 常被 JDK 置为 null（jdk.attach 的 AgentLoader 实现不
        // 填 CodeSource location），但 ClassLoader.getResource 仍能拿到 "jar:file:...!/..." 形式 URL，
        // 比 CodeSource 稳得多。
        try {
            String classRes = LogWriter.class.getName().replace('.', '/') + ".class";
            ClassLoader cl = LogWriter.class.getClassLoader();
            java.net.URL url = (cl != null) ? cl.getResource(classRes) : ClassLoader.getSystemResource(classRes);
            if (url != null) {
                String s = url.toString();
                if (s.startsWith("jar:file:")) {
                    int bang = s.indexOf("!");
                    if (bang > 0) {
                        String p = java.net.URLDecoder.decode(s.substring("jar:file:".length(), bang),
                                java.nio.charset.StandardCharsets.UTF_8);
                        java.io.File f = new java.io.File(p);
                        if (f.isFile()) return f.getParentFile();
                    }
                } else if (s.startsWith("file:")) {
                    // exploded jar / IDE target/classes 场景
                    String p = java.net.URLDecoder.decode(s.substring("file:".length()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    // 剥掉 class 文件本身对应的包路径，得到 classes 根目录
                    int idx = p.lastIndexOf(classRes);
                    if (idx > 0) {
                        java.io.File root = new java.io.File(p.substring(0, idx));
                        if (root.isDirectory()) return root;
                    }
                }
            }
        } catch (Throwable ignore) {
            // null-tolerant
        }
        // 再兜底：CodeSource（少数环境 CodeSource 反而能拿到、resource 路径走不通）
        try {
            java.security.CodeSource cs = LogWriter.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.io.File f = new java.io.File(cs.getLocation().toURI());
                if (f.isFile()) return f.getParentFile();
                if (f.isDirectory()) return f;
            }
        } catch (Throwable ignore) {
            // null-tolerant
        }
        return null;
    }

    private static boolean ensureDirWritable(java.io.File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            return dir.isDirectory() && dir.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前生效的日志文件绝对路径。premain 启动时会 info 这个值，方便运维知道去哪看日志。 */
    public synchronized String getCurrentLogPath() {
        return logPath;
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
        // bootstrap 文件保留：即使干净启动也不删。设计上 bootstrap log 记录了 agent jar 定位、
        // classpath append、yaml 路径 resolve 等早期步骤的完整审计记录，和最终 iast.log 是
        // 两份不同的文件，运维升级 / 换机 / 回放 boot 过程时有用；不值得自动清理省一点磁盘。
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
