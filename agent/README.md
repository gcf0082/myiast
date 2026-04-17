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
      plugin: <LogPlugin | RequestIdPlugin | CustomEventPlugin>
      pluginConfig: {...}                # 仅 CustomEventPlugin 使用
```

内置插件：

| 插件 | 作用 |
|-----|------|
| LogPlugin | 人读日志（调用、参数、返回值、栈） |
| RequestIdPlugin | 为 HttpServlet 请求生成 UUID → ThreadLocal + 响应头 X-Request-Id |
| CustomEventPlugin | 按 YAML 表达式提取参数、渲染模板、写 JSONL |

**同一方法可挂多个插件**——对同一 className 写多条规则即可，按声明顺序依次分发。

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
    - className: jakarta.servlet.Servlet
      matchType: interface
      methods:
        - "service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"
      plugin: LogPlugin

    # 老写法（exact）还是可用，两种可以混合
    - className: java.nio.file.Files
      methods: ["exists#(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]
      plugin: LogPlugin
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

## JSONL 事件格式

每行一个 JSON 对象：

```json
{"ts":"2026-04-17T09:12:34.567Z","id":"java.nio.file.Files.list","event":"file list | /tmp","event_type":"file.list","event_level":"info","phase":"enter","requestId":"abc...","callId":42,"className":"java.nio.file.Files","methodName":"list","thread":"http-nio-8080-exec-1","params":{"file_path":"/tmp"}}
```

配合 `jq` / Filebeat / Vector 消费：
```bash
tail -f /tmp/iast-events-*.jsonl | jq -c 'select(.event_type == "file.list")'
```

## 故障排查

| 现象 | 原因 / 排查 |
|-----|------------|
| `Target process does not support attach` | 目标 JVM 加了 `-XX:+DisableAttachMechanism`，或 attach 进程与目标不是同一 UID |
| iast-start.sh 挂后没日志 | 查 `/tmp/iast-agent-<pid>.log` 有无 `Loaded monitor rule: ...`；无即配置路径错了 |
| 事件日志空 | `/tmp/iast-agent-<pid>.log` 里看 `[CustomEventPlugin] loaded N definition(s)`；N=0 说明 YAML 没写 CustomEventPlugin 规则 |
| premain 启动后长时间没拦截 | 正常：默认 `premainDelayMs=60000` 会让字节码 install 延迟 1 分钟。日志里找 `Bytecode install deferred by ...`；立即生效把它设 `0` |
| `matchType: interface` 配了但没命中 | (1) premain 模式下应用还没起来就挂 Agent，把 `includeFutureClasses: true` 打开或调大 `premainDelayMs` 让快照更完整；(2) 方法是从抽象父类继承来、子类没 override —— 这时 hook 会发生在父类而非子类，属于正常 |
| JRE 环境 attach 失败 | `-DiastAttach=fallback` 强走 byte-buddy-agent；或安装 jattach |
| Windows JRE | 需要 byte-buddy-agent 路径（自动），或装 jattach Windows 版 |

## 日志清理

进程退出后 `/tmp/iast-agent-<pid>.log` 和 `/tmp/iast-events-<pid>.jsonl` 不会自动删除。
生产环境建议配 logrotate 或定时清理。
