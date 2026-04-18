# IAST Java Agent

Java Agent 实现的交互式应用安全测试工具，支持监控任意JDK方法调用，无需修改应用代码。

## 功能特性
- ✅ 基于Java字节码增强（ASM），性能损耗极低
- ✅ 支持监控任意JDK类/方法调用
- ✅ 配置化管理监控规则，无需修改代码
- ✅ 支持方法名、参数、返回值、调用栈全链路采集
- ✅ 支持 pre-agent 与 attach 两种挂载模式
- ✅ **接口级监控**：`matchType: interface` 一行规则覆盖所有实现类（含后加载的，可开关）
- ✅ **premain 启动友好**：默认延迟 1 分钟再 install 字节码，业务启动期零拦截开销
- ✅ **交互式 CLI**：arthas 风格的 REPL，实时查规则 / 插件 / 已加载类（WebSocket，仅 loopback）
- ✅ 完全兼容Java 8 ~ Java 21
- ✅ 无需额外启动参数（无需`-Xbootclasspath/a`）

## 快速开始

### 1. 构建Agent
```bash
cd agent
./build.sh
```
构建成功后会在`agent/target/`目录下生成`iast-agent.jar`

### 2. 配置监控规则
推荐用 YAML（`iast-monitor.yaml`），老 `.properties` 格式仍然兼容：

```yaml
monitor:
  default:
    includeFutureClasses: false   # 接口规则是否覆盖后加载的实现类
    premainDelayMs: 60000         # premain 模式延迟 install 字节码（毫秒），0=立即
  rules:
    # 精确类名
    - className: java.io.File
      methods: ["exists#()Z", "getAbsolutePath#()Ljava/lang/String;"]
      plugin: LogPlugin
    # 接口级：所有实现 jakarta.servlet.Servlet 的具体类 + 声明方法体的抽象父类都会被 hook
    - className: jakarta.servlet.Servlet
      matchType: interface
      methods: ["service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"]
      plugin: LogPlugin
```

字段详解看 [`agent/README.md`](agent/README.md)。

### 3. 两种使用模式

#### 模式一：Pre-agent模式（JVM启动时挂载）
```bash
java -javaagent:/path/to/iast-agent.jar -jar your-application.jar
```
自定义配置文件路径：
```bash
java -javaagent:/path/to/iast-agent.jar=config=/path/to/custom-config.properties -jar your-application.jar
```

#### 模式二：Attach模式（动态挂载到运行中的JVM）
无需重启目标应用，直接挂载到正在运行的JVM进程：
```bash
# 1. 先获取目标进程PID
jps -l

# 2. 挂载Agent
java -jar iast-agent.jar <目标进程PID>

# 自定义配置文件路径
java -jar iast-agent.jar <目标进程PID> config=/path/to/custom-config.properties
```

**Attach模式注意事项：**
- 目标JVM不能开启`-XX:+DisableAttachMechanism`启动参数
- 运行挂载工具的用户需要与目标进程用户权限一致
- JDK大版本需要与目标JVM保持一致
- 配置文件需要是目标进程可访问的绝对路径

### 4. 交互式 CLI（arthas 风格）

挂载之后随时开一个 REPL 查规则 / 插件 / 已加载类。客户端在独立项目 `iast-cli/` 里：

```bash
cd iast-cli && mvn -q package -DskipTests       # 构建 iast-cli.jar（只需一次）
./iast-cli-jattach.sh <目标PID>
iast> help
iast> status
iast> rules
iast> rules jakarta.servlet.http.HttpServlet
iast> classes DispatcherServlet
iast> classes re:^org\.springframework\..*Controller$
iast> disable          # 运行时关监控（不用再 jattach）
iast> enable
iast> quit
```

首次打开时 jattach 会让目标 JVM 在 `127.0.0.1` 起一个 WebSocket server（端口写
`/tmp/iast-agent-<pid>.port`），只 bind loopback、不暴露外网卡。完整命令表和协议细节看
[`agent/README.md`](agent/README.md#交互式-cli-arthas-风格)。

## 方法描述符说明
| Java方法声明 | 描述符 |
|--------------|--------|
| `boolean exists()` | `()Z` |
| `int length()` | `()I` |
| `long currentTimeMillis()` | `()J` |
| `String getAbsolutePath()` | `()Ljava/lang/String;` |
| `void connect(SocketAddress addr, int timeout)` | `(Ljava/net/SocketAddress;I)V` |
| `boolean add(Object o)` | `(Ljava/lang/Object;)Z` |

## 目录结构
```
├── agent/                  # IAST Agent 核心代码
│   ├── pom.xml             # Maven 构建配置
│   ├── build.sh            # 一键构建脚本
│   └── src/main/java/com/iast/agent/
│       ├── IastAgent.java          # Agent入口，字节码增强实现
│       └── MonitorConfig.java      # 配置文件加载解析
├── demo-spring/            # Spring Boot 示例与完整测试脚本
│   ├── src/main/java/com/iast/demo/FileCheckController.java   # /api/* 端点（含反射调用用例）
│   ├── iast-monitor.yaml          # 监控配置
│   ├── run-premain.sh             # -javaagent 启动示例
│   ├── test-monitor-switch.sh     # 7 步回归测试
│   └── test-interface-match.sh    # 接口级监控 + premainDelayMs 端到端测试（3 个用例）
├── iast-cli/                  # 交互式 CLI 独立 Maven 项目
│   ├── pom.xml
│   ├── iast-cli-jattach.sh    # 入口脚本（WebSocket 连目标 JVM）
│   └── src/main/java/com/iast/cli/
│       ├── CliClient.java     # REPL 客户端（raw-mode tty line editing）
│       └── WsFrame.java       # RFC 6455 text/close frame 编解码（客户端视角）
├── dist.sh                 # 发布打包脚本
└── README.md               # 项目说明文档
```

## 技术栈
- Java 21
- ASM 9.5（字节码操作）
- Maven（构建工具）

## License
MIT
