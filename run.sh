#!/usr/bin/env bash
# ============================================================
# JARVIS 本地启动脚本（推荐）
#   - 自动加载项目根目录 .env 作为环境变量
#   - 对 AGENTSCOPE_API_KEY 做占位符检查并给出提示
#   - 自动用 ./mvnw 启动 Spring Boot 后端
#
# 用法：
#   cp .env.example .env
#   vim .env      # 至少改 AGENTSCOPE_API_KEY
#   ./run.sh      # 启动
#   Ctrl+C 优雅停止
# ============================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

# ---------- 0. 检查 mvnw ----------
if [ ! -x ./mvnw ]; then
    echo "[jarvis] ⚠️  ./mvnw 不存在或不可执行，先用 mvn wrapper:wrapper 生成"
    exit 1
fi

# ---------- 1. 加载 .env ----------
ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    echo "[jarvis] ✅ 读取环境变量文件：$ENV_FILE"
    # shellcheck disable=SC2046
    export $(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$' | xargs)
else
    echo "[jarvis] ⚠️  未找到 $ENV_FILE（跳过 .env 加载）"
    echo "           建议：cp .env.example .env && vim .env   # 至少填 AGENTSCOPE_API_KEY"
    echo "           继续使用 application.properties 兜底默认值"
fi

# ---------- 2. 占位符检查 ----------
HAS_PLACEHOLDER=0
case "${AGENTSCOPE_API_KEY:-}" in
    ""|sk-demo-key|sk-your-real-deepseek-key-here|your-*|*placeholder*)
        HAS_PLACEHOLDER=1 ;;
esac

if [ "$HAS_PLACEHOLDER" -eq 1 ]; then
    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo "⚠️  AGENTSCOPE_API_KEY 仍是占位符！AI 对话功能会返回 401 错误。"
    echo ""
    echo "修复方式："
    echo "  1) 申请 DeepSeek API Key：https://platform.deepseek.com/"
    echo "  2) 编辑 $ENV_FILE：修改 AGENTSCOPE_API_KEY=sk-xxxxxxxxxxx"
    echo "  3) 重新运行 ./run.sh"
    echo ""
    echo "你也可以按回车继续启动（后台管理 / 用户登录不受影响，仅 AI 对话不可用）..."
    echo "────────────────────────────────────────────────────────────"
    read -r -p "是否继续？[y/N] " ANSWER
    case "$ANSWER" in
        y|Y|yes|YES|"") : ;;
        *) echo "[jarvis] 已退出。" ; exit 0 ;;
    esac
else
    echo "[jarvis] ✅ AGENTSCOPE_API_KEY 已配置（长度 ${#AGENTSCOPE_API_KEY}）"
fi

echo ""
echo "────────────────────────────────────────────────────────────"
echo "🚀 启动 JARVIS 后端 (Spring Boot)"
echo "   MySQL:      ${MYSQL_URL:-<default>}"
echo "   Model:      ${AGENTSCOPE_MODEL:-<default>} @ ${AGENTSCOPE_BASE_URL:-<default>}"
echo "   HTTP port:  8080"
echo "   前端开发:   Vite 另开终端： cd web && npm run dev  （端口 5173，代理 /api → 8080）"
echo "   停止:       Ctrl+C (SIGTERM, 不损坏数据库)"
echo "────────────────────────────────────────────────────────────"
echo ""

exec ./mvnw spring-boot:run
