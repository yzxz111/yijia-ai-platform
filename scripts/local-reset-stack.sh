#!/usr/bin/env bash
# 完全重置本地栈并重建（会删除全部本地数据）
#
# 用途：当前数据卷（2026-09-14 建立）的 gateway 迁移编号与当前代码分支不兼容——
# 旧库把 V40-V48 用作「技能/专家」线，而当前代码的 V40-V48 是「视频计量」线，
# 技能线已移至 V55-V63。Flyway 因此报 9 个 checksum mismatch，且旧库缺少
# model_usage_price / video_generation_task 等视频线必需的表。
#
# 该脚本删除命名卷（PostgreSQL、Redis、MinIO、JWT 密钥）后从零重建，
# 空库下所有迁移按当前编号顺序正常应用。
#
# 用法：
#   bash scripts/local-reset-stack.sh --yes    # 确认执行
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="deploy/env/.env"
COMPOSE_FILE="deploy/compose.yaml"
BASE_URL="http://127.0.0.1:18081"

if [ "${1:-}" != "--yes" ]; then
  cat >&2 <<'EOF'
此操作会永久删除以下本地数据，且不可恢复：
  - PostgreSQL：账号、套餐发放、API Key、调用记录、订单、余额账本
  - MinIO：已上传的文件
  - Redis：缓存与限流计数
  - JWT 密钥卷

如已确认可以丢弃，请加 --yes 重新执行：
  bash scripts/local-reset-stack.sh --yes
EOF
  exit 1
fi

echo "==> 1/3 停止并删除容器与命名卷"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  down --volumes --remove-orphans

echo "==> 2/3 确认数据卷已清除"
remaining=$(docker volume ls --filter name=yuzhihuihe-api-platform --format "{{.Name}}" | wc -l | tr -d ' ')
echo "    剩余 yuzhihuihe-api-platform 卷：$remaining"

echo "==> 3/3 从零构建并启动"
exec bash scripts/local-start-stack.sh
