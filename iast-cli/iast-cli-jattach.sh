#!/bin/bash
# 打开 IAST Agent 的交互式 CLI（arthas 风格）
# 用法：./iast-cli-jattach.sh <target-pid>
#
# 流程：
#   1. 看 /tmp/iast-agent-<pid>.port 是否存在且端口能连上
#      - 不能：jattach 发 agentmain("cli") 让 agent 把 WebSocket server 起来
#   2. 读端口、exec java -jar iast-cli.jar <port>
#
# 要求：
#   - PATH 里有 jattach（首次启动 CLI 时用；若已起过可不需要）
#   - PATH 里有 java（8+；跑纯 JDK 的 CliClient）
#
# 环境变量（可选）：
#   IAST_AGENT_JAR  默认找 ../agent/iast-agent.jar（源码布局）或 ./iast-agent.jar（release tarball）
#   IAST_CLI_JAR    默认找 ./iast-cli.jar（或 target/iast-cli.jar）

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ----- 定位 agent jar（给 jattach 用）-----
# 两种常见布局：
#   A. 源码仓：iast-cli/ 和 agent/ 平级 → ../agent/iast-agent.jar (优先 agent/, 备选 agent/target/)
#   B. release tarball：所有东西在同一目录 → ./iast-agent.jar
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

# ----- 定位 cli jar（给 exec java 用）-----
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
    echo "Usage: $0 <target-pid>"
    echo "  环境变量 IAST_AGENT_JAR / IAST_CLI_JAR 可覆盖默认 jar 查找路径"
    exit 1
}

PID="${1:-}"
[ -z "${PID}" ] && usage

AGENT_JAR="$(resolve_agent_jar || true)"
CLI_JAR="$(resolve_cli_jar || true)"
if [ -z "${CLI_JAR}" ]; then
    echo "❌ iast-cli.jar 未找到。先 mvn package 或设置 IAST_CLI_JAR。"
    exit 1
fi
if [ -z "${AGENT_JAR}" ]; then
    echo "⚠  iast-agent.jar 未找到；如果 CLI 已经在目标进程里启动过就能连上，否则设置 IAST_AGENT_JAR"
fi
command -v java > /dev/null 2>&1 || { echo "❌ 未检测到 java（运行 CLI 客户端需要）"; exit 1; }

PORT_FILE="/tmp/iast-agent-${PID}.port"

port_alive() {
    # bash 内置 /dev/tcp 探活，不依赖 nc
    local p="$1"
    (echo > "/dev/tcp/127.0.0.1/${p}") >/dev/null 2>&1
}

read_port() {
    [ -f "${PORT_FILE}" ] && cat "${PORT_FILE}" 2>/dev/null || true
}

PORT="$(read_port)"
if [ -z "${PORT}" ] || ! port_alive "${PORT}"; then
    if ! command -v jattach > /dev/null 2>&1; then
        echo "❌ 未检测到 jattach 且目标进程 CLI 端口未打开。"
        echo "   请先安装 jattach（https://github.com/jattach/jattach/releases）"
        exit 1
    fi
    if [ -z "${AGENT_JAR}" ]; then
        echo "❌ 目标进程 CLI 未起，且找不到 iast-agent.jar 无法发 jattach。设置 IAST_AGENT_JAR 指向 agent jar。"
        exit 1
    fi
    [ -n "${PORT}" ] && rm -f "${PORT_FILE}"
    echo "→ 首次进入 CLI 或端口失效，jattach 让目标进程起 CLI server..."
    jattach "${PID}" load instrument false "${AGENT_JAR}=cli" >/dev/null
    # 等最多 3s 让 agent 写 port 文件
    for i in 1 2 3 4 5 6; do
        PORT="$(read_port)"
        if [ -n "${PORT}" ] && port_alive "${PORT}"; then
            break
        fi
        sleep 0.5
    done
    if [ -z "${PORT}" ] || ! port_alive "${PORT}"; then
        echo "❌ CLI server 未在 3s 内就绪，请查看 /tmp/iast-agent-${PID}.log"
        exit 1
    fi
fi

echo "→ 已连接 127.0.0.1:${PORT}（Ctrl-D 或输入 quit 退出）"
exec java -jar "${CLI_JAR}" "${PORT}"
