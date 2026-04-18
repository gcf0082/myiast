#!/bin/bash
# IAST 项目发布打包脚本（monorepo 级别）
# 在项目根目录下运行，把 agent / iast-sdk / iast-cli / demo-spring / iast-plugin-demo
# 五个模块构建并打包成 tar.gz
# 产出：dist/iast-agent-<version>.tar.gz
# Tarball 里额外带 demo/ 子目录：可运行的 demo-spring jar + 测试脚本 + iast-plugin-demo
# 参考插件 jar，用户无需 clone 源码即可做端到端演练和外部插件样例
set -euo pipefail

cd "$(dirname "$0")"
ROOT_DIR="$(pwd)"
AGENT_DIR="${ROOT_DIR}/agent"
CLI_DIR="${ROOT_DIR}/iast-cli"
SDK_DIR="${ROOT_DIR}/iast-sdk"
DEMO_DIR="${ROOT_DIR}/demo-spring"
PLUGIN_DEMO_DIR="${ROOT_DIR}/iast-plugin-demo"
VERSION="${IAST_VERSION:-1.0.0}"
NAME="iast-agent-${VERSION}"
OUT="dist/${NAME}"

echo "==> 清理旧产物"
rm -rf "dist/${NAME}" "dist/${NAME}.tar.gz"
mkdir -p "${OUT}"

echo "==> 构建 SDK jar (先装到 local m2，agent 会依赖它)"
( cd "${SDK_DIR}" && mvn clean install -DskipTests -q )

echo "==> 构建 Agent jar"
( cd "${AGENT_DIR}" && mvn clean package -DskipTests -q )

echo "==> 构建 CLI jar"
( cd "${CLI_DIR}" && mvn clean package -DskipTests -q )

echo "==> 构建 demo-spring jar"
( cd "${DEMO_DIR}" && mvn clean package -DskipTests -q )

echo "==> 构建 iast-plugin-demo jar"
( cd "${PLUGIN_DEMO_DIR}" && mvn clean package -DskipTests -q )

echo "==> 拷贝发布文件"
cp "${AGENT_DIR}/target/iast-agent.jar"   "${OUT}/iast-agent.jar"
cp "${CLI_DIR}/target/iast-cli.jar"       "${OUT}/iast-cli.jar"
cp "${SDK_DIR}/target/iast-sdk.jar"       "${OUT}/iast-sdk.jar"
cp "${AGENT_DIR}/README.md"               "${OUT}/"
cp "${AGENT_DIR}/iast-monitor.yaml"       "${OUT}/"
cp "${AGENT_DIR}/iast-start.sh"           "${OUT}/"
cp "${AGENT_DIR}/iast-stop.sh"            "${OUT}/"
cp "${AGENT_DIR}/iast-start-jattach.sh"   "${OUT}/"
cp "${AGENT_DIR}/iast-stop-jattach.sh"    "${OUT}/"
cp "${CLI_DIR}/iast-cli-jattach.sh"       "${OUT}/"
chmod +x "${OUT}"/*.sh

# 空 plugins/ 目录 + 说明文件，方便用户一看就知道怎么放第三方插件
mkdir -p "${OUT}/plugins"
cat > "${OUT}/plugins/README.txt" <<'EOF'
把第三方 IAST 插件 jar 放到本目录，然后改 iast-monitor.yaml：

    monitor:
      default:
        pluginsDir: /absolute/path/to/this/plugins

参考插件 jar 在 ../demo/iast-plugin-demo.jar；想试玩拷过来即可。
插件作者请参考 iast-sdk.jar（本发布包已带）和源码仓 iast-plugin-demo/ 模块。
EOF

# demo 子目录：可运行的 Spring Boot 样例 + 端到端测试脚本。
# 脚本内部已做 layout 自适应（源码仓 / tarball 两种布局都能跑），用户可以直接
# `cd demo && ./run-premain.sh` 或 `./test-monitor-switch.sh` 验证
mkdir -p "${OUT}/demo"
cp "${DEMO_DIR}/target/demo-spring-1.0.0.jar"         "${OUT}/demo/demo-spring-1.0.0.jar"
cp "${DEMO_DIR}/iast-monitor.yaml"                    "${OUT}/demo/iast-monitor.yaml"
cp "${DEMO_DIR}/run-premain.sh"                       "${OUT}/demo/"
cp "${DEMO_DIR}/test-monitor-switch.sh"               "${OUT}/demo/"
cp "${DEMO_DIR}/test-interface-match.sh"              "${OUT}/demo/"
# 把 HelloPlugin 放到 demo/plugins/ 下——demo 的 iast-monitor.yaml 已经把 pluginsDir
# 指向这个相对子目录，启动后插件自动生效，不需要用户做任何额外配置。
mkdir -p "${OUT}/demo/plugins"
cp "${PLUGIN_DEMO_DIR}/target/iast-plugin-demo.jar"   "${OUT}/demo/plugins/iast-plugin-demo.jar"
chmod +x "${OUT}/demo"/*.sh
cat > "${OUT}/demo/README.txt" <<'EOF'
demo-spring 样例 + 测试脚本 + 参考插件（都是 layout 自适应：源码仓和本 tarball 都能跑）

文件
  demo-spring-1.0.0.jar          可运行的 Spring Boot demo（端口 8080，/api/* 接口）
  iast-monitor.yaml              demo 专用的 IAST 监控配置
  run-premain.sh                 用 -javaagent 启动 demo（premain 模式）
  test-monitor-switch.sh         7 步回归：start/stop 开关、requestId 跟踪、JSONL 事件、
                                 反射拦截、byte-buddy-agent / jattach 两条 attach 路径
  test-interface-match.sh        4 步：matchType=interface、includeFutureClasses 开关、
                                 premainDelayMs 延迟 install、ServletBodyPlugin body 抓取
  plugins/iast-plugin-demo.jar   参考外部插件实现（HelloPlugin）——demo 的 iast-monitor.yaml
                                 已配 pluginsDir: ./plugins，启动时自动生效，观察日志里
                                 [HelloPlugin] 行即可

快速上手
  ./run-premain.sh             # 以 premain 模式起来，命令行 Ctrl+C 退出
                                #   IAST 日志  /tmp/iast-agent-<pid>.log
                                #   JSONL 事件 /tmp/iast-events-<pid>.jsonl

端到端测试
  ./test-monitor-switch.sh     # attach 场景，7 步全通过即可
  ./test-interface-match.sh    # premain 场景，4 case（A/B/C/D）全绿即 OK

HelloPlugin 自动加载
  本 demo 的 iast-monitor.yaml 已配置 pluginsDir: ./plugins，HelloPlugin 挂到
  com.iast.demo.FileCheckController.hello()。启动后访问 /api/hello 即在
  /tmp/iast-agent-<pid>.log 看到 [HelloPlugin] ... 日志行。
EOF

echo "==> 打包 tarball"
(cd dist && tar czf "${NAME}.tar.gz" "${NAME}")

SIZE=$(du -h "dist/${NAME}.tar.gz" | cut -f1)
echo "==> 完成：${ROOT_DIR}/dist/${NAME}.tar.gz  (${SIZE})"
echo "    内容："
ls -la "${OUT}"
