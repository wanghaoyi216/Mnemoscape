#!/bin/bash
# verify-trace.sh — proves X-Correlation-Id rides the full chain
#
#   Client ──▶ Gateway ──▶ memory-service ──Feign──▶ ai-service
#
# Walks one real request through, then greps the docker logs of both
# memory-service and ai-service for the same id. If both stamp it, the
# Feign correlation interceptor is doing its job and any 500 "信标 ID"
# reported to the end user can be located across services in one grep.
#
# Prerequisites:
#   docker compose -f docker/docker-compose.all.yml up -d
#
# Usage:
#   ./scripts/verify-trace.sh            # uses default localhost:8080
#   BASE=http://other-host:8080 ./scripts/verify-trace.sh
#   ./scripts/verify-trace.sh --since 5m # how far back to grep logs

set -euo pipefail

BASE="${BASE:-http://localhost:8080/api/v1}"
SINCE="5m"
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift 2;;
    -h|--help)
      sed -n '2,25p' "$0"
      exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

PASS=0
FAIL=0
green() { echo -e "\033[32m  PASS\033[0m $1"; PASS=$((PASS+1)); }
red()   { echo -e "\033[31m  FAIL\033[0m $1 — $2"; FAIL=$((FAIL+1)); }

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required tool: $1" >&2; exit 2;
  }
}
require curl
require docker
require grep

# A recognisable, search-friendly correlation id — short prefix + ts + random.
TRACE_ID="trace-verify-$(date +%s)-$RANDOM"
echo "=== Mnemoscape Trace Propagation Verification ==="
echo "  base:       $BASE"
echo "  trace id:   $TRACE_ID"
echo "  log window: --since $SINCE"
echo ""

# 1. Register + login (mirrors test-e2e.sh; needs a JWT to clear gateway auth filter).
USER="trace-$(date +%s)"
EMAIL="${USER}@trace.test"
curl -s -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"password\":\"Trace1234!\"}" \
  >/dev/null

LOGIN_BODY=$(curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"Trace1234!\"}")
TOKEN=$(echo "$LOGIN_BODY" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
  red "Login (prereq)" "no token in response: $LOGIN_BODY"
  echo "Aborting — cannot continue without auth."
  exit 1
fi
green "Login (prereq)"

# 2. Fire the request with our known X-Correlation-Id.
# POST /memories synchronously chains memory-service → ai-service via Feign,
# so a successful 201 already proves the interceptor was on the call path.
HTTP_RESPONSE=$(curl -s -i -X POST "$BASE/memories" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $TRACE_ID" \
  -d '{"title":"Trace Probe","description":"Synthetic memory created by verify-trace.sh to exercise the memory→ai Feign hop and prove correlation ids ride along.","privacyLevel":"PRIVATE"}')

STATUS=$(echo "$HTTP_RESPONSE" | head -1 | awk '{print $2}')
RESP_TRACE=$(echo "$HTTP_RESPONSE" | grep -i '^X-Correlation-Id:' | head -1 | awk '{print $2}' | tr -d '\r')

if [ "$STATUS" = "201" ]; then
  green "POST /memories returned 201"
else
  red "POST /memories" "expected 201, got $STATUS"
fi

if [ "$RESP_TRACE" = "$TRACE_ID" ]; then
  green "Gateway echoed back the same X-Correlation-Id"
else
  red "Response header trace id" "expected $TRACE_ID, got '$RESP_TRACE'"
fi

# 3. Give the async log flush a beat to finalise.
sleep 1

# 4. Grep each container's logs for the trace id.
grep_container() {
  local name="$1"
  # 2>&1: docker logs writes app stdout+stderr depending on Spring profile
  docker logs --since "$SINCE" "$name" 2>&1 | grep -F "$TRACE_ID" || true
}

MEM_HITS=$(grep_container mnemoscape-memory | wc -l | tr -d ' ')
AI_HITS=$(grep_container mnemoscape-ai    | wc -l | tr -d ' ')
GW_HITS=$(grep_container mnemoscape-gateway | wc -l | tr -d ' ')

echo ""
echo "  log hits  gateway=$GW_HITS  memory=$MEM_HITS  ai=$AI_HITS"

if [ "$GW_HITS" -gt 0 ]; then
  green "Gateway logged the trace id ($GW_HITS line(s))"
else
  red "Gateway log presence" "no '$TRACE_ID' lines in mnemoscape-gateway"
fi

if [ "$MEM_HITS" -gt 0 ]; then
  green "memory-service logged the trace id ($MEM_HITS line(s))"
else
  red "memory-service log presence" "no '$TRACE_ID' lines in mnemoscape-memory"
fi

if [ "$AI_HITS" -gt 0 ]; then
  green "ai-service logged the trace id ($AI_HITS line(s))"
else
  # This is THE assertion that proves the Feign interceptor works. Without
  # the interceptor, the gateway-injected header dies at memory-service and
  # ai-service never sees it.
  red "ai-service log presence (Feign hop)" \
      "no '$TRACE_ID' lines in mnemoscape-ai — FeignCorrelationConfig may be inactive"
fi

# 5. Sample lines for the human reader.
if [ "$MEM_HITS" -gt 0 ] || [ "$AI_HITS" -gt 0 ]; then
  echo ""
  echo "--- sample log lines ---"
  for c in mnemoscape-gateway mnemoscape-memory mnemoscape-ai; do
    line=$(grep_container "$c" | head -1)
    if [ -n "$line" ]; then
      echo "[$c] $line"
    fi
  done
fi

echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
