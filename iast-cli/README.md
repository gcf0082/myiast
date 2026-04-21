# IAST CLI

IAST Agent 的交互式 WebSocket 控制客户端（arthas 风格 REPL）。独立 Maven 项目，
和 `iast-agent.jar` 解耦：用户本地机器只要有 Java 8+ 就能跑，不依赖目标进程的 JDK 版本。

## 架构（v2：CLI 监听、agent 主动 dial）

```
用户本地 shell                            目标 JVM (pid X)
iast-cli-jattach.sh X                    （iast-agent.jar 已挂载）
  │                                             │
  │ 1. java -jar iast-cli.jar freeport          │
  │    ↓ 拿到 loopback 空闲端口 P               │
  │                                             │
  │ 2. jattach X load instrument false          │
  │     "iast-agent.jar=cli=127.0.0.1:P"  ───►  ▼ agent 侧 CliServer.ensureConnected
  │                                         new Socket(127.0.0.1, P)（带 1.5s 重试）
  │ 3. exec java -jar iast-cli.jar              │
  │    listen 127.0.0.1 P                       │
  │    ↓ CliClient ServerSocket.bind + accept   │
  │                                             │
  ├───── WebSocket (RFC 6455, text frame) ──────┤
  │ agent 是 WS client（发 GET Upgrade，帧 mask）
  │ CLI   是 WS server（回 101，帧 unmask）
  ▼
REPL（用户输入 → CLI writeServerText → agent CliHandler → response → CLI 显示）
```

反转方向的目的：目标 JVM 只允许出方向（容器 egress、firewall 等场景）也能用 CLI。

## 构建

```bash
mvn -q package -DskipTests
# 产物：target/iast-cli.jar（约 20KB，无外部依赖）
```

## 子命令

CLI jar 是多用途入口，第一个位置参数是子命令：

```bash
java -jar iast-cli.jar listen [host] [port] [--port-file <path>]
    # 默认 127.0.0.1:0（OS 分配端口）。--port-file 把绑定端口写到文件里（脚本编排用）。
    # 输出第一行 "IAST_CLI_PORT=<port>" 方便 grep/捕获。
    # 一次性会话：accept 一个 agent 连接 → REPL → 会话结束后进程退出。

java -jar iast-cli.jar freeport
    # bind 127.0.0.1:0 拿 OS 分配的空闲端口，打印端口号，close，退出。
    # 只做端口发现；主要给 iast-cli-jattach.sh 脚本用。

java -jar iast-cli.jar connect <host> <port>
    # (进阶/遗留) 旧版 dial-to-server 模式：CLI 主动连对端 WS server。
    # 用于手动调试某个仍运行旧版本 agent 的场景；日常不需要。
```

## 日常使用

```bash
./iast-cli-jattach.sh <target-pid>
```

脚本内部三步：
1. `freeport` → 得到 `P`
2. `jattach <pid> load instrument false "<agent-jar>=cli=127.0.0.1:P"` → 目标进程 dial 回来
3. `exec iast-cli.jar listen 127.0.0.1 P` → 前台跑 REPL

### Jar 查找顺序

| 环境变量 | 默认查找路径 |
|------|-------------|
| `IAST_AGENT_JAR` | `<script dir>/iast-agent.jar` → `../agent/iast-agent.jar` → `../agent/target/iast-agent.jar` |
| `IAST_CLI_JAR`   | `<script dir>/iast-cli.jar` → `<script dir>/target/iast-cli.jar` |

两种常见布局都能自动适配：

- **源码仓**：`iast-cli/` 和 `agent/` 平级
- **release tarball**：所有 jar 和 script 都在同一目录

## 跨主机场景

脚本只处理 loopback。想让 agent 从另一台机器 dial 进来：

```bash
# CLI 机器（监听外网卡）
java -jar iast-cli.jar listen 0.0.0.0 9898

# 目标 JVM 所在机器（手动 jattach）
jattach <pid> load instrument false "iast-agent.jar=cli=<cli-host-ip>:9898"
```

**没有内建鉴权** —— 0.0.0.0 绑定 + 公网暴露等于后门。请自己加 SSH 隧道 / 防火墙 ACL / VPN。

## 命令表

见根目录 `README.md` 或 `agent/README.md` 里"交互式 CLI"章节。支持命令：
`help / status / plugins / rules [class] / classes <pattern> / enable / disable / quit`。

## v1 不做

- 历史 / Tab 补全 / 方向键编辑（能记录上一条命令）
- 身份认证（依赖本地 loopback-only + 同主机同用户边界；跨机用户自担风险）
- `reload` YAML 热加载 / `trace / watch` 方法追踪（属于 server 侧能力）
- agent 侧断开后自动重连（session 结束需重跑 iast-cli-jattach.sh）
- Windows 终端 raw 模式（依赖 `stty`，在 Git Bash / WSL 能跑，原生 cmd / PowerShell 可能得装 Unix shell）
