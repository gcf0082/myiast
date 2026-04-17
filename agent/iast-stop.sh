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
