#!/bin/bash
AGENT_JAR="../agent/target/iast-agent.jar"
if [ ! -f "$AGENT_JAR" ]; then
    echo "Error: Agent jar not found at $AGENT_JAR"
    echo "Please build the agent first: cd ../agent && ./build.sh"
    exit 1
fi
java \
-javaagent:$AGENT_JAR \
-cp . com.weihua.MyTest
