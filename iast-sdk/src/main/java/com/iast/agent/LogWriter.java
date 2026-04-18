package com.iast.agent;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * 纯原生文件日志写入工具类
 * 避免使用任何被监控的IO类，防止循环调用
 * 完全无第三方依赖，线程安全
 */
public class LogWriter {
    private static volatile LogWriter instance;
    private BufferedWriter writer;
    private String logPath;

    private LogWriter() {
        // 先获取进程ID，生成默认日志路径
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

    /**
     * 设置自定义日志路径，必须在init之前调用
     */
    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    /**
     * 初始化日志文件流，注册JVM退出钩子自动关闭
     */
    public void init() {
        try {
            // 完全使用原生流操作，不调用任何被监控的IO类
            FileOutputStream fos = new FileOutputStream(logPath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer = new BufferedWriter(osw);

            // 注册JVM退出钩子，自动关闭流
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
            // 初始化失败，静默处理，不影响源进程运行
        }
    }

    /**
     * 写INFO级别日志，线程安全
     */
    public void info(String msg) {
        if (writer == null) {
            return;
        }
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
