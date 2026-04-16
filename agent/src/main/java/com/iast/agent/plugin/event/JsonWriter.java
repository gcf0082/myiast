package com.iast.agent.plugin.event;

import java.util.Collection;
import java.util.Map;

/**
 * 轻量级JSON序列化工具
 * 仅支持基本类型、Map、List/Array
 * 不依赖任何第三方库，避免bootstrap classloader污染
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof CharSequence) {
            writeString(sb, v.toString());
        } else if (v instanceof Map) {
            writeMap(sb, (Map<?, ?>) v);
        } else if (v instanceof Collection) {
            writeCollection(sb, (Collection<?>) v);
        } else if (v instanceof Object[]) {
            writeArray(sb, (Object[]) v);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeCollection(StringBuilder sb, Collection<?> c) {
        sb.append('[');
        boolean first = true;
        for (Object o : c) {
            if (!first) sb.append(',');
            first = false;
            write(sb, o);
        }
        sb.append(']');
    }

    private static void writeArray(StringBuilder sb, Object[] a) {
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            write(sb, a[i]);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
