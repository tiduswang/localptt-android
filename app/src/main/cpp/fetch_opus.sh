#!/usr/bin/env bash
# 拉取 libopus 源码到 cpp/opus，供 CMakeLists.txt 构建 localptt_opus。
# 用法：在 app/src/main/cpp 目录执行  bash fetch_opus.sh
set -euo pipefail

OPUS_VERSION="${OPUS_VERSION:-1.4}"
OPUS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/opus"

if [ -d "$OPUS_DIR" ]; then
    echo "已存在 $OPUS_DIR，跳过下载"
    exit 0
fi

TARBALL="opus-${OPUS_VERSION}.tar.gz"
URL="https://downloads.xiph.org/releases/opus/${TARBALL}"

echo "下载 $URL ..."
curl -fL "$URL" -o "$TARBALL"
echo "解压到 $OPUS_DIR ..."
tar -xzf "$TARBALL"
mv "opus-${OPUS_VERSION}" "$OPUS_DIR"
rm -f "$TARBALL"
echo "完成。现在可在 app/build.gradle.kts 开启 externalNativeBuild。"
