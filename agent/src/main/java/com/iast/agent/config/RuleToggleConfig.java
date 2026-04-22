package com.iast.agent.config;

/**
 * 规则启停开关条目（main yaml 里 monitor.default.ruleToggles 的元素）。
 *
 * <h3>YAML 形状</h3>
 * <pre>
 * monitor:
 *   default:
 *     rulesDir: ./rules.d
 *     ruleToggles:
 *       - path: default/command           # 目录前缀（无 .yaml/.yml 后缀）
 *         mode: enable
 *       - path: default/file              # 关掉整个 default/file/
 *         mode: disable
 *       - path: default/file/keep.yaml    # 但保留这一个具体文件
 *         mode: enable
 * </pre>
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@code path}：相对 rulesDir 的路径；以 / 分隔（跨平台一致）；以 .yaml/.yml 结尾视为
 *       文件，否则视为目录前缀（自动覆盖该目录下所有子文件）</li>
 *   <li>{@code mode}：{@code enable} | {@code disable}；缺省视为 {@code enable}</li>
 *   <li>多条匹配同一文件 → <b>最长 path 胜出</b>（最具体原则；目录开关 + 个别文件覆盖最常见）</li>
 *   <li>没有任何 toggle 命中 → 默认 enable（零配置 = 现状）</li>
 * </ul>
 */
public class RuleToggleConfig {

    private String path;
    private String mode;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
