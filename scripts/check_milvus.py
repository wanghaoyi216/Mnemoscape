#!/usr/bin/env python
import urllib.request, json

def post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:19530" + path, data=data,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())

# 查看 collection 信息
r = post("/v2/vectordb/collections/describe", {"collectionName": "mnemoscape_memories"})
print(json.dumps(r, indent=2, ensure_ascii=False)[:2000])
