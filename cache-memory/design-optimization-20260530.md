# Mnemoscape 核心组件高级优化设计书

> 任务编号：TASK-OPT-20260530
> 创建日期：2026-05-30
> 任务类型：系统性功能完善与重构设计
> 执行人：AI Employee (Antigravity)

---

## 一、 数据流与整体架构设计

优化后的三大业务板块共享一个高效、稳健的分布式微服务架构。以下是各服务之间的时序调用与数据流动逻辑：

### 1.1 语义共鸣搜索数据流 (Milvus Semantic Resonance)

```
[前端 (Vue3)] ──(1. 选定种子记忆)──> [Resonance Service]
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  │ (2. 尝试向量召回)                               │ (3. 备用稀疏降级)
                  ▼                                               ▼
           [AI Service] ──(NVIDIA /v1/embeddings)           [Memory Service]
                  │                                               │
                  ▼                                               ▼
         [Milvus Vector Store]                            [MySQL memories Table]
                  │                                               │
                  ▼                                               ▼
       (高维稠密 COSINE 匹配)                              (关键词 Jaccard 加权分)
                  │                                               │
                  └───────────────────────┬───────────────────────┘
                                          ▼
                               [返回脱敏后的相似记忆列表]
```

### 1.2 意图快捷提示词生成流 (Dynamic intentHints)

```
[前端 AiMascotDock] ──(1. 提供当前内存记忆摘要)──> [AI Service (IntentHintController)]
                                                           │
                                                           ▼
                                                [MiniMax 2.7 via Spring AI]
                                                           │
                                                           ▼
                                                 (2. 生成 3-4 条快捷问题)
                                                           │
                                                           ▼
[前端渲染气泡胶囊] <────────(3. 返回 JSON String Array)─────────┘
```

---

## 二、 数据库设计与索引优化

本项优化不引入新的实体表，但基于现有的 MySQL 和 Milvus 数据库表结构，为保证高并发下的统计与检索速度，进行了专门的索引及查询优化设计。

### 2.1 MySQL 核心表索引现状及优化

#### `memories` 记忆主表
- **主键**：`id` VARCHAR(36)
- **外键**：`user_id` VARCHAR(36)
- **核心时空字段**：`created_at` TIMESTAMP, `updated_at` TIMESTAMP, `privacy_level` VARCHAR(20)
- **索引设计**：
  - `idx_user_privacy` (`user_id`, `privacy_level`)：用于跨服务拉取个人公开/私有记忆池。
  - `idx_created_updated` (`created_at`, `updated_at`)：用于管理端大屏趋势聚合（解决 `GROUP BY DATE_FORMAT` 时的大批量扫表性能缺陷）。
  - `idx_lat_lng` (`memory_lat`, `memory_lng`)：用于全球 3D 热力图格点高效 snap-to-grid 检索。

### 2.2 Milvus 向量库 Schema 设计
- **Collection Name**：`mnemoscape_memories`
- **Dimension**：`1024`（与 `nvidia/nv-embedqa-e5-v5` 编码后的维度保持强一致）
- **Metric Type**：`COSINE` (余弦相似度，值域 `[-1, 1]`，映射到 `[0, 0.99]`)
- **Schema 定义**：
  - `id` (VarChar, MaxLength=128, Primary Key, AutoID=false)
  - `vector` (FloatVector, Dim=1024)
  - `user_id` (VarChar, Dynamic Field)
  - `title` (VarChar, Dynamic Field)
  - `location` (VarChar, Dynamic Field)
  - `year` (Int32, Dynamic Field)
  - `snippet` (VarChar, Dynamic Field)
  - `privacy` (VarChar, Dynamic Field, PUBLIC / FRIENDS / PRIVATE)

---

## 三、 接口设计 (RESTful API & 错误码)

### 3.1 动态快捷问题推荐接口

- **路径**：`POST /api/v1/reconstruct/chat/hints`
- **格式**：`application/json`
- **请求包体 (Request)**：
```json
{
  "locale": "zh-CN",
  "context": [
    {
      "title": "大理洱海边的温暖晚风",
      "location": "大理洱海",
      "year": 2023
    }
  ]
}
```
- **响应包体 (Response)**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "hints": [
      "还记得前年在洱海吹过的晚风吗？",
      "聊聊那段你在大理的难忘瞬间",
      "洱海的旅途给你带来了哪些平和？"
    ]
  }
}
```

### 3.2 管理端时序聚合接口

- **路径**：`GET /api/v1/admin/stats/active-user-counts`
- **查询参数**：
  - `dimension`：`DAILY` | `WEEKLY` | `MONTHLY` | `YEARLY`（必填，错误抛出 400 `INVALID_DIMENSION`）
  - `from`：`YYYY-MM-DD`（可选，时域窗口起点）
  - `to`：`YYYY-MM-DD`（可选，时域窗口终点，默认当天）
- **错误码与兜底机制**：
  - `400 INVALID_RANGE`：起止时间倒置或单次查询桶数量超限（`> 366`），防止恶意超长查询击穿内存。
  - `503 SERVICE_UNAVAILABLE`：底层依赖损坏。

---

## 四、 服务调用与 Feign 降级设计

### 4.1 resonance-service → ai-service 向量检索调用

- **Feign Client**：
```java
@FeignClient(name = "ai-service", fallbackFactory = AiServiceFallbackFactory.class)
public interface AiServiceClient {
    @PostMapping("/api/v1/vector/search-public")
    ApiResponse<Map<String, Object>> searchPublic(@RequestBody Map<String, Object> request);
}
```
- **降级容错逻辑**：
  当 `ai-service` 发生超时（`ConnectTimeoutException` 或 `ReadTimeoutException`）或抛出 `500` 错误时，`AiServiceFallbackFactory` 将拦截异常，记录 `WARN` 日志并触发 `available = false`。
  `ResonanceService` 捕获此事件后，将**静默且优雅地切换到词频 Jaccard + 地点时间加权算法**，零延迟地返回本地高可用检索结果，用户对故障完全无感知。

---

## 五、 缓存与高并发设计 (Redis 60s SpEL)

为避免管理后台大屏定时刷新对 MySQL 造成并发统计查询压力，本系统设计了基于 Redis 的细粒度 SpEL 表达式缓存机制：

### 5.1 缓存配置明细

| 缓存区 (Cache Name) | 缓存 Key (SpEL) | 缓存 TTL | 同步机制 (Sync) | 业务作用 |
|--------------------|-----------------|---------|----------------|---------|
| `admin.active-users` | `dimension + '|' + from + '|' + to` | `60s` | `sync = true` | 活跃用户时序曲线 |
| `admin.memory-trends` | `dimension + '|' + from + '|' + to` | `60s` | `sync = true` | 记忆创建/更新时序趋势 |
| `admin.heatmap` | `resolution` | `60s` | `sync = true` | 3D 全球情绪热力图点集 |

- **`sync = true` 作用**：引入本地 JVM 双重检查锁（DCL），当缓存失效时，只有一个线程能去穿透查询 MySQL，其余线程在此阻塞并等待该线程写回缓存后直接复用，**彻底杜绝高并发大屏下的缓存击穿/雪崩效应**。

---

## 六、 安全与隐私隔离审查

- **多租户数据隔离**：在 `MilvusSearchTool.java` 中执行 `search` 时，强制在 filter 条件中注入当前登录账号的真实 `user_id`（从网关解析后注入 `SecurityContextHolder`），禁止从外部请求包体中修改，从根源上消除**水平越权漏洞**。
- **脱敏传递**：共鸣检索出的他人记忆，其 `ownerUsername` 绝不暴露数据库真实名称或 ID，采用固定的 UUID 密钥盐哈希处理（如：`回忆者-0428`），既在会话中保持了回忆者的拟人特征，又完全隐匿了用户社交隐私。
- **注入防御 (JSR-303)**：所有接收 DTO/VO 的入口类，均声明 `@NotNull` 及 `@Pattern` 参数校验，防止恶意请求侵入。
