#!/bin/bash
# E2E integration test script for Mnemoscape platform
# Prerequisites: All services running (docker compose -f docker/docker-compose.all.yml up -d)

BASE="http://localhost:8080/api/v1"
PASS=0
FAIL=0

green() { echo -e "\033[32m  PASS\033[0m $1"; ((PASS++)); }
red() { echo -e "\033[31m  FAIL\033[0m $1 — $2"; ((FAIL++)); }

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    green "$desc"
  else
    red "$desc" "expected $expected, got $actual"
  fi
}

assert_contains() {
  local desc="$1" pattern="$2" body="$3"
  if echo "$body" | grep -q "$pattern"; then
    green "$desc"
  else
    red "$desc" "response missing '$pattern'"
  fi
}

assert_status() {
  local desc="$1" expected="$2" actual="$3" body="$4"
  if [ "$actual" = "$expected" ]; then
    green "$desc"
  else
    red "$desc" "HTTP $actual — $body"
  fi
}

echo "=== Mnemoscape E2E Tests ==="
echo ""

# Generate unique test user
E2E_USER="e2euser-$(date +%s)"
E2E_EMAIL="e2e-$(date +%s)@test.com"
echo "[Auth]"
REG=$(curl -s -w "\n%{http_code}" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$E2E_USER"'","email":"'"$E2E_EMAIL"'","password":"Test1234!"}')
REG_BODY=$(echo "$REG" | head -1)
REG_CODE=$(echo "$REG" | tail -1)
assert_status "Register new user" 201 "$REG_CODE" "$REG_BODY"
assert_contains "Register returns id" '"code":201' "$REG_BODY"

# 2. Auth: Login
LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$E2E_USER"'","password":"Test1234!"}')
LOGIN_BODY=$(echo "$LOGIN" | head -1)
LOGIN_CODE=$(echo "$LOGIN" | tail -1)
assert_status "Login" 200 "$LOGIN_CODE" "$LOGIN_BODY"
TOKEN=$(echo "$LOGIN_BODY" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then
  green "Extract access token"
else
  red "Extract access token" "token not found in response"
fi

AUTH="Authorization: Bearer $TOKEN"

# 3. Auth: Profile
PROFILE=$(curl -s -w "\n%{http_code}" "$BASE/auth/profile" -H "$AUTH")
PROFILE_BODY=$(echo "$PROFILE" | head -1)
PROFILE_CODE=$(echo "$PROFILE" | tail -1)
assert_status "Get profile" 200 "$PROFILE_CODE" "$PROFILE_BODY"

echo ""

# 4. Memory: Create
echo "[Memory]"
MEM_CREATE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/memories" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"title":"Summer Evening","description":"A warm summer evening walk along the beach with friends watching the sunset over the ocean horizon","memoryYear":2010,"privacyLevel":"PRIVATE"}')
MEM_BODY=$(echo "$MEM_CREATE" | head -1)
MEM_CODE=$(echo "$MEM_CREATE" | tail -1)
assert_status "Create memory" 201 "$MEM_CODE" "$MEM_BODY"
MEM_ID=$(echo "$MEM_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$MEM_ID" ]; then
  green "Extract memory ID: $MEM_ID"
else
  red "Extract memory ID" "id not found"
fi

# 5. Memory: List
MEM_LIST=$(curl -s -w "\n%{http_code}" "$BASE/memories" -H "$AUTH")
MEM_LIST_BODY=$(echo "$MEM_LIST" | head -1)
MEM_LIST_CODE=$(echo "$MEM_LIST" | tail -1)
assert_status "List memories" 200 "$MEM_LIST_CODE" "$MEM_LIST_BODY"

# 6. Memory: Get detail
if [ -n "$MEM_ID" ]; then
  MEM_GET=$(curl -s -w "\n%{http_code}" "$BASE/memories/$MEM_ID" -H "$AUTH")
  MEM_GET_BODY=$(echo "$MEM_GET" | head -1)
  MEM_GET_CODE=$(echo "$MEM_GET" | tail -1)
  assert_status "Get memory detail" 200 "$MEM_GET_CODE" "$MEM_GET_BODY"

  # 7. Memory: Drift
  DRIFT=$(curl -s -w "\n%{http_code}" "$BASE/memories/$MEM_ID/drift" -H "$AUTH")
  DRIFT_BODY=$(echo "$DRIFT" | head -1)
  DRIFT_CODE=$(echo "$DRIFT" | tail -1)
  assert_status "Get drift state" 200 "$DRIFT_CODE" "$DRIFT_BODY"
  assert_contains "Drift has fadeLevel" '"fadeLevel"' "$DRIFT_BODY"
fi

echo ""

# 8. AI: Reconstruct
echo "[AI]"
RECON=$(curl -s -w "\n%{http_code}" -X POST "$BASE/reconstruct" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"description":"A summer evening"}')
RECON_BODY=$(echo "$RECON" | head -1)
RECON_CODE=$(echo "$RECON" | tail -1)
assert_status "Reconstruct scene" 200 "$RECON_CODE" "$RECON_BODY"
assert_contains "Scene has objects" '"objects"' "$RECON_BODY"

echo ""

# 9. Resonance: Search
echo "[Resonance]"
if [ -n "$MEM_ID" ]; then
  RES_SEARCH=$(curl -s -w "\n%{http_code}" "$BASE/resonances/search?memoryId=$MEM_ID" -H "$AUTH")
  RES_BODY=$(echo "$RES_SEARCH" | head -1)
  RES_CODE=$(echo "$RES_SEARCH" | tail -1)
  assert_status "Search resonances" 200 "$RES_CODE" "$RES_BODY"

  # 10. Resonance: Create space
  RES_SPACE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/resonances/spaces" \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"memoryId1\":\"$MEM_ID\",\"memoryId2\":\"mock-memory-1\"}")
  RES_SPACE_BODY=$(echo "$RES_SPACE" | head -1)
  RES_SPACE_CODE=$(echo "$RES_SPACE" | tail -1)
  assert_status "Create resonance space" 201 "$RES_SPACE_CODE" "$RES_SPACE_BODY"
  SPACE_ID=$(echo "$RES_SPACE_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

  if [ -n "$SPACE_ID" ]; then
    green "Extract space ID: $SPACE_ID"

    # 11. Resonance: Get space
    SPACE_GET=$(curl -s -w "\n%{http_code}" "$BASE/resonances/spaces/$SPACE_ID" -H "$AUTH")
    SPACE_GET_CODE=$(echo "$SPACE_GET" | tail -1)
    assert_status "Get resonance space" 200 "$SPACE_GET_CODE" "$(echo "$SPACE_GET" | head -1)"

    # 12. Resonance: Place note
    NOTE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/resonances/spaces/$SPACE_ID/notes" \
      -H "$AUTH" -H "Content-Type: application/json" \
      -d '{"content":"Beautiful shared memory","mood":"warm","position":{"x":1.0,"y":1.8,"z":-2.0}}')
    NOTE_CODE=$(echo "$NOTE" | tail -1)
    assert_status "Place note" 201 "$NOTE_CODE" "$(echo "$NOTE" | head -1)"
  fi
fi

echo ""

# 13. Asset: Upload (mock endpoint check)
echo "[Asset]"
ASSET_CHECK=$(curl -s -w "\n%{http_code}" -X POST "$BASE/assets/upload" \
  -H "$AUTH" -F "file=@/dev/null" 2>/dev/null || echo '{"message":"multipart expected"}' )
ASSET_CODE=$(echo "$ASSET_CHECK" | tail -1)
# This might return 400 or 500 depending on multipart handling — just check gateway routes it
if [ "$ASSET_CODE" != "000" ] && [ "$ASSET_CODE" != "502" ]; then
  green "Asset service reachable (HTTP $ASSET_CODE)"
else
  red "Asset service reachable" "HTTP $ASSET_CODE"
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi

echo "All E2E tests passed!"
