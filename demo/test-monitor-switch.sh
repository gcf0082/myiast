#!/bin/bash
# 监控开关功能测试脚本
cd "$(dirname "$0")"
AGENT_JAR="../agent/target/iast-agent.jar"
PID_FILE="/tmp/demo-test.pid"
DEMO_LOG="/tmp/demo-test.log"

# 清理旧进程
echo "🧹 清理旧进程..."
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat $PID_FILE)
    kill $OLD_PID 2>/dev/null
    rm -f $PID_FILE
fi
pkill -f "com.weihua.MyTest" 2>/dev/null
sleep 0.5

# 启动demo程序
echo "🚀 启动Demo程序..."
setsid java -cp . com.weihua.MyTest > $DEMO_LOG 2>&1 < /dev/null &
DEMO_PID=$!
echo $DEMO_PID > $PID_FILE
echo "✅ Demo程序PID: $DEMO_PID"
sleep 0.5

echo "========================================"
echo "1. 首次挂载Agent，开启监控..."
echo "========================================"
cd ../agent && java -jar target/iast-agent.jar $DEMO_PID
sleep 2

# 验证拦截日志
IAST_LOG="/tmp/iast-agent-$DEMO_PID.log"
echo "📝 检查拦截日志..."
BEFORE_STOP_COUNT=$(grep "Intercepted method call" $IAST_LOG | wc -l)
echo "   停止前拦截日志数量: $BEFORE_STOP_COUNT"
if [ $BEFORE_STOP_COUNT -gt 0 ]; then
    echo "✅ 监控开启正常，拦截生效"
else
    echo "❌ 监控开启失败，无拦截日志"
    kill $DEMO_PID
    rm -f $PID_FILE
    exit 1
fi

echo "========================================"
echo "2. 执行stop命令，停止监控..."
echo "========================================"
cd ../agent && java -jar target/iast-agent.jar $DEMO_PID stop
sleep 2

# 验证停止后没有新的拦截日志（两次采样对比，确保完全停止）
STOP_COUNT1=$(grep "Intercepted method call" $IAST_LOG | wc -l)
echo "📝 停止后第1次统计拦截日志数量: $STOP_COUNT1"
sleep 3
STOP_COUNT2=$(grep "Intercepted method call" $IAST_LOG | wc -l)
echo "📝 停止后第2次统计拦截日志数量: $STOP_COUNT2"

if [ $STOP_COUNT1 -eq $STOP_COUNT2 ]; then
    echo "✅ 监控停止成功，停止后5秒内无新的拦截日志，目标程序已恢复正常"
else
    echo "❌ 监控停止失败，停止后仍有新的拦截日志产生"
    kill $DEMO_PID
    rm -f $PID_FILE
    exit 1
fi

echo "========================================"
echo "3. 执行start命令，重新开启监控..."
echo "========================================"
cd ../agent && java -jar target/iast-agent.jar $DEMO_PID start
sleep 2

# 验证恢复后持续产生新的拦截日志（两次采样对比，确认恢复）
START_COUNT1=$(grep "Intercepted method call" $IAST_LOG | wc -l)
echo "📝 恢复后第1次统计拦截日志数量: $START_COUNT1"
sleep 3
START_COUNT2=$(grep "Intercepted method call" $IAST_LOG | wc -l)
echo "📝 恢复后第2次统计拦截日志数量: $START_COUNT2"

if [ $START_COUNT2 -gt $START_COUNT1 ]; then
    echo "✅ 监控恢复成功，恢复后持续产生新的拦截日志"
else
    echo "❌ 监控恢复失败，恢复后无新的拦截日志产生"
    kill $DEMO_PID
    rm -f $PID_FILE
    exit 1
fi

echo "========================================"
echo "✅ 所有测试通过！监控开关功能正常"
echo "========================================"
echo "   1. 开启监控：✅ 正常拦截"
echo "   2. 停止监控：✅ 无新拦截，程序恢复"
echo "   3. 恢复监控：✅ 重新拦截生效"
echo ""
echo "📝 IAST日志文件：$IAST_LOG"

# 清理
echo "🧹 清理测试进程..."
kill $DEMO_PID
rm -f $PID_FILE
rm -f $IAST_LOG 2>/dev/null
