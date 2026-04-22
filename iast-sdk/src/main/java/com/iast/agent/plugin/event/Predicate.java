package com.iast.agent.plugin.event;

import com.iast.agent.LogWriter;
import com.iast.agent.plugin.MethodContext;

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
 *   <li>{@code value}：必填，对照值；op=matches 时是 regex（编译期 {@code Pattern.compile}）</li>
 *   <li>{@code negate}：可选，默认 false；true 时把比较结果取反</li>
 * </ul>
 *
 * <p>语义：expr 求值结果 null 当作空字符串；运行期任何异常 → 视作 false（首次警告一次）。
 */
public final class Predicate {

    public enum Op { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final String source;       // 原始 yaml 描述，给日志用
    private final Expression expr;
    private final Op op;
    private final String value;
    private final Pattern regex;       // 仅 op==MATCHES 用
    private final boolean negate;

    private Predicate(String source, Expression expr, Op op, String value, Pattern regex, boolean negate) {
        this.source = source;
        this.expr = expr;
        this.op = op;
        this.value = value;
        this.regex = regex;
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
        String value = String.valueOf(valObj);
        Pattern regex = null;
        if (op == Op.MATCHES) {
            try {
                regex = Pattern.compile(value);
            } catch (PatternSyntaxException pse) {
                throw new IllegalArgumentException("invalid regex in predicate value: " + value, pse);
            }
        }
        boolean negate = (negObj instanceof Boolean) && (Boolean) negObj;
        String source = "{expr=" + exprObj + ", op=" + opObj + ", value=" + value
                + (negate ? ", negate=true" : "") + "}";
        return new Predicate(source, compiledExpr, op, value, regex, negate);
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
     */
    public boolean test(MethodContext ctx) {
        boolean raw;
        try {
            Object v = expr.eval(ctx);
            String s = (v == null) ? "" : String.valueOf(v);
            switch (op) {
                case EQUALS:      raw = s.equals(value); break;
                case CONTAINS:    raw = s.contains(value); break;
                case STARTS_WITH: raw = s.startsWith(value); break;
                case ENDS_WITH:   raw = s.endsWith(value); break;
                case MATCHES:     raw = regex.matcher(s).find(); break;
                default:          raw = false;
            }
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
}
