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
