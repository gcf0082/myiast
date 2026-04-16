package com.iast.agent.plugin;

import com.iast.agent.LogWriter;
import com.iast.agent.plugin.event.EventWriter;
import com.iast.agent.plugin.event.Expression;
import com.iast.agent.plugin.event.JsonWriter;

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
        String id;
        String className;
        String methodName;
        Map<String, Expression> paramExprs;
        String eventTemplate;
        String eventType;
        String eventLevel;
        EnumSet<MethodContext.CallPhase> phases;
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

        String requestId = RequestIdHolder.get();
        if (requestId == null) requestId = ctx.getRequestId();
        if (requestId != null) evt.put("requestId", requestId);

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
        def.eventTemplate = strOr(m.get("event"), "");
        def.eventType = strOr(m.get("event_type"), "custom");
        def.eventLevel = strOr(m.get("event_level"), "info");

        // 从id解析className + methodName；若无id则回退用className+第一个method
        String className = null, methodName = null;
        if (def.id != null && !def.id.isEmpty()) {
            int parenIdx = def.id.indexOf('(');
            int searchEnd = parenIdx > 0 ? parenIdx : def.id.length();
            int lastDot = def.id.lastIndexOf('.', searchEnd - 1);
            if (lastDot > 0) {
                className = def.id.substring(0, lastDot);
                methodName = def.id.substring(lastDot + 1, searchEnd);
            }
        }
        if (className == null) {
            className = strOr(m.get("className"), null);
            List<String> methods = (List<String>) m.get("methods");
            if (methods != null && !methods.isEmpty()) {
                String first = methods.get(0);
                int hash = first.indexOf('#');
                methodName = hash > 0 ? first.substring(0, hash) : first;
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
