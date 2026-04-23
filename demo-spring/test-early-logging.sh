#!/bin/bash
# 早期阶段日志兜底测试
#
# 覆盖：
#   1. 无配置文件（nonexistent）attach：agent 默认走 /tmp/iast_bootstrap_<pid>.log（repo 布局下 agent/target/logs/）
#   2. 损坏 yaml 配置：错误以 error 级别 + 堆栈记录到 bootstrap log
#   3. 默认落点 = agent jar 同目录下 logs/iast_bootstrap_<pid>.log
#
# 不覆盖（留给 manual / ad-hoc）：
#   - ro-fs 下 logs 目录不可写时的降级链（需要 root 改目录权限，CI 不稳定）

cd "$(dirname "$0")"
SCRIPT_DIR=$(pwd)

if [ -f "$SCRIPT_DIR/../iast-agent.jar" ]; then
    LAYOUT=tarball
    AGENT_JAR="$SCRIPT_DIR/../iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/demo-spring-1.0.0.jar"
else
    LAYOUT=repo
    AGENT_JAR="$SCRIPT_DIR/../agent/target/iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/target/demo-spring-1.0.0.jar"
fi
AGENT_JAR_DIR=$(cd "$(dirname "$AGENT_JAR")" && pwd)
BOOTSTRAP_LOGS_DIR="$AGENT_JAR_DIR/logs"

PID_FILE="/tmp/spring-demo-early-test.pid"
DEMO_LOG="/tmp/spring-demo-early-test.log"

cleanup_procs() {
    if [ -f "$PID_FILE" ]; then
        kill "$(cat "$PID_FILE")" 2>/dev/null
        rm -f "$PID_FILE"
    fi
    pkill -f "demo-spring-1.0.0.jar" 2>/dev/null
    sleep 0.5
}
trap cleanup_procs EXIT
cleanup_procs

[ -f "$APP_JAR" ]   || { echo "❌ demo jar 不存在: $APP_JAR"; exit 1; }
[ -f "$AGENT_JAR" ] || { echo "❌ agent jar 不存在: $AGENT_JAR"; exit 1; }

echo "🚀 启动 demo-spring..."
setsid java -jar "$APP_JAR" > "$DEMO_LOG" 2>&1 < /dev/null &
DEMO_PID=$!
echo $DEMO_PID > "$PID_FILE"
echo "✅ Demo PID: $DEMO_PID"

echo "⏳ 等 Spring Boot 就绪..."
for i in {1..30}; do
    if curl -s http://127.0.0.1:8080/api/hello > /dev/null 2>&1; then
        echo "✅ Spring Boot 就绪"
        break
    fi
    sleep 1
done

BOOTSTRAP_LOG="$BOOTSTRAP_LOGS_DIR/iast_bootstrap_${DEMO_PID}.log"
# 事先删掉可能存在的老文件，保证本次跑是干净的
rm -f "$BOOTSTRAP_LOG"

echo "========================================"
echo "1. attach 不存在的 yaml：expect bootstrap log 出现并含 'Config file not found'"
echo "========================================"
NONEXISTENT_YAML="/tmp/no-such-yaml-$$-$RANDOM.yaml"
java -jar "$AGENT_JAR" $DEMO_PID "config=$NONEXISTENT_YAML" > /dev/null 2>&1
sleep 3

if [ ! -f "$BOOTSTRAP_LOG" ]; then
    echo "❌ bootstrap log 文件未创建：$BOOTSTRAP_LOG"
    echo "--- 当前 agent jar dir logs 下的文件 ---"
    ls -la "$BOOTSTRAP_LOGS_DIR" 2>/dev/null
    exit 1
fi
echo "✅ bootstrap log 落点正确：$BOOTSTRAP_LOG"

if ! grep -q "bootstrap log file" "$BOOTSTRAP_LOG"; then
    echo "❌ 早期日志里缺少 'bootstrap log file' 自指行"
    exit 1
fi
echo "✅ 日志第一句自指 bootstrap 路径"

if ! grep -q "Config file not found" "$BOOTSTRAP_LOG"; then
    echo "❌ 不存在的 yaml 路径未产生 'Config file not found' 日志"
    echo "--- bootstrap log tail ---"
    tail -20 "$BOOTSTRAP_LOG"
    exit 1
fi
echo "✅ 配置文件不存在的错误已记录"

# 清理老文件，下一步是一个新的 demo 实例
cleanup_procs
rm -f "$BOOTSTRAP_LOG"

echo "========================================"
echo "2. attach 损坏的 yaml：expect bootstrap log 出现 'Failed to load config' + 堆栈"
echo "========================================"
echo "🚀 启动第二轮 demo-spring..."
setsid java -jar "$APP_JAR" > "$DEMO_LOG" 2>&1 < /dev/null &
DEMO_PID2=$!
echo $DEMO_PID2 > "$PID_FILE"
for i in {1..30}; do
    if curl -s http://127.0.0.1:8080/api/hello > /dev/null 2>&1; then
        echo "✅ Spring Boot 就绪"
        break
    fi
    sleep 1
done

BOOTSTRAP_LOG2="$BOOTSTRAP_LOGS_DIR/iast_bootstrap_${DEMO_PID2}.log"
rm -f "$BOOTSTRAP_LOG2"

# 构造一个 YAML 语法错误（冒号后面又来一个冒号）
BROKEN_YAML="/tmp/broken-iast-$$-$RANDOM.yaml"
cat > "$BROKEN_YAML" <<'EOF'
output:
  foo: : bar
monitor:
  default:
    rulesDir: ./rules.d
EOF

java -jar "$AGENT_JAR" $DEMO_PID2 "config=$BROKEN_YAML" > /dev/null 2>&1
sleep 3

if [ ! -f "$BOOTSTRAP_LOG2" ]; then
    echo "❌ bootstrap log 未创建：$BOOTSTRAP_LOG2"
    exit 1
fi

if ! grep -q "\[ERROR\]" "$BOOTSTRAP_LOG2"; then
    echo "❌ 损坏 yaml 没产生 ERROR 级别日志"
    echo "--- bootstrap log ---"
    cat "$BOOTSTRAP_LOG2"
    rm -f "$BROKEN_YAML"
    exit 1
fi
echo "✅ ERROR 级别记录"

if ! grep -q "Failed to load config" "$BOOTSTRAP_LOG2"; then
    echo "❌ 没找到 'Failed to load config' 消息"
    rm -f "$BROKEN_YAML"
    exit 1
fi
echo "✅ 错误消息记录"

# 堆栈应包含 SnakeYAML 相关类名
if ! grep -qE "org\.yaml\.snakeyaml|ScannerException|ParserException" "$BOOTSTRAP_LOG2"; then
    echo "❌ 没找到 SnakeYAML 的异常堆栈"
    echo "--- bootstrap log ---"
    cat "$BOOTSTRAP_LOG2"
    rm -f "$BROKEN_YAML"
    exit 1
fi
echo "✅ SnakeYAML 异常堆栈已记录"

rm -f "$BROKEN_YAML"

echo "========================================"
echo "3. 验证损坏 yaml 下不加载任何规则（monitoredClasses=0）"
echo "========================================"
# 用户决策：坏配置下不自动加默认规则兜底——让运维在日志里立刻看见"没规则生效"，而不是默默给个 File.exists 还以为配置生效了
if grep -q "monitoring 0 classes" "$BOOTSTRAP_LOG2"; then
    echo "✅ 损坏 yaml 下确实没加载任何规则"
elif grep -q "Agent installed successfully, monitoring [1-9]" "$BOOTSTRAP_LOG2"; then
    echo "❌ 损坏 yaml 仍然挂上了 N>0 个规则——期望 0"
    grep "Agent installed successfully" "$BOOTSTRAP_LOG2"
    exit 1
else
    echo "⚠️  没找到 'Agent installed successfully' 行，buildAndInstall 可能没走完"
    tail -20 "$BOOTSTRAP_LOG2"
fi

echo "========================================"
echo "✅ 早期日志兜底测试全部通过"
echo "========================================"
echo "📝 bootstrap log 1: $BOOTSTRAP_LOG"
echo "📝 bootstrap log 2: $BOOTSTRAP_LOG2"

# 本测试留下 bootstrap log 文件不清理，方便人工查看
