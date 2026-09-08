#!/usr/bin/env bash
# =============================================================================
# Mnemoscape — 推送本地 YAML 配置到 Nacos Server (Bash 备用)
#
# 用法：
#   ./push-nacos-config.sh
#   NACOS_HOST=10.0.0.5 ./push-nacos-config.sh
#   ./push-nacos-config.sh -d ai-service.yaml -g DEFAULT_GROUP
#
# 依赖：curl, jq (用于解析 Nacos 响应)
# =============================================================================
set -euo pipefail

NACOS_HOST="${NACOS_HOST:-100.66.166.46}"
NACOS_PORT="${NACOS_PORT:-8848}"
GROUP="${GROUP:-DEFAULT_GROUP}"
DATA_IDS=(
  "ai-service.yaml" "ai-service-dev.yaml" "ai-service-prod.yaml"
  "memory-service.yaml"
  "auth-service.yaml"
  "resonance-service.yaml"
  "asset-service.yaml"
  "api-gateway.yaml"
)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 解析 -d / -g 覆盖
while getopts "d:g:h" opt; do
  case "$opt" in
    d) DATA_IDS=("$OPTARG") ;;
    g) GROUP="$OPTARG" ;;
    h) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Unknown -$OPTARG" >&2; exit 1 ;;
  esac
done

HEALTH_URL="http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/console/health/readiness"
PUSH_URL="http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/cs/configs"

echo "==> Checking Nacos at $HEALTH_URL"
if ! curl -sf -m 5 "$HEALTH_URL" >/dev/null; then
  echo "    Nacos not reachable!" >&2
  echo "    Tip: run scripts/remote/diagnose-nacos.ps1 (PowerShell) first" >&2
  exit 1
fi
echo "    OK"

ok=0; fail=0
for dataId in "${DATA_IDS[@]}"; do
  file="$SCRIPT_DIR/$dataId"
  if [ ! -f "$file" ]; then
    echo "[skip] $dataId — file not found"
    fail=$((fail+1))
    continue
  fi
  resp=$(curl -sS -X POST "$PUSH_URL" \
    --data-urlencode "dataId=$dataId" \
    --data-urlencode "group=$GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@$file" \
    -m 10)
  if [ "$resp" = "true" ]; then
    bytes=$(wc -c < "$file")
    echo "[ok ] $dataId pushed ($bytes bytes)"
    ok=$((ok+1))
  else
    echo "[err] $dataId  $resp"
    fail=$((fail+1))
  fi
done

echo
echo "================ 推送结果 ================"
echo "  成功：$ok"
echo "  失败：$fail"
[ "$fail" -eq 0 ]
