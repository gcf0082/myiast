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
 * 线程安全，零第三方依赖
 */
public class EventWriter {
    private static volatile EventWriter instance;
    private BufferedWriter writer;
    private String eventsPath;
    private boolean shutdownHookRegistered;

    private EventWriter() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        this.eventsPath = "/tmp/iast-events-" + pid + ".jsonl";
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
            FileOutputStream fos = new FileOutputStream(eventsPath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer = new BufferedWriter(osw);
        } catch (Exception e) {
            writer = null;
        }
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
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            // 写入失败静默处理
        }
    }
}
