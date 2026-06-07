#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Milvus 向量回填脚本
从 MySQL 读取所有 seed 用户的记忆，批量写入 Milvus
"""
import json, math, random, urllib.request
import pymysql

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASS = "root123"

MILVUS_URL  = "http://127.0.0.1:19530"
COLLECTION  = "mnemoscape_memories"
EMBED_DIM   = 1024
BATCH_SIZE  = 50   # 每批 upsert 条数

random.seed(42)

def rand_vector(dim):
    v = [random.gauss(0, 1) for _ in range(dim)]
    norm = math.sqrt(sum(x*x for x in v))
    if norm < 1e-9: v[0]=1.0; norm=1.0
    return [x/norm for x in v]

def milvus_post(path, body):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        MILVUS_URL + path, data=data,
        headers={"Content-Type":"application/json","Accept":"application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"code": -1, "error": str(e)}

def upsert_batch(entities):
    r = milvus_post("/v2/vectordb/entities/upsert",
                    {"collectionName": COLLECTION, "data": entities})
    return r.get("code") in (0, 200)

def main():
    print("=" * 55)
    print("  Milvus 向量回填")
    print(f"  MySQL  : {MYSQL_HOST}:{MYSQL_PORT}")
    print(f"  Milvus : {MILVUS_URL}  collection={COLLECTION}")
    print("=" * 55)

    # 验证 collection 可用
    r = milvus_post("/v2/vectordb/collections/describe",
                    {"collectionName": COLLECTION})
    if r.get("code") not in (0, 200):
        print(f"[ERROR] collection 不可用: {r}")
        return
    dim = None
    for f in r.get("data", {}).get("fields", []):
        if f["name"] == "vector":
            for p in f.get("params", []):
                if p["key"] == "dim":
                    dim = int(p["value"])
    print(f"  collection OK  dim={dim}")
    actual_dim = EMBED_DIM
    if dim and dim != EMBED_DIM:
        print(f"  [WARN] 维度不匹配！脚本用 {EMBED_DIM}，collection 是 {dim}，改用 {dim}")
        actual_dim = dim

    # 从 MySQL 读取所有 seed 记忆
    conn = pymysql.connect(
        host=MYSQL_HOST, port=MYSQL_PORT,
        user=MYSQL_USER, password=MYSQL_PASS,
        charset="utf8mb4", db="mnemoscape_memory"
    )
    cur = conn.cursor()
    cur.execute("""
        SELECT id, user_id, title, memory_location, memory_year,
               description, privacy_level
        FROM memories
        WHERE user_id IN (
            SELECT id FROM mnemoscape_auth.users
            WHERE username LIKE 'mnemo_user_%'
        )
        ORDER BY created_at
    """)
    rows = cur.fetchall()
    cur.close()
    conn.close()
    print(f"  从 MySQL 读取 {len(rows)} 条记忆")

    ok_total = 0
    fail_total = 0
    batch = []
    actual_dim = EMBED_DIM  # ensure defined before loop

    for i, (mem_id, user_id, title, location, year, desc, privacy) in enumerate(rows):
        entity = {
            "id": mem_id,
            "vector": rand_vector(actual_dim),
            "user_id": user_id or "",
            "title": (title or "")[:480],
            "location": (location or "")[:240],
            "year": year or 0,
            "snippet": (desc or "")[:900],
            "privacy": (privacy or "private").upper(),
        }
        batch.append(entity)

        if len(batch) >= BATCH_SIZE or i == len(rows) - 1:
            ok = upsert_batch(batch)
            if ok:
                ok_total += len(batch)
                print(f"  [batch] upsert {len(batch)} 条 OK  累计={ok_total}")
            else:
                fail_total += len(batch)
                print(f"  [batch] upsert {len(batch)} 条 FAIL")
            batch = []

    print("\n" + "=" * 55)
    print(f"  向量回填完成！成功={ok_total}  失败={fail_total}")
    print("=" * 55)

if __name__ == "__main__":
    main()
