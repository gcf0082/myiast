#!/bin/bash
# 启动/恢复 IAST 监控（不依赖 Java，直接调用外部 jattach 二进制）
# 用法：./iast-start-jattach.sh <target-pid> [config-file]
#
# 相比 iast-start.sh：不需要本机安装 JRE，只要 PATH 里有 jattach
# （https://github.com/jattach/jattach 下载预编译二进制，约 50KB）。
# 适合容器/生产环境只部署 jattach 不装 JDK 的场景。
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_JAR="${SCRIPT_DIR}/iast-agent.jar"
DEFAULT_CONFIG="${SCRIPT_DIR}/iast-monitor.yaml"

usage() {
    echo "Usage: $0 <target-pid> [config-file]"
    echo "  target-pid   目标 JVM 进程 ID"
    echo "  config-file  可选，默认为 ${DEFAULT_CONFIG}"
    echo
    echo "依赖：jattach 二进制（https://github.com/jattach/jattach）"
    exit 1
}

PID="${1:-}"
CONFIG="${2:-$DEFAULT_CONFIG}"

[ -z "${PID}" ] && usage
command -v jattach > /dev/null 2>&1 || {
    echo "❌ 未检测到 jattach。请到 https://github.com/jattach/jattach/releases 下载对应平台的二进制，放到 PATH 里。"
    exit 1
}
[ ! -f "${AGENT_JAR}" ] && { echo "❌ agent jar 不存在: ${AGENT_JAR}"; exit 1; }
[ ! -f "${CONFIG}" ] && { echo "❌ 配置文件不存在: ${CONFIG}"; exit 1; }
if [ ! -d "/proc/${PID}" ] 2>/dev/null && [ "$(uname)" = "Linux" ]; then
    echo "❌ 目标进程 ${PID} 不存在"
    exit 1
fi

# 通过日志文件判断是否已经挂载过，决定发 "config=..." 还是 "start"
LOG="/tmp/iast-agent-${PID}.log"
if [ -f "${LOG}" ] && grep -q "Agent installed successfully" "${LOG}" 2>/dev/null; then
    echo "→ Agent 已加载，jattach 发送 start 恢复监控..."
    ARGS="start"
else
    echo "→ 首次挂载，jattach 加载 agent，配置 ${CONFIG}"
    ARGS="config=${CONFIG}"
fi

# jattach 协议：jattach <pid> load instrument false "<jarpath>=<args>"
#   load        加载 JVMTI agent
#   instrument  HotSpot 内置的 Java agent 桥（识别 jar 里的 Agent-Class/Premain-Class）
#   false       library 不是绝对路径（instrument 是 JVM 内建名）
exec jattach "${PID}" load instrument false "${AGENT_JAR}=${ARGS}"
