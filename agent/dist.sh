#!/bin/bash
# IAST Agent 发布打包脚本
# 在 agent/ 目录下运行，把本目录里的 jar + README + YAML + 启停脚本打成 tar.gz
# 产出：agent/dist/iast-agent-<version>.tar.gz
set -euo pipefail

cd "$(dirname "$0")"
AGENT_DIR="$(pwd)"
VERSION="${IAST_VERSION:-1.0.0}"
NAME="iast-agent-${VERSION}"
OUT="dist/${NAME}"

echo "==> 清理旧产物"
rm -rf "dist/${NAME}" "dist/${NAME}.tar.gz"
mkdir -p "${OUT}"

echo "==> 构建 Agent jar"
mvn clean package -DskipTests -q

echo "==> 拷贝发布文件"
cp "${AGENT_DIR}/target/iast-agent.jar" "${OUT}/iast-agent.jar"
cp "${AGENT_DIR}/README.md"             "${OUT}/"
cp "${AGENT_DIR}/iast-monitor.yaml"     "${OUT}/"
cp "${AGENT_DIR}/iast-start.sh"         "${OUT}/"
cp "${AGENT_DIR}/iast-stop.sh"          "${OUT}/"
chmod +x "${OUT}"/*.sh

echo "==> 打包 tarball"
(cd dist && tar czf "${NAME}.tar.gz" "${NAME}")

SIZE=$(du -h "dist/${NAME}.tar.gz" | cut -f1)
echo "==> 完成：${AGENT_DIR}/dist/${NAME}.tar.gz  (${SIZE})"
echo "    内容："
ls -la "${OUT}"
