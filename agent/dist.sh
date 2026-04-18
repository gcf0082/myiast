#!/bin/bash
# IAST Agent 发布打包脚本
# 在 agent/ 目录下运行，把 agent jar + CLI jar + README + YAML + 启停/CLI 脚本打成 tar.gz
# 产出：agent/dist/iast-agent-<version>.tar.gz
#
# 注：CLI 在独立 Maven 项目 ../iast-cli/，本脚本会顺带构建它并把产物一起带入 tarball，
#     这样用户下一个 tarball 就能用全部能力（启停 + CLI）
set -euo pipefail

cd "$(dirname "$0")"
AGENT_DIR="$(pwd)"
CLI_DIR="$(cd "${AGENT_DIR}/../iast-cli" && pwd)"
VERSION="${IAST_VERSION:-1.0.0}"
NAME="iast-agent-${VERSION}"
OUT="dist/${NAME}"

echo "==> 清理旧产物"
rm -rf "dist/${NAME}" "dist/${NAME}.tar.gz"
mkdir -p "${OUT}"

echo "==> 构建 Agent jar"
mvn clean package -DskipTests -q

echo "==> 构建 CLI jar"
( cd "${CLI_DIR}" && mvn clean package -DskipTests -q )

echo "==> 拷贝发布文件"
cp "${AGENT_DIR}/target/iast-agent.jar" "${OUT}/iast-agent.jar"
cp "${CLI_DIR}/target/iast-cli.jar"     "${OUT}/iast-cli.jar"
cp "${AGENT_DIR}/README.md"             "${OUT}/"
cp "${AGENT_DIR}/iast-monitor.yaml"     "${OUT}/"
cp "${AGENT_DIR}/iast-start.sh"         "${OUT}/"
cp "${AGENT_DIR}/iast-stop.sh"          "${OUT}/"
cp "${AGENT_DIR}/iast-start-jattach.sh" "${OUT}/"
cp "${AGENT_DIR}/iast-stop-jattach.sh"  "${OUT}/"
cp "${CLI_DIR}/iast-cli-jattach.sh"     "${OUT}/"
chmod +x "${OUT}"/*.sh

echo "==> 打包 tarball"
(cd dist && tar czf "${NAME}.tar.gz" "${NAME}")

SIZE=$(du -h "dist/${NAME}.tar.gz" | cut -f1)
echo "==> 完成：${AGENT_DIR}/dist/${NAME}.tar.gz  (${SIZE})"
echo "    内容："
ls -la "${OUT}"
