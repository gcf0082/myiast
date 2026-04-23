package com.iast.agent.plugin.event;

import com.iast.agent.LogWriter;
import com.iast.agent.plugin.MethodContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 结构化谓词：从 yaml map {expr, op, value, negate} 编译，运行时对 {@link MethodContext}
 * 求值并返回 boolean。专门给 CustomEventPlugin 的 filter 用，但作为 SDK 类放在公共位置，
 * 任何插件都能复用。
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code expr}：必填，复用 {@link Expression} 语法（{@code params[0]} /
 *       {@code params[0].toString()} / {@code target.getClass().getSimpleName()} / 等）</li>
 *   <li>{@code op}：必填，{@link Op} 枚举：equals / contains / startsWith / endsWith / matches</li>
 *   <li>{@code value}：必填，对照值；
 *       <b>支持单值或数组</b>——数组语义为 OR：任一元素命中 op 比较即视为真，
 *       省得给同一 expr+op 写多条谓词。op=matches 时数组每个元素分别 {@code Pattern.compile}。</li>
 *   <li>{@code negate}：可选，默认 false；true 时把比较结果（含数组 OR 后的结果）取反——
 *       相当于"none of"语义</li>
 * </ul>
 *
 * <p>语义：expr 求值结果 null 当作空字符串；运行期任何异常 → 视作 false（首次警告一次）。
 *
 * <h3>YAML 例</h3>
 * <pre>
 *   # 单值
 *   - expr: "params[0]"
 *     op: startsWith
 *     value: "/proc/"
 *
 *   # 数组（任一前缀命中即真，等价于三条 startsWith 单值谓词的 OR）
 *   - expr: "params[0]"
 *     op: startsWith
 *     value: ["/proc/", "/sys/", "/dev/"]
 *
 *   # 数组 + negate（none-of 语义：所有元素都不命中才为真）
 *   - expr: "params[0].toString()"
 *     op: endsWith
 *     value: [".class", ".jar"]
 *     negate: true
 *
 *   # 用 context.* 做调用栈/进程级过滤（stackTraceClasses 比 stackTrace 短得多，
 *   # 高频 hook 上首选）
 *   - expr: "context.stackTraceClasses"
 *     op: contains
 *     value: ["ch.qos.logback.", "org.apache.log4j."]
 *
 *   - expr: "context.hostname"
 *     op: startsWith
 *     value: "web-"
 * </pre>
 */
public final class Predicate {

    public enum Op { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final String source;            // 原始 yaml 描述，给日志用
    private final Expression expr;
    private final Op op;
    private final List<String> values;      // op != MATCHES 时用；至少一个元素
    private final List<Pattern> regexes;    // op == MATCHES 时用；和 values 一一对应
    private final boolean negate;

    private Predicate(String source, Expression expr, Op op,
                      List<String> values, List<Pattern> regexes, boolean negate) {
        this.source = source;
        this.expr = expr;
        this.op = op;
        this.values = values;
        this.regexes = regexes;
        this.negate = negate;
    }

    public String getSource() { return source; }

    /**
     * 从 yaml map 编译。失败抛 {@link IllegalArgumentException}，调用方应在 init 时 catch + WARN。
     */
    public static Predicate compile(Map<String, Object> map) {
        if (map == null) throw new IllegalArgumentException("predicate map is null");
        Object exprObj = map.get("expr");
        Object opObj = map.get("op");
        Object valObj = map.get("value");
        Object negObj = map.get("negate");
        if (!(exprObj instanceof String) || ((String) exprObj).isEmpty()) {
            throw new IllegalArgumentException("predicate missing 'expr': " + map);
        }
        if (!(opObj instanceof String) || ((String) opObj).isEmpty()) {
            throw new IllegalArgumentException("predicate missing 'op': " + map);
        }
        if (valObj == null) {
            throw new IllegalArgumentException("predicate missing 'value': " + map);
        }
        Op op = parseOp((String) opObj);
        Expression compiledExpr = Expression.compile((String) exprObj);

        // value 支持 String 或 List；统一规整成 List<String>
        List<String> values = new ArrayList<>();
        if (valObj instanceof List) {
            for (Object item : (List<?>) valObj) {
                if (item == null) continue;
                String s = String.valueOf(item);
                if (!s.isEmpty()) values.add(s);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("predicate 'value' array is empty after dropping null/blank: " + map);
            }
        } else {
            values.add(String.valueOf(valObj));
        }

        // op=matches 时每个元素单独编译成 Pattern
        List<Pattern> regexes = null;
        if (op == Op.MATCHES) {
            regexes = new ArrayList<>(values.size());
            for (String v : values) {
                try {
                    regexes.add(Pattern.compile(v));
                } catch (PatternSyntaxException pse) {
                    throw new IllegalArgumentException("invalid regex in predicate value: " + v, pse);
                }
            }
            regexes = Collections.unmodifiableList(regexes);
        }

        boolean negate = (negObj instanceof Boolean) && (Boolean) negObj;
        String source = "{expr=" + exprObj + ", op=" + opObj
                + ", value=" + (values.size() == 1 ? values.get(0) : values)
                + (negate ? ", negate=true" : "") + "}";
        return new Predicate(source, compiledExpr, op,
                Collections.unmodifiableList(values), regexes, negate);
    }

    private static Op parseOp(String name) {
        String n = name.trim();
        // 接收驼峰 / 下划线 / 全大写 / 全小写
        String norm = n.replace("_", "").toLowerCase();
        switch (norm) {
            case "equals":     return Op.EQUALS;
            case "contains":   return Op.CONTAINS;
            case "startswith": return Op.STARTS_WITH;
            case "endswith":   return Op.ENDS_WITH;
            case "matches":    return Op.MATCHES;
            default:
                throw new IllegalArgumentException("unknown op '" + name
                        + "' (expected: equals / contains / startsWith / endsWith / matches)");
        }
    }

    /**
     * 运行期判定。任何异常返回 false（首次警告一次），不打断业务。
     * 数组 value：任一元素命中 op 比较即返回 true（OR 语义）。
     * negate=true 把最终 OR 结果取反（none-of 语义）。
     */
    public boolean test(MethodContext ctx) {
        boolean raw;
        try {
            Object v = expr.eval(ctx);
            String s = (v == null) ? "" : String.valueOf(v);
            raw = matchAny(s);
        } catch (Throwable t) {
            String key = source + "|" + t.getClass().getName();
            if (WARNED.add(key)) {
                LogWriter.getInstance().warn("[Predicate] eval failed " + source + " -> "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return false;
        }
        return negate != raw;
    }

    private boolean matchAny(String s) {
        if (op == Op.MATCHES) {
            for (Pattern p : regexes) {
                if (p.matcher(s).find()) return true;
            }
            return false;
        }
        for (String value : values) {
            switch (op) {
                case EQUALS:      if (s.equals(value)) return true; break;
                case CONTAINS:    if (s.contains(value)) return true; break;
                case STARTS_WITH: if (s.startsWith(value)) return true; break;
                case ENDS_WITH:   if (s.endsWith(value)) return true; break;
                default:          break;
            }
        }
        return false;
    }
}
