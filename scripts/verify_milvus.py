#!/usr/bin/env python
import json, urllib.request

def post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:19530" + path, data=data,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())

# 用 query 抽样验证
r = post("/v2/vectordb/entities/query", {
    "collectionName": "mnemoscape_memories",
    "filter": 'user_id != ""',
    "outputFields": ["id", "user_id", "title", "privacy"],
    "limit": 5
})
print("抽样查询结果:")
for row in r.get("data", []):
    print(f"  id={row.get('id','')[:8]}... user={row.get('user_id','')[:8]}... title={row.get('title','')[:20]} privacy={row.get('privacy','')}")

# 统计总数（通过 count(*) 表达式）
r2 = post("/v2/vectordb/entities/query", {
    "collectionName": "mnemoscape_memories",
    "filter": 'user_id != ""',
    "outputFields": ["count(*)"],
})
print(f"\n向量总数: {r2.get('data', [{}])[0].get('count(*)', '?')}")
