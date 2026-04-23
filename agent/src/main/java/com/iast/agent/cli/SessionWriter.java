package com.iast.agent.cli;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 会话级文本帧写入器。{@link CliServer#sessionLoop} 写命令应答 + monitor advice 异步推帧
 * 共享同一 OutputStream —— 所有写入串到 {@code synchronized(out)} 块内，保证 WS 帧字节
 * 不会被并发线程交错。
 *
 * <p>设计取舍：v1 走"业务线程同步写"路径，简单可靠。若用户把 monitor 装在热点方法上
 * 写阻塞会拖慢业务线程；MonitorRegistry 会按命中数 throttle 兜底。
 */
public final class SessionWriter {
    private final OutputStream out;
    private volatile boolean alive = true;

    public SessionWriter(OutputStream out) {
        this.out = out;
    }

    /** 串行写一条 text 帧。socket 已坏 → 静默标 dead，后续调用 no-op。 */
    public void writeText(String text) {
        if (!alive) return;
        synchronized (out) {
            try {
                WsFrame.writeClientText(out, text);
            } catch (IOException e) {
                alive = false;
            }
        }
    }

    /** 写一个 close 帧，标 dead。caller 负责 close socket。 */
    public void writeClose(int code, String reason) {
        if (!alive) return;
        synchronized (out) {
            try {
                WsFrame.writeClose(out, code, reason, true);
            } catch (IOException ignore) {
            } finally {
                alive = false;
            }
        }
    }

    public void close() {
        alive = false;
    }

    public boolean isAlive() {
        return alive;
    }
}
