#!/bin/bash
# 验证 matchType=interface 的端到端行为。
#
# 覆盖两条路径：
#   case A  includeFutureClasses=true  + premain  → Spring 启动后加载的 DispatcherServlet 等
#                                                    应被接口规则 transform + 产生 service() 拦截日志
#   case B  includeFutureClasses=false + premain  → 预装快照里没有 DispatcherServlet，
#                                                    不应出现接口规则产生的 service() 拦截日志
#
# 通过 RequestIdPlugin 在 <TMP_DIR>/<instanceName>/iast.log 写的 "[IAST RequestId]...Request Started"
# 行数来判定（RequestIdPlugin 不按 className 过滤规则，interface 分派进来它就会跑）。

set -euo pipefail

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"

# Layout 自适应：tarball（agent jar 在 ../）vs 源码仓（agent 模块 target/）
if [ -f "$SCRIPT_DIR/../iast-agent.jar" ]; then
    AGENT_JAR="$SCRIPT_DIR/../iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/demo-spring-1.0.0.jar"
else
    AGENT_JAR="$SCRIPT_DIR/../agent/target/iast-agent.jar"
    APP_JAR="$SCRIPT_DIR/target/demo-spring-1.0.0.jar"
fi

[ -f "$AGENT_JAR" ] || { echo "❌ Agent jar 不存在: $AGENT_JAR (源码仓：agent/ 跑 mvn package；tarball：检查文件位置)"; exit 1; }
[ -f "$APP_JAR" ]   || { echo "❌ demo-spring jar 不存在: $APP_JAR (源码仓：demo-spring/ 跑 mvn package；tarball：检查文件位置)"; exit 1; }

TMP_DIR="$(mktemp -d -t iast-iftest-XXXX)"
trap 'rc=$?; kill_demo; rm -rf "$TMP_DIR"; exit $rc' EXIT INT TERM

DEMO_PID=""
kill_demo() {
    if [ -n "${DEMO_PID:-}" ] && kill -0 "$DEMO_PID" 2>/dev/null; then
        kill "$DEMO_PID" 2>/dev/null || true
        # 给 JVM 一点时间退出
        for _ in 1 2 3 4 5; do
            kill -0 "$DEMO_PID" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$DEMO_PID" 2>/dev/null || true
    fi
    DEMO_PID=""
}

write_config() {
    # $1 = true | false ; $2 = premain delay (ms, defaults to 0 for fast tests)
    local include_future="$1"
    local delay_ms="${2:-0}"
    local rules_dir="$TMP_DIR/rules-${include_future}-d${delay_ms}"
    local path="$TMP_DIR/config-${include_future}-d${delay_ms}.yaml"
    # instanceName 决定 outputDir 下子目录名，测试时按 case 命名，便于 grep 特定 case 的日志
    local inst="case-${include_future}-d${delay_ms}"
    mkdir -p "$rules_dir"
    cat > "$rules_dir/servlet-trace.yaml" <<'EOF'
id: test.trace.req-id
className: jakarta.servlet.Servlet
matchType: interface
methods:
  - "service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"
plugin: RequestIdPlugin
EOF
    cat > "$path" <<EOF
output:
  args: true
  return: true
  stacktrace: false
  stacktraceDepth: 4
  outputDir: ${TMP_DIR}
  instanceName: ${inst}

monitor:
  default:
    includeFutureClasses: ${include_future}
    premainDelayMs: ${delay_ms}
    rulesDir: ${rules_dir}
EOF
    echo "$path"
}

# 根据 write_config 的命名规则反推日志文件路径
iast_log_for() {
    local include_future="$1"
    local delay_ms="${2:-0}"
    echo "$TMP_DIR/case-${include_future}-d${delay_ms}/iast.log"
}

run_case() {
    # $1 = human label, $2 = includeFutureClasses value
    local label="$1"
    local include_future="$2"
    local config
    config="$(write_config "$include_future")"
    local stdout_log="$TMP_DIR/demo-${include_future}.stdout"

    echo
    echo "======================================================================"
    echo "  CASE: $label  (includeFutureClasses=${include_future})"
    echo "======================================================================"
    echo "  config: $config"

    # 启动 demo（premain 模式）
    java -javaagent:"$AGENT_JAR=config=$config" -jar "$APP_JAR" \
        > "$stdout_log" 2>&1 &
    DEMO_PID=$!
    echo "  demo pid: $DEMO_PID"

    # 等 Tomcat 就绪（最多 45s）
    local ready=0
    for i in $(seq 1 90); do
        if grep -q "Tomcat started on port" "$stdout_log" 2>/dev/null; then
            ready=1
            break
        fi
        if ! kill -0 "$DEMO_PID" 2>/dev/null; then
            echo "❌ demo 进程提前退出"
            tail -30 "$stdout_log"
            return 1
        fi
        sleep 0.5
    done
    [ "$ready" -eq 1 ] || { echo "❌ Tomcat 45s 未就绪"; tail -30 "$stdout_log"; return 1; }
    echo "  ✓ Tomcat 就绪"

    # 触发几次 HTTP 请求；404 也会经过整条 servlet 调用链
    for _ in 1 2 3; do
        curl -s http://127.0.0.1:8080/api/hello -o /dev/null -w "" || true
    done
    sleep 1

    local iast_log
    iast_log="$(iast_log_for "$include_future" 0)"
    [ -f "$iast_log" ] || { echo "❌ 找不到 Agent 日志: $iast_log"; return 1; }

    echo "  Agent log: $iast_log"
    local interface_rule_cnt dispatcher_cnt service_hit_cnt
    interface_rule_cnt=$(grep -cE "Interface rule: match all implementations of jakarta\.servlet\.Servlet" "$iast_log" || true)
    dispatcher_cnt=$(grep -cE "Transformed: org\.springframework\.web\.servlet\.DispatcherServlet" "$iast_log" || true)
    # RequestIdPlugin 每次 service() 入口打一行 "Request Started"，直接数即可
    service_hit_cnt=$(grep -cE "\[IAST RequestId\].*Request Started" "$iast_log" || true)

    echo "  Interface 规则已装载行数: $interface_rule_cnt"
    echo "  DispatcherServlet Transformed 次数: $dispatcher_cnt"
    echo "  Request Started 行数（接口规则下 RequestIdPlugin 触发计数）: $service_hit_cnt"

    # 打印一条完整的 Request Started 日志，让开发者肉眼确认函数调用确实被 hook
    if [ "$service_hit_cnt" -gt 0 ]; then
        echo "  ---------- 示例 Request Started ----------"
        grep -E "\[IAST RequestId\].*Request Started" "$iast_log" | head -1 | sed 's/^/    /'
        echo "  -------------------------------------------"
    fi

    kill_demo

    # 断言
    if [ "$interface_rule_cnt" -lt 1 ]; then
        echo "❌ [$label] Agent 未登记 interface 规则"
        return 1
    fi

    if [ "$include_future" = "true" ]; then
        if [ "$dispatcher_cnt" -lt 1 ] || [ "$service_hit_cnt" -lt 1 ]; then
            echo "❌ [$label] 期望命中 Servlet 调用链（DispatcherServlet 应被 transform 且 service 被拦截），但计数为 0"
            return 1
        fi
        echo "✅ [$label] Servlet 调用链被接口规则成功拦截"
    else
        # premain 模式下 DispatcherServlet 一定是 agent 安装之后才加载的；
        # includeFutureClasses=false 应把它排除在外，因此 service 拦截应为 0
        if [ "$service_hit_cnt" -ne 0 ]; then
            echo "❌ [$label] 期望 0 次 service 拦截（开关 OFF），实际 $service_hit_cnt 次 —— 开关失效"
            return 1
        fi
        echo "✅ [$label] 开关 OFF 生效：后加载的 Servlet 实现未被拦截"
    fi
}

run_case "A. 开关 ON (premain 捕获未来加载的 Servlet)"  "true"
run_case "B. 开关 OFF (premain 下不覆盖未来加载的 Servlet)" "false"

run_delay_case() {
    # 验证 premainDelayMs：启动后立即访问不应被拦截；延迟到期后再访问应被拦截
    local include_future="true"
    local delay_ms=6000
    local config
    config="$(write_config "$include_future" "$delay_ms")"
    local stdout_log="$TMP_DIR/demo-delay.stdout"

    echo
    echo "======================================================================"
    echo "  CASE: C. premain 延迟 install (premainDelayMs=${delay_ms})"
    echo "======================================================================"
    echo "  config: $config"

    java -javaagent:"$AGENT_JAR=config=$config" -jar "$APP_JAR" \
        > "$stdout_log" 2>&1 &
    DEMO_PID=$!
    echo "  demo pid: $DEMO_PID"

    local ready=0
    for i in $(seq 1 90); do
        if grep -q "Tomcat started on port" "$stdout_log" 2>/dev/null; then
            ready=1
            break
        fi
        if ! kill -0 "$DEMO_PID" 2>/dev/null; then
            echo "❌ demo 进程提前退出"
            tail -30 "$stdout_log"
            return 1
        fi
        sleep 0.5
    done
    [ "$ready" -eq 1 ] || { echo "❌ Tomcat 未就绪"; tail -30 "$stdout_log"; return 1; }
    echo "  ✓ Tomcat 就绪"

    local iast_log
    iast_log="$(iast_log_for "$include_future" "$delay_ms")"
    [ -f "$iast_log" ] || { echo "❌ 找不到 Agent 日志: $iast_log"; return 1; }

    # 确认启动日志里看到了 "Bytecode install deferred"
    if ! grep -q "Bytecode install deferred by ${delay_ms}ms" "$iast_log"; then
        echo "❌ Agent 未打印 delay 日志"
        return 1
    fi
    echo "  ✓ Agent 打印了 'Bytecode install deferred by ${delay_ms}ms'"

    # 在延迟期间请求（应无拦截）
    curl -s http://127.0.0.1:8080/api/hello -o /dev/null -w "" || true
    sleep 1
    local during_hits
    during_hits=$(grep -cE "\[IAST RequestId\].*Request Started" "$iast_log" || true)
    echo "  延迟期间拦截次数: $during_hits"
    if [ "$during_hits" -ne 0 ]; then
        echo "❌ 延迟生效前不应有拦截（实际 $during_hits）"
        return 1
    fi

    # 等延迟到期 + 一点 buffer
    local wait_s=$((delay_ms / 1000 + 3))
    echo "  等 ${wait_s}s 让延迟到期..."
    sleep "$wait_s"

    if ! grep -q "Building AgentBuilder and installing transformers" "$iast_log"; then
        echo "❌ install 未触发"
        return 1
    fi
    echo "  ✓ install 已触发"

    # 延迟后再请求，应该能看到拦截
    curl -s http://127.0.0.1:8080/api/hello -o /dev/null -w "" || true
    curl -s http://127.0.0.1:8080/api/hello -o /dev/null -w "" || true
    sleep 1
    local after_hits
    after_hits=$(grep -cE "\[IAST RequestId\].*Request Started" "$iast_log" || true)
    echo "  延迟到期后拦截次数: $after_hits"

    kill_demo

    if [ "$after_hits" -lt 1 ]; then
        echo "❌ install 之后仍未拦截 service() 调用"
        return 1
    fi
    echo "✅ [CASE C] premain 延迟 install 按预期工作"
}

run_delay_case

write_body_config() {
    local hard_limit="${1:-10485760}"
    local rules_dir="$TMP_DIR/rules-body-h${hard_limit}"
    local path="$TMP_DIR/config-body-h${hard_limit}.yaml"
    local inst="case-body-h${hard_limit}"
    mkdir -p "$rules_dir"

    # RequestIdPlugin 挂在 Servlet 接口规则上——和下面 HttpServlet exact+wrap 的规则
    # 不共享 className，advice 栈各自独立，两者都能触发。
    cat > "$rules_dir/trace.yaml" <<'EOF'
id: test.body.trace
className: jakarta.servlet.Servlet
matchType: interface
methods:
  - "service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"
plugin: RequestIdPlugin
EOF
    # ServletBodyPlugin：缓冲 body + 打日志
    cat > "$rules_dir/body.yaml" <<EOF
id: test.body.wrap
className: jakarta.servlet.http.HttpServlet
methods:
  - "service#(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"
plugin: ServletBodyPlugin
wrapServletRequest: true
pluginConfig:
  maxLogBytes: 8192
  hardLimitBytes: ${hard_limit}
  charset: UTF-8
EOF
    cat > "$path" <<EOF
output:
  args: true
  return: true
  stacktrace: false
  outputDir: ${TMP_DIR}
  instanceName: ${inst}

monitor:
  default:
    includeFutureClasses: true
    premainDelayMs: 0
    rulesDir: ${rules_dir}
EOF
    echo "$path"
}

run_body_case() {
    local config="$(write_body_config 10485760)"
    local stdout_log="$TMP_DIR/demo-body.stdout"

    echo
    echo "======================================================================"
    echo "  CASE: D. ServletBodyPlugin 端到端（wrapServletRequest）"
    echo "======================================================================"
    echo "  config: $config"

    java -javaagent:"$AGENT_JAR=config=$config" -jar "$APP_JAR" \
        > "$stdout_log" 2>&1 &
    DEMO_PID=$!
    echo "  demo pid: $DEMO_PID"

    local ready=0
    for i in $(seq 1 90); do
        if grep -q "Tomcat started on port" "$stdout_log" 2>/dev/null; then ready=1; break; fi
        if ! kill -0 "$DEMO_PID" 2>/dev/null; then
            echo "❌ demo 进程提前退出"; tail -30 "$stdout_log"; return 1
        fi
        sleep 0.5
    done
    [ "$ready" -eq 1 ] || { echo "❌ Tomcat 未就绪"; tail -30 "$stdout_log"; return 1; }
    echo "  ✓ Tomcat 就绪"

    local iast_log="$TMP_DIR/case-body-h10485760/iast.log"

    # D.0：上游带 X-Seeker-Request-Id 头时 RequestIdPlugin 应直接复用，不生成新 UUID
    echo "  ---------- D.0 上游 X-Seeker-Request-Id 直通 ----------"
    local incoming_id="upstream-id-abc123"
    curl -sS -XPOST -H 'Content-Type: application/json' \
        -H "X-Seeker-Request-Id: $incoming_id" \
        -d '{"probe":"id"}' http://127.0.0.1:8080/api/echo-body -o /dev/null -w ""
    sleep 1
    if ! grep -F "[requestId=${incoming_id}]" "$iast_log" >/dev/null; then
        echo "❌ D.0 日志里没透传上游 id=$incoming_id"
        grep -E "IAST RequestId|ServletBody" "$iast_log" | tail -10
        return 1
    fi
    local request_started_hits
    request_started_hits=$(grep -F "[requestId=${incoming_id}]" "$iast_log" \
                           | grep -c "Request Started" || true)
    if [ "$request_started_hits" -lt 1 ]; then
        echo "❌ D.0 RequestIdPlugin 没以上游 id 记录 Request Started"
        return 1
    fi
    echo "    ✓ 复用上游 X-Seeker-Request-Id: $incoming_id"

    # D.1：正常 JSON POST —— 业务响应必须 echo 回原样，日志含 body
    echo "  ---------- D.1 正常 JSON POST ----------"
    local payload='{"hello":"world","n":42}'
    local resp
    resp=$(curl -sS -XPOST -H 'Content-Type: application/json' -d "$payload" http://127.0.0.1:8080/api/echo-body)
    echo "    response: $resp"
    if [ "$resp" != "$payload" ]; then
        echo "❌ D.1 业务 echo 失败（wrapper 可能破坏了 body 读取）"; return 1
    fi
    sleep 1
    local hit
    hit=$(grep -E "\[ServletBody\].*body=\{\"hello\":\"world\",\"n\":42\}" "$iast_log" | head -1)
    if [ -z "$hit" ]; then
        echo "❌ D.1 日志里没看到 ServletBody 明细"; grep ServletBody "$iast_log" | tail -5; return 1
    fi
    echo "    ✓ 业务 echo 成功 + ServletBody 日志有 body"

    # D.2：二进制 POST —— 日志应是 <not read by app...> 或 "Content-Type 不在白名单"，不能打乱码
    echo "  ---------- D.2 二进制 POST (image/png) ----------"
    head -c 10240 /dev/urandom > "$TMP_DIR/fake.png"
    curl -sS -XPOST -H 'Content-Type: image/png' --data-binary @"$TMP_DIR/fake.png" \
         http://127.0.0.1:8080/api/echo-body -o /dev/null -w "" || true
    sleep 1
    local bin_line
    bin_line=$(grep -E "\[ServletBody\].*Content-Type=image/png" "$iast_log" | head -1)
    if [ -z "$bin_line" ]; then
        echo "❌ D.2 没看到 image/png 的 ServletBody 摘要"; tail -10 "$iast_log"; return 1
    fi
    if echo "$bin_line" | grep -q "body="; then
        echo "❌ D.2 二进制不应解码成 body=...（会是乱码）：$bin_line"; return 1
    fi
    echo "    ✓ image/png 只记摘要、不解码"

    # D.3：日志截断 —— body 比 maxLogBytes 大，业务应读到全量，日志有 log-truncated 标记
    echo "  ---------- D.3 JSON body 超过 maxLogBytes (8192) ----------"
    # 生成 ~20000 字节的 JSON（urandom→base64 保证纯 ASCII 且 JSON-safe）
    local fill
    fill=$(head -c 16000 /dev/urandom | base64 | tr -d '\n/=+' | head -c 20000)
    local big_payload='{"k":"'"$fill"'"}'
    local big_len=${#big_payload}
    resp=$(curl -sS -XPOST -H 'Content-Type: application/json' -d "$big_payload" http://127.0.0.1:8080/api/echo-body)
    local resp_len=${#resp}
    if [ "$resp_len" -lt "$big_len" ]; then
        echo "❌ D.3 业务回 echo 被截断：payload=${big_len}B response=${resp_len}B"; return 1
    fi
    sleep 1
    if ! grep -E "\[ServletBody\].*log-truncated, total " "$iast_log" >/dev/null; then
        echo "❌ D.3 日志没有 log-truncated 标记"; grep ServletBody "$iast_log" | tail -5; return 1
    fi
    echo "    ✓ 业务拿到全量 ${big_len}B，日志带 log-truncated 标记"

    kill_demo
    echo "✅ [CASE D] ServletBodyPlugin 行为符合预期"
}

run_body_case

echo
echo "======================================================================"
echo "  全部用例通过 ✅"
echo "======================================================================"
