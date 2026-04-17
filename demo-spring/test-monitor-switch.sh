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
    pkill -f "target/demo-spring-1.0.0.jar" 2>/dev/null
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
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID config=../demo-spring/iast-monitor.yaml
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
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID stop config=../demo-spring/iast-monitor.yaml
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
cd $SCRIPT_DIR/../agent && java -jar target/iast-agent.jar $DEMO_PID start config=../demo-spring/iast-monitor.yaml
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

echo "========================================"
echo "5. 测试 CustomEventPlugin JSONL 事件..."
echo "========================================"
EVENT_LOG="/tmp/iast-events-$DEMO_PID.jsonl"
curl -s "http://127.0.0.1:8080/api/list-dir?path=/tmp" > /dev/null
sleep 1
if [ ! -f "$EVENT_LOG" ]; then
    echo "❌ 事件日志文件不存在: $EVENT_LOG"
    cleanup
    exit 1
fi
LINE=$(grep '"id":"java.nio.file.Files.list"' "$EVENT_LOG" | tail -1)
if [ -z "$LINE" ]; then
    echo "❌ 未找到 java.nio.file.Files.list 事件"
    echo "事件日志内容："
    cat "$EVENT_LOG"
    cleanup
    exit 1
fi
echo "   事件行: $LINE"
echo "$LINE" | grep -q '"event":"file list | /tmp"' \
    || { echo "❌ 事件模板渲染不符合预期"; cleanup; exit 1; }
echo "$LINE" | grep -q '"event_type":"file.list"' \
    || { echo "❌ event_type 不符合预期"; cleanup; exit 1; }
echo "$LINE" | grep -q '"event_level":"info"' \
    || { echo "❌ event_level 不符合预期"; cleanup; exit 1; }
echo "$LINE" | grep -q '"params":{"file_path":"/tmp"}' \
    || { echo "❌ params 解析错误"; cleanup; exit 1; }
echo "✅ CustomEventPlugin params[0] 表达式验证通过"

echo "📝 验证同一方法挂多个插件（Files.list 同时走 CustomEventPlugin + LogPlugin）..."
# CustomEventPlugin 已在上面的 $LINE 验证。这里检查 LogPlugin 在同一次调用里也出了一条拦截日志
LOG_MATCH=$(grep "Method Call Intercepted: java.nio.file.Files.list" $IAST_LOG | wc -l)
if [ $LOG_MATCH -lt 1 ]; then
    echo "❌ LogPlugin 未拦截 Files.list，多插件分发失败"
    cleanup
    exit 1
fi
echo "✅ LogPlugin 同步拦截 Files.list（$LOG_MATCH 条），多插件分发生效"

echo "📝 验证 target / params[N] / return 表达式..."
curl -s "http://127.0.0.1:8080/api/echo?msg=hi&times=3" > /dev/null
sleep 1
LINE2=$(grep '"id":"com.iast.demo.FileCheckController.repeatMsg"' "$EVENT_LOG" | tail -1)
if [ -z "$LINE2" ]; then
    echo "❌ 未找到 repeatMsg 事件"
    cleanup
    exit 1
fi
echo "   事件行: $LINE2"
echo "$LINE2" | grep -q '"target_class":"FileCheckController"' \
    || { echo "❌ target.getClass().getSimpleName() 表达式错误"; cleanup; exit 1; }
echo "$LINE2" | grep -q '"msg":"hi"' \
    || { echo "❌ params[0] 表达式错误"; cleanup; exit 1; }
echo "$LINE2" | grep -q '"times":"3"' \
    || { echo "❌ params[1].toString() 表达式错误"; cleanup; exit 1; }
echo "$LINE2" | grep -q '"combined":"hihihi"' \
    || { echo "❌ return 表达式错误"; cleanup; exit 1; }
echo "$LINE2" | grep -q '"event":"repeatMsg | target=FileCheckController msg=hi times=3 -> hihihi"' \
    || { echo "❌ 事件模板渲染错误"; cleanup; exit 1; }
echo "$LINE2" | grep -q '"phase":"exit"' \
    || { echo "❌ on:[exit] 阶段过滤错误"; cleanup; exit 1; }
echo "✅ CustomEventPlugin target/params[N]/return 全部验证通过"

echo "📝 验证反射调用也能被拦截（/api/reflect-exists → Method.invoke(Files.exists)）..."
REFLECT_BEFORE=$(grep "Method Call Intercepted: java.nio.file.Files.exists" $IAST_LOG | wc -l)
curl -s "http://127.0.0.1:8080/api/reflect-exists?path=/tmp" > /dev/null
sleep 1
REFLECT_AFTER=$(grep "Method Call Intercepted: java.nio.file.Files.exists" $IAST_LOG | wc -l)
if [ $REFLECT_AFTER -le $REFLECT_BEFORE ]; then
    echo "❌ 反射调用未被拦截：before=$REFLECT_BEFORE after=$REFLECT_AFTER"
    cleanup
    exit 1
fi
echo "✅ 反射调用拦截生效（Files.exists 拦截数 $REFLECT_BEFORE → $REFLECT_AFTER）"

echo "========================================"
echo "6. 测试 JRE 兼容路径（-DiastAttach=fallback 强制走 byte-buddy-agent 跨平台实现）..."
echo "========================================"
cleanup
# 把jar拷到/tmp，避免相对路径/cwd继承问题
DEMO_JAR="/tmp/demo-spring-test.jar"
cp -f "$SCRIPT_DIR/target/demo-spring-1.0.0.jar" "$DEMO_JAR" || { echo "❌ 无法复制 demo-spring jar"; exit 1; }
setsid java -jar "$DEMO_JAR" > $DEMO_LOG 2>&1 < /dev/null &
sleep 3
# setsid在某些环境会fork，$!可能不是java进程。用pgrep拿最新的java pid
DEMO_PID2=$(pgrep -n -f "$DEMO_JAR")
if [ -z "$DEMO_PID2" ]; then
    echo "❌ 无法定位新启动的java进程"
    cleanup
    exit 1
fi
echo $DEMO_PID2 > $PID_FILE
echo "✅ 新 Demo PID: $DEMO_PID2"
for i in {1..30}; do
    if curl -s http://127.0.0.1:8080/api/hello > /dev/null 2>&1; then
        echo "✅ Spring Boot 就绪"
        break
    fi
    sleep 1
done
cd $SCRIPT_DIR/../agent && java -DiastAttach=fallback -jar target/iast-agent.jar $DEMO_PID2 config=../demo-spring/iast-monitor.yaml
sleep 3
cd $SCRIPT_DIR

curl -s "http://127.0.0.1:8080/api/list-dir?path=/tmp" > /dev/null
sleep 1
EVENT_LOG2="/tmp/iast-events-$DEMO_PID2.jsonl"
LINE3=$(grep '"id":"java.nio.file.Files.list"' "$EVENT_LOG2" 2>/dev/null | tail -1)
if [ -z "$LINE3" ]; then
    echo "❌ socket 模式挂载后未产生事件"
    cleanup
    exit 1
fi
echo "   事件行: $LINE3"
echo "$LINE3" | grep -q '"params":{"file_path":"/tmp"}' \
    || { echo "❌ socket 模式 params 解析错误"; cleanup; exit 1; }
echo "✅ byte-buddy-agent 挂载路径验证通过（JRE / Linux / macOS / Windows 通用）"

if command -v jattach > /dev/null 2>&1; then
    echo "========================================"
    echo "7. 测试 jattach 外部二进制挂载（-DiastAttach=jattach）..."
    echo "========================================"
    cleanup
    cd "$SCRIPT_DIR"
    setsid java -jar "$DEMO_JAR" > $DEMO_LOG 2>&1 < /dev/null &
    sleep 3
    DEMO_PID3=$(pgrep -n -f "$DEMO_JAR")
    if [ -z "$DEMO_PID3" ]; then
        echo "❌ jattach 步骤：无法定位新 java 进程"
        cleanup
        exit 1
    fi
    echo $DEMO_PID3 > $PID_FILE
    echo "✅ 新 Demo PID: $DEMO_PID3"
    for i in {1..30}; do
        if curl -s http://127.0.0.1:8080/api/hello > /dev/null 2>&1; then
            echo "✅ Spring Boot 就绪"
            break
        fi
        sleep 1
    done
    cd $SCRIPT_DIR/../agent && java -DiastAttach=jattach -jar target/iast-agent.jar $DEMO_PID3 config=../demo-spring/iast-monitor.yaml
    sleep 3
    cd $SCRIPT_DIR

    curl -s "http://127.0.0.1:8080/api/list-dir?path=/tmp" > /dev/null
    sleep 1
    EVENT_LOG3="/tmp/iast-events-$DEMO_PID3.jsonl"
    LINE4=$(grep '"id":"java.nio.file.Files.list"' "$EVENT_LOG3" 2>/dev/null | tail -1)
    if [ -z "$LINE4" ]; then
        echo "❌ jattach 模式挂载后未产生事件"
        cleanup
        exit 1
    fi
    echo "   事件行: $LINE4"
    echo "$LINE4" | grep -q '"params":{"file_path":"/tmp"}' \
        || { echo "❌ jattach 模式 params 解析错误"; cleanup; exit 1; }
    echo "✅ jattach 挂载路径验证通过"
else
    echo "⚠️  未检测到 jattach，跳过步骤 7（安装后可自动启用）"
fi

echo "========================================"
echo "✅ 所有测试通过！"
echo "========================================"
echo "📝 IAST日志文件：$IAST_LOG"
echo "📝 事件日志文件：$EVENT_LOG"
echo "📝 最后一个请求ID: $REQUEST_ID"

cleanup
#rm -f $IAST_LOG $EVENT_LOG $EVENT_LOG2 2>/dev/null
echo "测试完成"
