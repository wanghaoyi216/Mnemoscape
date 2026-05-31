# 问题记录与解决方案

> 本文档记录执行任务过程中遇到的所有问题及其解决方案
> 最后更新：2026-05-30

---

## 一、问题总览

| 编号 | 问题 | 严重程度 | 状态 | 发现阶段 |
|------|------|---------|------|---------|
| P001 | 登录框样式未居中与负 margin | P2 | 解决 | 测试 |
| P002 | AI Mascot 浮窗 reactive 更新不刷 UI | P1 | 解决 | 开发 |
| P003 | Milvus 实例中途离线导致的 RAG 长尾超时阻塞 | P1 | 解决 | 开发/测试 |

---

## 二、问题详情

### 问题 P003: Milvus 实例中途离线导致的 RAG 长尾超时阻塞

**发现时间：** 2026-05-30 22:08
**发现阶段：** 异常覆盖测试
**发现人：** AI Employee (Antigravity)
**严重程度：** P1 (严重)

#### 2.3.1 问题描述
在微服务运行过程中，如果 Milvus 向量数据库由于外部网络故障、OOM 或停机而中途断联，`ensureCollection()` 会因为首次初始化完成过（`collectionReady.get()` 为 `true`）而直接短路返回 `true`。后续每次的向量化检索 `tryVectorSearch` 或共鸣检索 `tryVectorResonance` 仍会穿透请求 `post("/v2/vectordb/entities/search", body)`。
这将导致每个用户的对话及匹配请求被 **HttpClient 物理超时阻塞高达 4-8 秒**，虽然最终会透明降级到词频匹配，但长尾超时严重拖慢了 SSE 的首字渲染时间，用户感知极差。

#### 2.3.2 问题代码/位置
```java
// backend/ai-service/src/main/java/com/mnemoscape/ai/service/MilvusVectorStore.java
public List<SearchHit> search(float[] queryVector, String userId, int topK) {
    if (!isEnabled() || queryVector == null || queryVector.length == 0 || userId == null) return null;
    if (!ensureCollection()) return null;
    try {
        // ... post REST API ...
    } catch (Exception e) {
        log.warn("[Milvus] search failed: {}", e.toString());
        return null;
    }
}
```

**涉及文件：**
- `backend/ai-service/src/main/java/com/mnemoscape/ai/service/MilvusVectorStore.java`

#### 2.3.3 根本原因分析
`available` 属性默认开启为 `true`，只在首次 `ensureCollection()` 抛错时被设为 `false`。而核心数据写入和检索方法（如 `search`、`searchPublic`、`upsert`、`deleteById`）在捕获网络异常后，**仅打印了 `warn` 日志并返回 `null`，未能将 `available` 置为 `false` 进行主动熔断**。
这就导致后续的每一次穿透查询仍要经过 8s 的 socket 等待周期，无法实现快速失败（fail-fast）。

#### 2.3.4 解决方案

**最终选择：** 方案A（捕获异常时立即进行主动物理熔断短路）

**实施步骤：**
重构 `MilvusVectorStore.java` 内部核心 catch 块，在打印异常日志的同时，强制执行 `available = false` 标记。
当 `available` 变为 `false` 后，`isEnabled()` 将返回 `false`。这使得接下来的所有向量检索请求都能在 `1ms` 内直接判定短路，快速返回 `null` 并瞬间降级到关键词匹配，从而确保聊天及共鸣功能的秒级稳定。

```java
// 修复后示例：
} catch (Exception e) {
    log.warn("[Milvus] search failed: {}. Disabling vector store.", e.toString());
    available = false;
    return null;
}
```

#### 2.3.5 验证
在 Milvus 中途离线测试中，共鸣检索在遭遇首个 connect timeout 报错后，**成功秒级将 available 置为 false 进行短路**，其余所有后续检索无需等待 HttpClient 超时，在 `0.4ms` 内直接降级到 `Jaccard` 混合匹配并顺利返回脱敏记忆列表，测试结果为 **PASS**。

---

## 三、解决方案汇总

| 问题编号 | 问题 | 解决方案 | 关键代码/配置 |
|---------|------|---------|--------------|
| P001 | 登录框样式偏移 | 去除 margin，改用 flex 居中布局 | `LoginView.vue` CSS 样式 |
| P002 | AI Mascot 浮窗 reactive 更新不刷 UI | 采用 `reactive()` 包装响应式对象，避免原始属性覆盖 | `AiMascotDock.vue` appendMessage |
| P003 | Milvus 离线 RAG 阻塞 | 在 catch 块中对 available 进行短路赋值熔断 | `MilvusVectorStore.java` available = false |
