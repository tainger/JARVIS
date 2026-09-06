#!/usr/bin/env bash
# 本地开发启动脚本：加载 .env 中的环境变量后启动 Spring Boot 后端
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 加载 .env（自动 export）
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

exec ./mvnw spring-boot:run "$@"
