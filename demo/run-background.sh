#!/bin/bash
# 后台运行Demo程序，作为Attach测试的目标进程
cd "$(dirname "$0")"
echo "Starting demo program in background..."
echo "PID: $$"
echo "Process will run for 60 seconds..."

# 运行Demo，将输出重定向到文件
java -cp . com.weihua.MyTest > demo-output.log 2>&1 &
DEMO_PID=$!

echo "Demo PID: $DEMO_PID"
echo "Demo output: ./demo-output.log"
echo ""
echo "You can run attach command in another terminal:"
echo "java -jar ../agent/target/iast-agent.jar $DEMO_PID"
echo ""
echo "Waiting 60 seconds before exit..."
sleep 60

kill $DEMO_PID 2>/dev/null
echo "Demo process stopped."