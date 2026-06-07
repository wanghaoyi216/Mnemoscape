#!/usr/bin/env python
import pymysql, json, urllib.request

# MySQL 验证
conn = pymysql.connect(host="127.0.0.1", user="root", password="root123", charset="utf8mb4")
cur = conn.cursor()

cur.execute("SELECT COUNT(*) FROM mnemoscape_auth.users WHERE username LIKE 'mnemo_user_%'")
user_count = cur.fetchone()[0]

cur.execute("""
    SELECT COUNT(*) FROM mnemoscape_memory.memories
    WHERE user_id IN (
        SELECT id FROM mnemoscape_auth.users WHERE username LIKE 'mnemo_user_%'
    )
""")
mem_count = cur.fetchone()[0]

cur.execute("""
    SELECT COUNT(*) FROM mnemoscape_memory.memory_versions
    WHERE memory_id IN (
        SELECT id FROM mnemoscape_memory.memories
        WHERE user_id IN (
            SELECT id FROM mnemoscape_auth.users WHERE username LIKE 'mnemo_user_%'
        )
    )
""")
ver_count = cur.fetchone()[0]

cur.execute("""
    SELECT privacy_level, COUNT(*) FROM mnemoscape_memory.memories
    WHERE user_id IN (
        SELECT id FROM mnemoscape_auth.users WHERE username LIKE 'mnemo_user_%'
    )
    GROUP BY privacy_level
""")
privacy_dist = cur.fetchall()

cur.close()
conn.close()

print("=" * 45)
print("  MySQL 验证结果")
print(f"  用户数  : {user_count}")
print(f"  记忆数  : {mem_count}  (期望 {user_count * 15})")
print(f"  版本快照: {ver_count}")
print(f"  隐私分布: {dict(privacy_dist)}")
print("=" * 45)

# Milvus 验证
def milvus_post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:19530" + path, data=data,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())

r = milvus_post("/v2/vectordb/collections/get_stats",
                {"collectionName": "mnemoscape_memories"})
row_count = r.get("data", {}).get("rowCount", "?")
print(f"\n  Milvus 向量数: {row_count}  (期望 {mem_count})")
print("=" * 45)
