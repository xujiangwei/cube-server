#!/bin/bash
#
# 生成白泽服务端 API 文档
#   解析 apiDoc 注释，构建按需加载阅读器，并同步 dispatcher 静态文档。
#

set -euo pipefail

cd "$(dirname "$0")"

command -v node >/dev/null || { echo 'Node.js 18+ is required.' >&2; exit 1; }
command -v npm >/dev/null || { echo 'npm is required.' >&2; exit 1; }
lock_hash="$(node -e "process.stdout.write(require('crypto').createHash('sha256').update(require('fs').readFileSync('package-lock.json')).digest('hex').toUpperCase())")"
if [[ ! -f node_modules/.cube-docs-lock ]] || [[ "$(cat node_modules/.cube-docs-lock)" != "$lock_hash" ]]; then
  npm ci --include=dev --ignore-scripts --no-audit --no-fund
  printf '%s' "$lock_hash" > node_modules/.cube-docs-lock
fi
echo 'Building API documentation reader...'
node reader/build.mjs "$@"
