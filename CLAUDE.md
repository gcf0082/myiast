# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库工作时提供指引。

## 构建与发布

这是一个**没有父 POM 的 5 模块 Maven monorepo**——每个模块各自独立 `pom.xml`，必须按依赖顺序构建。`iast-sdk` **必须**第一个 `mvn install` 到本地 `~/.m2`，因为 `agent` 和 `iast-plugin-demo` 都是按普通 Maven 依赖去解析它（不走 reactor 聚合）。

整体构建 + 产出发布 tarball（`dist/iast-agent-<version>.tar.gz`）：
```bash
./dist.sh                          # 用 IAST_VERSION 环境变量；缺省 1.0.0
IAST_VERSION=1.2.3 ./dist.sh
```

手工分模块构建（顺序如下）：
```bash
( cd iast-sdk         && mvn -q install -DskipTests )    # 必须最先
( cd agent            && mvn -q clean package -DskipTests )   # 或 ./agent/build.sh
( cd iast-cli         && mvn -q clean package -DskipTests )
( cd iast-plugin-demo && mvn -q clean package -DskipTests )
( cd demo-spring      && mvn -q clean package -DskipTests )
```

**JDK 版本要求：** 整体构建需要 Java 21（`agent` 和 `demo-spring` 都是 `source/target=21`）。`iast-sdk` 和 `iast-cli` 故意保持 `source/target=1.8`——别动它，这样插件作者和 CLI 用户就能停在 Java 8。

## 测试

**仓库里没有任何 JUnit 测试**。验证全部通过 bash 脚本端到端跑 demo-spring 完成：

```bash
# Premain 模式 + matchType=interface + includeFutureClasses + premainDelayMs（4 个用例）
( cd demo-spring && ./test-interface-match.sh )

# Attach 模式回归：start/stop、requestId、JSONL 事件、反射 hook（7 步）
( cd demo-spring && ./test-monitor-switch.sh )
```

两个脚本都能 layout 自适应——源码仓和解包后的 release tarball（`demo/` 子目录）下都能跑。它们会在 8080 端口起 Spring Boot jar，然后各自在临时目录里按 `output.instanceName` 划定的子目录 grep `iast.log` / `iast.jsonl`。

手动跑 demo：
```bash
( cd demo-spring && ./run-premain.sh )    # premain 模式，Ctrl+C 退出
```

## 高层架构

### 模块职责

| 模块 | 产物 | 谁依赖它 |
|--------|------------------|-------------------|
| `iast-sdk/`         | `iast-sdk.jar` —— `IastPlugin` 接口 + `MethodContext` / `IastContext` / `LogWriter` / `EventWriter` / `Expression` / `JsonWriter`。**插件作者的公共 API 暴露面**。Java 8、零运行时依赖。 | `agent`（shade 进 fat jar）、`iast-plugin-demo`（`provided` scope）|
| `agent/`            | `iast-agent.jar` —— 含 Byte Buddy + ASM + SnakeYAML + JNA 的 fat shaded jar。`Premain-Class` 和 `Agent-Class` **都**指向 `com.iast.agent.IastAgent`；`Main-Class` 是 `AttachTool`（所以 `java -jar iast-agent.jar <pid>` 能自挂载）。 | 终端用户 |
| `iast-cli/`         | `iast-cli.jar` —— 独立的小 WebSocket REPL 客户端（入口：`com.iast.cli.CliClient`）。 | 终端用户 |
| `iast-plugin-demo/` | `iast-plugin-demo.jar` —— 参考外部插件（`HelloPlugin`）。第三方插件作者的模板。 | 仅 demo |
| `demo-spring/`      | `demo-spring-1.0.0.jar` —— 端到端测试用的 Spring Boot 目标应用。 | 仅测试/demo |

### Agent 如何挂载并插桩

`IastAgent.premain`（JVM 启动时）和 `IastAgent.agentmain`（运行时 attach）共享 `startAgent()`。首次 init 之后，后续 `agentmain` 调用变成**控制命令**：`start` / `stop` 翻 `MONITOR_ENABLED` 全局开关；`cli` 启动 WebSocket CLI dialer。`iast-start.sh` / `iast-stop.sh` / `iast-cli-jattach.sh` 都是这么工作的——用不同的 `agentArgs` 重新 attach agent jar。

`AttachTool` 按以下顺序逐个尝试 attach 路径，第一个能用就用（也可以 `-DiastAttach=jdk|jattach|fallback` 强制）：
1. `jdk.attach`（只有 JDK 才有，JRE 没有）
2. PATH 上的 `jattach` 二进制（约 50KB，JRE 也能用）
3. Byte Buddy Agent 自带的兜底路径（Linux/macOS 走 UNIX Socket、Windows 走 JNA-based DLL 注入）

完成配置 + 插件 init 之后，`buildAndInstall()` 构造 Byte Buddy 的 `AgentBuilder`。**premain 模式下默认延迟 `monitor.default.premainDelayMs`（缺省 60s）**才在 daemon 线程里 install，避免给应用启动期增加 hot path 开销。`agentmain` 总是立即 install。

### `matchType: interface` vs `exact`——一个语义陷阱

`iast-monitor.yaml` 里规则缺省 `matchType: exact`（字面类名匹配）。把规则的 `className` 写成接口或抽象类、再设 `matchType: interface`，就让 Byte Buddy 用 `hasSuperType(className)`，展开到**所有具体实现类 + 抽象父类**（保留抽象父类是必要的——像 `HttpServlet.service` 这种方法是继承的、不是 override 的，只 hook 具体子类拦不到）。

配套开关 `monitor.default.includeFutureClasses`（缺省 `false`）控制**安装之后**新加载的类要不要也被 hook。`false` 的时候 agent 在安装瞬间通过 `Instrumentation.getAllLoadedClasses()` 拍一张快照，`NameInSetMatcher` 据此过滤。

**不那么显然的部分：** `matchType: interface` 下，运行期 `MethodContext` 里的 `className` 是**具体实现类**（比如 `org.apache.catalina.servlet.DefaultServlet`），不是 YAML 规则里写的接口名。`CustomEventPlugin` 内部按 `className+methodName` 索引定义来做快路由，所以挂在 interface 规则上时它的事件会**静默不发**。interface 规则下用那种不按 className 筛规则的插件（`RequestIdPlugin`、`ServletBodyPlugin`）。`MonitorConfig.linkConcreteToPlugins(concreteName, interfaceName)` 是其它分发路径能跑通的桥——在 AgentBuilder transformer 的 link 时刻被调。

### 插件分发

`MethodMonitorAdvice` / `ConstructorMonitorAdvice`（`IastAgent` 的内部类）会被 Byte Buddy inline 进目标类。它们构建 `MethodContext`、存到 `ThreadLocal`（让 `onExit` 能拿回 className/methodName），然后调 `MonitorConfig.dispatchToPlugins(internalClassName, ctx)` 遍历 `classPluginMap.get(internalClassName)` 依次调每个插件的 `handleMethodCall`。

**同一方法挂多个插件**是支持的——给同一 `className` 写多条 YAML 规则、`plugin:` 不同就行。**但同一 `(className, methodName)` 上挂两条 `CustomEventPlugin` 会撞**——`CustomEventPlugin` 内部按 `className+methodName` 去重 key，第二条会静默覆盖第一条。

内置插件在 `agent/src/main/java/com/iast/agent/plugin/`：`LogPlugin`、`RequestIdPlugin`、`CustomEventPlugin`、`ServletBodyPlugin`、`HttpForwardPlugin`（出口链路头透传，反射 setter，目标类不在 classpath 时静默不命中）。**外部插件**通过 `monitor.default.pluginsDir` 的 `ServiceLoader` 加载（每个插件 jar 必须有 `META-INF/services/com.iast.agent.plugin.IastPlugin`，列出实现类 FQCN）。`getName()` 和内置撞名的外部插件会被打 WARN 跳过——内置永远不会被覆盖。

### `wrapper-java` 源集（构建小机关）

`agent/src/wrapper-java/com/iast/agent/runtime/jakarta/BufferingHttpServletRequestWrapper.java` **故意被排除在 agent 运行时 classpath 外**。`pom.xml` 里的流程：

1. `build-helper-maven-plugin` 把 `src/wrapper-java` 加为额外源根 → 它会被编译。
2. `maven-antrun-plugin`（`process-classes` 阶段）把 `.class` 文件**搬出** `target/classes/com/iast/agent/runtime/...`、改名成 `target/classes/iast/runtime/jakarta-*.class.bin` 资源。
3. 运行期，`WrapperInjector` 通过 `getResourceAsStream` 读这些 `.class.bin` 资源，再用 `Instrumentation.appendToBootstrapClassLoaderSearch` / `defineClass` 注入到**目标应用的 classloader** 里——而不是 bootstrap classloader。

为什么：bootstrap CL 看不到 `jakarta.servlet.*`，所以加载这些 class 会失败。它们必须在用户应用的 classloader 里 materialize，那里能看到 Servlet API。改 move/rename 构建步骤或者 `WrapperInjector` 的时候要同时理解这两半。

### 交互式 CLI 架构

**CLI 是 WebSocket server；agent 主动 dial 出去。** 这是 v2 翻转过来的——动机是容器化 / 防火墙的目标只允许 egress 连接。类名还沿用旧语义（agent jar 里叫 `CliServer`、CLI jar 里叫 `CliClient`）——这是为了 diff 集中刻意保留的，但读的时候要按"agent 侧 CLI 引导"和"CLI 侧 REPL"理解。

CLI 拆在两个模块。**Agent 侧代码**（`agent/src/main/java/com/iast/agent/cli/`：`CliServer`、`CliHandler`、`WsFrame`）打在 `iast-agent.jar` 里，因为它直接读 `MonitorConfig` / `PluginManager` / `Instrumentation`。**CLI 侧代码**（`iast-cli/src/main/java/com/iast/cli/`：`CliClient`、`WsFrame`）是独立 jar，让用户在 Java 8 上也能跑。

**有两份 `WsFrame` class**（一边一份，逐字节同义）——这是有意为之，不是不小心的重复。别想着抽到公共模块去。

`iast-cli-jattach.sh` 流程：
1. `java -jar iast-cli.jar freeport` —— 绑一个 loopback ephemeral socket，打印 OS 分配的端口，关闭。纯端口发现工具。
2. `jattach <pid> load instrument false "iast-agent.jar=cli=127.0.0.1:<port>"` —— agent 解析 agentArgs 里的 `cli=host:port`，spawn 一个 daemon `iast-cli-dialer` 线程做 `new Socket(host, port)`。
3. `exec java -jar iast-cli.jar listen 127.0.0.1 <port>` —— CLI 在同一端口绑 `ServerSocket` 并 `accept()` agent 的连接。

第 1 步关 socket 和第 3 步重新 bind 之间有小 race。**agent 侧 dialer 重试约 1.5 秒**（`CliServer.dialWithRetry` 的 `CONNECT_RETRIES * CONNECT_RETRY_DELAY_MS`）来吸收这个窗口加 CLI 的 JVM 启动时间。

握手之后：agent 发**客户端侧** WS 握手（`GET / HTTP/1.1 ... Upgrade: websocket`），CLI 回 `101 Switching Protocols`。从那以后双向走 text frame——CLI 发用户命令（server-sent，不 mask），agent 回 handler 响应（client-sent，必须 mask）。欢迎 banner 现在由 CLI **本地**打印，不再走线缆。

安全边界缺省仍是"同主机同 UID"。CLI 接受 `listen 0.0.0.0 <port>` 形式做跨主机用——但这只是裸 socket，需要自己加防火墙 / SSH 隧道；没有任何内建鉴权。

会话结束：agent 关 socket、dialer 线程退出。没有自动重连——再连就再跑一次 `iast-cli-jattach.sh`。

### 输出文件

文件固定名 `iast.log` / `iast.jsonl`，路径由 `output.outputDir` + `output.instanceName` 决定：
`<outputDir>/<instanceName>/iast.{log,jsonl}`

- **`outputDir`**（默认 `/tmp`）：输出根目录，相对路径按 yaml 所在目录解析，不存在自动 mkdirs。
- **`instanceName`**（默认 `iast_<pid>`）：实例标识，作为子目录名，把多 JVM 隔离从"文件名 pid 后缀"抬到"目录层"。支持 `${VAR}` 引环境变量（env 未设 → WARN + 替换空串；解析后为空 → 兜底 `iast_<pid>`）。前缀 `iast_` 避免在 `/tmp` 下出现纯数字目录。

例：`outputDir: /var/log/iast`、`instanceName: ${HOSTNAME}` → `/var/log/iast/web01/iast.log`

不再写 CLI 端口文件了（agent 不再有自己的监听端口）。这些文件**不会自动清理**——线上请配 logrotate。

### bootstrap 日志

yaml 解析完成 + `setLogPath` 切到最终路径之前的"早期阶段"日志（agent jar 定位、yaml 语法错、outputDir mkdir 失败等）写到 `<agent-jar-dir>/logs/iast_bootstrap_<pid>.log`——tarball 布局下等同 `iast-start.sh` 所在目录。jar 路径 resolve 不到时降级到 `<cwd>/logs/iast_bootstrap_<pid>.log` → `/tmp/iast_bootstrap_<pid>.log`。

`setLogPath` 切走之后：
- 如果 bootstrap 期间**没有** ERROR（即配置加载成功 + 干净启动）→ 自动删掉 bootstrap 文件，不积累。
- 如果有 ERROR（yaml 损坏、mkdir 失败等）→ bootstrap 文件保留，运维排错用。

**yaml 损坏的语义**：catch 住异常后**不再**自动加载默认规则兜底（以前会偷偷挂一个 `java.io.File.exists()`）。让日志里直接出现 "monitoring 0 classes"，避免运维以为自己的 yaml 生效了却默默只在拦 File。

## 重要约定与陷阱

- **YAML 里的 Java 方法描述符** —— 方法按 `"name#descriptor"` 匹配，比如 `"exists#()Z"`、`"<init>#*"` 表示任意构造、`"name#*"` 表示任意签名。`README.md` 末尾有示例表。
- **`javax.servlet.*` ≠ `jakarta.servlet.*`** —— Servlet 4 vs 5+，两套独立命名空间。要同时覆盖 Tomcat 9 和 Tomcat 10+ 目标的话，分开各写一条规则。
- **`IastAgent.startAgent` 的 init 步骤顺序别乱动** —— `MonitorConfig.init` 必须在 `initPlugins()` 之前，因为 `PluginManager.registerPlugin` 要读 `MonitorConfig.getPluginConfigs()`；插件 init 必须在 `buildAndInstall()` 之前，因为 AgentBuilder transformer 会调已注册的插件。
- **`includeFutureClasses=false` + premain 模式** 由 `premainDelayMs` 延迟兜底：已加载类快照在 install 时刻取，不是 JVM 启动时刻取，所以 Spring 的 `DispatcherServlet`（应用启动时加载）只要在延迟到期前加载完了就会被纳入。
- **新功能只用 YAML** —— `.properties` 格式仍然能加载，但样例里不再展示；新功能仅 YAML。
- **规则放规则目录，不再 inline** —— 主 yaml 只有 `output` 和 `monitor.default`；规则全部放 `monitor.default.rulesDir` 指向的目录，每个 yaml 文件 multi-doc（`---` 分割）、每条 rule 可加 `id:` 可读标签。inline `monitor.rules:` 检测到只 WARN、不解析。loader 用 SnakeYAML typed `Constructor(MonitorRuleConfig.class) + loadAll`，刻意避免 YAML 1.1 把 `on:/yes:/no:` 这类 pluginConfig key 误识别成 Boolean。
- **日志级别** —— 全局四档 `debug`/`info`(默认)/`warn`/`error`，由 `output.logLevel` 控制；CLI `loglevel <level>` 命令可运行时切换。`LogWriter` 单例存级别为 volatile int，比较开销可忽略。新插件代码请尽量用 `lw.isDebugEnabled()` 包住 debug 路径上的字符串拼接（hot path 上即使被 filter 掉也不要付出拼接成本）。`addHeader` 这类"对业务功能不可见但插件承诺要做"的失败一律走 `warn` 而不是吞掉——`RequestIdPlugin` 早期版本因为吞掉过 `addHeader` 异常导致响应头丢失没人发现。

## 发布流程

Tag 发布通过 `.github/workflows/release.yml`（`workflow_dispatch` 仅限 GitHub Actions UI 手动触发）。Workflow 用 `IAST_VERSION` 跑 `dist.sh`、然后 `gh release create v<version>` 上传 tarball。同一 version 重新跑会先删旧 release 再重建。
