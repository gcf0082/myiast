package com.iast.cli;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * IAST CLI 客户端（Arthas 风格）。v2：默认 <b>CLI 监听、agent 主动 dial</b>。
 *
 * <pre>
 *   # 日常使用（推荐）：先起 CLI，再用 jattach 让 agent 连进来
 *   java -jar iast-cli.jar listen [host] [port]    # 默认 127.0.0.1:0（随机端口）
 *       --port-file &lt;path&gt;                      # 把绑定端口写进文件（脚本编排用）
 *
 *   # 拿一个空闲端口（脚本用来避免端口竞争）
 *   java -jar iast-cli.jar freeport
 *
 *   # (进阶) 反向：CLI 主动 dial 一个仍在跑旧版 WS server 的目标
 *   java -jar iast-cli.jar connect &lt;host&gt; &lt;port&gt;
 * </pre>
 *
 * <p>只依赖 JDK 1.8+ 的 {@code java.net}/{@code java.security}/{@code Runtime.exec("stty")}，
 * 目的是在任何 Java 版本上都能跑。
 *
 * <p>REPL 流程：
 * <ul>
 *   <li>有 tty：把 tty 切到 raw 模式（{@code stty -icanon -echo}），自己读字节 / 自己 echo /
 *       自己处理 backspace（识别 {@code 0x08}/{@code 0x7F} 两种）</li>
 *   <li>无 tty（stdin 是管道 / heredoc / 测试脚本）：退回 {@link BufferedReader#readLine()}</li>
 * </ul>
 *
 * <p>对端发 close 帧或 socket 断开时客户端静默退出（exit code 0）；退出前 shutdown hook 兜底
 * 把 tty 恢复到 sane 状态。
 */
public final class CliClient {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String PROMPT = "iast> ";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { usage(); return; }
        String cmd = args[0].toLowerCase();
        String[] tail = tailArgs(args);
        switch (cmd) {
            case "listen":   runListen(tail);  break;
            case "freeport": runFreeport();    break;
            case "connect":  runConnect(tail); break;
            default:         usage();          break;
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java -jar iast-cli.jar listen [host] [port]   # wait for agent to dial in");
        System.err.println("                             [--port-file <path>]");
        System.err.println("  java -jar iast-cli.jar freeport               # print a free 127.0.0.1 port");
        System.err.println("  java -jar iast-cli.jar connect <host> <port>  # (advanced) dial a legacy WS server");
        System.exit(2);
    }

    private static String[] tailArgs(String[] args) {
        String[] t = new String[args.length - 1];
        System.arraycopy(args, 1, t, 0, t.length);
        return t;
    }

    // ---------- listen：CLI 自己起 ServerSocket，等 agent 来连 ----------

    private static void runListen(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 0;
        String portFile = null;
        int posIdx = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--port-file".equals(a) && i + 1 < args.length) {
                portFile = args[++i];
            } else if (posIdx == 0) {
                host = a; posIdx++;
            } else if (posIdx == 1) {
                port = Integer.parseInt(a); posIdx++;
            }
        }
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, port));
            int bound = ss.getLocalPort();
            System.out.println("IAST_CLI_PORT=" + bound);
            System.out.flush();
            if (portFile != null) {
                try (FileWriter w = new FileWriter(portFile)) {
                    w.write(String.valueOf(bound));
                }
            }
            System.out.println("iast-cli listening on " + host + ":" + bound + ", waiting for agent...");
            System.out.flush();
            try (Socket sock = ss.accept()) {
                DataInputStream in = new DataInputStream(sock.getInputStream());
                OutputStream out = sock.getOutputStream();
                if (!doServerHandshake(in, out)) return;
                runRepl(in, out, /*isServer=*/true);
            }
        }
    }

    // ---------- freeport：给 shell 脚本探 OS 分配的空闲端口 ----------

    private static void runFreeport() throws Exception {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            System.out.println(ss.getLocalPort());
        }
    }

    // ---------- connect：旧的 dial-to-server 模式，保留给手动调试 ----------

    private static void runConnect(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: connect <host> <port>  (or: connect <port>)");
            System.exit(2);
        }
        String host;
        int port;
        if (args.length == 1) {
            host = "127.0.0.1";
            port = Integer.parseInt(args[0]);
        } else {
            host = args[0];
            port = Integer.parseInt(args[1]);
        }
        try (Socket sock = new Socket(host, port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            doClientHandshake(in, out, host, port);
            runRepl(in, out, /*isServer=*/false);
        }
    }

    // ---------- REPL 共享入口 ----------

    private static void runRepl(DataInputStream in, OutputStream out, boolean isServer) throws Exception {
        System.out.println("iast-cli connected. Type 'help' for commands.");
        System.out.flush();
        Thread reader = new Thread(() -> readLoop(in, isServer), "iast-cli-reader");
        reader.setDaemon(true);
        reader.start();
        // 给 reader 线程一小段窗口把第一帧（若有）落屏再打 prompt
        Thread.sleep(100);
        if (isTty()) {
            rawModeRepl(out, isServer);
        } else {
            pipedRepl(out, isServer);
        }
    }

    // ---------- REPL: raw TTY 模式 ----------

    /**
     * Raw 模式行编辑器：自己管 echo 和 backspace，彻底绕开 tty erase 设置和键盘实际字符
     * 不匹配导致的 {@code ^H} 问题。支持 {@code 0x08}/{@code 0x7F} 两种 backspace、Ctrl-C、
     * Ctrl-D、Enter；方向键 / 历史 / 补全不做（v1 范围）。
     */
    private static void rawModeRepl(OutputStream out, boolean isServer) throws Exception {
        String savedStty = captureSttyState();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreStty(savedStty), "iast-cli-stty-restore"));
        try {
            runStty("-icanon -echo min 1 time 0");

            System.out.print(PROMPT);
            System.out.flush();

            InputStream stdin = System.in;
            StringBuilder buf = new StringBuilder();
            while (true) {
                int c = stdin.read();
                if (c < 0) {
                    System.out.println();
                    return;
                }
                if (c == '\r' || c == '\n') {
                    System.out.println();
                    String line = buf.toString().trim();
                    buf.setLength(0);
                    if (line.isEmpty()) {
                        System.out.print(PROMPT);
                        System.out.flush();
                        continue;
                    }
                    try {
                        writeText(out, isServer, line);
                    } catch (IOException e) {
                        return;
                    }
                    if ("quit".equals(line) || "exit".equals(line)) {
                        Thread.sleep(100);
                        return;
                    }
                    Thread.sleep(50);
                    System.out.print(PROMPT);
                    System.out.flush();
                } else if (c == 0x7F || c == 0x08) {
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                } else if (c == 0x03) {
                    System.out.println("^C");
                    return;
                } else if (c == 0x04) {
                    if (buf.length() == 0) {
                        System.out.println();
                        return;
                    }
                } else if (c == 0x1B) {
                    if (stdin.available() > 0) stdin.read();
                    if (stdin.available() > 0) stdin.read();
                } else if (c == 0x15) {
                    while (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                    System.out.flush();
                } else if (c >= 0x20 && c < 0x7F) {
                    buf.append((char) c);
                    System.out.write(c);
                    System.out.flush();
                }
            }
        } finally {
            restoreStty(savedStty);
        }
    }

    // ---------- REPL: 管道模式（非 tty，测试脚本用）----------

    private static void pipedRepl(OutputStream out, boolean isServer) throws Exception {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        System.out.print(PROMPT);
        System.out.flush();
        String line;
        while ((line = stdin.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                System.out.print(PROMPT);
                System.out.flush();
                continue;
            }
            try {
                writeText(out, isServer, trimmed);
            } catch (IOException e) {
                break;
            }
            if ("quit".equals(trimmed) || "exit".equals(trimmed)) {
                Thread.sleep(100);
                break;
            }
            Thread.sleep(50);
            System.out.print(PROMPT);
            System.out.flush();
        }
    }

    // ---------- WS 方向感知的 read/write 工具 ----------

    private static void writeText(OutputStream out, boolean isServer, String text) throws IOException {
        if (isServer) {
            WsFrame.writeServerText(out, text);
        } else {
            WsFrame.writeClientText(out, text);
        }
    }

    private static void readLoop(DataInputStream in, boolean isServer) {
        boolean expectMasked = isServer;  // listen 模式下对端是 WS client，它发来的帧 *必须* mask
        try {
            while (true) {
                WsFrame.Frame f = WsFrame.read(in, expectMasked);
                if (f == null) {
                    System.out.println("\n[connection closed]");
                    return;
                }
                if (f.opcode == WsFrame.OP_TEXT) {
                    System.out.println();
                    System.out.println(f.text());
                    System.out.print(PROMPT);
                    System.out.flush();
                } else if (f.opcode == WsFrame.OP_CLOSE) {
                    return;
                }
            }
        } catch (IOException e) {
            // socket 断开就退
        }
    }

    // ---------- tty 工具 ----------

    private static boolean isTty() {
        return System.console() != null;
    }

    private static String captureSttyState() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty")
                    .redirectErrorStream(false).start();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) baos.write(buf, 0, n);
            p.waitFor();
            if (p.exitValue() != 0) return null;
            return baos.toString("UTF-8").trim();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void runStty(String args) {
        try {
            new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty").start().waitFor();
        } catch (Exception ignore) {}
    }

    private static void restoreStty(String saved) {
        if (saved != null && !saved.isEmpty()) {
            runStty(saved);
        } else {
            runStty("sane");
        }
    }

    // ---------- WS 握手：两种方向各一份 ----------

    /**
     * 服务端视角握手：读 HTTP 请求头；若 Upgrade: websocket 成立，写回 101 Switching Protocols。
     * listen 模式下 agent 打过来的是 client 握手，这里按 server 应答。
     */
    private static boolean doServerHandshake(DataInputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null) return false;
        Map<String, String> headers = new HashMap<>();
        String line;
        int total = 0;
        while ((line = reader.readLine()) != null) {
            total += line.length();
            if (total > 16 * 1024) return false;
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

    /** 客户端视角握手：给 connect 模式保留，发 GET Upgrade 并校验 101 响应。 */
    private static void doClientHandshake(DataInputStream in, OutputStream out, String host, int port) throws IOException {
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
            throw new IOException("handshake failed: " + statusLine);
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
        if (!ok) throw new IOException("handshake failed: bad Sec-WebSocket-Accept");
    }

    private static String base64Sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(md.digest(s.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
