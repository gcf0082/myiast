# IAST CLI

IAST Agent 的交互式 WebSocket 客户端（arthas 风格 REPL）。独立 Maven 项目，
和 `iast-agent.jar` 解耦：用户本地机器只要有 Java 8+ 就能跑，不依赖目标进程的
JDK 版本。

## 构建

```bash
mvn -q package -DskipTests
# 产物：target/iast-cli.jar（约 20KB，无外部依赖）
```

## 使用

```bash
./iast-cli-jattach.sh <target-pid>
```

脚本逻辑：

1. 先探活 `/tmp/iast-agent-<pid>.port` 里的端口；通则直接连
2. 不通（首次或失效）用 jattach 给目标进程发 `agentmain("cli")`，让 agent 把
   WebSocket server 起来，然后再连
3. `exec java -jar iast-cli.jar <port>` 进入 REPL

### Jar 查找顺序

脚本会在以下位置找 jar（按优先级）：

| 变量 | 默认查找路径 |
|------|-------------|
| `IAST_AGENT_JAR` | `<script dir>/iast-agent.jar` → `../agent/iast-agent.jar` → `../agent/target/iast-agent.jar` |
| `IAST_CLI_JAR`   | `<script dir>/iast-cli.jar` → `<script dir>/target/iast-cli.jar` |

两种常见布局都能自动适配：

- **源码仓**：`iast-cli/` 和 `agent/` 平级，从源码目录运行
- **release tarball**：所有 jar 和 script 都在同一目录

## 命令表

见项目根 `README.md` 或 `agent/README.md` 里"交互式 CLI"章节。支持命令：
`help / status / plugins / rules [class] / classes <pattern> / enable / disable / quit`。

## 架构

```
用户 PID X  ← iast-cli-jattach.sh
                 │
                 ├─ jattach X load instrument false "iast-agent.jar=cli"
                 │  （首次触发，让目标进程 bind 127.0.0.1:<port>）
                 │
                 └─ exec java -jar iast-cli.jar <port>
                        │
                        │ WebSocket (RFC 6455, text frame)
                        ▼
                  目标进程内 CliServer ── 调 CliHandler ── 读 MonitorConfig / PluginManager
                  （CliServer / CliHandler 在 iast-agent.jar 里）
```

- `com.iast.cli.CliClient` ── 本项目提供
- `com.iast.cli.WsFrame` ── 本项目提供；和 agent 里 `com.iast.agent.cli.WsFrame` 逐字节同义
  （两份刻意独立，没做公共 jar ── 协议稳定、改动双边 review 即可）
- `com.iast.agent.cli.CliServer` / `CliHandler` ── 留在 `iast-agent.jar`，和 agent 内部状态耦合

## v1 不做

- 历史 / Tab 补全 / 方向键编辑（能记录上一条命令）
- 身份认证（全靠本地 loopback-only + 同主机同用户边界）
- `reload` YAML 热加载 / `trace / watch` 方法追踪（属于 server 侧能力）
- Windows 终端 raw 模式（依赖 `stty`，在 Git Bash / WSL 能跑，但原生 cmd / PowerShell 可能得装 Unix shell）
