# IAST Agent

基于 Byte Buddy 的 Java 应用运行时插桩 Agent。通过 YAML 声明方法拦截规则，
内置请求跟踪与结构化 JSONL 事件输出，支持 JDK/JRE 及 Linux/macOS/Windows。

## 目录结构

```
iast-agent-<version>/
├── README.md                本文档
├── iast-agent.jar           Agent 主 jar（已 shade，自包含所有依赖）
├── iast-monitor.yaml        监控配置样例（可直接用）
├── iast-start.sh            启动/恢复监控（用 java 跑 AttachTool）
├── iast-stop.sh             停止监控（用 java 跑 AttachTool）
├── iast-start-jattach.sh    启动/恢复监控（直接调 jattach，不依赖 java）
└── iast-stop-jattach.sh     停止监控（直接调 jattach）
```

> 交互式 CLI 客户端（`iast-cli-jattach.sh` + `iast-cli.jar`）已拆到独立项目
> [`iast-cli/`](../iast-cli/)。release tarball 打包时会把两边的 jar 和 script 都放进来。

## 两种部署模式

### 模式一：Attach（对运行中的 JVM 动态挂载，推荐用于开发/测试）

```bash
# 1. 找目标 Java 进程 PID
jps -l

# 2. 启动监控（默认读当前目录的 iast-monitor.yaml）
./iast-start.sh 12345

# 3. 停止监控（仅关闭拦截开关，Agent 仍在进程里）
./iast-stop.sh 12345

# 4. 再次启动
./iast-start.sh 12345

# 指定自定义配置
./iast-start.sh 12345 /path/to/custom.yaml
```

**无 Java 环境的替代方案（iast-*-jattach.sh）**

如果目标机器没有 JRE，只能下个 [jattach](https://github.com/jattach/jattach/releases) 二进制（~50KB，单文件，支持 Linux/macOS/Windows），
用 `iast-start-jattach.sh` / `iast-stop-jattach.sh` 代替：

```bash
./iast-start-jattach.sh 12345         # 挂载 + 默认配置
./iast-stop-jattach.sh 12345
./iast-start-jattach.sh 12345 custom.yaml
```

两者行为完全等价，只是底层挂载不走 `java -jar iast-agent.jar`，而是直接：
`jattach <pid> load instrument false "<agent-jar>=<args>"`。

**输出文件**
- 人读日志：`/tmp/iast-agent-<pid>.log`（拦截调用、参数、栈、插件启动信息）
- JSONL 事件：`/tmp/iast-events-<pid>.jsonl`（CustomEventPlugin 结构化事件，每行一个 JSON）

### 模式二：premain（JVM 启动时挂载，推荐用于生产常驻）

在 java 命令里加 `-javaagent`：

```bash
java -javaagent:/opt/iast/iast-agent.jar=config=/opt/iast/iast-monitor.yaml \
     -jar your-app.jar
```

关键语法：
- `-javaagent:<agent-jar>=<agent-args>` —— `=` 前是 jar 路径，`=` 后是传给 Agent 的参数
- 当前支持的 agent-args：
  - `config=<path>` —— 监控配置文件路径（`.yaml`/`.yml`/`.properties`）
  - 不传则使用内置默认规则（仅监控 `java.io.File.exists`）

Spring Boot 示例：
```bash
java -javaagent:/opt/iast/iast-agent.jar=config=/opt/iast/iast-monitor.yaml \
     -Xmx512m \
     -jar my-spring-boot-app.jar
```

Tomcat `catalina.sh` / systemd service：把上面那行加到 `JAVA_OPTS` 或 `CATALINA_OPTS`。

premain 模式下 Agent 随 JVM 生命周期常驻。想临时停/启监控，仍可在外部用
`iast-stop.sh` / `iast-start.sh` 按 PID 远程切换，不需重启。

## Attach 实现路径

AttachTool（`iast-start.sh` 内部调用）自动按以下顺序尝试，首个可用即用：

1. `jdk.attach` 模块（JDK 进程）
2. PATH 里的 `jattach` 二进制（纯 C，最轻量）
3. byte-buddy-agent 内置实现（Linux/macOS UNIX Socket + Windows JNA，JRE 兜底）

手动强制：`java -DiastAttach=jdk|jattach|fallback -jar iast-agent.jar <pid> ...`

## 配置文件 iast-monitor.yaml

结构总览：

```yaml
output:
  args: true              # 拦截日志是否打印入参
  return: true            # 打印返回值
  stacktrace: true        # 打印调用栈
  stacktraceDepth: 8
  logLevel: info          # debug | info | warn | error，默认 info；CLI 也可运行时 `loglevel debug`

monitor:
  default:
    includeFutureClasses: false   # 见"接口级监控"一节
    premainDelayMs: 60000         # 见"premain 延迟 install"一节
  rules:
    - className: <FQCN>
      matchType: exact                  # exact | interface，默认 exact
      methods:
        - "<methodName>#<descriptor>"   # 具体签名，"<init>" 为构造函数
        - "<methodName>#*"               # 通配任意签名
      plugin: <CustomEventPlugin | RequestIdPlugin | ServletBodyPlugin | LogPlugin>
      pluginConfig: {...}                # CustomEventPlugin / ServletBodyPlugin 用
```

内置插件：

| 插件 | 作用 | 按 className 筛规则？ |
|-----|------|---------------------|
| CustomEventPlugin | 按 YAML 表达式提取参数 / 渲染模板 / 写 JSONL 结构化事件 | **是**（只在 exact 规则下稳定工作） |
| RequestIdPlugin | 为每个请求生成 / 复用 `X-Request-Id`，挂到 ThreadLocal + 响应头；并采集 `client_ip` / `forward_req_id` / `forward_ip` / `xseeker` 到 IastContext 供出口侧透传用 | 否（任何分派进来都跑） |
| ServletBodyPlugin | 改写 `service` 入参为缓冲 wrapper，记录请求 body | 否（仅对显式标 `wrapServletRequest: true` 的规则生效） |
| HttpForwardPlugin | 出口链路头透传：钩到 `HttpRest.sendHttpRequest`，从 IastContext 读上下文，反射调 `args[2].putHttpContextHeader(String, String)` 把 `x-seeker-forward-req-id` / `x-seeker-forward-ip` / `xseeker` 注入到下游请求；目标类不在 classpath 时静默不命中 | 否（无 pluginConfig，行为对齐 HttpRest 固定签名；适配其他客户端时直接新写一个插件） |
| LogPlugin | 人读日志（调用、参数、返回值、栈） | 否；注：默认配置样例里已不用它，改推荐 CustomEventPlugin 走 JSONL |

**同一方法可挂多个插件**——对同一 className 写多条规则即可，按声明顺序依次分发。
**但不要挂两条 CustomEventPlugin 在同一 (className, methodName) 对上**——它内部按 className+methodName 去重 key，后一条会覆盖前一条；多插件分发要用**不同类型**的插件组合。

### CustomEventPlugin 表达式语法

```
Expr := Root ( '.' Ident '()' )*
Root := params[N] | target | return | result | throwable | requestId | callId | className | methodName
```

| 根变量 | 说明 |
|-------|------|
| `params[0]`,`params[1]`...`params[N]` | 第 N 个参数（0 起） |
| `target` | 被监控对象（this） |
| `return`（别名 `result`） | 返回值（仅 EXIT 阶段） |
| `throwable` | 异常对象（仅 EXCEPTION 阶段） |
| `requestId` | 当前请求 ID（来自 RequestIdHolder 全局 ThreadLocal） |
| `callId` / `className` / `methodName` | 调用 ID / 类名 / 方法名 |

支持链式无参方法调用：`params[0].toString()`、`target.getClass().getSimpleName()`。

### CustomEventPlugin YAML 字段

```yaml
pluginConfig:
  id: my.package.ClassName.methodName          # 事件标识（可选，缺省按 className.methods[0]）
  my_params:                                    # 参数名 → 表达式
    file_path: "params[0].toString()"
    caller:   "target.getClass().getSimpleName()"
  event: "file list | {file_path}"              # 事件模板，{key} 用 my_params 的值替换
  event_type: file.list                         # 事件类型
  event_level: info                             # 级别
  on: [enter]                                   # 触发阶段：enter/exit/exception，默认 enter
```

## 接口级监控（matchType: interface）

规则的 `className` 写在接口或抽象父类上，Agent 会展开到该接口 / 父类的**所有具体实现类**，
同时 hook 抽象父类里实际声明方法体的那层（典型如 `jakarta.servlet.Servlet` → 真正执行的
是 `HttpServlet.service(ServletRequest, ServletResponse)`；只写具体子类拦不到，因为方法
是继承来的）。

```yaml
monitor:
  default:
    includeFutureClasses: false   # 是否把"Agent install 之后"才加载的实现类也纳入监控
  rules:
    # 监控所有 jakarta.servlet.Servlet 实现的 service(ServletRequest,ServletResponse)
    # interface 规则推荐挂 RequestIdPlugin / ServletBodyPlugin 这种不按 className 筛规则的
    # 插件；CustomEventPlugin 在 interface 规则下运行期 className 是具体实现（如 HttpServlet），
    # 和规则注册时的接口名 (Servlet) 对不上，会静默不发事件。
    - className: jakarta.servlet.Servlet
      matchType: interface
      methods:
        - "service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"
      plugin: RequestIdPlugin

    # exact 规则下 CustomEventPlugin 正常工作（className 运行期和规则注册时一致）
    - className: java.nio.file.Files
      methods: ["exists#(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]
      plugin: CustomEventPlugin
      pluginConfig:
        my_params:
          path: "params[0].toString()"
        event: "Files.exists({path})"
        event_type: file.exists
```

`includeFutureClasses` 全局开关：

| 模式 \ 开关 | `false`（默认） | `true` |
|-------------|----------------|--------|
| **attach / agentmain** | 挂进去那一刻已加载的实现类全部 hook；之后动态加载的实现类 **不** 管。适合"拍快照"式审计，最安全 | 之后加载的实现类也一起 hook |
| **premain** | 只 hook install 时已加载的类；Spring 启动后的 `DispatcherServlet` 等仍会被覆盖——因为下面的 `premainDelayMs` 把快照推迟到业务启动之后 | 无差别覆盖所有实现类（含后加载的） |

`javax.servlet.*` 和 `jakarta.servlet.*` 是两套独立命名空间（Servlet 4 vs 5+），不互相继承；
目标容器跑哪个就配哪个，要同时覆盖两套就各写一条规则。

## premain 延迟 install（premainDelayMs）

premain 模式下 Agent 跟 JVM 一起起来，随后的 retransform 和逐类拦截会给业务启动加明显
开销。把 `monitor.default.premainDelayMs` 设成非 0 毫秒，Agent 启动时只做配置加载 / 插件
初始化，字节码 install 会被丢到后台 daemon 线程里，等延迟到期再一次性做完。

```yaml
monitor:
  default:
    premainDelayMs: 60000   # 默认 1 分钟；0 = 关闭延迟，恢复老行为
```

语义：
- `premain` 方法本身不阻塞，JVM 启动没有等待。
- 延迟期间 Agent 已加载 YAML、初始化插件，但**不拦截任何方法**，应用此时的调用完全等同于无 Agent。
- 延迟到期后，Agent 在后台线程里 build AgentBuilder → `installOn(inst)`，JVM 会对已加载的类做一次
  retransform，从此点开始拦截生效。
- `includeFutureClasses=false` 的快照也是在"延迟到期那一刻"取的——于是 premain 模式下这条开关
  才变得真正有用（能覆盖应用启动期加载的类，却排除运行期动态加载的插件 / JSP / OSGi bundle 等）。
- `agentmain` / attach 模式忽略此配置，始终立即 install。

日志可以用这两行判断延迟链路：
```
[IAST Agent] Bytecode install deferred by 60000ms to protect app startup (premain mode). ...
[IAST Agent] Building AgentBuilder and installing transformers...   # 到期后才出现
```

## 交互式 CLI（arthas 风格）

挂载完成后，想查"当前加载了哪些规则 / 都有哪些插件 / JVM 里有没有某个类"，
用 [`iast-cli/`](../iast-cli/) 里的 `iast-cli-jattach.sh` 开一个 REPL：

```bash
../iast-cli/iast-cli-jattach.sh 12345
→ jattach 让目标进程 dial 回 127.0.0.1:35971 ...
→ CLI listening on 127.0.0.1:35971，等待 agent 接入（Ctrl-D 或 quit 退出）
iast-cli connected. Type 'help' for commands.
iast> status
pid:            12345
monitorEnabled: true
callCount:      12
configPath:     ./iast-monitor.yaml
uptimeSec:      17
javaVersion:    21.0.9
iast> quit
```

**工作原理（v2：CLI 监听、agent 主动 dial）**：

1. 脚本先跑 `iast-cli.jar freeport` 拿一个 loopback 空闲端口 `P`。
2. jattach 给 agent 发 `cli=127.0.0.1:P` agentArg，agent 起一个 daemon 线程
   `iast-cli-dialer`，用 `new Socket("127.0.0.1", P)` 连回本机 CLI。首次连不上会自动
   重试 ~1.5s，覆盖 CLI exec 的冷启动窗口。
3. 脚本 `exec iast-cli.jar listen 127.0.0.1 P`：CLI 绑 `ServerSocket` 到同一端口 `accept`，
   握手后进入 REPL。
4. 每次会话独立：quit / 断开 → agent 线程退出；想再开 REPL 再跑一次 `iast-cli-jattach.sh`。

反转方向的目的：目标 JVM 只允许出方向（容器 egress、企业 firewall）也能用 CLI。

agent 侧代码（`com.iast.agent.cli.{CliServer, CliHandler, WsFrame}`）依旧在
`iast-agent.jar` 里——和 agent 内部状态耦合，不能拆出去；类名保留 `CliServer` 但
真实语义已是 "agent 侧 CLI bootstrap"，内部 spawn 的线程名为 `iast-cli-dialer`。

### 命令表

| 命令 | 说明 |
|------|------|
| `help` | 列出所有命令 |
| `status` | PID / MONITOR_ENABLED / globalCallCount / 配置文件路径 / 启动至今秒数 / Java 版本 |
| `plugins` | 列出已注册插件：`name → 实现类 FQCN` |
| `rules` | 表格列出所有被监控类：`ClassName / matchType / wrapBody / 方法数` |
| `rules <class>` | 打印单个类的 matchType、关联插件、方法签名列表（`<class>` 可 FQ 也可 internal，两者等价） |
| `classes <substring>` | 在 `Instrumentation.getAllLoadedClasses()` 里按**子串**（大小写不敏感）搜 |
| `classes re:<regex>` | 正则搜（`Pattern.compile` + `matcher.find()`），要精确锚自己加 `^`/`$` |
| `methods <class>` | 打印类声明的方法（含 ctor）+ 对应 JVM 描述符，YAML 可直接粘贴 |
| `methods <class> <filter>` | 方法名子串过滤（大小写不敏感） |
| `methods <class> all` | 额外沿 superclass 链展开（到 Object 前停），注释里标 `[inherited from X]` |
| `enable` / `disable` | 运行时翻 `MONITOR_ENABLED`；效果等价 `iast-start.sh` / `iast-stop.sh` 但不经过 jattach |
| `loglevel [<level>]` | 查询或切换日志级别（debug/info/warn/error） |
| `quit` / `exit` | 关闭会话 |

**classes 命令的输出约定**：命中 ≤500 打全 + `total: N`；>500 打前 500 + `... (N more truncated; total M)`；命中 0 → `no loaded class matches: <pattern>`。

### 常用姿势

```bash
iast> classes DispatcherServlet          # 子串
iast> classes re:^org\.springframework\..*Controller$   # 正则
iast> rules jakarta.servlet.http.HttpServlet            # FQ
iast> rules java/io/File                                # internal 也行
iast> methods com.iast.demo.FileCheckController         # 列全部声明方法 + 描述符
iast> methods java.io.File exists                       # 过滤方法名
iast> methods org.springframework.web.servlet.DispatcherServlet all service  # 展开继承链
```

**methods 命令**输出的每一行是 YAML 可直接粘贴的形态：
```
  - "exists#()Z"                           # public boolean exists()
```
把 `"exists#()Z"` 这一段直接粘到 `iast-monitor.yaml` 里规则的 `methods:` 下即可，`#` 后的注释是人读 Java 签名帮你 double-check。加 `all` 展开继承时 `[inherited from X]` 标签会告诉你方法实际来自哪层父类。

### 安全与边界

- **默认 loopback**：脚本用 `127.0.0.1` 绑定 + agent 以 `127.0.0.1` 连回，同主机同 UID 边界。想跨主机需要手动 `iast-cli.jar listen 0.0.0.0 <port>` + `cli=<cli-host-ip>:<port>`；**没有内建鉴权**，跨主机暴露请自行叠加 SSH 隧道 / 防火墙 ACL / VPN
- **按需启动**：没发 `cli=host:port` agentArg 之前 agent 不开任何网络资源，零开销
- **单活跃会话**：同时只有一条 dialer 线程；已有会话时再发 `cli=...` 会被忽略并打日志
- **会话结束即退出**：quit / 对端断开 → dialer 线程退出；再连一次就再跑一次 `iast-cli-jattach.sh`（或手动 jattach 新的 `cli=host:port`）
- **agent 不再监听端口**：v1 的 `/tmp/iast-agent-<pid>.port` 文件已取消——agent 现在是 WS 客户端，由 CLI 决定监听地址

### v1 不做

`reload`（YAML 热加载）、`trace/watch`（动态注入 advice 追踪某方法的入参/耗时/栈）、身份认证——工程量较大，放到后续 PR。

## 外部插件加载（pluginsDir）

内置 4 个插件（LogPlugin / RequestIdPlugin / CustomEventPlugin / ServletBodyPlugin）
写死在 agent 里；想加自己的插件不用改 agent 源码——把插件 jar 扔进一个目录，
`iast-monitor.yaml` 配 `monitor.default.pluginsDir` 指向它，agent 启动时 ServiceLoader
发现并加载。

```yaml
monitor:
  default:
    pluginsDir: /opt/iast/plugins        # 空字符串 = 不加载外部插件（默认）
```

**插件 jar 要求**（见 [`iast-plugin-demo/`](../iast-plugin-demo/) 样板）：
1. 依赖 `com.iast:iast-sdk:1.0.0`（`<scope>provided</scope>`）
2. 实现 `com.iast.agent.plugin.IastPlugin`
3. `META-INF/services/com.iast.agent.plugin.IastPlugin` 列出实现类 FQCN（一行一个，
   一个 jar 可注册多个插件）
4. `mvn package` 得到 jar，放进 `pluginsDir`

**加载流程**：
- Agent 启动时把 `pluginsDir/*.jar` 合并成一个 URLClassLoader，parent = agent 所在 CL
- `ServiceLoader.load(IastPlugin.class, ...)` 遍历、实例化、`init(pluginConfig)`、`PluginManager.registerPlugin`
- 之后和内置插件**完全等价**：YAML 里 `plugin: <getName()>` 引用；`classPluginMap` 路由；`handleMethodCall` 分派

**冲突策略**：外部插件 `getName()` 和内置撞名（LogPlugin / RequestIdPlugin / CustomEventPlugin / ServletBodyPlugin）→ 打 WARN 日志跳过外部那个；不允许外部覆盖内置实现。

**v1 不做**：插件热加载 / 卸载、多目录、版本兼容校验、沙箱隔离——改插件后需要重启目标 JVM。

## JSONL 事件格式

每行一个 JSON 对象：

```json
{"ts":"2026-04-17T09:12:34.567Z","id":"java.nio.file.Files.list","event":"file list | /tmp","event_type":"file.list","event_level":"info","phase":"enter","requestId":"abc...","callId":42,"className":"java.nio.file.Files","methodName":"list","thread":"http-nio-8080-exec-1","params":{"file_path":"/tmp"}}
```

配合 `jq` / Filebeat / Vector 消费：
```bash
tail -f /tmp/iast-events-*.jsonl | jq -c 'select(.event_type == "file.list")'
```

## 日志级别 & 调试

四档：`debug` / `info`（默认）/ `warn` / `error`。每行日志按 `[LEVEL] [Subsystem] msg` 形式输出。

切换方式：
- **配置文件**：`output.logLevel: debug`（重启目标 JVM 才能生效）
- **CLI 运行时切换**（推荐）：`iast-cli-jattach.sh <pid>` → `loglevel debug`，无需重启
- **CLI 查看当前级别**：`status` 命令输出里有 `logLevel:` 行，或 `loglevel`（不带参数）单独打印

debug 模式打开后，下面这些"静默问题"会冒出来：

| 静默症状 | debug 下能看到什么 |
|---------|-------------------|
| 响应头 `X-Request-Id` 没出现 | `[WARN] [IAST RequestId] addHeader threw on <responseClass>: ...`（response 包装类不支持 addHeader 或方法抛异常）；或 `[DEBUG] [IAST RequestId] skip addHeader: nested call (depth=N)` 表示当前调用是嵌套层不该加头 |
| 配了 yaml 规则但插件没动静 | `[DEBUG] [IAST Plugin] dispatch miss: plugin 'XXX' not registered (...)`，通常是插件名拼错 / 外部插件没扫到 |
| 上游 `X-Request-Id` 头没被复用 | `[DEBUG] [IAST RequestId] req has no getHeader (class=...)`，说明 advice 拿到的 args[0] 不是 HttpServletRequest |
| stop/start 切换后行为不对 | `[INFO] [IAST Agent] MONITOR_ENABLED true -> false (stop)` / `... -> true (start)` 标定切换时点；插件 enter/exit 是否成对完成可看 `[IAST RequestId] === Request Started ===` / `Request Completed` 配对 |

debug 级别会显著增加日志量（每个被监控请求多 ~3 行）；线上场景建议只在排查期临时打开，结束 `loglevel info`。

## 故障排查

| 现象 | 原因 / 排查 |
|-----|------------|
| `Target process does not support attach` | 目标 JVM 加了 `-XX:+DisableAttachMechanism`，或 attach 进程与目标不是同一 UID |
| iast-start.sh 挂后没日志 | 查 `/tmp/iast-agent-<pid>.log` 有无 `Loaded monitor rule: ...`；无即配置路径错了 |
| 事件日志空 | `/tmp/iast-agent-<pid>.log` 里看 `[CustomEventPlugin] loaded N definition(s)`；N=0 说明 YAML 没写 CustomEventPlugin 规则 |
| premain 启动后长时间没拦截 | 正常：默认 `premainDelayMs=60000` 会让字节码 install 延迟 1 分钟。日志里找 `Bytecode install deferred by ...`；立即生效把它设 `0` |
| `matchType: interface` 配了但没命中 | (1) premain 模式下应用还没起来就挂 Agent，把 `includeFutureClasses: true` 打开或调大 `premainDelayMs` 让快照更完整；(2) 方法是从抽象父类继承来、子类没 override —— 这时 hook 会发生在父类而非子类，属于正常 |
| JRE 环境 attach 失败 | `-DiastAttach=fallback` 强走 byte-buddy-agent；或安装 jattach |
| `iast-cli-jattach.sh` 卡在 `waiting for agent` | 看 `/tmp/iast-agent-<pid>.log` 尾行是否 `[IAST CLI] dialer gave up after N attempts`：(1) agent 还没到"已初始化"——先跑一次 `iast-start-jattach.sh` 挂上再开 cli；(2) 容器/网络策略不允许目标 JVM 出方向连 `127.0.0.1` 上本机 CLI 的端口（非常少见，user-namespace / 奇葩 bridge 可能触发） |
| CLI 连上但 `rules` / `status` 空 | agent 还没执行 `buildAndInstall`（比如 premain 延迟期），等一下再试；或 `disable` → `enable` 看回显确认连接正常 |
| Windows JRE | 需要 byte-buddy-agent 路径（自动），或装 jattach Windows 版 |

## 日志清理

进程退出后 `/tmp/iast-agent-<pid>.log` 和 `/tmp/iast-events-<pid>.jsonl` 不会自动删除。
生产环境建议配 logrotate 或定时清理。
