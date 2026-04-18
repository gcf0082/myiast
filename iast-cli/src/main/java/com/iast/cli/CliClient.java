package com.iast.cli;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * IAST CLI 客户端（Arthas 风格）：
 * <pre>
 *   java -cp iast-agent.jar com.iast.agent.cli.CliClient &lt;port&gt;
 * </pre>
 *
 * 只依赖 JDK 1.8+ 的 java.net / java.security / java.lang.Runtime.exec(stty)，
 * 目的是在目标机器任何 Java 版本上都能跑。
 *
 * <p>REPL 流程：
 * <ul>
 *   <li>有 tty：把 tty 切到 raw 模式（{@code stty -icanon -echo}），自己读字节 / 自己 echo /
 *       自己处理 backspace——同时识别 {@code 0x08}(^H) 和 {@code 0x7F}(^?) 两种 backspace，
 *       这样不依赖 tty 的 erase 设置是否和键盘一致（否则 cooked 模式下会看到 {@code ^H} 被
 *       塞进 buffer 而不是真的擦字符）</li>
 *   <li>无 tty（stdin 是管道 / heredoc / 测试脚本）：退回到
 *       {@link BufferedReader#readLine()} 简单按行读，不改终端模式</li>
 * </ul>
 *
 * <p>服务端发 close 帧或 socket 断开时，客户端静默退出（exit code 0）。退出前一定会把
 * tty 恢复到 sane 状态（shutdown hook 兜底）。
 */
public final class CliClient {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String PROMPT = "iast> ";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -cp iast-agent.jar com.iast.agent.cli.CliClient <port> [host]");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        String host = args.length >= 2 ? args[1] : "127.0.0.1";
        try (Socket sock = new Socket(host, port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            doHandshake(in, out, host, port);

            // 后台线程只负责读服务端帧并打印；主线程读 stdin 发 frame。
            Thread reader = new Thread(() -> readLoop(in), "iast-cli-reader");
            reader.setDaemon(true);
            reader.start();

            // prompt 先等一小会儿让欢迎横幅出来
            Thread.sleep(100);

            if (isTty()) {
                rawModeRepl(out);
            } else {
                pipedRepl(out);
            }
        }
    }

    // ---------- REPL: raw TTY 模式 ----------

    /**
     * Raw 模式行编辑器：自己管 echo 和 backspace，彻底绕开 tty erase 设置和键盘实际字符
     * 不匹配导致的 {@code ^H} 问题。支持 {@code 0x08}/{@code 0x7F} 两种 backspace、Ctrl-C、
     * Ctrl-D、Enter；方向键 / 历史 / 补全不做（v1 范围）。
     */
    private static void rawModeRepl(OutputStream out) throws Exception {
        String savedStty = captureSttyState();
        // Ctrl-C / kill -9 都能把 tty 还原回来
        Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreStty(savedStty), "iast-cli-stty-restore"));
        try {
            // -icanon 关掉行缓冲（逐字节）；-echo 关掉自动回显（我们自己 echo）
            // isig 保留（Ctrl-C 依然发 SIGINT）；min 1 time 0：至少拿到 1 字节再返回
            runStty("-icanon -echo min 1 time 0");

            System.out.print(PROMPT);
            System.out.flush();

            InputStream stdin = System.in;
            StringBuilder buf = new StringBuilder();
            while (true) {
                int c = stdin.read();
                if (c < 0) {           // EOF
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
                        WsFrame.writeClientText(out, line);
                    } catch (IOException e) {
                        return;   // socket 断了
                    }
                    if ("quit".equals(line) || "exit".equals(line)) {
                        Thread.sleep(100);
                        return;
                    }
                    // 给 reader 线程一个窗口把响应落到屏幕（它会自己重打 prompt）
                    Thread.sleep(50);
                    System.out.print(PROMPT);
                    System.out.flush();
                } else if (c == 0x7F || c == 0x08) {   // DEL / BS 都当 backspace
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        // 擦掉屏幕上最后一个字符：光标左移 + 空格覆盖 + 再左移
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                } else if (c == 0x03) {                 // Ctrl-C
                    System.out.println("^C");
                    return;
                } else if (c == 0x04) {                 // Ctrl-D：空行则退出
                    if (buf.length() == 0) {
                        System.out.println();
                        return;
                    }
                } else if (c == 0x1B) {                 // ESC 序列（方向键等）：吃掉后续 2 字节，不处理
                    if (stdin.available() > 0) stdin.read();
                    if (stdin.available() > 0) stdin.read();
                } else if (c == 0x15) {                 // Ctrl-U：清整行
                    while (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                    System.out.flush();
                } else if (c >= 0x20 && c < 0x7F) {     // 可见 ASCII
                    buf.append((char) c);
                    System.out.write(c);
                    System.out.flush();
                }
                // 其它控制字符一律忽略
            }
        } finally {
            restoreStty(savedStty);
        }
    }

    // ---------- REPL: 管道模式（非 tty，测试脚本用）----------

    private static void pipedRepl(OutputStream out) throws Exception {
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
                WsFrame.writeClientText(out, trimmed);
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

    // ---------- tty 工具 ----------

    private static boolean isTty() {
        // System.console() 在 stdin 是管道 / redirect 时返回 null，最轻量的 tty 探测
        return System.console() != null;
    }

    /** 存当前 tty 设置，退出时原样恢复。失败返回 null（退出时退回 `stty sane`）。 */
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

    // ---------- WS 协议 ----------

    private static void readLoop(DataInputStream in) {
        try {
            while (true) {
                WsFrame.Frame f = WsFrame.read(in, false);
                if (f == null) {
                    System.out.println("\n[connection closed]");
                    return;
                }
                if (f.opcode == WsFrame.OP_TEXT) {
                    // 插入一个前导换行让输出不和 "iast> " 挤一行
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

    private static void doHandshake(DataInputStream in, OutputStream out, String host, int port) throws IOException {
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

        // 读 101 响应直到空行
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
