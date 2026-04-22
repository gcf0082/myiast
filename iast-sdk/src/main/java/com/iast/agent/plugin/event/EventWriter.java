package com.iast.agent.plugin.event;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * 事件日志写入工具（JSONL格式）
 * 与LogWriter分离，每行一个JSON对象，便于下游jq/Filebeat解析
 * 线程安全，零第三方依赖，支持按大小滚动
 */
public class EventWriter {
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(java.time.ZoneOffset.UTC);

    private static volatile EventWriter instance;
    private BufferedWriter writer;
    private String eventsPath;
    private boolean shutdownHookRegistered;

    // 滚动配置：maxFileSizeMb ≤ 0 禁用；maxFiles 含活跃文件
    private int maxFiles = 5;
    private long maxFileBytes = 20L * 1024 * 1024;
    private long currentFileBytes = 0;

    private EventWriter() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        // 默认布局：/tmp/iast_<pid>/iast.jsonl（前缀 iast_ 避免在 /tmp 下出现纯数字目录）。
        // yaml 里配 outputDir / instanceName 会通过 setEventsPath 切走。
        this.eventsPath = "/tmp/iast_" + pid + "/iast.jsonl";
    }

    public static EventWriter getInstance() {
        if (instance == null) {
            synchronized (EventWriter.class) {
                if (instance == null) {
                    instance = new EventWriter();
                }
            }
        }
        return instance;
    }

    /**
     * 设置事件文件路径。init 之前调 → 之后 init 走新路径；之后调 → 立刻 reopen
     * （关旧 writer + 开新 writer），便于运行期切换（如 yaml 配 outputDir 后 MonitorConfig
     * 调过来切目录）。空 / 未变化 → no-op。
     */
    public synchronized void setEventsPath(String newPath) {
        if (newPath == null || newPath.isEmpty() || newPath.equals(this.eventsPath)) return;
        if (writer == null) {
            // 未 init 过 → 只记新路径，留给后面的 init() 用
            this.eventsPath = newPath;
            return;
        }
        String old = this.eventsPath;
        closeWriter();
        // 旧文件 0 字节就清掉，避免在 /tmp 留空文件（典型场景：eager init 后 setEventsPath
        // 切到 outputDir 路径，旧 default 路径下的 events 文件还没写过任何事件）
        try {
            java.io.File of = new java.io.File(old);
            if (of.exists() && of.length() == 0L) of.delete();
        } catch (Throwable ignore) {}
        this.eventsPath = newPath;
        openWriter();
    }

    public String getEventsPath() {
        return eventsPath;
    }

    /** 设置滚动参数。≤0 的值保留默认。可在 init 前后调用。 */
    public synchronized void configure(int maxFiles, int maxFileSizeMb) {
        if (maxFiles > 0) this.maxFiles = maxFiles;
        if (maxFileSizeMb > 0) this.maxFileBytes = (long) maxFileSizeMb * 1024 * 1024;
        else if (maxFileSizeMb == 0) this.maxFileBytes = 0; // 0 = 禁用滚动
    }

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

    private void openWriter() {
        try {
            java.io.File f = new java.io.File(eventsPath);
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            currentFileBytes = f.exists() ? f.length() : 0;
            FileOutputStream fos = new FileOutputStream(eventsPath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer = new BufferedWriter(osw);
        } catch (Exception e) {
            writer = null;
        }
    }

    /** 滚动当前文件：close → rename（加时间戳）→ 清理旧文件 → 打开新文件。须在锁内调用。 */
    private void rollFile() {
        closeWriter();
        java.io.File cur = new java.io.File(eventsPath);
        if (cur.exists() && cur.length() > 0) {
            String ts = TS_FMT.format(java.time.Instant.now());
            String name = cur.getName();                    // "iast.jsonl"
            int dot = name.lastIndexOf('.');
            String rolled = (dot < 0)
                    ? name + "_" + ts
                    : name.substring(0, dot) + "_" + ts + name.substring(dot); // "iast_20260422143005.jsonl"
            cur.renameTo(new java.io.File(cur.getParent(), rolled));
        }
        pruneOldFiles();
        openWriter();
    }

    private void pruneOldFiles() {
        java.io.File dir = new java.io.File(eventsPath).getParentFile();
        if (dir == null || !dir.isDirectory()) return;
        String name = new java.io.File(eventsPath).getName();   // "iast.jsonl"
        int dot = name.lastIndexOf('.');
        final String prefix = (dot < 0 ? name : name.substring(0, dot)) + "_"; // "iast_"
        final String suffix = (dot < 0 ? "" : name.substring(dot));             // ".jsonl"
        java.io.File[] old = dir.listFiles((d, n) ->
                n.startsWith(prefix) && n.endsWith(suffix) && n.length() > prefix.length() + suffix.length());
        if (old == null) return;
        int maxRotated = Math.max(0, maxFiles - 1);  // 活跃文件占 1 个名额
        if (old.length <= maxRotated) return;
        java.util.Arrays.sort(old, java.util.Comparator.comparing(java.io.File::getName));
        for (int i = 0; i < old.length - maxRotated; i++) old[i].delete();
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignore) {
        }
        writer = null;
    }

    public void writeEvent(String jsonLine) {
        if (writer == null) return;
        try {
            synchronized (this) {
                if (maxFileBytes > 0) {
                    // +1 粗估换行符；ASCII-dominant JSONL 误差极小
                    if (currentFileBytes + jsonLine.length() + 1 > maxFileBytes) {
                        rollFile();
                    }
                }
                if (writer == null) return;  // openWriter 失败时安全降级
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
                currentFileBytes += jsonLine.length() + 1;
            }
        } catch (IOException e) {
            // 写入失败静默处理
        }
    }
}
