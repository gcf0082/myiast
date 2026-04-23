#!/bin/bash
# 打开 IAST Agent 的 CLI（arthas 风格）
# 用法：
#   ./iast-cli-jattach.sh <target-pid>                                  # 交互式 REPL
#   ./iast-cli-jattach.sh <target-pid> --command "monitor com.foo.Bar doSomething"
#                                                                       # 非交互：送一条命令、流式打印、Ctrl-C 退出
#
# v2 流程（CLI 监听，agent 主动 dial）：
#   1. 用 iast-cli.jar freeport 探一个空闲 loopback 端口
#   2. jattach 发 agentmain("cli=127.0.0.1:<port>") 让目标进程 dial 回来
#   3. exec iast-cli.jar listen 127.0.0.1 <port> [--command "..."] 在前台接 REPL / 流
#
# freeport 关端口和 listen 再起之间有 ~ms 级 race window；单用户开发机上实测不命中，
# 若线上环境遇到可以改用 --port-file 编排。
#
# 要求：
#   - PATH 里有 jattach
#   - PATH 里有 java（8+，跑纯 JDK 的 CliClient）
#
# 环境变量（可选）：
#   IAST_AGENT_JAR  默认 ../agent/iast-agent.jar（源码布局）或 ./iast-agent.jar（tarball）
#   IAST_CLI_JAR    默认 ./iast-cli.jar（或 target/iast-cli.jar）

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

resolve_agent_jar() {
    local candidates=(
        "${IAST_AGENT_JAR:-}"
        "${SCRIPT_DIR}/iast-agent.jar"
        "${SCRIPT_DIR}/../agent/iast-agent.jar"
        "${SCRIPT_DIR}/../agent/target/iast-agent.jar"
    )
    for c in "${candidates[@]}"; do
        [ -n "$c" ] && [ -f "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

resolve_cli_jar() {
    local candidates=(
        "${IAST_CLI_JAR:-}"
        "${SCRIPT_DIR}/iast-cli.jar"
        "${SCRIPT_DIR}/target/iast-cli.jar"
    )
    for c in "${candidates[@]}"; do
        [ -n "$c" ] && [ -f "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

usage() {
    echo "Usage: $0 <target-pid> [--command \"<cmd>\"]"
    echo "  环境变量 IAST_AGENT_JAR / IAST_CLI_JAR 可覆盖默认 jar 查找路径"
    echo "  --command \"<cmd>\"  非交互：送一条命令、流式打印响应，Ctrl-C 退出（典型用法：monitor）"
    exit 1
}

PID="${1:-}"
[ -z "${PID}" ] && usage
shift  # 后续参数（--command 等）会原样透传给 java -jar iast-cli.jar listen ...

CLI_JAR="$(resolve_cli_jar || true)"
AGENT_JAR="$(resolve_agent_jar || true)"
if [ -z "${CLI_JAR}" ]; then
    echo "❌ iast-cli.jar 未找到。先 mvn package 或设置 IAST_CLI_JAR。"
    exit 1
fi
if [ -z "${AGENT_JAR}" ]; then
    echo "❌ iast-agent.jar 未找到。设置 IAST_AGENT_JAR 指向 agent jar。"
    exit 1
fi
command -v java > /dev/null 2>&1 || { echo "❌ 未检测到 java（运行 CLI 客户端需要）"; exit 1; }
command -v jattach > /dev/null 2>&1 || {
    echo "❌ 未检测到 jattach。"
    echo "   请先安装 jattach（https://github.com/jattach/jattach/releases）"
    exit 1
}

# 1. 拿一个空闲端口
PORT="$(java -jar "${CLI_JAR}" freeport)" || { echo "❌ freeport 失败"; exit 1; }
if ! [[ "${PORT}" =~ ^[0-9]+$ ]]; then
    echo "❌ freeport 返回非数字：${PORT}"
    exit 1
fi

# 2. 让目标进程 dial 进来
echo "→ jattach 让目标进程 dial 回 127.0.0.1:${PORT} ..."
jattach "${PID}" load instrument false "${AGENT_JAR}=cli=127.0.0.1:${PORT}" >/dev/null

# 3. 前台起 CLI，等 agent 连进来
echo "→ CLI listening on 127.0.0.1:${PORT}，等待 agent 接入（Ctrl-D 或 quit 退出 REPL；非交互模式下 Ctrl-C）"
exec java -jar "${CLI_JAR}" listen 127.0.0.1 "${PORT}" "$@"
