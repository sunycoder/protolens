#!/bin/sh
# macOS / Linux 源码启动入口；路径始终相对于脚本，不依赖终端当前目录。
set -eu
SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"
if [ "$#" -gt 0 ]; then
  sh "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" extractCatalog "-PcompetitorDir=$1" --console=plain
fi
printf '%s\n' 'Starting backend and frontend. The browser opens when both are ready.'
exec sh "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" run --console=plain
