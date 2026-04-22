package com.iast.agent.config;

import java.util.List;
import java.util.Map;

/**
 * 单条事件过滤器实体（filtersDir 下 yaml 一对一映射）。
 *
 * <p>当前消费方是 {@code CustomEventPlugin}：在 init 阶段按 {@code target}（rule id）
 * 找到对应的 EventDef，把 {@code when}/{@code unless} 编译成 Predicate 挂上去。
 *
 * <h3>YAML 形状</h3>
 * <pre>
 * id: filter.quiet-proc-paths        # 可选，给 CLI / 日志展示用
 * target: file.io.File               # 必填，关联的 rule id
 * when:                              # 可选；非空时所有谓词都为真才发（AND）
 *   - { expr: "params[0].toString()", op: endsWith, value: ".jsp" }
 * unless:                            # 可选；任一谓词为真即 drop（OR）
 *   - { expr: "params[0].toString()", op: contains,  value: "/proc/" }
 *   - { expr: "params[0].toString()", op: startsWith, value: "/sys/" }
 * </pre>
 *
 * <p>谓词字段说明见 {@code com.iast.agent.plugin.event.Predicate}。
 */
public class FilterConfig {

    private String id;
    private String target;
    private List<Map<String, Object>> when;
    private List<Map<String, Object>> unless;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public List<Map<String, Object>> getWhen() { return when; }
    public void setWhen(List<Map<String, Object>> when) { this.when = when; }

    public List<Map<String, Object>> getUnless() { return unless; }
    public void setUnless(List<Map<String, Object>> unless) { this.unless = unless; }
}
