#!/bin/bash
# IAST Agent 自动Attach脚本
# 功能：自动启动demo程序，执行Agent挂载，无验证步骤

cd "$(dirname "$0")"
AGENT_JAR="../agent/target/iast-agent.jar"
DEMO_OUTPUT="/tmp/demo-attach.log"
PID_FILE="/tmp/demo-running.pid"

# 检查Agent是否存在
if [ ! -f "$AGENT_JAR" ]; then
    echo "❌ Error: Agent jar not found at $AGENT_JAR"
    echo "请先构建Agent：cd ../agent && ./build.sh"
    exit 1
fi

# 1. 清理旧进程
echo "🧹 清理旧的demo进程..."
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat $PID_FILE)
    kill $OLD_PID 2>/dev/null
    rm -f $PID_FILE
fi
pkill -f "com.weihua.MyTest" 2>/dev/null
sleep 0.5

# 2. 启动后台测试程序
echo "🚀 启动demo测试程序..."
setsid java  -cp . com.weihua.MyTest > $DEMO_OUTPUT 2>&1 < /dev/null &
DEMO_PID=$!
echo $DEMO_PID > $PID_FILE
echo "✅ Demo程序已启动，PID: $DEMO_PID"
sleep 0.1

# 3. 检查进程是否运行
if ! ps aux | grep $DEMO_PID | grep -q "com.weihua.MyTest"; then
    echo "❌ Error: 测试程序启动失败"
    cat $DEMO_OUTPUT
    exit 1
fi

# 4. 执行Attach挂载
echo "🔗 开始挂载IAST Agent..."
cd ../agent && java -jar target/iast-agent.jar $DEMO_PID
