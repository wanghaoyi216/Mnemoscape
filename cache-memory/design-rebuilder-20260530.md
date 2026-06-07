# 历史 Fragments 批量重建管理入口 (Admin Fragment Rebuilder) 架构设计书

> 创建日期：2026-05-30
> 适用服务：memory-service, frontend
> 状态：Detailed Design

---

## 一、 数据库与数据一致性设计

### 1.1 数据清洗范围判定
我们需要判定一条记忆的 `MemoryFragment` 是否属于历史英文 Mock 产生的“脏数据”或仍处于未重建的初始态。
- 策略：
  - 如果记忆的 `visualData` 包含 legacy 英文特征（`needsVisualDataCleanup` 判定逻辑），则其碎片一定需要重建。
  - 获取该记忆的 `List<MemoryFragment>`：
    - 若碎片为空，表明该记忆从未生成过 3D 碎片，需要补建。
    - 遍历现有碎片，若包含英文 Mock 特征字符（如 "playground", "grandma's kitchen", "pure childhood joy"），判定为 legacy 碎片，需要全部清除并重新生成。

### 1.2 事务与删除安全
由于批量重建需要删除原有的碎片并重新写入，这属于敏感写操作：
- 重建必须是**基于单条 memory 幂等且隔离的事务**。
- 单条记忆重建的内部流程：
  1. 开启事务。
  2. 根据 `memoryId` 执行 `fragmentRepository.deleteByMemoryId(memoryId)` 物理删除该记忆原有的所有碎片。
  3. 调用 AI 接口 / 本地规则重构：`enrichWithReconstruction(memory)`，写入新的 `visualData`、`emotionProfile` 并通过 `saveFragmentBestEffort` 批量存入新的碎片。
  4. 提交事务。
  5. 记录快照审计版本。
- 风险控制：如果在调用 AI 重建或保存新碎片时抛出异常，整个事务会回滚，从而避免出现原碎片已被删除但新碎片未生成的“空状态”。

---

## 二、 接口设计

### 2.1 批量重建触发端点
- **路径**：`/api/v1/admin/memories/rebuild-fragments`
- **方法**：`POST`
- **角色约束**：`ROLE_ADMIN`
- **请求负载**：
  ```json
  {
    "limit": 500
  }
  ```
- **响应结构**：
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
      "scanned": 120,
      "dispatched": 45,
      "limit": 500,
      "total": 350
    }
  }
  ```

---

## 三、 微服务调用与异步线程池设计

### 3.1 异步设计机制
AI 重构在真实 LLM 模式下每条可能耗时 15-30s，批量处理 500 条会导致网关 HTTP 连接被占满。因此采用 Spring `@Async`。
- 管理员发起 `POST /rebuild-fragments` 时，主线程分页加载记忆记录，判断 `needsFragmentRebuild`，若为 `true` 则将其 `memoryId` 提交给 `@Async` 异步方法 `runFragmentRebuildAsync(memoryId)`。
- 主线程瞬间返回扫描及下发数量，保证 HTTP 响应在 100ms 内完成。
- `@Async` 底层由 Spring 的 `TaskExecutor` 线程池调度处理。在底层执行时，逐条执行事务性清除与重新生成，如果某条记忆失败，不影响其他任务的进行。

```
[Admin UI] 
   │ (POST /rebuild-fragments)
   ▼
[AdminMemoryManagementController] (主线程)
   │
   ├─► 1. 分页检索记忆 
   ├─► 2. 筛选 needsFragmentRebuild
   ├─► 3. 循环调用 runFragmentRebuildAsync (异步) ───┐
   │                                                 ▼
[Spring TaskExecutor 线程池] ◄───────────────────────┘
   │
   ├─► 开启最小事务
   ├─► deleteByMemoryId (清除旧碎片)
   ├─► enrichWithReconstruction (调用 AI 或 Rule 重构新碎片)
   ├─► 提交事务并落版本
```
