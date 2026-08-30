#!/usr/bin/env bash
# 安装 git pre-push 钩子（对应 openspec add-rag-eval-system 任务3.2）。
# 用法：bash deploy/hooks/install.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK_SRC="$ROOT_DIR/deploy/hooks/pre-push"
HOOK_DST="$ROOT_DIR/.git/hooks/pre-push"

if [ ! -f "$HOOK_SRC" ]; then
  echo "❌ 找不到钩子脚本：$HOOK_SRC"
  exit 1
fi

cp "$HOOK_SRC" "$HOOK_DST"
chmod +x "$HOOK_DST"
echo "✅ 已安装 pre-push 钩子：改动检索内核/标注集/评测包时，push 前自动跑检索层评测"
echo "   逃生通道：git push --no-verify"
