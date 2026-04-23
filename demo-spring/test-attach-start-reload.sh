#!/bin/bash
# attach start 补偿 transform + 热加载配置 功能测试
#
# 覆盖：
#   1. attach start 触发 reloadConfigBestEffort → 重新读取 rulesDir 下的规则
#   2. 新加的规则在 start 之后被 install（reinstallTransformerBestEffort 走 reset + 重装）
#   3. 删掉规则文件 + start 后，被删规则不再产出事件
#   4. 连发 2 次 start 不会让已有规则的 advice 被双挂（单次请求的 event 数不翻倍）
#   5. 日志里出现 reload:/reinstall:/retransform missing: 三条痕迹，证明新代码路径被走到
#
# 不覆盖（需要注入错误、改 agent 代码才能测）：
#   - INSTALL_SUCCEEDED=false 分支的恢复行为（首次 install 抛异常后 start 再装成功）
#
# Layout 自适应（同 test-monitor-switch.sh）：源码仓 / tarball 都能跑。

cd "$(dirname "$0")"
SCRIPT_DIR=$(pwd)

if [ -f "$SCRIPT_DIR/../iast-agent.jar" ]; then
    LAYOUT=tarball
    AGENT_JAR="$SCRIPT_DIR/../iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/demo-spring-1.0.0.jar"
    RULES_DIR_SRC="$SCRIPT_DIR/../rules.d"
    MAIN_YAML="$SCRIPT_DIR/../iast-monitor.yaml"
else
    LAYOUT=repo
    AGENT_JAR="$SCRIPT_DIR/../agent/target/iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/target/demo-spring-1.0.0.jar"
    RULES_DIR_SRC="$SCRIPT_DIR/../agent/rules.d"
    MAIN_YAML="$SCRIPT_DIR/../agent/iast-monitor.yaml"
fi

PID_FILE="/tmp/spring-demo-reload-test.pid"
DEMO_LOG="/tmp/spring-demo-reload-test.log"
TEST_OUT="/tmp/iast-reload-$$"
TEST_YAML="$SCRIPT_DIR/.iast-monitor-reload-$$.yaml"
TEST_RULES_DIR="$TEST_OUT/rules.d"
# 动态测试规则文件；hook FileCheckController.hello 方法，demo-spring 的 /api/hello 端点会调用它
EXTRA_RULE_FILE="$TEST_RULES_DIR/zz-test-hello.yaml"

mkdir -p "$TEST_OUT"

cleanup_procs() {
    if [ -f "$PID_FILE" ]; then
        kill "$(cat "$PID_FILE")" 2>/dev/null
        rm -f "$PID_FILE"
    fi
    pkill -f "demo-spring-1.0.0.jar" 2>/dev/null
    sleep 0.5
}
final_cleanup() {
    cleanup_procs
    rm -rf "$TEST_OUT"
    rm -f "$TEST_YAML"
}
if [ -z "$KEEP_LOGS" ]; then
    trap final_cleanup EXIT
else
    trap cleanup_procs EXIT
fi
cleanup_procs

[ -f "$APP_JAR" ]   || { echo "❌ demo jar 不存在: $APP_JAR"; exit 1; }
[ -f "$AGENT_JAR" ] || { echo "❌ agent jar 不存在: $AGENT_JAR"; exit 1; }
[ -d "$RULES_DIR_SRC" ] || { echo "❌ rules.d 不存在: $RULES_DIR_SRC"; exit 1; }
[ -f "$MAIN_YAML" ] || { echo "❌ 主 yaml 不存在: $MAIN_YAML"; exit 1; }

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

# 拷贝 rules.d 到测试目录，基于主 yaml 派生一份 test yaml——指向临时 rulesDir + 写到 TEST_OUT/<inst>/
# 主 yaml 里 outputDir/instanceName 是注释、rulesDir 是相对路径，用 awk 就地改：
#   - 在 `output:` 行后面注入 outputDir + instanceName 实值（覆盖注释版）
#   - rulesDir 原行替换成 TEST_RULES_DIR 绝对路径
cp -r "$RULES_DIR_SRC" "$TEST_RULES_DIR"
awk -v outdir="$TEST_OUT" -v inst="reload-$DEMO_PID" -v rd="$TEST_RULES_DIR" '
    /^output:/ { print; print "  outputDir: " outdir; print "  instanceName: " inst; next }
    /^    rulesDir:/ { print "    rulesDir: " rd; next }
    { print }
' "$MAIN_YAML" > "$TEST_YAML"

IAST_LOG="$TEST_OUT/reload-$DEMO_PID/iast.log"
EVENT_LOG="$TEST_OUT/reload-$DEMO_PID/iast.jsonl"
# 统计自定义 event_type=test.hello 的事件条数；不存在返回 0
count_hello_events() {
    [ -f "$EVENT_LOG" ] || { echo 0; return; }
    local n
    n=$(grep -c '"event_type":"test.hello"' "$EVENT_LOG" 2>/dev/null)
    echo "${n:-0}"
}

trigger_hello() {
    curl -s "http://127.0.0.1:8080/api/hello" > /dev/null 2>&1
}

echo "========================================"
echo "1. 首次挂载 agent（rulesDir 里尚未加入 hello 规则）"
echo "========================================"
java -jar "$AGENT_JAR" $DEMO_PID config="$TEST_YAML" > /dev/null
sleep 4

for i in 1 2 3; do trigger_hello; done
sleep 1

BASELINE=$(count_hello_events)
echo "📝 baseline test.hello 事件数: $BASELINE"
if [ "$BASELINE" -ne 0 ]; then
    echo "❌ 期望 baseline 为 0（规则文件尚未加入）实际为 $BASELINE"
    exit 1
fi
echo "✅ baseline 正确：hello 当前未被 hook"

echo "========================================"
echo "2. 向 rulesDir 写入新规则文件，发送 attach start"
echo "========================================"
cat > "$EXTRA_RULE_FILE" <<'EOF'
id: test.hello.controller
className: com.iast.demo.FileCheckController
methods:
  - "hello#()Ljava/lang/String;"
plugin: CustomEventPlugin
pluginConfig:
  event: "hello endpoint hit"
  event_type: test.hello
  event_level: info
EOF
echo "📝 已写入 $EXTRA_RULE_FILE"

java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 3

echo "📝 检查 agent 日志里的 reload/reinstall/retransform 三条新日志"
RELOAD_LINE=$(grep -E "\[IAST Agent\] reload: re-loading config" "$IAST_LOG" | tail -1)
REINSTALL_LINE=$(grep -E "\[IAST Agent\] reinstall: resetting old transformer" "$IAST_LOG" | tail -1)
RETRANSFORM_LINE=$(grep -E "\[IAST Agent\] retransform missing" "$IAST_LOG" | tail -1)
[ -z "$RELOAD_LINE" ]     && { echo "❌ 未找到 reload 日志"; exit 1; }
[ -z "$REINSTALL_LINE" ]  && { echo "❌ 未找到 reinstall 日志"; exit 1; }
[ -z "$RETRANSFORM_LINE" ] && { echo "❌ 未找到 retransform missing 日志"; exit 1; }
echo "   $RELOAD_LINE"
echo "   $REINSTALL_LINE"
echo "   $RETRANSFORM_LINE"
echo "✅ 三条新代码路径日志都出现"

for i in 1 2 3; do trigger_hello; done
sleep 1

AFTER_ADD=$(count_hello_events)
echo "📝 加规则 + start 后 test.hello 事件数: $AFTER_ADD"
if [ "$AFTER_ADD" -lt 3 ]; then
    echo "❌ 新规则未生效：期望 ≥3 实际 $AFTER_ADD"
    exit 1
fi
echo "✅ 新规则热加载生效"

echo "========================================"
echo "3. start 幂等性：连发 2 次 start 不应使 advice 被双挂"
echo "========================================"
java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 2
java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 3

BEFORE_IDEMPOTENT=$(count_hello_events)
trigger_hello
sleep 1
AFTER_IDEMPOTENT=$(count_hello_events)
DELTA=$((AFTER_IDEMPOTENT - BEFORE_IDEMPOTENT))
echo "📝 一次 /api/hello 调用触发的 test.hello 事件增量: $DELTA"
# 期望 1；若 reset 漏掉旧 transformer 会变成 2 或更多
if [ "$DELTA" -ne 1 ]; then
    echo "❌ advice 被双挂（或没挂上）：期望 1，实际 $DELTA"
    exit 1
fi
echo "✅ 连发两次 start 后 advice 未双挂（每次请求恰好 1 个事件）"

echo "========================================"
echo "4. 删除规则文件 + start，验证被删规则不再命中"
echo "========================================"
rm -f "$EXTRA_RULE_FILE"
java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 3

BEFORE_DEL=$(count_hello_events)
for i in 1 2 3; do trigger_hello; done
sleep 1
AFTER_DEL=$(count_hello_events)
DEL_DELTA=$((AFTER_DEL - BEFORE_DEL))
echo "📝 删除规则后 3 次请求的 test.hello 事件增量: $DEL_DELTA"
if [ "$DEL_DELTA" -ne 0 ]; then
    echo "❌ 规则已删但 hook 仍在生效（增量应为 0，实际 $DEL_DELTA）"
    exit 1
fi
echo "✅ 删除规则 + start 后 hook 确实下线"

echo "========================================"
echo "5. 验证老规则（file-io）依然正常：回归未受 reset/重装影响"
echo "========================================"
# /api/check-file 调 java.io.File.exists → rule id=file.io.File → event_type=file.io
BEFORE_FILE=$(grep -c '"event_type":"file.io"' "$EVENT_LOG" 2>/dev/null); BEFORE_FILE=${BEFORE_FILE:-0}
curl -s -X POST "http://127.0.0.1:8080/api/check-file?path=/etc/passwd" > /dev/null
sleep 1
AFTER_FILE=$(grep -c '"event_type":"file.io"' "$EVENT_LOG" 2>/dev/null); AFTER_FILE=${AFTER_FILE:-0}
if [ "$AFTER_FILE" -le "$BEFORE_FILE" ]; then
    echo "❌ 老规则 file.io 失效：$BEFORE_FILE → $AFTER_FILE"
    exit 1
fi
echo "✅ 老规则依然生效（file.io 事件数 $BEFORE_FILE → $AFTER_FILE）"

echo "========================================"
echo "✅ attach start 补偿 transform + 热加载配置 测试全部通过"
echo "========================================"
echo "📝 IAST 日志: $IAST_LOG"
echo "📝 事件日志: $EVENT_LOG"
