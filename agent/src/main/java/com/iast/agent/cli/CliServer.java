package com.iast.agent.cli;

import com.iast.agent.LogWriter;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 内置的 WebSocket 控制平面 server。按需启动（agentmain("cli") 触发），绑定
 * 127.0.0.1 + OS 分配端口，写端口号到 /tmp/iast-agent-&lt;pid&gt;.port，供 CLI 客户端脚本读取。
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>同进程最多一个 accept 线程（{@link #ensureStarted()} 幂等）</li>
 *   <li>同时只处理一个客户端：新连接到来前旧连接未 close 的话，会强制关掉旧连接</li>
 *   <li>只 bind loopback，永不暴露外网卡</li>
 *   <li>handler 抛任何异常都不会打断 accept loop；单连接异常独立</li>
 * </ul>
 */
public final class CliServer {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int SO_TIMEOUT_MS = 0;  // 0 = 无限等，让客户端控制空闲
    private static final int MAX_LINE = 16 * 1024;

    private static volatile boolean started = false;
    private static volatile ServerSocket serverSocket;
    private static volatile Socket activeClient;
    private static final Object START_LOCK = new Object();

    private CliServer() {}

    /** 幂等：首次调用起 server；已起过则打一条日志就返回。 */
    public static void ensureStarted() {
        if (started) {
            LogWriter.getInstance().info("[IAST CLI] CLI server already running on port " + (serverSocket == null ? -1 : serverSocket.getLocalPort()));
            return;
        }
        synchronized (START_LOCK) {
            if (started) return;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                serverSocket = ss;
                int port = ss.getLocalPort();
                writePortFile(port);
                Thread t = new Thread(CliServer::acceptLoop, "iast-cli-server");
                t.setDaemon(true);
                t.start();
                started = true;
                LogWriter.getInstance().info("[IAST CLI] CLI server started on 127.0.0.1:" + port);
            } catch (IOException e) {
                LogWriter.getInstance().info("[IAST CLI] Failed to start CLI server: " + e.getMessage());
            }
        }
    }

    private static void writePortFile(int port) {
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        if (at > 0) pid = pid.substring(0, at);
        File f = new File("/tmp/iast-agent-" + pid + ".port");
        try (FileWriter w = new FileWriter(f)) {
            w.write(String.valueOf(port));
        } catch (IOException e) {
            LogWriter.getInstance().info("[IAST CLI] Failed to write port file " + f + ": " + e.getMessage());
        }
    }

    private static void acceptLoop() {
        ServerSocket ss = serverSocket;
        while (!ss.isClosed()) {
            Socket client = null;
            try {
                client = ss.accept();
                client.setSoTimeout(SO_TIMEOUT_MS);
                closePrevious();
                activeClient = client;
                final Socket c = client;
                Thread handler = new Thread(() -> handleClient(c), "iast-cli-conn");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    LogWriter.getInstance().info("[IAST CLI] accept error: " + e.getMessage());
                }
                if (client != null) safeClose(client);
            }
        }
    }

    private static void closePrevious() {
        Socket prev = activeClient;
        if (prev != null && !prev.isClosed()) {
            LogWriter.getInstance().info("[IAST CLI] New connection arrived; closing previous session");
            safeClose(prev);
        }
    }

    private static void handleClient(Socket sock) {
        try {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (!doHandshake(in, out)) {
                safeClose(sock);
                return;
            }
            // 欢迎横幅（放第一帧）
            WsFrame.writeServerText(out, "iast-cli connected. Type 'help' for commands.");
            while (!sock.isClosed()) {
                WsFrame.Frame f = WsFrame.read(in, true);
                if (f == null) break;
                switch (f.opcode) {
                    case WsFrame.OP_TEXT:
                        String cmd = f.text();
                        String resp = CliHandler.execute(cmd);
                        if ("__QUIT__".equals(resp)) {
                            WsFrame.writeServerText(out, "bye");
                            WsFrame.writeClose(out, 1000, "bye", false);
                            return;
                        }
                        WsFrame.writeServerText(out, resp);
                        break;
                    case WsFrame.OP_PING:
                        WsFrame.writeServerPong(out, f.payload);
                        break;
                    case WsFrame.OP_CLOSE:
                        WsFrame.writeClose(out, 1000, "bye", false);
                        return;
                    default:
                        // 忽略 pong / continuation / binary
                        break;
                }
            }
        } catch (IOException e) {
            LogWriter.getInstance().info("[IAST CLI] session I/O error: " + e.getMessage());
        } catch (Throwable t) {
            LogWriter.getInstance().info("[IAST CLI] session unexpected error: " + t);
        } finally {
            safeClose(sock);
            if (activeClient == sock) activeClient = null;
        }
    }

    private static boolean doHandshake(DataInputStream in, OutputStream out) throws IOException {
        // 读 HTTP 请求头直到空行
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null) return false;
        Map<String, String> headers = new HashMap<>();
        String line;
        int total = 0;
        while ((line = reader.readLine()) != null) {
            total += line.length();
            if (total > MAX_LINE) return false;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
            }
        }
        String key = headers.get("sec-websocket-key");
        String upgrade = headers.get("upgrade");
        if (key == null || upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            String bad = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nExpect WebSocket upgrade\n";
            out.write(bad.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return false;
        }
        String accept = base64Sha1(key + GUID);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return true;
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
