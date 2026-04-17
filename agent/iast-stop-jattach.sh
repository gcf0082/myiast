#!/bin/bash
# 停止 IAST 监控（不依赖 Java，直接调用外部 jattach 二进制）
# 用法：./iast-stop-jattach.sh <target-pid>
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_JAR="${SCRIPT_DIR}/iast-agent.jar"

PID="${1:-}"
if [ -z "${PID}" ]; then
    echo "Usage: $0 <target-pid>"
    echo "依赖：jattach 二进制（https://github.com/jattach/jattach）"
    exit 1
fi
command -v jattach > /dev/null 2>&1 || {
    echo "❌ 未检测到 jattach。请到 https://github.com/jattach/jattach/releases 下载对应平台的二进制，放到 PATH 里。"
    exit 1
}
[ ! -f "${AGENT_JAR}" ] && { echo "❌ agent jar 不存在: ${AGENT_JAR}"; exit 1; }

exec jattach "${PID}" load instrument false "${AGENT_JAR}=stop"
