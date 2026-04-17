package com.iast.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 基于外部 jattach 二进制的挂载实现。
 *
 * jattach（https://github.com/jattach/jattach）是纯 C 写的 HotSpot
 * attach 协议客户端，单文件、无 JVM 依赖、覆盖 Linux/macOS/Windows。
 * 路径最短、启动最快，如果用户 PATH 里有就优先用它。
 *
 * 协议：jattach &lt;pid&gt; load instrument false "&lt;jar&gt;=&lt;args&gt;"
 *   - load           挂载 JVMTI agent
 *   - instrument     HotSpot 内置 Java agent 桥（负责 premain/agentmain 的 jar 加载）
 *   - false          该 library 不是绝对路径，让 JVM 按名字查找（instrument 是内置的）
 */
public final class JattachAttacher {

    private static final int PROBE_TIMEOUT_SECONDS = 3;
    private static final int ATTACH_TIMEOUT_SECONDS = 30;

    private JattachAttacher() {}

    /**
     * 检测 PATH 里有没有 jattach 命令。快速且不抛异常。
     */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("jattach")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            // jattach 无参调用退出码非零，只要能启动就说明二进制存在
            return true;
        } catch (IOException e) {
            // command not found
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void attach(String pid, String agentJarPath, String agentArgs) throws Exception {
        String options = (agentArgs == null || agentArgs.isEmpty())
                ? agentJarPath
                : agentJarPath + "=" + agentArgs;

        System.out.println("[IAST AttachTool] Attaching to process " + pid + " via jattach native binary...");
        System.out.println("[IAST AttachTool] Agent jar path: " + agentJarPath);
        System.out.println("[IAST AttachTool] Loading IAST Agent...");

        Process p = new ProcessBuilder("jattach", pid, "load", "instrument", "false", options)
                .redirectErrorStream(true)
                .start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        boolean finished = p.waitFor(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("jattach timed out after " + ATTACH_TIMEOUT_SECONDS + "s");
        }
        int rc = p.exitValue();
        String trimmed = out.toString().trim();
        if (rc != 0) {
            throw new IOException("jattach failed with exit " + rc + ": " + trimmed);
        }
        if (!trimmed.isEmpty()) {
            System.out.println("[IAST AttachTool] jattach output: " + trimmed);
        }
        System.out.println("[IAST AttachTool] IAST Agent loaded successfully!");
    }
}
