#!/usr/bin/env bash
# 一键 RAG 评测（对应 openspec add-rag-eval-system 任务3.1）。
#
# 用法：
#   ./run-eval.sh           # 检索层（零 token，需本地 Ollama + MySQL）
#   ./run-eval.sh --llm     # 追加生成层（走真实 DeepSeek，需后端在 8080 运行且有真实 API Key）
#
# 环境变量（可写入 .env 自动加载）：
#   RAG_EVAL_TOKEN 或 RAG_EVAL_USERNAME/RAG_EVAL_PASSWORD —— 生成层评测的登录凭据
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# ---------- 参数 ----------
LLM=false
for arg in "$@"; do
  case "$arg" in
    --llm) LLM=true ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（支持 --llm）"; exit 1 ;;
  esac
done

# ---------- .env 加载（生成层需要真实 AGENTSCOPE_API_KEY） ----------
if [ -f "$ROOT_DIR/.env" ]; then
  set -a; source "$ROOT_DIR/.env"; set +a
fi

# ---------- 前置检查 ----------
if ! curl -s --noproxy '*' -m 3 http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  echo "❌ Ollama 不可达（127.0.0.1:11434）。先启动：OLLAMA_MODELS=/tmp/ollama-models ollama serve"
  exit 1
fi
if ! curl -s --noproxy '*' -m 3 http://127.0.0.1:11434/api/tags | grep -q "bge-m3"; then
  echo "❌ Ollama 缺少 bge-m3 模型。先执行：ollama pull bge-m3"
  exit 1
fi

if $LLM; then
  if [ -z "${RAG_EVAL_TOKEN:-}" ] && { [ -z "${RAG_EVAL_USERNAME:-}" ] || [ -z "${RAG_EVAL_PASSWORD:-}" ]; }; then
    echo "❌ --llm 需要登录凭据：设置 RAG_EVAL_TOKEN，或 RAG_EVAL_USERNAME/RAG_EVAL_PASSWORD"
    exit 1
  fi
fi

# ---------- 跑评测 ----------
export RAG_EVAL=true
if $LLM; then
  export RAG_EVAL_LLM=true
  echo "▶ 运行评测：检索层 + 生成层（消耗 token）"
else
  echo "▶ 运行评测：检索层"
fi
./mvnw test -Dtest=RagEvalTest

echo
echo "✅ 评测完成。归档：docs/eval/history/，报告副本：target/rag-eval-report*.md"
