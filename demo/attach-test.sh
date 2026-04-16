#!/bin/bash
# IAST Agent Attach模式自动验证脚本
# 功能：自动启动后台测试程序，挂载Agent，验证功能，最后清理进程

cd "$(dirname "$0")"
AGENT_JAR="../agent/target/iast-agent.jar"
DEMO_OUTPUT="/tmp/demo-attach-test.log"
PID_FILE="/tmp/demo-test.pid"

# 检查Agent是否存在
if [ ! -f "$AGENT_JAR" ]; then
    echo "❌ Error: Agent jar not found at $AGENT_JAR"
    echo "请先构建Agent：cd ../agent && ./build.sh"
    exit 1
fi

# 1. 清理旧进程
echo "🧹 清理旧进程..."
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat $PID_FILE)
    kill $OLD_PID 2>/dev/null
    rm -f $PID_FILE
fi
pkill -f "com.weihua.MyTest" 2>/dev/null
sleep 0.5

# 2. 启动后台测试程序
echo "🚀 启动后台测试程序..."
setsid java  -cp . com.weihua.MyTest > $DEMO_OUTPUT 2>&1 < /dev/null &
DEMO_PID=$!
echo $DEMO_PID > $PID_FILE
echo "✅ 测试程序已启动，PID: $DEMO_PID"
sleep 0.1

# 3. 检查进程是否运行
if ! ps aux | grep $DEMO_PID | grep -q "com.weihua.MyTest"; then
    echo "❌ Error: 测试程序启动失败"
    cat $DEMO_OUTPUT
    exit 1
fi

# 4. 执行Attach挂载
echo "🔗 开始挂载IAST Agent..."
ATTACH_OUTPUT=$(cd ../agent && java -jar target/iast-agent.jar $DEMO_PID 2>&1)

if echo "$ATTACH_OUTPUT" | grep -q "IAST Agent loaded successfully"; then
    echo "✅ Agent挂载成功！"
else
    echo "❌ Agent挂载失败，错误信息："
    echo "$ATTACH_OUTPUT"
    kill $DEMO_PID
    rm -f $PID_FILE
    exit 1
fi

# 5. 等待2秒，让Agent完成初始化并触发方法拦截
sleep 2

# 6. 验证拦截效果
echo "🔍 验证方法拦截效果..."
echo "======================================"
echo "📝 目标程序输出（含Agent拦截日志）："
echo "--------------------------------------"
grep -E "\[IAST Agent\]|exists|path|Intercepted" $DEMO_OUTPUT | tail -20
echo "======================================"

# 验证具体函数拦截情况
FILE_EXISTS_INTERCEPTED=$(grep -q "Intercepted method call: java.io.File.exists" $DEMO_OUTPUT && echo "✅" || echo "❌")
NIO_FILES_EXISTS_INTERCEPTED=$(grep -q "Intercepted method call: java.nio.file.Files.exists" $DEMO_OUTPUT && echo "✅" || echo "❌")

echo "📋 监控方法拦截结果："
echo "  - java.io.File.exists(): $FILE_EXISTS_INTERCEPTED"
echo "  - java.nio.file.Files.exists(): $NIO_FILES_EXISTS_INTERCEPTED"
echo "======================================"

if [ "$FILE_EXISTS_INTERCEPTED" = "✅" ] && [ "$NIO_FILES_EXISTS_INTERCEPTED" = "✅" ]; then
    echo "🎉 Attach模式验证成功！所有配置的监控方法均已正常拦截"
else
    echo "❌ 错误：部分监控方法未被拦截，请检查Agent配置"
    kill $DEMO_PID
    rm -f $PID_FILE
    exit 1
fi

# 7. 清理进程
echo "🧹 清理测试进程..."
kill $DEMO_PID
rm -f $PID_FILE

echo ""
echo "✅ 验证完成！"
echo "📝 完整日志文件：$DEMO_OUTPUT"
