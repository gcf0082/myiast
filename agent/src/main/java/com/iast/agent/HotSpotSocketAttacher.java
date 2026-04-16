package com.iast.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * HotSpot 原生 attach 协议实现（JRE 兼容路径）
 *
 * 不依赖 jdk.attach 模块，只需 Java 16+ 的 UnixDomainSocketAddress。
 * 仅支持 Linux/macOS 上的 HotSpot JVM——这些平台通过 /tmp/.java_pid&lt;pid&gt;
 * UNIX Socket 暴露 attach 接口。协议：
 *
 *   1. 创建标记文件 /tmp/.attach_pid&lt;pid&gt;
 *   2. kill -3 &lt;pid&gt; 触发 HotSpot 信号处理程序创建 Attach Listener
 *   3. 连接 UNIX Socket 发送 "1\0load\0instrument\0false\0&lt;jar&gt;=&lt;args&gt;\0"
 *   4. 读响应 "&lt;code&gt;\n&lt;output&gt;"，code=0 代表成功
 *
 * Windows 使用不同的 DLL 注入方案，本实现不支持；Windows 用户请安装完整 JDK。
 */
public final class HotSpotSocketAttacher {
    private HotSpotSocketAttacher() {}

    public static void attach(String pid, String agentJarPath, String agentArgs) throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            throw new IOException("HotSpot socket attach fallback only supports Linux/macOS. "
                    + "Current OS: " + System.getProperty("os.name")
                    + ". Please install a JDK (includes jdk.attach module).");
        }

        int targetPid;
        try {
            targetPid = Integer.parseInt(pid);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid pid: " + pid);
        }

        // 粗略检查PID是否存在（仅Linux有/proc）
        File procDir = new File("/proc/" + targetPid);
        if (osName.contains("linux") && !procDir.exists()) {
            throw new IOException("Target process " + targetPid + " not found");
        }
        System.out.println("[IAST AttachTool] Found target process: PID=" + pid + " (via socket protocol)");

        File sock = new File("/tmp/.java_pid" + pid);
        if (!sock.exists()) {
            triggerAttachListener(targetPid);
            // 等待目标JVM创建socket，最多6秒
            for (int i = 0; i < 60 && !sock.exists(); i++) {
                Thread.sleep(100);
            }
        }
        if (!sock.exists()) {
            throw new IOException("Attach socket was not created at " + sock
                    + ". Ensure target JVM is HotSpot and attach is not disabled (-XX:+DisableAttachMechanism).");
        }

        System.out.println("[IAST AttachTool] Attaching to process " + pid + " via HotSpot UNIX socket...");
        System.out.println("[IAST AttachTool] Agent jar path: " + agentJarPath);
        System.out.println("[IAST AttachTool] Loading IAST Agent...");
        connectAndLoad(sock, agentJarPath, agentArgs);
        System.out.println("[IAST AttachTool] IAST Agent loaded successfully!");
    }

    private static void triggerAttachListener(int pid) throws IOException, InterruptedException {
        // 创建 /tmp/.attach_pid<pid> 标记文件，owner 必须与目标JVM匹配
        File marker = new File("/tmp/.attach_pid" + pid);
        if (!marker.exists()) {
            if (!marker.createNewFile()) {
                throw new IOException("Cannot create attach marker: " + marker);
            }
        }
        try {
            Process kill = new ProcessBuilder("kill", "-3", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            int rc = kill.waitFor();
            if (rc != 0) {
                throw new IOException("kill -3 " + pid + " exited with " + rc);
            }
        } finally {
            // HotSpot 监听到信号后会自行处理标记文件；兜底删除
            // noinspection ResultOfMethodCallIgnored
            marker.delete();
        }
    }

    private static void connectAndLoad(File sockFile, String agentJarPath, String agentArgs) throws IOException {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(sockFile.toPath());
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(addr);

            String options = agentArgs == null ? "" : agentArgs;
            // 协议版本 "1"，命令 "load"，arg0=instrument，arg1=false（jar不是绝对库路径标记），
            // arg2=<jarPath>=<agentArgs>
            byte[] payload = buildLoadCommand(agentJarPath, options);
            ByteBuffer out = ByteBuffer.wrap(payload);
            while (out.hasRemaining()) {
                ch.write(out);
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ByteBuffer in = ByteBuffer.allocate(1024);
            while (true) {
                int n = ch.read(in);
                if (n < 0) break;
                if (n > 0) {
                    in.flip();
                    buf.write(in.array(), in.position(), in.remaining());
                    in.clear();
                }
            }

            String resp = buf.toString(StandardCharsets.UTF_8);
            int nl = resp.indexOf('\n');
            String codeStr = nl >= 0 ? resp.substring(0, nl) : resp;
            String tail = nl >= 0 ? resp.substring(nl + 1) : "";
            int code;
            try {
                code = Integer.parseInt(codeStr.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Unparseable attach response: " + resp);
            }
            if (code != 0) {
                throw new IOException("Attach failed (code=" + code + "): " + tail);
            }
            String trimmed = tail.trim();
            if (!trimmed.isEmpty()) {
                System.out.println("[IAST AttachTool] Target response: " + trimmed);
            }
        }
    }

    private static byte[] buildLoadCommand(String jarPath, String options) {
        String joined = "1\0load\0instrument\0false\0" + jarPath + "=" + options + "\0";
        return joined.getBytes(StandardCharsets.UTF_8);
    }
}
