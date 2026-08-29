#!/usr/bin/env bash
# ================================================================
# JARVIS 一键启动脚本（Docker Compose）
#   用法：
#     ./deploy.sh up        # 构建并启动全部服务（首次会拉 bge-m3 + 编译）
#     ./deploy.sh down      # 停止并移除容器（保留 volume 数据）
#     ./deploy.sh logs -f   # 跟随日志
#     ./deploy.sh ps        # 容器状态
#     ./deploy.sh check     # 健康检查
#     ./deploy.sh backup    # 备份知识库 + Ollama 模型数据卷到 backup/
# ================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# 优先用 docker compose v2，兜底 v1 docker-compose
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=docker-compose
else
  echo "[jarvis] 找不到 docker compose，请先安装 Docker Desktop 或 docker-compose。"
  exit 1
fi

ACTION="${1:-up}"

case "$ACTION" in
  up)
    [ -f .env ] || cp deploy/.env.example .env && echo "[jarvis] 已从 deploy/.env.example 生成 .env，记得编辑 AGENTSCOPE_API_KEY！"
    if grep -q 'sk-your-real-deepseek-key-here\|sk-demo-key' .env; then
      echo "[jarvis] ⚠️  .env 中的 AGENTSCOPE_API_KEY 仍是占位符，请先填真实 Key。"
      echo "          编辑：vim $(pwd)/.env"
      exit 2
    fi
    echo "[jarvis] 构建并启动所有服务（首次编译 / 拉模型需要几分钟）..."
    $COMPOSE up --build -d
    echo "[jarvis] 等待服务就绪（最多 2 分钟）..."
    for _ in $(seq 1 40); do
      if curl -sfS "http://127.0.0.1:$(grep -E '^JARVIS_FRONTEND_PORT=' .env 2>/dev/null | cut -d= -f2 || echo 8080)/" \
          -o /dev/null -m 5; then
        echo "[jarvis] ✅  就绪：http://localhost:$(grep -E '^JARVIS_FRONTEND_PORT=' .env 2>/dev/null | cut -d= -f2 || echo 8080)"
        exit 0
      fi
      sleep 3
    done
    echo "[jarvis] ❌ 前端未就绪，查看日志：$COMPOSE logs -f backend frontend"
    exit 1
    ;;

  down)
    echo "[jarvis] 停止并移除容器（volume 数据保留）..."
    $COMPOSE down
    ;;

  logs)
    shift || true
    $COMPOSE logs "$@"
    ;;

  ps)
    $COMPOSE ps
    ;;

  check)
    FP=$(grep -E '^JARVIS_FRONTEND_PORT=' .env 2>/dev/null | cut -d= -f2 || echo 8080)
    BP=$(grep -E '^JARVIS_BACKEND_PORT=' .env 2>/dev/null | cut -d= -f2 || echo 18080)
    echo "前端: $(curl -sSf -o /dev/null -w 'HTTP_%{http_code}' --max-time 5 "http://127.0.0.1:${FP}/" 2>/dev/null || echo FAIL)   http://localhost:${FP}"
    echo "后端: $(curl -sSf -o /dev/null -w 'HTTP_%{http_code}' --max-time 5 "http://127.0.0.1:${BP}/api/knowledge/stats" 2>/dev/null || echo FAIL)   http://localhost:${BP}/api/knowledge/stats"
    echo "知识库 stats:"; curl -sS --max-time 5 "http://127.0.0.1:${BP}/api/knowledge/stats" 2>/dev/null || echo "(unreachable)"
    echo
    $COMPOSE ps
    ;;

  backup)
    DEST="backup/jarvis-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$DEST"
    echo "[jarvis] 备份到 $DEST ..."
    $COMPOSE run --rm --no-deps --entrypoint tar backend -C /app -cf - log data | gzip > "$DEST/backend-logdata.tar.gz"
    echo "  - backend 日志 & 数据 → $DEST/backend-logdata.tar.gz"
    $COMPOSE run --rm --no-deps --entrypoint tar ollama -C /root/.ollama -cf - . | gzip > "$DEST/ollama-models.tar.gz"
    echo "  - Ollama 模型 → $DEST/ollama-models.tar.gz"
    echo "[jarvis] 完成。"
    ;;

  *)
    echo "用法：$0 { up | down | logs | ps | check | backup }"
    exit 1
    ;;
esac
