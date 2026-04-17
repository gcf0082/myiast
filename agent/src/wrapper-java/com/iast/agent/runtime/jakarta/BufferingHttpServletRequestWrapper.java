package com.iast.agent.runtime.jakarta;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * IAST Agent 的请求包装类：把 body 字节缓存到堆内 byte[]，
 * 业务每次 getInputStream() / getReader() 都拿到基于 byte[] 的 fresh 流，
 * 既不会把 body 吃空、又让 Agent 能事后观测。
 *
 * <p>本类**不会**出现在 agent 主 classpath 里（编译后会被重命名成 .class.bin 资源），
 * 只能通过 {@code WrapperInjector} 按字节定义进目标应用 ClassLoader。
 */
public class BufferingHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final long hardLimitBytes;
    private volatile byte[] cachedBody;   // 业务没读过 body 时保持 null
    private volatile long totalLength;
    private volatile boolean truncated;

    public BufferingHttpServletRequestWrapper(HttpServletRequest delegate, long hardLimitBytes) {
        super(delegate);
        this.hardLimitBytes = hardLimitBytes > 0 ? hardLimitBytes : Long.MAX_VALUE;
    }

    private synchronized void ensureCached() throws IOException {
        if (cachedBody != null) return;
        InputStream src = super.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(8192, (int) Math.min(hardLimitBytes, Integer.MAX_VALUE)));
        byte[] buf = new byte[4096];
        long remaining = hardLimitBytes;
        long skipped = 0;
        int n;
        while ((n = src.read(buf)) != -1) {
            if (remaining <= 0) {
                // 已到硬上限：剩余字节丢弃。业务此后读到的也就是截断的部分。
                truncated = true;
                skipped += n;
                continue;
            }
            int take = (int) Math.min((long) n, remaining);
            bos.write(buf, 0, take);
            remaining -= take;
            if (take < n) {
                truncated = true;
                skipped += (n - take);
            }
        }
        totalLength = bos.size() + skipped;
        cachedBody = bos.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        ensureCached();
        return new CachedServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        ensureCached();
        String enc = getCharacterEncoding();
        Charset cs = (enc != null && !enc.isEmpty()) ? Charset.forName(enc) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }

    /** 未被读过返回 null；否则返回缓存的 body 引用（调用方自律不 mutate）。 */
    public byte[] peekCachedBody() {
        return cachedBody;
    }

    public long getTotalLength() {
        return totalLength;
    }

    public boolean isTruncated() {
        return truncated;
    }

    private static final class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream bais;

        CachedServletInputStream(byte[] body) {
            this.bais = new ByteArrayInputStream(body != null ? body : new byte[0]);
        }

        @Override
        public boolean isFinished() {
            return bais.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // 粗暴实现：缓存都在内存里，立即 onDataAvailable + onAllDataRead
            try {
                if (readListener != null) {
                    readListener.onDataAvailable();
                    readListener.onAllDataRead();
                }
            } catch (IOException e) {
                // ignore
            }
        }

        @Override
        public int read() {
            return bais.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return bais.read(b, off, len);
        }

        @Override
        public int available() {
            return bais.available();
        }
    }
}
