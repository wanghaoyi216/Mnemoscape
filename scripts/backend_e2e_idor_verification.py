#!/usr/bin/env python3
"""
Mnemoscape E2E Integration and IDOR Penetration Verification Suite
Tests the real microservice pipeline through Spring Cloud Gateway on :8080.
"""

import sys
import json
import uuid
import time
import urllib.request
import urllib.error

GATEWAY_BASE = "http://localhost:8080"
PASS_COUNT = 0
FAIL_COUNT = 0


def log_step(name):
    print(f"\n[TEST STEP] {name}")


def assert_true(condition, message):
    global PASS_COUNT, FAIL_COUNT
    if condition:
        print(f"  ✓ PASS: {message}")
        PASS_COUNT += 1
    else:
        print(f"  ✗ FAIL: {message}")
        FAIL_COUNT += 1
        raise AssertionError(message)


def http_request(method, path, data=None, token=None, headers=None):
    url = f"{GATEWAY_BASE}{path}"
    req_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if token:
        req_headers["Authorization"] = f"Bearer {token}"
    if headers:
        req_headers.update(headers)

    body_bytes = None
    if data is not None:
        body_bytes = json.dumps(data).encode("utf-8")

    req = urllib.request.Request(url, data=body_bytes, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status = resp.status
            content = resp.read().decode("utf-8")
            json_body = json.loads(content) if content else None
            return status, json_body
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8")
        try:
            json_body = json.loads(content)
        except Exception:
            json_body = {"raw": content}
        return e.code, json_body
    except Exception as e:
        print(f"Request exception for {method} {url}: {e}")
        return 0, {"error": str(e)}


def main():
    global PASS_COUNT, FAIL_COUNT
    print("================================================================")
    print("  MNEMOSCAPE E2E & IDOR SECURITY TEST RUNNER")
    print("================================================================")

    uid = uuid.uuid4().hex[:8]
    user_a_name = f"user_a_{uid}"
    user_a_email = f"user_a_{uid}@mnemoscape.local"
    user_a_pass = "Password123!"

    user_b_name = f"user_b_{uid}"
    user_b_email = f"user_b_{uid}@mnemoscape.local"
    user_b_pass = "Password123!"

    # 1. Register User A
    log_step("1. Register User A")
    status, res = http_request("POST", "/api/v1/auth/register", {
        "username": user_a_name,
        "email": user_a_email,
        "password": user_a_pass
    })
    assert_true(status == 201, f"User A registered (HTTP {status})")
    assert_true("data" in res and "accessToken" in res["data"], "User A received auth token")
    token_a = res["data"]["accessToken"]
    user_a_id = res["data"]["userId"]
    print(f"    User A: id={user_a_id}, name={user_a_name}")

    # 2. Register User B
    log_step("2. Register User B")
    status, res = http_request("POST", "/api/v1/auth/register", {
        "username": user_b_name,
        "email": user_b_email,
        "password": user_b_pass
    })
    assert_true(status == 201, f"User B registered (HTTP {status})")
    assert_true("data" in res and "accessToken" in res["data"], "User B received auth token")
    token_b = res["data"]["accessToken"]
    user_b_id = res["data"]["userId"]
    print(f"    User B: id={user_b_id}, name={user_b_name}")

    # 3. User A Profile Check
    log_step("3. User A Profile Lookup")
    status, res = http_request("GET", "/api/v1/auth/profile", token=token_a)
    assert_true(status == 200, f"User A profile accessible (HTTP {status})")
    assert_true(res["data"]["id"] == user_a_id, "Profile returns matching user ID")

    # 4. User A Creates Public Memory
    log_step("4. User A Creates Public Memory")
    status, res = http_request("POST", "/api/v1/memories", {
        "title": f"Public Cherry Blossom Spring - {uid}",
        "description": "Sitting under cherry blossoms beside the lotus pond, looking at ancient mountains.",
        "memoryYear": 2024,
        "memorySeason": "SPRING",
        "memoryLocation": "Hangzhou West Lake",
        "privacyLevel": "PUBLIC"
    }, token=token_a)
    assert_true(status == 201, f"Public memory created (HTTP {status})")
    m_pub_id = res["data"]["id"]
    print(f"    Public memory id={m_pub_id}")

    # 5. User A Creates Private Memory
    log_step("5. User A Creates Private Memory")
    status, res = http_request("POST", "/api/v1/memories", {
        "title": f"Private Secret Starlight Diary - {uid}",
        "description": "Deep secret thoughts under the dark purple aurora sky and moonlit bamboo path.",
        "memoryYear": 2023,
        "memorySeason": "WINTER",
        "memoryLocation": "Secret Garden",
        "privacyLevel": "PRIVATE"
    }, token=token_a)
    assert_true(status == 201, f"Private memory created (HTTP {status})")
    m_priv_id = res["data"]["id"]
    print(f"    Private memory id={m_priv_id}")

    # 6. User A Lists Own Memories
    log_step("6. User A List Own Memories")
    status, res = http_request("GET", "/api/v1/memories", token=token_a)
    assert_true(status == 200, f"User A listed memories (HTTP {status})")
    items = res["data"]["items"]
    assert_true(len(items) >= 2, f"User A sees >= 2 memories (found {len(items)})")

    # 7. User A Gets Own Private Memory
    log_step("7. User A Reads Own Private Memory")
    status, res = http_request("GET", f"/api/v1/memories/{m_priv_id}", token=token_a)
    assert_true(status == 200, f"Owner reads private memory (HTTP {status})")

    # 8. IDOR Test 1: User B Attempts to Read User A's Private Memory
    log_step("8. [IDOR Test 1] User B Read User A's Private Memory (Expected: 403)")
    status, res = http_request("GET", f"/api/v1/memories/{m_priv_id}", token=token_b)
    assert_true(status == 403, f"Non-owner reading private memory blocked with HTTP {status} (Expected 403)")

    # 9. IDOR Test 2: User B Attempts to Update User A's Public Memory
    log_step("9. [IDOR Test 2] User B Update User A's Public Memory (Expected: 403)")
    status, res = http_request("PUT", f"/api/v1/memories/{m_pub_id}", {
        "title": "Hacked Title by User B",
        "description": "Malicious tampering description"
    }, token=token_b)
    assert_true(status == 403, f"Non-owner updating public memory blocked with HTTP {status} (Expected 403)")

    # 10. IDOR Test 3: User B Attempts to Delete User A's Public Memory
    log_step("10. [IDOR Test 3] User B Delete User A's Public Memory (Expected: 403)")
    status, res = http_request("DELETE", f"/api/v1/memories/{m_pub_id}", token=token_b)
    assert_true(status == 403, f"Non-owner deleting public memory blocked with HTTP {status} (Expected 403)")

    # 11. IDOR Test 4: User B Attempts to Lock User A's Public Memory
    log_step("11. [IDOR Test 4] User B Lock User A's Public Memory (Expected: 403)")
    status, res = http_request("POST", f"/api/v1/memories/{m_pub_id}/lock", token=token_b)
    assert_true(status == 403, f"Non-owner locking public memory blocked with HTTP {status} (Expected 403)")

    # 12. IDOR Test 5: Chat Group Access Control
    log_step("12. [IDOR Test 5] Chat Group Membership Authorization")
    status, res = http_request("POST", "/api/v1/chat/groups", {
        "name": f"Secret Private Group - {uid}",
        "memberIds": []
    }, token=token_a)
    assert_true(status == 201, f"User A created chat group (HTTP {status})")
    group_id = res["data"]["id"]

    # User B tries to read group history without being a member
    status, res = http_request("GET", f"/api/v1/chat/messages?groupId={group_id}", token=token_b)
    assert_true(status == 403, f"Non-member reading chat group history blocked with HTTP {status} (Expected 403)")

    # 13. Vertical Privilege Escalation: Regular User Accessing Admin Endpoints
    log_step("13. [Vertical AuthZ] Regular User Accessing /api/v1/admin/* (Expected: 403)")
    status, res = http_request("GET", "/api/v1/admin/stats/overview", token=token_a)
    assert_true(status == 403, f"Regular user accessing admin endpoint rejected with HTTP {status} (Expected 403)")

    # 14. Unauthenticated Access to Admin Endpoints
    log_step("14. [Vertical AuthZ] Anonymous Access to /api/v1/admin/* (Expected: 401)")
    status, res = http_request("GET", "/api/v1/admin/stats/overview")
    assert_true(status == 401, f"Anonymous access to admin endpoint rejected with HTTP {status} (Expected 401)")

    # 15. Header Spoofing Attack Prevention
    log_step("15. [Security] Header Spoofing Prevention via Gateway")
    status, res = http_request("GET", "/api/v1/auth/profile", headers={
        "X-User-Id": user_a_id,
        "X-User-Role": "ADMIN"
    })
    assert_true(status == 401, f"Forged identity headers on unauthenticated request stripped with HTTP {status} (Expected 401)")

    # 16. Resonance Hub Stats & Search
    log_step("16. Resonance Hub Stats & Recall")
    status, res = http_request("GET", "/api/v1/resonances/stats", token=token_a)
    assert_true(status == 200, f"Resonance stats retrieved (HTTP {status})")
    assert_true("avgScore" in res["data"], "Resonance stats contains avgScore")

    status, res = http_request("GET", f"/api/v1/resonances/search?memoryId={m_pub_id}", token=token_a)
    assert_true(status == 200, f"Resonance search executed (HTTP {status})")

    # 17. Exception Sanitization Check (Verify No Java Stack Trace Leaks)
    log_step("17. [Sanitization] Exception Semantics and Stack Trace Privacy")
    status, res = http_request("GET", "/api/v1/memories/non-existent-uuid-99999", token=token_a)
    assert_true(status in (404, 500), f"Invalid lookup returned safe error status (HTTP {status})")
    error_message = json.dumps(res)
    assert_true("Exception" not in error_message and "at com." not in error_message,
                "Response does not leak raw Java class names or stack trace")

    print("\n================================================================")
    print(f"  E2E TEST SUMMARY: {PASS_COUNT} PASSED, {FAIL_COUNT} FAILED")
    print("================================================================")
    if FAIL_COUNT > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
