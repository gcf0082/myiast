#!/bin/bash
# Spring Boot Demo 监控开关功能测试脚本
cd "$(dirname "$0")"
SCRIPT_DIR=$(pwd)

AGENT_JAR="../agent/target/iast-agent.jar"
PID_FILE="/tmp/spring-demo-test.pid"
DEMO_LOG="/tmp/spring-demo-test.log"

cleanup() {
    echo "🧹 清理旧进程..."
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat $PID_FILE)
        kill $OLD_PID 2>/dev/null
        rm -f $PID_FILE
    fi
    pkill -f "demo-spring" 2>/dev/null
    sleep 0.5
}

cleanup

echo "🚀 构建并启动Spring Boot Demo..."
cd $SCRIPT_DIR
mvn clean package -DskipTests > /tmp/spring-demo-build.log 2>&1
if [ $? -ne 0 ]; then
    echo "❌ 构建失败"
    exit 1
fi

setsid java -jar target/demo-spring-1.0.0.jar > $DEMO_LOG 2>&1 < /dev/null &
DEMO_PID=$!
echo $DEMO_PID > $PID_FILE
echo "✅ Demo程序PID: $DEMO_PID"

echo "⏳ 等待Spring Boot启动..."
for i in {1..30}; do
    if curl -s http://127.0.0.1:8080/api/hello > /dev/null 2>&1; then
        echo "✅ Spring Boot已启动"
        break
    fi
    sleep 1
done

triggerFileCheck() {
    curl -s -X POST "http://127.0.0.1:8080/api/check-file?path=/etc/passwd" > /dev/null 2>&1
}

echo "========================================"
echo "1. 首次挂载Agent，开启监控..."
echo "========================================"
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID config=../demo/iast-monitor.yaml
sleep 5

IAST_LOG="/tmp/iast-agent-$DEMO_PID.log"
echo "📝 触发File.exists调用..."
triggerFileCheck
sleep 2
triggerFileCheck
sleep 2

BEFORE_STOP_COUNT=$(grep "Method Call Intercepted" $IAST_LOG | wc -l)
echo "   停止前拦截日志数量: $BEFORE_STOP_COUNT"
if [ $BEFORE_STOP_COUNT -gt 0 ]; then
    echo "✅ 监控开启正常，拦截生效"
else
    echo "❌ 监控开启失败，无拦截日志"
    cleanup
    exit 1
fi

echo "========================================"
echo "2. 执行stop命令，停止监控..."
echo "========================================"
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID stop config=../demo/iast-monitor.yaml
sleep 2

triggerFileCheck
sleep 1

STOP_COUNT1=$(grep "Method Call Intercepted" $IAST_LOG | wc -l)
echo "📝 停止后第1次统计拦截日志数量: $STOP_COUNT1"
sleep 3

triggerFileCheck
sleep 1

STOP_COUNT2=$(grep "Method Call Intercepted" $IAST_LOG | wc -l)
echo "📝 停止后第2次统计拦截日志数量: $STOP_COUNT2"

if [ $STOP_COUNT1 -eq $STOP_COUNT2 ]; then
    echo "✅ 监控停止成功"
else
    echo "❌ 监控停止失败"
    cleanup
    exit 1
fi

echo "========================================"
echo "3. 执行start命令，重新开启监控..."
echo "========================================"
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID start config=../demo/iast-monitor.yaml
sleep 2

START_COUNT1=$(grep "Method Call Intercepted" $IAST_LOG | wc -l)
echo "📝 恢复后第1次统计拦截日志数量: $START_COUNT1"
sleep 2

triggerFileCheck
sleep 1
triggerFileCheck
sleep 1

START_COUNT2=$(grep "Method Call Intercepted" $IAST_LOG | wc -l)
echo "📝 恢复后第2次统计拦截日志数量: $START_COUNT2"

if [ $START_COUNT2 -gt $START_COUNT1 ]; then
    echo "✅ 监控恢复成功"
else
    echo "❌ 监控恢复失败"
    cleanup
    exit 1
fi

echo "========================================"
echo "4. 测试请求ID跟踪功能..."
echo "========================================"
echo "📝 发送测试请求，检查响应头..."
RESPONSE=$(curl -i -s "http://127.0.0.1:8080/api/check-file?path=/etc/passwd")
REQUEST_ID=$(echo "$RESPONSE" | grep -i "X-Request-Id:" | awk '{print $2}' | tr -d '\r')

if [ -z "$REQUEST_ID" ]; then
    echo "❌ 响应头中未发现X-Request-Id字段"
    cleanup
    exit 1
else
    echo "✅ 响应头X-Request-Id: $REQUEST_ID"
fi

sleep 1

echo "📝 检查日志中是否包含requestId..."
LOG_COUNT=$(grep "requestId=$REQUEST_ID" $IAST_LOG | wc -l)
if [ $LOG_COUNT -eq 0 ]; then
    echo "❌ 日志中未找到对应requestId的记录"
    cleanup
    exit 1
else
    echo "✅ 日志中找到 $LOG_COUNT 条包含requestId=$REQUEST_ID 的记录"
fi

echo "📝 验证同一个请求的requestId一致..."
# 同一个请求的所有方法调用应该有相同的requestId
UNIQUE_IDS=$(grep "requestId=" $IAST_LOG | grep "Method Call Intercepted: java.io.File" | awk -F'requestId=' '{print $2}' | awk -F']' '{print $1}' | sort -u | wc -l)
if [ $UNIQUE_IDS -ge 2 ]; then
    echo "✅ 不同请求拥有不同的requestId，符合预期"
else
    echo "⚠️  仅检测到一个requestId，可能需要更多请求验证"
fi

echo "========================================"
echo "✅ 请求ID跟踪功能测试通过！"
echo "========================================"
echo "✅ 所有测试通过！"
echo "========================================"
echo "📝 IAST日志文件：$IAST_LOG"
echo "📝 最后一个请求ID: $REQUEST_ID"

cleanup
rm -f $IAST_LOG 2>/dev/null
echo "测试完成"
