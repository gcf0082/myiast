#!/bin/bash
# CLI `monitor` 命令端到端测试（非交互 --command 模式）。
# 启一个 demo-spring，挂 agent，对若干场景跑 iast-cli-jattach.sh ... --command "monitor ..."，
# 抓 stdout 文件做断言。Case A/D/E 验功能；B/C 验错误路径。
#
# 复用 test-monitor-switch.sh 的脚手架风格（layout 自适应、TEST_OUT 临时根、trap 清理）。
cd "$(dirname "$0")"
SCRIPT_DIR=$(pwd)

if [ -f "$SCRIPT_DIR/../iast-agent.jar" ]; then
    LAYOUT=tarball
    AGENT_JAR="$SCRIPT_DIR/../iast-agent.jar"
    CLI_JAR="$SCRIPT_DIR/../iast-cli.jar"
    JATTACH_SCRIPT="$SCRIPT_DIR/../iast-cli-jattach.sh"
    APP_JAR="$SCRIPT_DIR/demo-spring-1.0.0.jar"
else
    LAYOUT=repo
    AGENT_JAR="$SCRIPT_DIR/../agent/target/iast-agent.jar"
    CLI_JAR="$SCRIPT_DIR/../iast-cli/target/iast-cli.jar"
    JATTACH_SCRIPT="$SCRIPT_DIR/../iast-cli/iast-cli-jattach.sh"
    APP_JAR="$SCRIPT_DIR/target/demo-spring-1.0.0.jar"
fi
CONFIG_YAML="$SCRIPT_DIR/iast-monitor.yaml"

TEST_OUT="/tmp/iast-monitor-cmd-$$"
mkdir -p "$TEST_OUT"

# 派生临时 yaml，把 outputDir/instanceName 切到测试目录（与 test-monitor-switch.sh 同套约定）。
# 临时 yaml 必须落在 $SCRIPT_DIR 才能让里面的 ./rules.d 之类相对路径解析正确。
TEST_YAML="$SCRIPT_DIR/.iast-monitor-cmd-$$.yaml"
DEMO_LOG="$TEST_OUT/demo.stdout"
DEMO_PID=""
MON_PIDS=()

cleanup_demo() {
    if [ -n "$DEMO_PID" ]; then
        kill -9 "$DEMO_PID" 2>/dev/null || true
    fi
    pkill -f "demo-spring-1.0.0.jar" 2>/dev/null || true
    sleep 0.3
}
final_cleanup() {
    for p in "${MON_PIDS[@]}"; do
        kill -9 "$p" 2>/dev/null || true
    done
    cleanup_demo
    rm -f "$TEST_YAML"
    # 留下 $TEST_OUT 方便排查；想要清理就 rm -rf "$TEST_OUT"
    echo "📂 测试输出：$TEST_OUT"
}
trap final_cleanup EXIT

echo "🧹 清理旧 demo..."
cleanup_demo

[ -f "$AGENT_JAR" ] || { echo "❌ agent jar 不存在：$AGENT_JAR"; exit 1; }
[ -f "$CLI_JAR"   ] || { echo "❌ cli jar 不存在：$CLI_JAR"; exit 1; }
[ -f "$APP_JAR"   ] || { echo "❌ demo jar 不存在：$APP_JAR（先 mvn package）"; exit 1; }
command -v jattach >/dev/null 2>&1 || { echo "❌ jattach 未安装"; exit 1; }

# 写临时 yaml：outputDir → $TEST_OUT，instanceName → demo
awk -v outdir="$TEST_OUT" -v inst="demo" '
    /^  outputDir:/ { print "  outputDir: " outdir; print "  instanceName: " inst; next }
    /^  instanceName:/ { next }
    { print }
' "$CONFIG_YAML" > "$TEST_YAML"

echo "🚀 启动 demo-spring（agent attached）..."
APP_DIR=$(dirname "$APP_JAR")
APP_BASENAME=$(basename "$APP_JAR")
# nohup + subshell 下 $! 拿到的是 wrapper PID，不一定是 JVM PID。等 Tomcat 起来后用
# jps 反查 demo 的 JVM PID 才稳；下面 jattach 必须打这个 PID。
( cd "$APP_DIR" && nohup java -javaagent:"$AGENT_JAR=config=$TEST_YAML" \
    -jar "$APP_BASENAME" > "$DEMO_LOG" 2>&1 & )

# 等 Tomcat
for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:8080/api/hello" >/dev/null 2>&1; then break; fi
    sleep 0.5
done
curl -sf "http://127.0.0.1:8080/api/hello" >/dev/null 2>&1 \
    || { echo "❌ Spring Boot 未起来"; tail -20 "$DEMO_LOG"; exit 1; }
DEMO_PID=$(jps | awk '/demo-spring-1.0.0.jar/ {print $1; exit}')
[ -n "$DEMO_PID" ] || { echo "❌ jps 找不到 demo JVM"; exit 1; }
echo "  demo PID=$DEMO_PID"
echo "  ✓ Spring Boot 就绪"

# 触发各 controller 路径，让对应类加载（monitor 要求 class loaded）
curl -sf "http://127.0.0.1:8080/api/check-file-get?path=/etc/passwd" >/dev/null
curl -sf "http://127.0.0.1:8080/api/list-dir?path=/tmp" >/dev/null
curl -sf "http://127.0.0.1:8080/api/echo?msg=hi&times=2" >/dev/null

# 跑一个 monitor，stdout 收到 $1 文件，等 $2 秒 install ok 后返回 PID。
# 用法：start_monitor <out-file> <wait-sec> <monitor-cmd-args...>
start_monitor() {
    local outfile="$1"; shift
    local wait_sec="$1"; shift
    local cmd="$*"
    "$JATTACH_SCRIPT" "$DEMO_PID" --command "$cmd" > "$outfile" 2>&1 &
    local pid=$!
    MON_PIDS+=("$pid")
    # 等到 stdout 看到 "monitor installed" 或 "ERROR" 任一即可继续
    local i=0
    while [ $i -lt $((wait_sec * 10)) ]; do
        if grep -qE "(monitor installed|ERROR:)" "$outfile" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
        sleep 0.1
        i=$((i + 1))
    done
    echo "$pid"
    return 1
}

stop_monitor() {
    local pid="$1"
    kill -INT "$pid" 2>/dev/null || true
    # CLI 走 shutdown hook 发 close 帧再退出，最多等 2s
    local i=0
    while [ $i -lt 20 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 0.1
        i=$((i + 1))
    done
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

PASSED=0
FAILED=0
fail() { echo "  ❌ $*"; FAILED=$((FAILED + 1)); }
ok()   { echo "  ✓ $*"; PASSED=$((PASSED + 1)); }

# ============================================================
# CASE A：基本流式 —— monitor checkFileGet，发 3 次请求，期望 3 行 SUCCESS
# ============================================================
echo ""
echo "======================================================================"
echo "  CASE A：基本逐次流式输出 + 自动撤销"
echo "======================================================================"
A_OUT="$TEST_OUT/case-a.out"
A_PID=$(start_monitor "$A_OUT" 8 "monitor com.iast.demo.FileCheckController checkFileGet")
if grep -q "monitor installed on com.iast.demo.FileCheckController.checkFileGet" "$A_OUT"; then
    ok "ack 正常：installed"
else
    fail "未拿到 install ack"; tail -5 "$A_OUT"
fi
for i in 1 2 3; do
    curl -sf "http://127.0.0.1:8080/api/check-file-get?path=/etc/passwd" >/dev/null
    sleep 0.15
done
sleep 0.8
HIT_LINES=$(grep -c "checkFileGet  SUCCESS" "$A_OUT" || true)
if [ "$HIT_LINES" -ge 3 ]; then
    ok "捕获到 ${HIT_LINES} 行 SUCCESS（≥3）"
else
    fail "SUCCESS 行数 = ${HIT_LINES}（期望 ≥3）"; sed -n '1,20p' "$A_OUT"
fi
stop_monitor "$A_PID"
sleep 0.5
# uninstall 日志应进入 demo 的 iast.log
IAST_LOG="$TEST_OUT/demo/iast.log"
if grep -q "monitor uninstalled: com.iast.demo.FileCheckController.checkFileGet (hits=" "$IAST_LOG" 2>/dev/null; then
    ok "agent 日志记录到 monitor uninstalled"
else
    fail "agent 日志缺 monitor uninstalled 行"; tail -10 "$IAST_LOG" 2>/dev/null
fi
# 撤销后再触发，demo 仍可用
curl -sf "http://127.0.0.1:8080/api/check-file-get?path=/etc/passwd" >/dev/null \
    && ok "撤销后 demo 仍正常服务" \
    || fail "撤销后 demo 接口不通"

# ============================================================
# CASE B：错误路径 —— 类未加载
# ============================================================
echo ""
echo "======================================================================"
echo "  CASE B：类未加载 → ERROR"
echo "======================================================================"
B_OUT="$TEST_OUT/case-b.out"
B_PID=$(start_monitor "$B_OUT" 6 "monitor no.such.NeverLoadedClass foo")
if grep -q "ERROR: class not loaded: no.such.NeverLoadedClass" "$B_OUT"; then
    ok "返回 ERROR: class not loaded"
else
    fail "未拿到 class-not-loaded ERROR"; sed -n '1,15p' "$B_OUT"
fi
stop_monitor "$B_PID"

# ============================================================
# CASE C：错误路径 —— 缺方法名
# ============================================================
echo ""
echo "======================================================================"
echo "  CASE C：缺方法名 → usage 提示"
echo "======================================================================"
C_OUT="$TEST_OUT/case-c.out"
C_PID=$(start_monitor "$C_OUT" 6 "monitor com.iast.demo.FileCheckController")
if grep -qE "usage: monitor <fqcn> <methodName>" "$C_OUT"; then
    ok "返回 usage 提示"
else
    fail "未返回 usage"; sed -n '1,15p' "$C_OUT"
fi
stop_monitor "$C_PID"

# ============================================================
# CASE D：异常路径 —— monitor listDir，传不存在的目录，期望 FAIL 行
# ============================================================
echo ""
echo "======================================================================"
echo "  CASE D：方法抛异常 → FAIL 行带异常类名"
echo "======================================================================"
D_OUT="$TEST_OUT/case-d.out"
D_PID=$(start_monitor "$D_OUT" 8 "monitor com.iast.demo.FileCheckController listDir")
if grep -q "monitor installed" "$D_OUT"; then
    ok "ack 正常"
else
    fail "未拿到 install ack"; sed -n '1,10p' "$D_OUT"
fi
# 触发存在路径（SUCCESS）和不存在路径（FAIL）
curl -sf "http://127.0.0.1:8080/api/list-dir?path=/tmp"          >/dev/null
curl -s  "http://127.0.0.1:8080/api/list-dir?path=/no/such/dir"  >/dev/null
sleep 0.8
if grep -q "listDir  SUCCESS" "$D_OUT"; then
    ok "捕获到 SUCCESS 行"
else
    fail "无 SUCCESS 行"
fi
if grep -qE "listDir  FAIL.*(NoSuchFileException|IOException|FileSystemException)" "$D_OUT"; then
    ok "捕获到 FAIL 行 + 异常类名"
else
    fail "无 FAIL 行 / 缺异常类名"; sed -n '1,15p' "$D_OUT"
fi
stop_monitor "$D_PID"

# ============================================================
# CASE E：描述符精确匹配 —— 仅匹配 String→String 这个签名
# ============================================================
echo ""
echo "======================================================================"
echo "  CASE E：描述符精确匹配"
echo "======================================================================"
E_OUT="$TEST_OUT/case-e.out"
E_PID=$(start_monitor "$E_OUT" 8 "monitor com.iast.demo.FileCheckController checkFileGet#(Ljava/lang/String;)Ljava/lang/String;")
if grep -q "monitor installed on com.iast.demo.FileCheckController.checkFileGet#(Ljava/lang/String;)Ljava/lang/String;" "$E_OUT"; then
    ok "ack 含描述符"
else
    fail "ack 不含描述符"; sed -n '1,10p' "$E_OUT"
fi
curl -sf "http://127.0.0.1:8080/api/check-file-get?path=/etc/passwd" >/dev/null
curl -sf "http://127.0.0.1:8080/api/check-file-get?path=/etc/hosts"  >/dev/null
sleep 0.8
HIT_E=$(grep -c "checkFileGet  SUCCESS" "$E_OUT" || true)
if [ "$HIT_E" -ge 2 ]; then
    ok "描述符精确匹配下捕获 ${HIT_E} 次"
else
    fail "描述符精确匹配 hits=${HIT_E}（期望 ≥2）"; sed -n '1,15p' "$E_OUT"
fi
stop_monitor "$E_PID"

# ============================================================
# 总结
# ============================================================
echo ""
echo "======================================================================"
if [ "$FAILED" -eq 0 ]; then
    echo "  ✅ 全部用例通过（$PASSED 项）"
    exit 0
else
    echo "  ❌ 失败 $FAILED 项 / 通过 $PASSED 项"
    exit 1
fi
