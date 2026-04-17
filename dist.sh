#!/bin/bash
# IAST Agent 发布打包脚本
# 产出：dist/iast-agent-<version>.tar.gz
# 包内含：jar + 启停脚本 + 配置样例 + README
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
VERSION="${IAST_VERSION:-1.0.0}"
NAME="iast-agent-${VERSION}"
OUT="dist/${NAME}"

echo "==> 清理旧产物"
rm -rf "dist/${NAME}" "dist/${NAME}.tar.gz"
mkdir -p "${OUT}"

echo "==> 构建 Agent jar"
(cd agent && mvn clean package -DskipTests -q)

echo "==> 拷贝核心文件"
cp agent/target/iast-agent.jar "${OUT}/iast-agent.jar"

echo "==> 生成默认配置 iast-monitor.yaml"
cat > "${OUT}/iast-monitor.yaml" <<'YAML'
# ============================================
# IAST Agent 监控配置（YAML）
# 字段说明详见 README.md
# ============================================

output:
  args: true                 # 拦截日志是否打印入参
  return: true               # 是否打印返回值
  stacktrace: true           # 是否打印调用栈
  stacktraceDepth: 8
  # eventsPath: /tmp/iast-events.jsonl   # 可选，默认 /tmp/iast-events-<pid>.jsonl

monitor:
  rules:
    # ---------- 示例 1：File/Files 基础拦截 ----------
    - className: java.io.File
      methods:
        - "<init>#*"
        - "exists#()Z"
        - "getAbsolutePath#()Ljava/lang/String;"
      plugin: LogPlugin

    - className: java.net.Socket
      methods:
        - "connect#*"
      plugin: LogPlugin

    - className: java.lang.Runtime
      methods:
        - "exec#*"
      plugin: LogPlugin

    # ---------- 示例 2：Servlet 请求 ID 跟踪 ----------
    # 为每个 HTTP 请求生成 UUID，写到 ThreadLocal 和响应头 X-Request-Id
    - className: jakarta.servlet.http.HttpServlet
      methods:
        - "service#*"
      plugin: RequestIdPlugin

    # ---------- 示例 3：CustomEventPlugin 结构化事件 ----------
    # 同一方法可挂多个插件（见下一条，同时走 LogPlugin）
    - className: java.nio.file.Files
      methods:
        - "list#(Ljava/nio/file/Path;)Ljava/util/stream/Stream;"
      plugin: CustomEventPlugin
      pluginConfig:
        id: java.nio.file.Files.list
        my_params:
          file_path: "params[0].toString()"
        event: "file list | {file_path}"
        event_type: file.list
        event_level: info
        # on: [enter]          # 默认 enter；可填 enter/exit/exception 组合

    - className: java.nio.file.Files
      methods:
        - "list#(Ljava/nio/file/Path;)Ljava/util/stream/Stream;"
      plugin: LogPlugin
YAML

echo "==> 生成 iast-start.sh"
cat > "${OUT}/iast-start.sh" <<'SH'
#!/bin/bash
# 启动/恢复 IAST 监控
# 用法：./iast-start.sh <target-pid> [config-file]
# 首次调用会挂载 agent 并按配置拦截；后续调用等同于 "start" 恢复开关。
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_JAR="${SCRIPT_DIR}/iast-agent.jar"
DEFAULT_CONFIG="${SCRIPT_DIR}/iast-monitor.yaml"

usage() {
    echo "Usage: $0 <target-pid> [config-file]"
    echo "  target-pid   目标 JVM 进程 ID"
    echo "  config-file  可选，默认为 ${DEFAULT_CONFIG}"
    exit 1
}

PID="${1:-}"
CONFIG="${2:-$DEFAULT_CONFIG}"

[ -z "${PID}" ] && usage
[ ! -f "${AGENT_JAR}" ] && { echo "❌ agent jar 不存在: ${AGENT_JAR}"; exit 1; }
[ ! -f "${CONFIG}" ] && { echo "❌ 配置文件不存在: ${CONFIG}"; exit 1; }
if [ ! -d "/proc/${PID}" ] 2>/dev/null && [ "$(uname)" = "Linux" ]; then
    echo "❌ 目标进程 ${PID} 不存在"
    exit 1
fi

# 通过日志文件判断是否已经挂载过，决定发 "config=..." 还是 "start"
LOG="/tmp/iast-agent-${PID}.log"
if [ -f "${LOG}" ] && grep -q "Agent installed successfully" "${LOG}" 2>/dev/null; then
    echo "→ Agent 已加载，发送 start 恢复监控..."
    exec java -jar "${AGENT_JAR}" "${PID}" start
else
    echo "→ 首次挂载，使用配置 ${CONFIG}"
    exec java -jar "${AGENT_JAR}" "${PID}" "config=${CONFIG}"
fi
SH
chmod +x "${OUT}/iast-start.sh"

echo "==> 生成 iast-stop.sh"
cat > "${OUT}/iast-stop.sh" <<'SH'
#!/bin/bash
# 停止 IAST 监控（Agent 不卸载，仅关闭拦截开关）
# 用法：./iast-stop.sh <target-pid>
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_JAR="${SCRIPT_DIR}/iast-agent.jar"

PID="${1:-}"
if [ -z "${PID}" ]; then
    echo "Usage: $0 <target-pid>"
    exit 1
fi
[ ! -f "${AGENT_JAR}" ] && { echo "❌ agent jar 不存在: ${AGENT_JAR}"; exit 1; }

exec java -jar "${AGENT_JAR}" "${PID}" stop
SH
chmod +x "${OUT}/iast-stop.sh"

echo "==> 生成 README.md"
cat > "${OUT}/README.md" <<README
# IAST Agent v${VERSION}

基于 Byte Buddy 的 Java 应用运行时插桩 Agent。通过 YAML 声明方法拦截规则，
内置请求跟踪与结构化 JSONL 事件输出，支持 JDK/JRE 及 Linux/macOS/Windows。

## 目录结构

\`\`\`
iast-agent-${VERSION}/
├── README.md           本文档
├── iast-agent.jar      Agent 主 jar（已 shade，自包含所有依赖）
├── iast-monitor.yaml   监控配置样例（可直接用）
├── iast-start.sh       启动/恢复监控
└── iast-stop.sh        停止监控
\`\`\`

## 两种部署模式

### 模式一：Attach（对运行中的 JVM 动态挂载，推荐用于开发/测试）

\`\`\`bash
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
\`\`\`

**输出文件**
- 人读日志：\`/tmp/iast-agent-<pid>.log\`（拦截调用、参数、栈、插件启动信息）
- JSONL 事件：\`/tmp/iast-events-<pid>.jsonl\`（CustomEventPlugin 结构化事件，每行一个 JSON）

### 模式二：premain（JVM 启动时挂载，推荐用于生产常驻）

在 java 命令里加 \`-javaagent\`：

\`\`\`bash
java -javaagent:/opt/iast/iast-agent.jar=config=/opt/iast/iast-monitor.yaml \\
     -jar your-app.jar
\`\`\`

关键语法：
- \`-javaagent:<agent-jar>=<agent-args>\` —— \`=\` 前是 jar 路径，\`=\` 后是传给 Agent 的参数
- 当前支持的 agent-args：
  - \`config=<path>\` —— 监控配置文件路径（\`.yaml\`/\`.yml\`/\`.properties\`）
  - 不传则使用内置默认规则（仅监控 \`java.io.File.exists\`）

Spring Boot 示例：
\`\`\`bash
java -javaagent:/opt/iast/iast-agent.jar=config=/opt/iast/iast-monitor.yaml \\
     -Xmx512m \\
     -jar my-spring-boot-app.jar
\`\`\`

Tomcat \`catalina.sh\` / systemd service：把上面那行加到 \`JAVA_OPTS\` 或 \`CATALINA_OPTS\`。

premain 模式下 Agent 随 JVM 生命周期常驻。想临时停/启监控，仍可在外部用
\`iast-stop.sh\` / \`iast-start.sh\` 按 PID 远程切换，不需重启。

## Attach 实现路径

AttachTool（\`iast-start.sh\` 内部调用）自动按以下顺序尝试，首个可用即用：

1. \`jdk.attach\` 模块（JDK 进程）
2. PATH 里的 \`jattach\` 二进制（纯 C，最轻量）
3. byte-buddy-agent 内置实现（Linux/macOS UNIX Socket + Windows JNA，JRE 兜底）

手动强制：\`java -DiastAttach=jdk|jattach|fallback -jar iast-agent.jar <pid> ...\`

## 配置文件 iast-monitor.yaml

结构总览：

\`\`\`yaml
output:
  args: true              # 拦截日志是否打印入参
  return: true            # 打印返回值
  stacktrace: true        # 打印调用栈
  stacktraceDepth: 8

monitor:
  rules:
    - className: <FQCN>
      methods:
        - "<methodName>#<descriptor>"   # 具体签名，"<init>" 为构造函数
        - "<methodName>#*"               # 通配任意签名
      plugin: <LogPlugin | RequestIdPlugin | CustomEventPlugin>
      pluginConfig: {...}                # 仅 CustomEventPlugin 使用
\`\`\`

内置插件：

| 插件 | 作用 |
|-----|------|
| LogPlugin | 人读日志（调用、参数、返回值、栈） |
| RequestIdPlugin | 为 HttpServlet 请求生成 UUID → ThreadLocal + 响应头 X-Request-Id |
| CustomEventPlugin | 按 YAML 表达式提取参数、渲染模板、写 JSONL |

**同一方法可挂多个插件**——对同一 className 写多条规则即可，按声明顺序依次分发。

### CustomEventPlugin 表达式语法

\`\`\`
Expr := Root ( '.' Ident '()' )*
Root := params[N] | target | return | result | throwable | requestId | callId | className | methodName
\`\`\`

| 根变量 | 说明 |
|-------|------|
| \`params[0]\`,\`params[1]\`...\`params[N]\` | 第 N 个参数（0 起） |
| \`target\` | 被监控对象（this） |
| \`return\`（别名 \`result\`） | 返回值（仅 EXIT 阶段） |
| \`throwable\` | 异常对象（仅 EXCEPTION 阶段） |
| \`requestId\` | 当前请求 ID（来自 RequestIdHolder 全局 ThreadLocal） |
| \`callId\` / \`className\` / \`methodName\` | 调用 ID / 类名 / 方法名 |

支持链式无参方法调用：\`params[0].toString()\`、\`target.getClass().getSimpleName()\`。

### CustomEventPlugin YAML 字段

\`\`\`yaml
pluginConfig:
  id: my.package.ClassName.methodName          # 事件标识（可选，缺省按 className.methods[0]）
  my_params:                                    # 参数名 → 表达式
    file_path: "params[0].toString()"
    caller:   "target.getClass().getSimpleName()"
  event: "file list | {file_path}"              # 事件模板，{key} 用 my_params 的值替换
  event_type: file.list                         # 事件类型
  event_level: info                             # 级别
  on: [enter]                                   # 触发阶段：enter/exit/exception，默认 enter
\`\`\`

## JSONL 事件格式

每行一个 JSON 对象：

\`\`\`json
{"ts":"2026-04-17T09:12:34.567Z","id":"java.nio.file.Files.list","event":"file list | /tmp","event_type":"file.list","event_level":"info","phase":"enter","requestId":"abc...","callId":42,"className":"java.nio.file.Files","methodName":"list","thread":"http-nio-8080-exec-1","params":{"file_path":"/tmp"}}
\`\`\`

配合 \`jq\` / Filebeat / Vector 消费：
\`\`\`bash
tail -f /tmp/iast-events-*.jsonl | jq -c 'select(.event_type == "file.list")'
\`\`\`

## 故障排查

| 现象 | 原因 / 排查 |
|-----|------------|
| \`Target process does not support attach\` | 目标 JVM 加了 \`-XX:+DisableAttachMechanism\`，或 attach 进程与目标不是同一 UID |
| iast-start.sh 挂后没日志 | 查 \`/tmp/iast-agent-<pid>.log\` 有无 \`Loaded monitor rule: ...\`；无即配置路径错了 |
| 事件日志空 | \`/tmp/iast-agent-<pid>.log\` 里看 \`[CustomEventPlugin] loaded N definition(s)\`；N=0 说明 YAML 没写 CustomEventPlugin 规则 |
| JRE 环境 attach 失败 | \`-DiastAttach=fallback\` 强走 byte-buddy-agent；或安装 jattach |
| Windows JRE | 需要 byte-buddy-agent 路径（自动），或装 jattach Windows 版 |

## 日志清理

进程退出后 \`/tmp/iast-agent-<pid>.log\` 和 \`/tmp/iast-events-<pid>.jsonl\` 不会自动删除。
生产环境建议配 logrotate 或定时清理。
README

echo "==> 打包 tarball"
(cd dist && tar czf "${NAME}.tar.gz" "${NAME}")

SIZE=$(du -h "dist/${NAME}.tar.gz" | cut -f1)
echo "==> 完成：dist/${NAME}.tar.gz  (${SIZE})"
echo "    内容："
ls -la "${OUT}"
