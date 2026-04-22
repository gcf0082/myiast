package com.iast.agent.plugin;

import com.iast.agent.LogWriter;
import com.iast.agent.plugin.event.EventWriter;
import com.iast.agent.plugin.event.Expression;
import com.iast.agent.plugin.event.JsonWriter;
import com.iast.agent.plugin.event.Predicate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用事件插件
 * 按YAML pluginConfig声明的id/my_params/event/event_type/event_level 提取参数并以JSONL写入事件日志
 */
public class CustomEventPlugin implements IastPlugin {

    private static final class EventDef {
        String id;             // 事件 id（出现在 JSONL 的 "id" 字段；老规矩 = className.methodName 或 user-override）
        String ruleId;         // rules.d 顶层 rule.id；filter.target 用这个做匹配
        String className;
        String methodName;
        Map<String, Expression> paramExprs;
        String eventTemplate;
        String eventType;
        String eventLevel;
        EnumSet<MethodContext.CallPhase> phases;
        // filtersDir 关联到本 EventDef 的谓词列表；filterWhen 全过 + filterUnless 全不过才发
        List<Predicate> filterWhen;
        List<Predicate> filterUnless;
    }

    // key: className + "." + methodName
    private final Map<String, EventDef> byMethodId = new HashMap<>();

    @Override
    public void init(Map<String, Object> config) {
        EventWriter.getInstance().init();
        if (config == null) return;
        Object defsObj = config.get("definitions");
        if (!(defsObj instanceof List)) return;

        int ok = 0, fail = 0;
        for (Object d : (List<?>) defsObj) {
            if (!(d instanceof Map)) continue;
            try {
                EventDef def = parseDef((Map<String, Object>) d);
                byMethodId.put(def.className + "." + def.methodName, def);
                ok++;
            } catch (Exception e) {
                fail++;
                LogWriter.getInstance().info("[CustomEventPlugin] bad definition: " + d + " -> " + e.getMessage());
            }
        }
        LogWriter.getInstance().info("[CustomEventPlugin] loaded " + ok + " definition(s), " + fail + " failed");

        // 关联 filtersDir 加载来的过滤器：按 target=ruleId 找匹配的 EventDef 挂上 when/unless
        attachFilters(config.get("filters"));
    }

    /**
     * 把 IastAgent 透过来的 FilterConfig 列表，按 target 与每个 EventDef.id 匹配挂载。
     * 找不到 target 的 filter → WARN+跳过。同一 EventDef 多个 filter → when/unless 列表追加合并。
     */
    private void attachFilters(Object filtersObj) {
        if (!(filtersObj instanceof List)) return;
        // 反向索引：rule.id → 多 EventDef（理论上每个 ruleId 只对应一个 def，但容错）
        Map<String, List<EventDef>> byRuleId = new HashMap<>();
        for (EventDef def : byMethodId.values()) {
            if (def.ruleId == null) continue;
            byRuleId.computeIfAbsent(def.ruleId, k -> new ArrayList<>()).add(def);
        }
        int attached = 0, missed = 0;
        for (Object f : (List<?>) filtersObj) {
            if (!(f instanceof com.iast.agent.config.FilterConfig)) continue;
            com.iast.agent.config.FilterConfig fc = (com.iast.agent.config.FilterConfig) f;
            List<EventDef> targets = byRuleId.get(fc.getTarget());
            if (targets == null || targets.isEmpty()) {
                LogWriter.getInstance().warn("[CustomEventPlugin] filter [id=" + fc.getId()
                        + "] target rule '" + fc.getTarget()
                        + "' not found among CustomEventPlugin rules; skip");
                missed++;
                continue;
            }
            List<Predicate> when = compilePredicateList(fc.getWhen(), fc.getId(), "when");
            List<Predicate> unless = compilePredicateList(fc.getUnless(), fc.getId(), "unless");
            for (EventDef def : targets) {
                if (!when.isEmpty()) {
                    if (def.filterWhen == null) def.filterWhen = new ArrayList<>();
                    def.filterWhen.addAll(when);
                }
                if (!unless.isEmpty()) {
                    if (def.filterUnless == null) def.filterUnless = new ArrayList<>();
                    def.filterUnless.addAll(unless);
                }
                LogWriter.getInstance().info("[CustomEventPlugin] attached filter [id=" + fc.getId()
                        + "] to rule [ruleId=" + def.ruleId + "] (when=" + when.size()
                        + ", unless=" + unless.size() + ")");
                attached++;
            }
        }
        if (attached > 0 || missed > 0) {
            LogWriter.getInstance().info("[CustomEventPlugin] filter attach summary: "
                    + attached + " attached, " + missed + " missed");
        }
    }

    private List<Predicate> compilePredicateList(List<Map<String, Object>> raw, String filterId, String role) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Predicate> out = new ArrayList<>(raw.size());
        for (Map<String, Object> p : raw) {
            try {
                out.add(Predicate.compile(p));
            } catch (Exception e) {
                LogWriter.getInstance().warn("[CustomEventPlugin] filter [id=" + filterId + "] " + role
                        + " predicate skip (compile failed): " + p + " -> " + e.getMessage());
            }
        }
        return out;
    }

    @Override
    public void handleMethodCall(MethodContext ctx) {
        if (ctx == null || ctx.getClassName() == null || ctx.getMethodName() == null) return;
        EventDef def = byMethodId.get(ctx.getClassName() + "." + ctx.getMethodName());
        if (def == null) return;
        if (!def.phases.contains(ctx.getPhase())) return;

        // 1) 求值参数表达式
        Map<String, Object> paramValues = new LinkedHashMap<>();
        if (def.paramExprs != null) {
            for (Map.Entry<String, Expression> e : def.paramExprs.entrySet()) {
                Object v = e.getValue().eval(ctx);
                paramValues.put(e.getKey(), v == null ? null : String.valueOf(v));
            }
        }

        // 1.5) 应用 filter（filtersDir 加载、按 def.id 关联进来）：
        //      任一 unless 命中 → 静默 drop；when 非空且未全过 → 静默 drop。
        //      用户明确不要 dropped 计数，纯 silent。
        if (def.filterUnless != null) {
            for (Predicate p : def.filterUnless) {
                if (p.test(ctx)) return;
            }
        }
        if (def.filterWhen != null && !def.filterWhen.isEmpty()) {
            for (Predicate p : def.filterWhen) {
                if (!p.test(ctx)) return;
            }
        }

        // 2) 模板渲染 "{key}" → value
        String rendered = renderTemplate(def.eventTemplate, paramValues);

        // 3) 组装事件对象（顺序稳定，便于grep和人工阅读）
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("ts", Instant.now().toString());
        evt.put("id", def.id);
        evt.put("event", rendered);
        evt.put("event_type", def.eventType);
        evt.put("event_level", def.eventLevel);
        evt.put("phase", ctx.getPhase() == null ? null : ctx.getPhase().name().toLowerCase());

        // requestId 由 RequestIdPlugin 写入 RequestIdHolder（全局 ThreadLocal），
        // 任何插件都能通过 RequestIdHolder.get() 直接访问；这里默认总是写入事件，
        // 没有请求上下文时为 null，保持字段稳定便于下游消费。
        String requestId = RequestIdHolder.get();
        if (requestId == null) requestId = ctx.getRequestId();
        evt.put("requestId", requestId);
        // x-seeker-request-id 与 requestId 值相同，但用 HTTP 头形式的字段名方便下游 grep /
        // Filebeat 模板按字面 header key 提取（与响应头 X-Seeker-Request-Id 对齐）
        evt.put("x-seeker-request-id", requestId);
        // forward_req_id 是上游 x-seeker-forward-req-id 头透进来的链，由 RequestIdPlugin
        // 入口采集到 IastContext。本字段总是写入（无值时为 null），便于消费侧统一字段集。
        Object forwardReqId = IastContext.getAttribute(RequestIdPlugin.ATTR_FORWARD_REQ_ID);
        evt.put("forward_req_id", forwardReqId);

        evt.put("callId", ctx.getCallId());
        evt.put("className", ctx.getClassName());
        evt.put("methodName", ctx.getMethodName());
        if (ctx.getThreadName() != null) evt.put("thread", ctx.getThreadName());
        evt.put("params", paramValues);

        // 4) 写JSONL
        EventWriter.getInstance().writeEvent(JsonWriter.toJson(evt));
    }

    @Override
    public void destroy() {
        byMethodId.clear();
    }

    @Override
    public String getName() {
        return "CustomEventPlugin";
    }

    // -------------- helpers --------------

    @SuppressWarnings("unchecked")
    private EventDef parseDef(Map<String, Object> m) {
        EventDef def = new EventDef();
        def.id = strOr(m.get("id"), null);
        def.ruleId = strOr(m.get("ruleId"), null);   // 由 MonitorConfig.applyRule 透过来；filter.target 匹配它
        def.eventTemplate = strOr(m.get("event"), "");
        def.eventType = strOr(m.get("event_type"), "custom");
        def.eventLevel = strOr(m.get("event_level"), "info");

        // className / methodName 优先从显式字段拿（applyRule 总会 putIfAbsent 进去）；
        // id 仅作为人读标签，不再用作派生 —— 否则用户用 "file.io.File" 这种点分友好 id
        // 会被误解析成 className="file.io" methodName="File"，运行期路由 miss 不发事件。
        String className = strOr(m.get("className"), null);
        String methodName = null;
        List<String> methods = (List<String>) m.get("methods");
        if (methods != null && !methods.isEmpty()) {
            String first = methods.get(0);
            int hash = first.indexOf('#');
            methodName = hash > 0 ? first.substring(0, hash) : first;
        }
        // 兜底：极端老用法 className/methods 都没的话，按老规矩从 def.id 解析
        if (className == null && def.id != null && !def.id.isEmpty()) {
            int parenIdx = def.id.indexOf('(');
            int searchEnd = parenIdx > 0 ? parenIdx : def.id.length();
            int lastDot = def.id.lastIndexOf('.', searchEnd - 1);
            if (lastDot > 0) {
                className = def.id.substring(0, lastDot);
                methodName = def.id.substring(lastDot + 1, searchEnd);
            }
        }
        if (className == null || methodName == null) {
            throw new IllegalArgumentException("cannot derive className.methodName from definition");
        }
        def.className = className;
        def.methodName = methodName;
        if (def.id == null) {
            def.id = className + "." + methodName;
        }

        // 参数表达式
        def.paramExprs = new LinkedHashMap<>();
        Object paramsObj = m.get("my_params");
        if (paramsObj instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) paramsObj).entrySet()) {
                String name = String.valueOf(e.getKey());
                String expr = String.valueOf(e.getValue());
                try {
                    def.paramExprs.put(name, Expression.compile(expr));
                } catch (Exception ex) {
                    LogWriter.getInstance().info("[CustomEventPlugin] compile expression failed for '"
                            + name + "'='" + expr + "': " + ex.getMessage());
                }
            }
        }

        // 阶段
        def.phases = parsePhases(m.get("on"));
        return def;
    }

    private static EnumSet<MethodContext.CallPhase> parsePhases(Object o) {
        EnumSet<MethodContext.CallPhase> set = EnumSet.noneOf(MethodContext.CallPhase.class);
        List<String> tokens;
        if (o instanceof List) {
            tokens = new ArrayList<>();
            for (Object e : (List<?>) o) tokens.add(String.valueOf(e));
        } else if (o instanceof String) {
            tokens = Collections.singletonList((String) o);
        } else {
            tokens = Collections.emptyList();
        }
        for (String t : tokens) {
            switch (t.trim().toLowerCase()) {
                case "enter":     set.add(MethodContext.CallPhase.ENTER); break;
                case "exit":
                case "return":    set.add(MethodContext.CallPhase.EXIT); break;
                case "exception": set.add(MethodContext.CallPhase.EXCEPTION); break;
                default: break;
            }
        }
        if (set.isEmpty()) set.add(MethodContext.CallPhase.ENTER);
        return set;
    }

    private static String renderTemplate(String tmpl, Map<String, Object> vals) {
        if (tmpl == null || tmpl.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(tmpl.length() + 32);
        int i = 0, len = tmpl.length();
        while (i < len) {
            char c = tmpl.charAt(i);
            if (c == '{') {
                int end = tmpl.indexOf('}', i + 1);
                if (end > i) {
                    String key = tmpl.substring(i + 1, end).trim();
                    Object v = vals.get(key);
                    sb.append(v == null ? "null" : String.valueOf(v));
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String strOr(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }
}
