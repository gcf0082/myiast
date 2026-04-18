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

    public void setEventsPath(String path) {
        this.eventsPath = path;
    }

    public String getEventsPath() {
        return eventsPath;
    }

    public void init() {
        if (writer != null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(eventsPath, true);
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

    public void writeEvent(String jsonLine) {
        if (writer == null) {
            return;
        }
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
