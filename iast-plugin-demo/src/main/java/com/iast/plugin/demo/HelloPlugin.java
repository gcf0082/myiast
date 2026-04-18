package com.iast.plugin.demo;

import com.iast.agent.LogWriter;
import com.iast.agent.plugin.IastPlugin;
import com.iast.agent.plugin.MethodContext;
import com.iast.agent.plugin.RequestIdHolder;

import java.util.List;
import java.util.Map;

/**
 * 最小外部插件样例：拦截到方法调用时往 agent 日志里打一行。
 *
 * <p>YAML 使用示例：
 * <pre>
 * monitor:
 *   default:
 *     pluginsDir: /opt/iast/plugins      # 目录里放本 jar
 *   rules:
 *     - className: java.io.File
 *       methods: ["exists#()Z"]
 *       plugin: HelloPlugin               # 要和 getName() 一致
 *       pluginConfig:
 *         prefix: "demo"                  # 可选，覆盖默认前缀
 * </pre>
 */
public class HelloPlugin implements IastPlugin {

    private LogWriter log;
    /** 日志行前缀，默认 "HelloPlugin"，YAML 里可通过 pluginConfig.prefix 覆盖 */
    private String prefix = "HelloPlugin";

    @Override
    @SuppressWarnings("unchecked")
    public void init(Map<String, Object> config) {
        this.log = LogWriter.getInstance();
        // agent 把 YAML 里 pluginConfig 聚合到 config["definitions"]（每条规则一个 map）
        // 任挑一条的 prefix 即可；这是 demo 的做法，生产插件可以按 id/className 分桶
        Object defs = config == null ? null : config.get("definitions");
        if (defs instanceof List) {
            for (Object d : (List<Object>) defs) {
                if (!(d instanceof Map)) continue;
                Object p = ((Map<String, Object>) d).get("prefix");
                if (p instanceof String && !((String) p).isEmpty()) {
                    this.prefix = (String) p;
                    break;
                }
            }
        }
        log.info("[" + prefix + "] initialized (prefix=" + prefix + ")");
    }

    @Override
    public void handleMethodCall(MethodContext ctx) {
        if (ctx.getPhase() != MethodContext.CallPhase.ENTER) return;
        String reqId = RequestIdHolder.get();
        log.info("[" + prefix + "] [callId=" + ctx.getCallId()
                + "] [requestId=" + (reqId == null ? "-" : reqId) + "] "
                + ctx.getClassName() + "." + ctx.getMethodName());
    }

    @Override
    public void destroy() {
        // no-op
    }

    @Override
    public String getName() {
        return "HelloPlugin";
    }
}
