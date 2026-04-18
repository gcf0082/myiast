#!/bin/bash
# demo-spring premain 模式运行脚本
#
# 演示如何在 JVM 启动时用 -javaagent 挂载 IAST Agent，
# 等价于生产环境在 JAVA_OPTS / catalina.sh / systemd unit 里加 -javaagent。
#
# 用法：./run-premain.sh [extra-agent-args]
#   extra-agent-args  可选，直接拼到 agent 参数里（如 config=...）
set -e

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"

# Layout 自适应：tarball（agent jar 在 ../）vs 源码仓（agent 模块 target/）
if [ -f "$SCRIPT_DIR/../iast-agent.jar" ]; then
    AGENT_JAR="$SCRIPT_DIR/../iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/demo-spring-1.0.0.jar"
else
    AGENT_JAR="$SCRIPT_DIR/../agent/target/iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/target/demo-spring-1.0.0.jar"
fi
CONFIG="$SCRIPT_DIR/iast-monitor.yaml"
EXTRA_AGENT_ARGS="${1:-}"

[ ! -f "$AGENT_JAR" ] && { echo "❌ Agent jar 不存在：$AGENT_JAR (先跑 agent 的 mvn package)"; exit 1; }
[ ! -f "$APP_JAR" ]   && { echo "❌ demo-spring jar 不存在：$APP_JAR (先跑 demo-spring 的 mvn package)"; exit 1; }
[ ! -f "$CONFIG" ]    && { echo "❌ 配置文件不存在：$CONFIG"; exit 1; }

AGENT_ARGS="config=$CONFIG"
if [ -n "$EXTRA_AGENT_ARGS" ]; then
    AGENT_ARGS="$AGENT_ARGS $EXTRA_AGENT_ARGS"
fi

echo "==> 以 premain 模式启动 demo-spring"
echo "    Agent : $AGENT_JAR"
echo "    Config: $CONFIG"
echo "    App   : $APP_JAR"
echo "    -javaagent:$AGENT_JAR=$AGENT_ARGS"
echo
echo "按 Ctrl+C 退出；日志实时输出到控制台。"
echo "    IAST 日志：/tmp/iast-agent-<pid>.log"
echo "    JSONL 事件：/tmp/iast-events-<pid>.jsonl"
echo
exec java \
    "-javaagent:$AGENT_JAR=$AGENT_ARGS" \
    -jar "$APP_JAR"
