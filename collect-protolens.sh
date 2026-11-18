#!/bin/sh
# 兼容入口：采集器已由后端统一管理，不再额外启动第二个采集器。
set -eu
SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
exec sh "$SCRIPT_DIR/start-protolens.sh" "$@"
