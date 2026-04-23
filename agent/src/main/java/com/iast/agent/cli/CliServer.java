package com.iast.agent.cli;

import com.iast.agent.LogWriter;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Agent 内置的 WebSocket 控制平面 <b>dialer</b>（注意：尽管叫 CliServer，实际是 WS 客户端）。
 *
 * <p>按需启动——{@code agentmain("cli=host:port")} 触发 {@link #ensureConnected(String, int)}
 * 向 CLI 所在的 {@code host:port} 发起 WebSocket 连接，握手成功后进入命令循环：
 * 读 CLI 发来的文本帧（server-sent，不 mask）→ 交 {@link CliHandler} 执行 → 结果写回
 * （client-sent，必须 mask）。
 *
 * <p>历史：此类早期实现是 WebSocket server（目标 JVM 监听端口，CLI 进来连）。v2 翻转为
 * CLI 监听、agent 出 dial，以支持只允许出口连接的容器/防火墙场景。类名保留 {@code CliServer}
 * 是为了改动集中；真实语义是 "agent 一侧的 CLI 控制会话 bootstrap"。
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>同进程最多一个 dialer 线程（{@link #ensureConnected} 幂等）</li>
 *   <li>1:1 会话；session 结束就退出线程，不自动重连——再连由 CLI 侧重新 jattach 触发</li>
 *   <li>handler 抛任何异常都不会影响 agent 其它功能；dialer 异常只打日志</li>
 * </ul>
 */
public final class CliServer {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int SO_TIMEOUT_MS = 0;  // 0 = 无限等，让命令层控制空闲
    // 冷启动窗口：jattach 先到、CLI 后 exec 的情况下要等 CLI 把 ServerSocket 绑上来。
    // 5 次 × 300ms ≈ 1.5s，覆盖典型 JVM 启动时间；首次连成功就立刻返回。
    private static final int CONNECT_RETRIES = 5;
    private static final long CONNECT_RETRY_DELAY_MS = 300L;

    private static volatile boolean running = false;
    private static volatile Socket activeSocket;
    private static final Object START_LOCK = new Object();

    private CliServer() {}

    /**
     * 幂等：若当前已有活动会话，打一条日志就返回；否则 spawn 一个 daemon dialer 线程
     * 连到 {@code host:port}。
     */
    public static void ensureConnected(String host, int port) {
        if (running) {
            LogWriter.getInstance().info("[IAST CLI] dialer already running; ignoring new cli=" + host + ":" + port);
            return;
        }
        synchronized (START_LOCK) {
            if (running) return;
            running = true;
            Thread t = new Thread(() -> runSession(host, port), "iast-cli-dialer");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void runSession(String host, int port) {
        Socket sock = null;
        try {
            LogWriter.getInstance().info("[IAST CLI] Dialing CLI at " + host + ":" + port);
            sock = dialWithRetry(host, port);
            if (sock == null) return;
            sock.setSoTimeout(SO_TIMEOUT_MS);
            activeSocket = sock;

            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            if (!doClientHandshake(in, out, host, port)) {
                LogWriter.getInstance().info("[IAST CLI] handshake failed");
                return;
            }
            LogWriter.getInstance().info("[IAST CLI] CLI session established");

            sessionLoop(sock, in, out);
        } catch (IOException e) {
            LogWriter.getInstance().info("[IAST CLI] dialer I/O error: " + e.getMessage());
        } catch (Throwable t) {
            LogWriter.getInstance().info("[IAST CLI] dialer unexpected error: " + t);
        } finally {
            if (sock != null) safeClose(sock);
            activeSocket = null;
            running = false;
            LogWriter.getInstance().info("[IAST CLI] CLI session closed");
        }
    }

    /**
     * 按 {@link #CONNECT_RETRIES} × {@link #CONNECT_RETRY_DELAY_MS} 做短暂重试：jattach 触发本方法时
     * CLI 可能还没完成 {@code ServerSocket.bind}（典型 iast-cli-jattach.sh 流程里 jattach 在 exec CLI 前跑）。
     * 返回 null 表示最终失败；日志在每次失败时打一行，方便排错。
     */
    private static Socket dialWithRetry(String host, int port) {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_RETRIES; attempt++) {
            try {
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                if (attempt > 1) {
                    LogWriter.getInstance().info("[IAST CLI] connected on attempt " + attempt);
                }
                return s;
            } catch (IOException e) {
                last = e;
                if (attempt < CONNECT_RETRIES) {
                    try {
                        Thread.sleep(CONNECT_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        LogWriter.getInstance().info("[IAST CLI] dialer gave up after " + CONNECT_RETRIES
                + " attempts: " + (last == null ? "?" : last.getMessage()));
        return null;
    }

    private static void sessionLoop(Socket sock, DataInputStream in, OutputStream out) throws IOException {
        // 整个会话注册到 MonitorRegistry：CLI 命令应答 + monitor advice 推帧共用同一 SessionWriter
        // （内部 synchronized(out) 串行所有 WS 帧）。finally 兜底撤 monitor + close writer。
        MonitorRegistry.SessionContext sc = MonitorRegistry.openSession(out);
        try {
            while (!sock.isClosed()) {
                // expectMasked=false：CLI 现在是 WS server，它发来的帧 *不得* mask
                WsFrame.Frame f = WsFrame.read(in, false);
                if (f == null) break;
                switch (f.opcode) {
                    case WsFrame.OP_TEXT:
                        String cmd = f.text();
                        String resp = CliHandler.execute(cmd, sc);
                        if ("__QUIT__".equals(resp)) {
                            sc.getWriter().writeText("bye");
                            sc.getWriter().writeClose(1000, "bye");
                            return;
                        }
                        sc.getWriter().writeText(resp);
                        break;
                    case WsFrame.OP_PING:
                        WsFrame.writeClientPong(out, f.payload);
                        break;
                    case WsFrame.OP_CLOSE:
                        sc.getWriter().writeClose(1000, "bye");
                        return;
                    default:
                        // 忽略 pong / continuation / binary
                        break;
                }
            }
        } finally {
            MonitorRegistry.closeSession(sc);
        }
    }

    /**
     * 客户端视角的 WS 握手：发 {@code GET / HTTP/1.1 ... Upgrade: websocket}，等 {@code 101}
     * 并校验 {@code Sec-WebSocket-Accept}。
     */
    private static boolean doClientHandshake(DataInputStream in, OutputStream out,
                                             String host, int port) throws IOException {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET / HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String statusLine = br.readLine();
        if (statusLine == null || !statusLine.contains(" 101 ")) {
            LogWriter.getInstance().info("[IAST CLI] handshake rejected: " + statusLine);
            return false;
        }
        String expected = base64Sha1(key + GUID);
        String line;
        boolean ok = false;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String k = line.substring(0, colon).trim();
                String v = line.substring(colon + 1).trim();
                if ("Sec-WebSocket-Accept".equalsIgnoreCase(k) && expected.equals(v)) {
                    ok = true;
                }
            }
        }
        if (!ok) {
            LogWriter.getInstance().info("[IAST CLI] handshake failed: bad Sec-WebSocket-Accept");
        }
        return ok;
    }

    private static String base64Sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void safeClose(Socket s) {
        try { s.close(); } catch (IOException ignore) {}
    }
}
