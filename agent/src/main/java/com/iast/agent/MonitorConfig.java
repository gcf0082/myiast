package com.iast.agent;

import com.iast.agent.config.MonitorRuleConfig;
import com.iast.agent.config.OutputConfig;
import com.iast.agent.config.YamlRootConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 监控配置管理类
 * 支持从外部配置文件加载监控规则
 */
public class MonitorConfig {
    private static final Map<String, List<MethodRule>> monitorRules = new HashMap<>();
    // 类名 -> 插件名称列表（支持同一个方法挂多个插件，按YAML声明顺序依次调用）
    private static final Map<String, List<String>> classPluginMap = new HashMap<>();
    // 插件名 -> 该插件的所有规则配置块（已附带className/methods信息）
    private static final Map<String, List<Map<String, Object>>> pluginConfigs = new HashMap<>();
    // internal className -> matchType（"exact" 或 "interface"），默认缺省视为 "exact"
    private static final Map<String, String> classMatchType = new HashMap<>();
    // internal className -> 是否启用 ServletBody 包装（仅对 HttpServlet.service 规则有意义）
    private static final Map<String, Boolean> classWrapServletRequest = new HashMap<>();
    // 全局开关：matchType=interface 时，是否把安装后新加载的实现类也纳入监控
    private static volatile boolean includeFutureClasses = false;
    // premain 模式下字节码 install 的延迟毫秒数，默认 1 分钟
    private static volatile long premainDelayMs = 60_000L;
    // 外部插件目录（存放 plugin jar）。空字符串 = 不加载外部插件
    private static volatile String pluginsDir = "";
    // 规则目录（每个 yaml 文件 multi-doc 规则）。空字符串 = 不从目录加载
    private static volatile String rulesDir = "";
    // 过滤器目录（每个 yaml 文件 multi-doc 过滤器）。空字符串 = 不加载任何过滤器
    private static volatile String filtersDir = "";
    // 加载完的过滤器原始定义（IastAgent.initPlugins 时塞进每个插件的 init config）
    private static final List<com.iast.agent.config.FilterConfig> filterDefs = new ArrayList<>();
    // 规则启停开关（main yaml 里 monitor.default.ruleToggles）。按 path 长度降序排，
    // 查找时第一个匹配即胜出（"最具体路径胜出"语义）。
    private static final List<com.iast.agent.config.RuleToggleConfig> ruleToggles = new ArrayList<>();
    // internal className -> 该类下所有规则的 id 列表（id 缺省时该 rule 不计入；CLI rules 命令展示用）
    private static final Map<String, List<String>> classRuleIds = new HashMap<>();
    private static final String DEFAULT_YAML_CONFIG_PATH = "iast-monitor.yaml";
    private static final String DEFAULT_PROPERTIES_CONFIG_PATH = "iast-monitor.properties";
    private static String configFilePath = DEFAULT_YAML_CONFIG_PATH;

    /** CLI 命令要用（status 打印当前生效的配置文件路径）；init() 执行过后才有意义。 */
    public static String getConfigFilePath() {
        return configFilePath;
    }

    /** 当前生效的 instanceName（已做 ${VAR} 展开 + 空兜底）。init() 执行过后才有意义；
     *  给 EvalContext bind 用，供 Expression 的 context.instanceName 读。 */
    private static volatile String resolvedInstanceName = "";
    public static String getResolvedInstanceName() { return resolvedInstanceName; }

    // 输出控制选项
    private static boolean outputArgs = true;
    private static boolean outputReturn = true;
    private static boolean outputStacktrace = true;
    private static int stacktraceDepth = 8;

    static {
        // 默认兜底配置
        addDefaultRule();
    }

    /**
     * 初始化配置，支持从agent参数指定配置文件路径
     */
    public static void init(String agentArgs) {
        // 解析agent参数，支持指定config路径
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                if (arg.startsWith("config=")) {
                    configFilePath = arg.substring("config=".length()).trim();
                    break;
                }
            }
        }
        
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    private static void loadConfig() {
        File configFile = new File(configFilePath);
        // 如果是默认配置路径，yaml不存在则尝试找properties配置
        if (!configFile.exists() && DEFAULT_YAML_CONFIG_PATH.equals(configFilePath)) {
            File propertiesConfig = new File(DEFAULT_PROPERTIES_CONFIG_PATH);
            if (propertiesConfig.exists()) {
                configFilePath = DEFAULT_PROPERTIES_CONFIG_PATH;
                configFile = propertiesConfig;
                LogWriter.getInstance().info("[IAST Agent] Use default properties config: " + DEFAULT_PROPERTIES_CONFIG_PATH);
            }
        }
        if (!configFile.exists()) {
            LogWriter.getInstance().info("[IAST Agent] Config file not found at " + configFilePath + ", using default rules");
            return;
        }

        try (InputStream is = new FileInputStream(configFile)) {
            // 清空所有 in-memory 集合，让 reload 回到干净状态。
            // 这些集合都在 loadConfig / processYamlConfig / loadFiltersDir 里 put / add，
            // 不清就会 accumulate——典型症状：删除 filter / rule 后 reload，老条目仍在生效。
            monitorRules.clear();
            classPluginMap.clear();
            pluginConfigs.clear();
            classMatchType.clear();
            classWrapServletRequest.clear();
            classRuleIds.clear();
            filterDefs.clear();
            // ruleToggles 在 processYamlConfig 的 main yaml 解析段里会 clear + 重填，这里保持不动

            // 判断配置文件类型：yaml/yml还是properties
            if (configFilePath.toLowerCase().endsWith(".yaml") || configFilePath.toLowerCase().endsWith(".yml")) {
                // 加载YAML格式配置
                Yaml yaml = new Yaml();
                YamlRootConfig rootConfig = yaml.loadAs(is, YamlRootConfig.class);
                processYamlConfig(rootConfig);
            } else {
                // 加载Properties格式配置（兼容旧版本）
                Properties props = new Properties();
                props.load(is);
                processPropertiesConfig(props);
            }
            
            if (monitorRules.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] No valid monitor rules found in config, using default rules");
                addDefaultRule();
            }
        } catch (Exception e) {
            // yaml/properties 解析失败：ERROR + 堆栈；运维排"为什么规则没生效"时必须第一眼看到原因
            LogWriter.getInstance().error("[IAST Agent] Failed to load config file " + configFilePath
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            // 不加载任何规则——坏配置下"默默给个默认规则挂上"会掩盖问题（业务还以为自己 yaml 生效了）。
            // 宁可让用户在日志里立刻看到 "rules empty, agent essentially a no-op"，也不沉默兜底。
            // 清掉可能部分 populate 的 state，确保 agent 不挂任何 advice。
            monitorRules.clear();
            classPluginMap.clear();
            pluginConfigs.clear();
            classMatchType.clear();
            classWrapServletRequest.clear();
            classRuleIds.clear();
            filterDefs.clear();
        }
    }

    /**
     * 处理YAML格式配置
     */
    private static void processYamlConfig(YamlRootConfig rootConfig) {
        if (rootConfig == null) {
            return;
        }

        // 解析输出配置
        OutputConfig outputConfig = rootConfig.getOutput();
        if (outputConfig != null) {
            outputArgs = outputConfig.isArgs();
            outputReturn = outputConfig.isReturn();
            outputStacktrace = outputConfig.isStacktrace();
            stacktraceDepth = outputConfig.getStacktraceDepth();

            // 路径布局：<outputDir>/<instanceName>/iast.{log,jsonl}
            //   - outputDir 未配 → /tmp（与 LogWriter/EventWriter 默认一致）
            //   - instanceName 未配 / 解析后空 → 兜底 iast_<pid>（多 JVM 默认隔离；
            //                                       前缀 iast_ 避免在 outputDir 下出现纯数字目录）
            //   - instanceName 支持 ${VAR} 引环境变量
            // 始终运行（无任何配置时也跑），确保 yaml 解析后路径是统一布局。
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String pidInst = "iast_" + pid;
            String baseDir;
            String od = outputConfig.getOutputDir();
            if (od != null && !od.trim().isEmpty()) {
                String resolved = od.trim();
                if (!new File(resolved).isAbsolute()) {
                    File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                    if (cfgParent != null) resolved = new File(cfgParent, resolved).getAbsolutePath();
                }
                baseDir = resolved;
            } else {
                baseDir = "/tmp";
            }

            String inst = outputConfig.getInstanceName();
            String resolvedInst;
            if (inst == null || inst.trim().isEmpty()) {
                resolvedInst = pidInst;
            } else {
                resolvedInst = expandEnvVars(inst.trim());
                if (resolvedInst.isEmpty()) {
                    LogWriter.getInstance().warn(
                            "[IAST Agent] instanceName resolved to empty, fallback to: " + pidInst);
                    resolvedInst = pidInst;
                }
            }

            resolvedInstanceName = resolvedInst;
            File dir = new File(baseDir, resolvedInst);
            if (!dir.exists() && !dir.mkdirs()) {
                LogWriter.getInstance().warn("[IAST Agent] mkdir failed: "
                        + dir.getAbsolutePath() + " (keep current path)");
            } else {
                LogWriter.getInstance().setLogPath(
                        new File(dir, "iast.log").getAbsolutePath());
                if (outputConfig.getEventsPath() == null || outputConfig.getEventsPath().isEmpty()) {
                    com.iast.agent.plugin.event.EventWriter.getInstance().setEventsPath(
                            new File(dir, "iast.jsonl").getAbsolutePath());
                }
                String fromHint = (inst != null && !inst.equals(resolvedInst))
                        ? ", from '" + inst + "'" : "";
                LogWriter.getInstance().info("[IAST Agent] output dir: "
                        + dir.getAbsolutePath() + " (instanceName=" + resolvedInst + fromHint + ")");
            }

            if (outputConfig.getEventsPath() != null && !outputConfig.getEventsPath().isEmpty()) {
                // 全路径 eventsPath 覆盖 outputDir/instanceName 推算出来的 events 路径
                com.iast.agent.plugin.event.EventWriter.getInstance().setEventsPath(outputConfig.getEventsPath());
            }
            // 日志级别从 yaml 应用——本调用要早于"Loaded monitor rule"等后续 info 日志，
            // 这样若用户配 logLevel: debug，整个 init 阶段的 debug 日志也能被记录。
            if (outputConfig.getLogLevel() != null && !outputConfig.getLogLevel().isEmpty()) {
                LogWriter.getInstance().setLevel(outputConfig.getLogLevel());
            }
            // 滚动参数：log + jsonl 共用同一对字段
            LogWriter.getInstance().configure(outputConfig.getMaxFiles(), outputConfig.getMaxFileSizeMb());
            com.iast.agent.plugin.event.EventWriter.getInstance().configure(
                    outputConfig.getMaxFiles(), outputConfig.getMaxFileSizeMb());
        }

        // 解析全局 default 配置
        if (rootConfig.getMonitor() != null && rootConfig.getMonitor().getDefault() != null) {
            com.iast.agent.config.MonitorDefaultConfig defCfg = rootConfig.getMonitor().getDefault();
            includeFutureClasses = defCfg.isIncludeFutureClasses();
            if (includeFutureClasses) {
                LogWriter.getInstance().info("[IAST Agent] includeFutureClasses=true: interface rules will also match classes loaded after agent install");
            }
            long d = defCfg.getPremainDelayMs();
            premainDelayMs = Math.max(0L, d);
            if (premainDelayMs != 60_000L) {
                LogWriter.getInstance().info("[IAST Agent] premainDelayMs override: " + premainDelayMs + "ms");
            }
            String pd = defCfg.getPluginsDir();
            pluginsDir = pd == null ? "" : pd.trim();
            // 相对路径按**本 yaml 文件所在目录**解析，不是 JVM 的 CWD——避免被业务进程启动目录
            // 坑了（尤其是 agentmain attach 场景下调用方的 CWD 常常是控制台当前目录）
            if (!pluginsDir.isEmpty() && !new File(pluginsDir).isAbsolute()) {
                File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                if (cfgParent != null) {
                    pluginsDir = new File(cfgParent, pluginsDir).getAbsolutePath();
                }
            }
            if (!pluginsDir.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] pluginsDir: " + pluginsDir);
            }

            // rulesDir：和 pluginsDir 同一套相对路径解析
            String rd = defCfg.getRulesDir();
            rulesDir = rd == null ? "" : rd.trim();
            if (!rulesDir.isEmpty() && !new File(rulesDir).isAbsolute()) {
                File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                if (cfgParent != null) {
                    rulesDir = new File(cfgParent, rulesDir).getAbsolutePath();
                }
            }
            if (!rulesDir.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] rulesDir: " + rulesDir);
            }

            // filtersDir：和 rulesDir 同套相对路径解析
            String fd = defCfg.getFiltersDir();
            filtersDir = fd == null ? "" : fd.trim();
            if (!filtersDir.isEmpty() && !new File(filtersDir).isAbsolute()) {
                File cfgParent = new File(configFilePath).getAbsoluteFile().getParentFile();
                if (cfgParent != null) {
                    filtersDir = new File(cfgParent, filtersDir).getAbsolutePath();
                }
            }
            if (!filtersDir.isEmpty()) {
                LogWriter.getInstance().info("[IAST Agent] filtersDir: " + filtersDir);
            }

            // ruleToggles：规则启停开关。排序保证 "最长 path 胜出"（最具体优先）的查找便利
            ruleToggles.clear();
            List<com.iast.agent.config.RuleToggleConfig> toggles = defCfg.getRuleToggles();
            if (toggles != null) {
                for (com.iast.agent.config.RuleToggleConfig t : toggles) {
                    if (t == null || t.getPath() == null || t.getPath().trim().isEmpty()) {
                        LogWriter.getInstance().warn("[IAST Agent] ruleToggle missing path; skip");
                        continue;
                    }
                    String p = t.getPath().trim().replace('\\', '/');
                    while (p.startsWith("/")) p = p.substring(1);
                    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                    String mode = t.getMode() == null ? "enable" : t.getMode().trim().toLowerCase();
                    if (!"enable".equals(mode) && !"disable".equals(mode)) {
                        LogWriter.getInstance().warn("[IAST Agent] ruleToggle path=" + p
                                + " unknown mode '" + t.getMode() + "' (expected enable/disable); treat as enable");
                        mode = "enable";
                    }
                    com.iast.agent.config.RuleToggleConfig norm = new com.iast.agent.config.RuleToggleConfig();
                    norm.setPath(p);
                    norm.setMode(mode);
                    ruleToggles.add(norm);
                    LogWriter.getInstance().info("[IAST Agent] ruleToggle: " + mode + " " + p);
                }
                ruleToggles.sort((a, b) -> Integer.compare(b.getPath().length(), a.getPath().length()));
            }
        }

        // inline monitor.rules: 已废弃。检测到老 yaml 仍有该节就 WARN，但不解析。
        if (rootConfig.getMonitor() != null && rootConfig.getMonitor().getRules() != null
                && !rootConfig.getMonitor().getRules().isEmpty()) {
            LogWriter.getInstance().warn("[IAST Agent] inline 'monitor.rules:' is no longer supported "
                    + "(found " + rootConfig.getMonitor().getRules().size() + " rule(s)); "
                    + "move them to monitor.default.rulesDir directory (one rule per yaml doc, multi-doc with '---')");
        }

        // 从 rulesDir 加载所有规则
        if (!rulesDir.isEmpty()) {
            loadRulesDir(rulesDir);
        }

        // 从 filtersDir 加载所有过滤器（仅作 raw FilterConfig 列表暂存，由 IastAgent.initPlugins
        // 在注册插件时塞进 init config，再由 CustomEventPlugin.init 关联到对应 EventDef）
        if (!filtersDir.isEmpty()) {
            loadFiltersDir(filtersDir);
        }
    }

    /**
     * 决策：相对 rulesDir 的某个文件 path 是否被启用？
     * 在已按 path 长度降序排好的 ruleToggles 里找第一个匹配（最具体路径胜出）：
     * - toggle.path 以 .yaml/.yml 结尾视为文件，必须与 filePath 精确相等
     * - 否则视为目录前缀，匹配 filePath == path 或 filePath 以 path + "/" 开头
     * 都不匹配 → 默认 enable（零配置 = 全部启用）。
     */
    private static boolean isRuleFileEnabled(String filePath) {
        for (com.iast.agent.config.RuleToggleConfig t : ruleToggles) {
            String tp = t.getPath();
            String tpLow = tp.toLowerCase();
            boolean isFileToggle = tpLow.endsWith(".yaml") || tpLow.endsWith(".yml");
            boolean match;
            if (isFileToggle) {
                match = filePath.equals(tp);
            } else {
                match = filePath.equals(tp) || filePath.startsWith(tp + "/");
            }
            if (match) {
                return "enable".equals(t.getMode());
            }
        }
        return true;  // 默认启用
    }

    /**
     * 递归扫一个目录，加载所有 *.yaml/*.yml 里的规则（multi-doc，用 --- 分割每条规则）。
     * 子目录里的规则也加载，深度无限制（symlink 循环用 canonical-path Set 防住）。
     * 文件按**相对 rulesDir 的路径**字典序处理；某个文件 IO/解析失败会 WARN+继续。
     * 不对 `.` 隐藏文件/目录做特殊处理——所有 yaml 都加载。
     */
    private static void loadRulesDir(String absDirPath) {
        File dir = new File(absDirPath);
        if (!dir.exists()) {
            LogWriter.getInstance().warn("[IAST Agent] rulesDir does not exist: " + absDirPath);
            return;
        }
        if (!dir.isDirectory()) {
            LogWriter.getInstance().warn("[IAST Agent] rulesDir is not a directory: " + absDirPath);
            return;
        }
        List<File> files = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        walkRules(dir, files, visited);
        if (files.isEmpty()) {
            LogWriter.getInstance().info("[IAST Agent] no *.yaml/*.yml found under " + absDirPath + " (recursive)");
            return;
        }
        // 按相对 rulesDir 的路径字典序排，多次启动顺序稳定
        final String rootCanon;
        try { rootCanon = dir.getCanonicalPath(); }
        catch (IOException e) {
            LogWriter.getInstance().warn("[IAST Agent] cannot canonicalize rulesDir: " + e.getMessage());
            return;
        }
        files.sort((a, b) -> relPath(a, rootCanon).compareTo(relPath(b, rootCanon)));

        for (File f : files) {
            String origin = relPath(f, rootCanon);
            // 应用 ruleToggles：被 disable 的文件直接跳过，连解析都省了
            if (!isRuleFileEnabled(origin)) {
                LogWriter.getInstance().info("[IAST Agent] Skipped rule file (toggle disable): " + origin);
                continue;
            }
            int loaded = 0;
            try (InputStream in = new FileInputStream(f)) {
                // 用 typed Constructor —— 关键：避免 SnakeYAML 把 `on:`/`yes:`/`no:` 这类 YAML 1.1 关键字
                // 在 Map key 位置自动 resolve 成 Boolean。typed 路径下 pluginConfig 的 key 保持 String。
                Yaml y = new Yaml(new org.yaml.snakeyaml.constructor.Constructor(
                        MonitorRuleConfig.class, new org.yaml.snakeyaml.LoaderOptions()));
                for (Object doc : y.loadAll(in)) {
                    if (doc == null) continue;  // 空 doc（典型为文件头/尾的空 ---）
                    if (!(doc instanceof MonitorRuleConfig)) {
                        LogWriter.getInstance().warn("[IAST Agent] rule doc in " + origin
                                + " is not a rule (got " + doc.getClass().getSimpleName() + "); skip");
                        continue;
                    }
                    MonitorRuleConfig rule = (MonitorRuleConfig) doc;
                    if (rule.getClassName() == null || rule.getClassName().isEmpty()
                            || rule.getMethods() == null || rule.getMethods().isEmpty()) {
                        LogWriter.getInstance().warn("[IAST Agent] rule [id=" + rule.getId() + "] in "
                                + origin + " missing required fields (className/methods); skip");
                        continue;
                    }
                    applyRule(rule, origin);
                    loaded++;
                }
            } catch (Exception e) {
                LogWriter.getInstance().warn("[IAST Agent] failed to load rules from " + origin
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            LogWriter.getInstance().info("[IAST Agent] Loaded " + loaded + " rule(s) from " + origin);
        }
    }

    /**
     * 递归收集 dir 及其所有子目录下的 *.yaml/*.yml 文件到 out。
     * visited 存 canonical path，防 symlink 死循环；listFiles 拿不到（无权限）就静默跳过。
     */
    private static void walkRules(File dir, List<File> out, Set<String> visited) {
        String canon;
        try { canon = dir.getCanonicalPath(); }
        catch (IOException e) { return; }
        if (!visited.add(canon)) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File e : entries) {
            if (e.isDirectory()) {
                walkRules(e, out, visited);
            } else if (e.isFile()) {
                String low = e.getName().toLowerCase();
                if (low.endsWith(".yaml") || low.endsWith(".yml")) out.add(e);
            }
        }
    }

    /** 计算 f 相对 rootCanonical 的相对路径（用 / 分隔，跨平台读起来一致）；算不出回退 file 名。 */
    private static String relPath(File f, String rootCanonical) {
        try {
            String fc = f.getCanonicalPath();
            if (fc.startsWith(rootCanonical)) {
                String rel = fc.substring(rootCanonical.length());
                if (rel.startsWith(File.separator)) rel = rel.substring(File.separator.length());
                return rel.replace(File.separatorChar, '/');
            }
        } catch (IOException ignore) {}
        return f.getName();
    }

    /**
     * 递归扫 filtersDir 下所有 *.yaml/*.yml，typed Constructor(FilterConfig.class) loadAll，
     * 把 FilterConfig 暂存到 {@link #filterDefs}。和 rulesDir 的加载约定一致：
     * 子目录递归、按相对路径字典序、单文件失败 WARN+继续、symlink 用 canonical-path Set 防循环。
     * 关联到具体 rule 的工作留给 IastAgent / CustomEventPlugin。
     */
    private static void loadFiltersDir(String absDirPath) {
        File dir = new File(absDirPath);
        if (!dir.exists()) {
            LogWriter.getInstance().warn("[IAST Agent] filtersDir does not exist: " + absDirPath);
            return;
        }
        if (!dir.isDirectory()) {
            LogWriter.getInstance().warn("[IAST Agent] filtersDir is not a directory: " + absDirPath);
            return;
        }
        List<File> files = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        walkRules(dir, files, visited);  // 同套递归 walker
        if (files.isEmpty()) {
            LogWriter.getInstance().info("[IAST Agent] no *.yaml/*.yml found under " + absDirPath + " (recursive)");
            return;
        }
        final String rootCanon;
        try { rootCanon = dir.getCanonicalPath(); }
        catch (IOException e) {
            LogWriter.getInstance().warn("[IAST Agent] cannot canonicalize filtersDir: " + e.getMessage());
            return;
        }
        files.sort((a, b) -> relPath(a, rootCanon).compareTo(relPath(b, rootCanon)));

        for (File f : files) {
            String origin = relPath(f, rootCanon);
            int loaded = 0;
            try (InputStream in = new FileInputStream(f)) {
                Yaml y = new Yaml(new org.yaml.snakeyaml.constructor.Constructor(
                        com.iast.agent.config.FilterConfig.class, new org.yaml.snakeyaml.LoaderOptions()));
                for (Object doc : y.loadAll(in)) {
                    if (doc == null) continue;
                    if (!(doc instanceof com.iast.agent.config.FilterConfig)) {
                        LogWriter.getInstance().warn("[IAST Agent] filter doc in " + origin
                                + " is not a filter (got " + doc.getClass().getSimpleName() + "); skip");
                        continue;
                    }
                    com.iast.agent.config.FilterConfig fc = (com.iast.agent.config.FilterConfig) doc;
                    if (fc.getTarget() == null || fc.getTarget().isEmpty()) {
                        LogWriter.getInstance().warn("[IAST Agent] filter [id=" + fc.getId() + "] in "
                                + origin + " missing required field 'target' (rule id); skip");
                        continue;
                    }
                    filterDefs.add(fc);
                    loaded++;
                    LogWriter.getInstance().info("[IAST Agent] Loaded filter: [id=" + fc.getId()
                            + "] target=" + fc.getTarget() + " (from: " + origin + ")");
                }
            } catch (Exception e) {
                LogWriter.getInstance().warn("[IAST Agent] failed to load filters from " + origin
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            LogWriter.getInstance().info("[IAST Agent] Loaded " + loaded + " filter(s) from " + origin);
        }
    }

    /**
     * 把一条 rule 合到全局状态里（monitorRules / classPluginMap / classMatchType /
     * classWrapServletRequest / pluginConfigs / classRuleIds）。所有规则源（inline 已废弃，
     * 当前只剩 rulesDir）共用本路径，保证语义一致。
     */
    private static void applyRule(MonitorRuleConfig rule, String origin) {
        String className = rule.getClassName();
        String methodRuleStr = String.join(",", rule.getMethods());
        List<MethodRule> methods = parseMethodRules(methodRuleStr);
        if (methods.isEmpty()) {
            LogWriter.getInstance().warn("[IAST Agent] rule [id=" + rule.getId() + "] in " + origin
                    + " has no parseable methods; skip");
            return;
        }
        String internalClassName = className.replace('.', '/');

        // 同一个类多条规则，合并方法列表（去重）
        List<MethodRule> existing = monitorRules.computeIfAbsent(internalClassName, k -> new ArrayList<>());
        for (MethodRule m : methods) {
            boolean dup = false;
            for (MethodRule e : existing) {
                if (e.getMethodName().equals(m.getMethodName())
                        && e.getDescriptor().equals(m.getDescriptor())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) existing.add(m);
        }

        // 记录 matchType（先声明胜出 + warn 冲突）
        String ruleMatchType = normalizeMatchType(rule.getMatchType());
        String existingMatchType = classMatchType.get(internalClassName);
        if (existingMatchType == null) {
            classMatchType.put(internalClassName, ruleMatchType);
        } else if (!existingMatchType.equals(ruleMatchType)) {
            LogWriter.getInstance().warn("[IAST Agent] conflicting matchType for " + className
                    + " (existing=" + existingMatchType + ", new=" + ruleMatchType + " from " + origin + "), keeping existing");
        }

        // wrapServletRequest 任一为 true → 整个 className 走包装
        if (rule.isWrapServletRequest()) {
            classWrapServletRequest.put(internalClassName, Boolean.TRUE);
        }

        // 插件名（缺省 LogPlugin），按声明顺序累加
        String pluginName = rule.getPlugin();
        if (pluginName == null || pluginName.isEmpty()) {
            pluginName = "LogPlugin";
        }
        List<String> pluginList = classPluginMap.computeIfAbsent(internalClassName, k -> new ArrayList<>());
        if (!pluginList.contains(pluginName)) {
            pluginList.add(pluginName);
        }

        // pluginConfig 块（附带 className/methods/ruleId，给插件 init 用）
        if (rule.getPluginConfig() != null && !rule.getPluginConfig().isEmpty()) {
            Map<String, Object> block = new HashMap<>(rule.getPluginConfig());
            block.putIfAbsent("className", className);
            block.putIfAbsent("methods", rule.getMethods());
            // rule.id 单独放 "ruleId" 而非 "id"——保留 pluginConfig.id（事件 id）的原语义不被覆盖；
            // CustomEventPlugin 用 ruleId 做 filter target 匹配，事件本身的 id 仍按老规矩 auto-derive。
            if (rule.getId() != null) block.putIfAbsent("ruleId", rule.getId());
            pluginConfigs.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(block);
        }

        // id（可选）记到 classRuleIds，给 CLI / 日志展示
        if (rule.getId() != null && !rule.getId().isEmpty()) {
            List<String> ids = classRuleIds.computeIfAbsent(internalClassName, k -> new ArrayList<>());
            if (!ids.contains(rule.getId())) ids.add(rule.getId());
        }

        String idTag = rule.getId() == null ? "" : "[id=" + rule.getId() + "] ";
        LogWriter.getInstance().info("[IAST Agent] Loaded monitor rule: " + idTag
                + className + " -> " + methods + " (plugin: " + pluginName + ", from: " + origin + ")");
    }

    /**
     * 处理Properties格式配置（兼容旧版本）
     */
    private static void processPropertiesConfig(Properties props) {
        // 解析日志路径配置
        String customLogPath = props.getProperty("log.path");
        if (customLogPath != null && !customLogPath.trim().isEmpty()) {
            LogWriter.getInstance().setLogPath(customLogPath.trim());
        }

        // 解析输出控制选项
        outputArgs = getBooleanProperty(props, "output.args", true);
        outputReturn = getBooleanProperty(props, "output.return", true);
        outputStacktrace = getBooleanProperty(props, "output.stacktrace", true);
        stacktraceDepth = getIntProperty(props, "output.stacktrace.depth", 8);

        // 与 yaml 对齐：output.logLevel = debug|info|warn|error
        String level = props.getProperty("output.logLevel");
        if (level != null && !level.trim().isEmpty()) {
            LogWriter.getInstance().setLevel(level.trim());
        }

        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            
            if (key.startsWith("monitor.")) {
                String className = key.substring("monitor.".length()).trim();
                if (className.isEmpty() || value.isEmpty()) {
                    continue;
                }
                
                List<MethodRule> methods = parseMethodRules(value);
                if (!methods.isEmpty()) {
                    monitorRules.put(className.replace('.', '/'), methods); // 转为内部类名格式
                    LogWriter.getInstance().info("[IAST Agent] Loaded monitor rule: " + className + " -> " + methods);
                }
            }
        }
    }

    /**
     * 解析方法规则
     * 格式：方法1名#方法1描述符,方法2名#方法2描述符
     */
    private static List<MethodRule> parseMethodRules(String value) {
        List<MethodRule> rules = new ArrayList<>();
        String[] methodDefs = value.split(",");
        
        for (String methodDef : methodDefs) {
            methodDef = methodDef.trim();
            if (methodDef.isEmpty()) {
                continue;
            }
            
            String[] parts = methodDef.split("#");
            if (parts.length >= 2) {
                String methodName = parts[0].trim();
                String descriptor = parts[1].trim();
                if (!methodName.isEmpty() && !descriptor.isEmpty()) {
                    rules.add(new MethodRule(methodName, descriptor));
                }
            }
        }
        
        return rules;
    }

    /**
     * 添加默认监控规则
     */
    private static void addDefaultRule() {
        List<MethodRule> defaultMethods = new ArrayList<>();
        defaultMethods.add(new MethodRule("exists", "()Z"));
        monitorRules.put("java/io/File", defaultMethods);
        LogWriter.getInstance().info("[IAST Agent] Using default monitor rule: java.io.File.exists()");
    }

    /**
     * 获取所有需要监控的类名（内部格式）
     */
    public static List<String> getMonitoredClasses() {
        return new ArrayList<>(monitorRules.keySet());
    }

    /**
     * 获取指定类的监控方法规则
     */
    public static List<MethodRule> getMethodRules(String internalClassName) {
        return monitorRules.getOrDefault(internalClassName, new ArrayList<>());
    }

    private static final List<String> DEFAULT_PLUGINS = java.util.Collections.singletonList("LogPlugin");

    /**
     * 获取指定类对应的所有插件名称列表
     */
    public static List<String> getPluginNames(String internalClassName) {
        return classPluginMap.getOrDefault(internalClassName, DEFAULT_PLUGINS);
    }

    /**
     * 按声明顺序把方法调用事件分发给该类对应的所有插件
     */
    public static void dispatchToPlugins(String internalClassName, com.iast.agent.plugin.MethodContext context) {
        List<String> names = getPluginNames(internalClassName);
        com.iast.agent.plugin.PluginManager pm = com.iast.agent.plugin.PluginManager.getInstance();
        for (int i = 0; i < names.size(); i++) {
            pm.handleMethodCall(names.get(i), context);
        }
    }

    /**
     * 获取各插件聚合的规则配置列表
     * 用于IastAgent.initPlugins()时传入plugin.init(config)
     */
    public static Map<String, List<Map<String, Object>>> getPluginConfigs() {
        return pluginConfigs;
    }

    /**
     * 全局开关：matchType=interface 时是否也监控安装后新加载的实现类
     */
    public static boolean isIncludeFutureClasses() {
        return includeFutureClasses;
    }

    /**
     * premain 模式下字节码 install 的延迟毫秒数。默认 60_000（1 分钟）；0 表示立即 install。
     * agentmain（attach）模式不使用该值。
     */
    public static long getPremainDelayMs() {
        return premainDelayMs;
    }

    /**
     * 外部插件目录；空字符串表示不加载外部插件。
     */
    public static String getPluginsDir() {
        return pluginsDir;
    }

    /**
     * 查询某个 className（internal 格式）配置的 matchType
     * 未显式配置时返回 "exact"，兼容老规则
     */
    public static String getMatchType(String internalClassName) {
        return classMatchType.getOrDefault(internalClassName, "exact");
    }

    /**
     * 是否对该规则启用 ServletBody 包装（改写 service 入参为缓冲 wrapper）
     */
    public static boolean isWrapServletRequest(String internalClassName) {
        return classWrapServletRequest.getOrDefault(internalClassName, Boolean.FALSE);
    }

    /**
     * 该 className 上配的所有规则 id（缺省 id 的不计入）。给 CLI rules 命令展示用。
     */
    public static List<String> getRuleIds(String internalClassName) {
        return classRuleIds.getOrDefault(internalClassName, java.util.Collections.emptyList());
    }

    /**
     * 返回 filtersDir 加载的所有 FilterConfig 不可变快照。IastAgent.initPlugins 在注册插件时
     * 把它塞进每个插件的 init config（key="filters"），由 CustomEventPlugin 自行消费。
     */
    public static List<com.iast.agent.config.FilterConfig> getFilterDefs() {
        return java.util.Collections.unmodifiableList(filterDefs);
    }

    /**
     * 把接口规则命中到的具体类路由到接口规则声明的插件列表。
     * 在 ByteBuddy transform 阶段调用：advice 的 dispatchToPlugins 以具体类的 internal name 查 classPluginMap，
     * 若接口规则没把具体类挂到 map 里，运行期就会查不到插件导致事件丢失。
     *
     * @param concreteInternalName  具体实现类的 internal name（斜杠形式）
     * @param interfaceInternalName 声明规则的接口/父类 internal name
     */
    public static void linkConcreteToPlugins(String concreteInternalName, String interfaceInternalName) {
        if (concreteInternalName == null || interfaceInternalName == null) {
            return;
        }
        if (concreteInternalName.equals(interfaceInternalName)) {
            return; // 接口/抽象类本身已经在 map 里，无需自链
        }
        List<String> interfacePlugins = classPluginMap.get(interfaceInternalName);
        if (interfacePlugins == null || interfacePlugins.isEmpty()) {
            return;
        }
        List<String> concretePlugins = classPluginMap.computeIfAbsent(concreteInternalName, k -> new ArrayList<>());
        for (String p : interfacePlugins) {
            if (!concretePlugins.contains(p)) {
                concretePlugins.add(p);
            }
        }
    }

    private static String normalizeMatchType(String raw) {
        if (raw == null) return "exact";
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) return "exact";
        if ("interface".equals(v) || "exact".equals(v)) return v;
        LogWriter.getInstance().info("[IAST Agent] Warning: unknown matchType '" + raw + "', falling back to 'exact'");
        return "exact";
    }

    /**
     * 方法规则实体
     */
    public static class MethodRule {
        private final String methodName;
        private final String descriptor;
        private final boolean wildcardDescriptor;

        public MethodRule(String methodName, String descriptor) {
            this.methodName = methodName;
            this.wildcardDescriptor = "*".equals(descriptor);
            this.descriptor = descriptor;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public boolean isWildcardDescriptor() {
            return wildcardDescriptor;
        }

        @Override
        public String toString() {
            return methodName + "#" + descriptor;
        }
    }

    // --- 输出控制 getter ---

    public static boolean isOutputArgs() {
        return outputArgs;
    }

    public static boolean isOutputReturn() {
        return outputReturn;
    }

    public static boolean isOutputStacktrace() {
        return outputStacktrace;
    }

    public static int getStacktraceDepth() {
        return stacktraceDepth;
    }

    // --- 属性解析辅助 ---

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val.trim());
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // --- 环境变量替换 ---

    private static final java.util.regex.Pattern ENV_VAR_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * 把字符串里 ${VAR} 形式的占位替换成对应环境变量值。
     * env 不存在 → WARN + 替换为空串（让调用方决定要不要兜底）。
     * 不支持 ${VAR:-default} 等扩展语法。
     */
    private static String expandEnvVars(String s) {
        if (s == null || s.isEmpty()) return s;
        java.util.regex.Matcher m = ENV_VAR_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer(s.length() + 32);
        while (m.find()) {
            String name = m.group(1);
            String val = System.getenv(name);
            if (val == null) {
                LogWriter.getInstance().warn(
                        "[IAST Agent] env var not set: " + name + " (substituting empty)");
                val = "";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
