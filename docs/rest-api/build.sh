#!/bin/bash
#
# 生成白泽服务端 API 文档
#   apidoc 官方模板产出 HTML -> 再注入现代主题（apidoc-theme/）
#
# 主题细节见 apidoc-theme/theme.css 顶部说明。
#

set -euo pipefail

cd "$(dirname "$0")"

echo 'Make Baize Server API documents...'
apidoc -i src -o output

echo 'Applying modern theme...'
node apidoc-theme/apply.mjs output
