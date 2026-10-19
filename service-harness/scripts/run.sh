#!/bin/sh
set -eu
# 默认读取 stage/config/harness.properties；保留三个参数的旧调用方式以显式覆盖配置路径。
# 所有传入路径使用绝对路径，停止文件仅属于当前容器。
case "$#" in
  2) harness_stage=$1; harness_stop=$2; set -- ;;
  3) harness_stage=$1; harness_config=$2; harness_stop=$3; set -- "-Dharness.config=$harness_config" ;;
  *) echo "Usage: run.sh <absolute-stage> [absolute-config] <absolute-stop-file>" >&2; exit 2 ;;
esac
cd -- "$harness_stage"
exec "${HARNESS_JAVA:-java}" "$@" -cp 'bin/cell.jar:libs/*:cellets/cube-service-harness-3.0.jar' cube.service.harness.HarnessCell cell.xml "$harness_stop"
