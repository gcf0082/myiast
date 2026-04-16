#!/bin/bash
AGENT_JAR="../agent/target/iast-agent.jar"
if [ ! -f "$AGENT_JAR" ]; then
    echo "Error: Agent jar not found at $AGENT_JAR"
    echo "Please build the agent first: cd ../agent && ./build.sh"
    exit 1
fi

echo "🚀 启动Demo程序，IAST Agent已加载"
echo "📝 IAST拦截日志将写入 /tmp/iast-agent-{进程ID}.log 文件"
echo ""

java \
-javaagent:$AGENT_JAR \
-cp . com.weihua.MyTest
