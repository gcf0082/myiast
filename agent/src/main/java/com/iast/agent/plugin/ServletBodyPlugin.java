package com.iast.agent.plugin;

import com.iast.agent.LogWriter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Servlet 请求体监控插件：不走常规 handleMethodCall 分发——专用的 ServletBodyAdvice 直接静态调用
 * {@link #emit} 吐日志、调用 {@link #shouldWrap} / {@link #hardLimitBytes} 决定是否包装请求。
 *
 * <p>配置字段：
 * <ul>
 *   <li>{@code maxLogBytes}（默认 8192）：日志里 body 最多打多少字节，超出追加 "(log-truncated, total N bytes)"</li>
 *   <li>{@code hardLimitBytes}（默认 10485760 = 10MB）：wrapper 把 body 读进堆时的硬上限，
 *       超过会丢弃剩余字节；生产别调小，否则可能把业务也一起截了</li>
 *   <li>{@code textContentTypes}：只对匹配这些前缀的 Content-Type 做包装与解码打印；非文本不包装、
 *       只记长度。默认 {@code [application/json, application/xml, application/x-www-form-urlencoded, text/]}。
 *       填 {@code ["*"]} 放行所有 Content-Type</li>
 *   <li>{@code charset}：Content-Type 不带 charset 时的兜底，默认 UTF-8</li>
 * </ul>
 */
public class ServletBodyPlugin implements IastPlugin {

    private static final List<String> DEFAULT_TEXT_PREFIXES = Arrays.asList(
            "application/json",
            "application/xml",
            "application/x-www-form-urlencoded",
            "text/"
    );

    private static volatile int maxLogBytes = 8192;
    private static volatile long hardLimit = 10L * 1024 * 1024;
    private static volatile List<String> textPrefixes = DEFAULT_TEXT_PREFIXES;
    private static volatile Charset fallbackCharset = StandardCharsets.UTF_8;
    private static volatile boolean allowAllContentTypes = false;

    @Override
    public void init(Map<String, Object> config) {
        // PluginManager 传进来的 config 结构：{ "definitions": List<Map<String,Object>> }，
        // 每个 definition 对应 YAML 里一条 rule 的 pluginConfig（已由 MonitorConfig 附带 className/methods）。
        // 我们只关心第一条 ServletBodyPlugin 规则的配置；多条配置取最后一条（以 YAML 声明顺序覆盖）。
        if (config == null) return;
        Object defs = config.get("definitions");
        if (!(defs instanceof List)) return;
        for (Object raw : (List<?>) defs) {
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) raw;
            applyOne(def);
        }
        LogWriter.getInstance().info("[ServletBody] Plugin initialized: maxLogBytes=" + maxLogBytes
                + ", hardLimit=" + hardLimit + ", textPrefixes=" + (allowAllContentTypes ? "[*]" : textPrefixes));
    }

    private static void applyOne(Map<String, Object> def) {
        Object v;
        if ((v = def.get("maxLogBytes")) instanceof Number) maxLogBytes = ((Number) v).intValue();
        if ((v = def.get("hardLimitBytes")) instanceof Number) hardLimit = ((Number) v).longValue();
        if ((v = def.get("charset")) instanceof String) {
            try { fallbackCharset = Charset.forName((String) v); } catch (Throwable ignore) {}
        }
        if ((v = def.get("textContentTypes")) instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) v;
            if (list.size() == 1 && "*".equals(list.get(0))) {
                allowAllContentTypes = true;
                textPrefixes = DEFAULT_TEXT_PREFIXES;  // 保持一个非空默认，以防 allowAll 后又被关
            } else if (!list.isEmpty()) {
                allowAllContentTypes = false;
                textPrefixes = List.copyOf(list);
            }
        }
    }

    @Override
    public void handleMethodCall(MethodContext context) {
        // 本插件不走常规分发；IastAgent 对 wrapServletRequest=true 的规则挂 ServletBodyAdvice 而非 MethodMonitorAdvice
    }

    @Override public void destroy() {}
    @Override public String getName() { return "ServletBodyPlugin"; }

    // ============= 给 Advice 用的静态 API =============

    public static long hardLimitBytes() { return hardLimit; }

    /** Content-Type 不在白名单 → false；声明长度超 hardLimit → false；否则 true。*/
    public static boolean shouldWrap(String contentType, long declaredLength) {
        if (declaredLength > hardLimit) return false;     // 用户显式说"我有 20MB"，默认 10MB 超了，不包
        if (allowAllContentTypes) return true;
        if (contentType == null) return false;             // 无 CT 的典型是 GET，干脆不包
        String ct = contentType.toLowerCase();
        for (String prefix : textPrefixes) {
            if (ct.startsWith(prefix)) return true;
        }
        return false;
    }

    /** ServletBodyAdvice.onExit 调过来。body == null 代表业务没读过 body。 */
    public static void emit(String requestId,
                             String contentType,
                             String characterEncoding,
                             byte[] body,
                             long totalLength,
                             boolean wrapperTruncated) {
        try {
            String rid = requestId == null ? "-" : requestId;
            String ct = contentType == null ? "-" : contentType;
            if (body == null) {
                LogWriter.getInstance().info("[ServletBody] [requestId=" + rid + "] "
                        + "<not read by app, Content-Type=" + ct + ", Content-Length=" + totalLength + ">");
                return;
            }

            Charset cs = pickCharset(characterEncoding, contentType);
            int logLen = Math.min(body.length, maxLogBytes);
            String text = new String(body, 0, logLen, cs);

            StringBuilder sb = new StringBuilder(128 + logLen);
            sb.append("[ServletBody] [requestId=").append(rid)
              .append("] Content-Type=").append(ct)
              .append(" length=").append(body.length)
              .append(" body=").append(text);
            if (body.length > logLen) sb.append(" ...(log-truncated, total ").append(body.length).append(" bytes)");
            if (wrapperTruncated) sb.append(" [hardLimit-truncated at ").append(hardLimit)
                                    .append(", real total=").append(totalLength).append("]");

            LogWriter.getInstance().info(sb.toString());
        } catch (Throwable t) {
            LogWriter.getInstance().info("[ServletBody] emit failed: " + t);
        }
    }

    private static Charset pickCharset(String encoding, String contentType) {
        if (encoding != null && !encoding.isEmpty()) {
            try { return Charset.forName(encoding); } catch (Throwable ignore) {}
        }
        if (contentType != null) {
            int idx = contentType.toLowerCase().indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + "charset=".length()).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) cs = cs.substring(0, semi).trim();
                try { return Charset.forName(cs); } catch (Throwable ignore) {}
            }
        }
        return fallbackCharset;
    }
}
