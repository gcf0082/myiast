#!/bin/bash
# IAST Agent 发布打包脚本
# 把 agent/target/iast-agent.jar 和 agent/dist/ 下的文件打成 tar.gz
# 产出：dist/iast-agent-<version>.tar.gz
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
VERSION="${IAST_VERSION:-1.0.0}"
NAME="iast-agent-${VERSION}"
OUT="dist/${NAME}"
SRC_DIR="agent"

echo "==> 清理旧产物"
rm -rf "dist/${NAME}" "dist/${NAME}.tar.gz"
mkdir -p "${OUT}"

echo "==> 构建 Agent jar"
(cd agent && mvn clean package -DskipTests -q)

echo "==> 拷贝发布文件"
cp agent/target/iast-agent.jar   "${OUT}/iast-agent.jar"
cp "${SRC_DIR}/README.md"        "${OUT}/"
cp "${SRC_DIR}/iast-monitor.yaml" "${OUT}/"
cp "${SRC_DIR}/iast-start.sh"    "${OUT}/"
cp "${SRC_DIR}/iast-stop.sh"     "${OUT}/"
chmod +x "${OUT}"/*.sh

echo "==> 打包 tarball"
(cd dist && tar czf "${NAME}.tar.gz" "${NAME}")

SIZE=$(du -h "dist/${NAME}.tar.gz" | cut -f1)
echo "==> 完成：dist/${NAME}.tar.gz  (${SIZE})"
echo "    内容："
ls -la "${OUT}"
