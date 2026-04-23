#!/bin/bash
# Expression `context` 顶层变量端到端测试
#
# 覆盖：
#   1. rule 的 my_params 里用 context.pid / context.hostname / context.stackTrace /
#      context.stackTraceClasses / context.instanceName / context.phase / context.threadName
#      都能正确求值
#   2. filter 的 unless 用 context.stackTraceClasses contains "<known-package>" 能正确
#      drop 掉走过该包的调用（verified via 带 filter 之后事件计数不增）
#   3. 去掉 filter 再 attach start，事件重新出现
#
# 不覆盖：
#   - hostname 的 null/empty 边界（容器环境里可能抛，这里只断言"非异常、字段存在"）

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

PID_FILE="/tmp/spring-demo-ctx-test.pid"
DEMO_LOG="/tmp/spring-demo-ctx-test.log"
TEST_OUT="/tmp/iast-ctx-$$"
TEST_YAML="$SCRIPT_DIR/.iast-monitor-ctx-$$.yaml"
TEST_RULES_DIR="$TEST_OUT/rules.d"
TEST_FILTERS_DIR="$TEST_OUT/filters.d"
RULE_FILE="$TEST_RULES_DIR/zz-ctx-hello.yaml"
FILTER_FILE="$TEST_FILTERS_DIR/zz-ctx-drop.yaml"
INSTANCE_NAME="ctx-$$"
# 栈里必走的包，用来断言 stackTraceClasses / stackTrace 字段拼出来的字符串里包含它
KNOWN_PKG="org.springframework.web.servlet."

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

# 拷贝 rules.d，加一条用 context.* 的 rule 文件
cp -r "$RULES_DIR_SRC" "$TEST_RULES_DIR"
mkdir -p "$TEST_FILTERS_DIR"

cat > "$RULE_FILE" <<EOF
id: ctx.demo.hello
className: com.iast.demo.FileCheckController
methods:
  - "hello#()Ljava/lang/String;"
plugin: CustomEventPlugin
pluginConfig:
  my_params:
    pid:                 "context.pid"
    hostname:            "context.hostname"
    instance_name:       "context.instanceName"
    phase:               "context.phase"
    thread_name:         "context.threadName"
    stack_trace:         "context.stackTrace"
    stack_classes:       "context.stackTraceClasses"
  event: "ctx demo hit"
  event_type: ctx.demo
  event_level: info
EOF

# 派生 test yaml：outputDir 指临时目录、instanceName 定值、rulesDir 指临时 rules、filtersDir 指临时 filters
awk -v outdir="$TEST_OUT" -v inst="$INSTANCE_NAME" -v rd="$TEST_RULES_DIR" -v fd="$TEST_FILTERS_DIR" '
    /^output:/ {
        print; print "  outputDir: " outdir; print "  instanceName: " inst; next
    }
    /^    rulesDir:/    { print "    rulesDir: " rd; next }
    /^    filtersDir:/  { print "    filtersDir: " fd; next }
    { print }
' "$MAIN_YAML" > "$TEST_YAML"

IAST_LOG="$TEST_OUT/$INSTANCE_NAME/iast.log"
EVENT_LOG="$TEST_OUT/$INSTANCE_NAME/iast.jsonl"

trigger_hello() {
    curl -s "http://127.0.0.1:8080/api/hello" > /dev/null 2>&1
}

count_ctx_events() {
    [ -f "$EVENT_LOG" ] || { echo 0; return; }
    local n
    n=$(grep -c '"event_type":"ctx.demo"' "$EVENT_LOG" 2>/dev/null)
    echo "${n:-0}"
}

echo "========================================"
echo "1. 挂载 agent"
echo "========================================"
java -jar "$AGENT_JAR" $DEMO_PID config="$TEST_YAML" > /dev/null
sleep 4

[ -f "$EVENT_LOG" ] || { echo "⚠️  EVENT_LOG 路径尚未创建，可能 agent 日志路径解析失败：$EVENT_LOG"; }

echo "========================================"
echo "2. 触发 /api/hello，校验 context.* 各字段已被正确求值"
echo "========================================"
trigger_hello
sleep 1

LINE=$(grep '"event_type":"ctx.demo"' "$EVENT_LOG" 2>/dev/null | tail -1)
if [ -z "$LINE" ]; then
    echo "❌ 未发到 ctx.demo 事件"
    echo "--- agent log tail ---"
    tail -30 "$IAST_LOG" 2>/dev/null
    exit 1
fi
echo "   事件行: $LINE"

# 断言 pid
if ! echo "$LINE" | grep -q "\"pid\":\"$DEMO_PID\""; then
    echo "❌ context.pid 不等于 demo 进程 pid（期望 $DEMO_PID）"
    exit 1
fi
echo "✅ context.pid = $DEMO_PID"

# hostname 只要非空字符串就算通过
if ! echo "$LINE" | grep -qE '"hostname":"[^"]+"'; then
    echo "❌ context.hostname 为空或字段缺失"
    exit 1
fi
echo "✅ context.hostname 非空"

# instanceName 必须等于我们指定值
if ! echo "$LINE" | grep -q "\"instance_name\":\"$INSTANCE_NAME\""; then
    echo "❌ context.instanceName 不等于 '$INSTANCE_NAME'"
    exit 1
fi
echo "✅ context.instanceName = $INSTANCE_NAME"

# phase 应为 ENTER（规则没显式 on:，默认 ENTER-only）
if ! echo "$LINE" | grep -q '"phase":"ENTER"'; then
    echo "❌ context.phase 不是 ENTER"
    exit 1
fi
echo "✅ context.phase = ENTER"

# threadName 在 Tomcat 下形如 http-nio-8080-exec-N
if ! echo "$LINE" | grep -qE '"thread_name":"http-nio-[^"]+"'; then
    echo "❌ context.threadName 不像 Tomcat 工作线程名"
    exit 1
fi
echo "✅ context.threadName 是 Tomcat 工作线程"

# stackTrace 多行字符串里应有 DispatcherServlet（会被 JSON 转义成 \n 分隔）
if ! echo "$LINE" | grep -q "$KNOWN_PKG"; then
    echo "❌ context.stackTrace 里没有 $KNOWN_PKG"
    exit 1
fi
echo "✅ context.stackTrace 包含 $KNOWN_PKG"

# stackTraceClasses 用 | 分隔；应同样含 spring web servlet 包
if ! echo "$LINE" | grep -qE "\"stack_classes\":\"[^\"]*${KNOWN_PKG//./\\.}"; then
    echo "❌ context.stackTraceClasses 里没有 $KNOWN_PKG"
    exit 1
fi
echo "✅ context.stackTraceClasses 包含 $KNOWN_PKG"

echo "========================================"
echo "3. 加 filter：unless: context.stackTraceClasses contains 已知包 → 应全 drop"
echo "========================================"
cat > "$FILTER_FILE" <<EOF
id: filter.ctx.demo.drop-by-stack
target: ctx.demo.hello
unless:
  - expr: "context.stackTraceClasses"
    op: contains
    value: "$KNOWN_PKG"
EOF

BEFORE_FILTER=$(count_ctx_events)
java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 3

for i in 1 2 3; do trigger_hello; done
sleep 1

AFTER_FILTER=$(count_ctx_events)
DELTA=$((AFTER_FILTER - BEFORE_FILTER))
echo "📝 加 filter 后 3 次请求事件增量: $DELTA"
if [ "$DELTA" -ne 0 ]; then
    echo "❌ filter 未生效：期望 drop 全部（增量 0），实际 +$DELTA"
    exit 1
fi
echo "✅ context.stackTraceClasses 过滤生效（drop 全部）"

echo "========================================"
echo "4. 删 filter，attach start，事件应恢复"
echo "========================================"
rm -f "$FILTER_FILE"
BEFORE_NOFILTER=$(count_ctx_events)
java -jar "$AGENT_JAR" $DEMO_PID start > /dev/null
sleep 3

for i in 1 2 3; do trigger_hello; done
sleep 1

AFTER_NOFILTER=$(count_ctx_events)
DELTA2=$((AFTER_NOFILTER - BEFORE_NOFILTER))
echo "📝 删 filter 后 3 次请求事件增量: $DELTA2"
if [ "$DELTA2" -lt 3 ]; then
    echo "❌ filter 已删但事件没恢复：期望 ≥3，实际 +$DELTA2"
    exit 1
fi
echo "✅ 删除 filter + start 后事件重新出现"

echo "========================================"
echo "✅ Expression context.* 测试全部通过"
echo "========================================"
echo "📝 事件日志: $EVENT_LOG"
