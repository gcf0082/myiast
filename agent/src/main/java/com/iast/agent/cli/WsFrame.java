package com.iast.agent.cli;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 最小 RFC 6455 text/close frame 编解码。
 *
 * <p>仅覆盖 CLI 协议要用到的子集：text（0x1）/ close（0x8）/ ping（0x9）/ pong（0xA），
 * 单帧模式（FIN=1），payload 长度两档（≤125 / 126+2byte；不支持 64bit 长度——超长响应由
 * 上层截断到 64K 以内）。
 *
 * <p><b>Mask 规则</b>：按协议，客户端→服务端的帧**必须**带 mask（4 字节 key，对 payload 做
 * XOR）；服务端→客户端的帧**不得**带 mask。两个方向都在这里按角色区分实现。
 *
 * <p>这个类是纯字节工具，既被 {@link CliServer}（作为服务端）也被 {@link CliClient}（作为
 * 客户端）用，所以同时提供 "readFrame(isServer)" 和 "writeText(isClient)" 两种视角。
 */
final class WsFrame {

    static final int OP_CONT = 0x0;
    static final int OP_TEXT = 0x1;
    static final int OP_CLOSE = 0x8;
    static final int OP_PING = 0x9;
    static final int OP_PONG = 0xA;

    /** 一帧解码结果。payload 已经去 mask、已经是原始字节。 */
    static final class Frame {
        final int opcode;
        final boolean fin;
        final byte[] payload;
        Frame(int opcode, boolean fin, byte[] payload) {
            this.opcode = opcode;
            this.fin = fin;
            this.payload = payload;
        }
        String text() { return new String(payload, StandardCharsets.UTF_8); }
    }

    private WsFrame() {}

    /**
     * 从流里读一帧。
     *
     * @param in 输入流
     * @param expectMasked true=按服务端视角读（要求客户端帧带 mask），false=按客户端视角读（拒绝 mask）
     * @return 解码后的 Frame；EOF 返回 null
     */
    static Frame read(DataInputStream in, boolean expectMasked) throws IOException {
        int b1;
        try {
            b1 = in.read();
        } catch (IOException e) {
            return null;
        }
        if (b1 < 0) return null;
        boolean fin = (b1 & 0x80) != 0;
        int opcode = b1 & 0x0F;

        int b2 = in.read();
        if (b2 < 0) return null;
        boolean masked = (b2 & 0x80) != 0;
        int len = b2 & 0x7F;

        if (expectMasked && !masked) throw new IOException("client frame must be masked");
        if (!expectMasked && masked) throw new IOException("server-sent frame must not be masked");

        long payloadLen;
        if (len < 126) {
            payloadLen = len;
        } else if (len == 126) {
            payloadLen = in.readUnsignedShort();
        } else {
            // 64-bit 长度：拒绝过长帧（本 CLI 响应都限 64K 以内）
            long hi = ((long) in.readInt()) & 0xFFFFFFFFL;
            long lo = ((long) in.readInt()) & 0xFFFFFFFFL;
            payloadLen = (hi << 32) | lo;
            if (payloadLen < 0 || payloadLen > (1L << 20)) {
                throw new IOException("payload too large: " + payloadLen);
            }
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            in.readFully(mask);
        }
        byte[] data = new byte[(int) payloadLen];
        in.readFully(data);
        if (mask != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ mask[i & 3]);
            }
        }
        return new Frame(opcode, fin, data);
    }

    /** 服务端写 text 帧（FIN=1, 不 mask）。 */
    static void writeServerText(OutputStream out, String text) throws IOException {
        writeFrame(out, OP_TEXT, text.getBytes(StandardCharsets.UTF_8), false);
    }

    /** 客户端写 text 帧（FIN=1, mask）。mask key 随机。 */
    static void writeClientText(OutputStream out, String text) throws IOException {
        writeFrame(out, OP_TEXT, text.getBytes(StandardCharsets.UTF_8), true);
    }

    /** 服务端 / 客户端写 close 帧（payload 放 status code + reason，可选）。 */
    static void writeClose(OutputStream out, int code, String reason, boolean mask) throws IOException {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        writeFrame(out, OP_CLOSE, payload, mask);
    }

    /** 服务端写 pong 响应 ping（原样回 payload，不 mask）。 */
    static void writeServerPong(OutputStream out, byte[] pingPayload) throws IOException {
        writeFrame(out, OP_PONG, pingPayload, false);
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload, boolean mask) throws IOException {
        int len = payload.length;
        out.write(0x80 | (opcode & 0x0F));          // FIN=1 + opcode
        int maskBit = mask ? 0x80 : 0x00;
        if (len < 126) {
            out.write(maskBit | len);
        } else if (len <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((((long) len) >> (i * 8)) & 0xFF));
            }
        }
        if (mask) {
            byte[] key = new byte[4];
            // 非加密强度即可：时间戳 + System.nanoTime 做随机源
            long seed = System.nanoTime() ^ System.identityHashCode(out);
            for (int i = 0; i < 4; i++) {
                key[i] = (byte) (seed >>> (i * 8));
            }
            out.write(key);
            byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked[i] = (byte) (payload[i] ^ key[i & 3]);
            }
            out.write(masked);
        } else {
            out.write(payload);
        }
        out.flush();
    }
}
