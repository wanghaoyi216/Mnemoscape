# Mnemoscape 异步图谱投影与 AI 提示词 Redis 缓存优化执行计划

> 任务编号：TASK-OPT-ASYNC-CACHE-20260530
> 创建日期：2026-05-30
> 任务类型：微服务性能与架构优化
> 优先级：P1
> 预计工时：2天
> 执行人：AI Employee (Antigravity)

---

## 一、 任务概述

### 1.1 任务目标
为了进一步提升 Mnemoscape 系统的流畅度、响应耗时以及在高并发下的服务韧性，本优化任务旨在对以下两个核心业务链路进行极致优化：
1. **Neo4j 异步图谱投影与 Milvus 异步向量索引**：目前在 `memory-service` 中，当创建/修改记忆时，主线程通过自代理调用 `@Async` 执行的后台流 `runEnrichmentAsync` 是**串行顺序**跑 `enrichWithReconstruction`（3D 场景重建，涉及 LLM 交互，耗时 10-30s）、`extractAndProjectGraph`（Neo4j 图谱投影）和 `indexMemoryVector`（Milvus 向量索引）。这意味着关系图谱和向量检索需要等待长达 30 秒才能更新，导致体验割裂。我们将该串行链路拆分为 **3 个并行执行的 `@Async` 异步线程任务**，确保关系图谱与向量检索能够在秒级（即接口本身耗时内）瞬间完成投影。
2. **AI 悬浮球 (Mascot Dock) 推荐问题 Redis 缓存**：为了达到首屏加载的极致秒开体验（ recommendation 响应延迟 `< 5ms`），我们将在 `ai-service` 中引入 **Redis 缓存** 模块，利用 `BypassOnFailureCacheManager` 保护机制对 dynamically generated conversation starter questions (`intentHints`) 进行高可用缓存缓存（TTL 600s）。使用基于 `List<MemoryDigest>` 与 locale 的 deterministic MD5 哈希作为 Cache Key，降低 LLM 调用频次，保护上游 API 额度，并在 Redis 断联时平滑降级（Bypass）而不抛出 5xx 错误给前端。

### 1.2 成功标准
- [ ] **多端服务编译 100% 成功**：后端所有微服务 `./mvnw compile` 无任何 Java 报错与警告。
- [ ] **无损异步提取与投影**：记忆被保存时，3D 重建、Neo4j 关系图谱写入、Milvus 向量索引异步同时并发触发，Neo4j 节点和 Milvus 检索结果在记忆创建完成后即可秒级查询，不再受 3D 重建的 30s 长尾延迟拖累。
- [ ] **高可靠 Mascot Redis 缓存**：`ai-service` 引入缓存后，同一用户多次展开 Mascot 时，推荐提示词查询从 Redis 秒级返回（`< 5ms`），而在 Redis 断联/故障时自动降级到词频 Jaccard，保证不抛出异常。
- [ ] **前端 TypeScript 类型检查 0 错误**：`npx vue-tsc --noEmit` 完美通过。

### 1.3 范围边界

**包含：**
- `memory-service` 的 `MemoryService.java` 后台异步机制拆分，新增 `runGraphProjectionAsync` 和 `runVectorIndexingAsync`。
- `ai-service` 的 `pom.xml` 中引入 `spring-boot-starter-data-redis` 依赖。
- `ai-service` 增加 `AiCacheConfig.java` 缓存配置类，将 `RedisCacheManager` 代理进 `BypassOnFailureCacheManager`。
- `ai-service` 的 `IntentHintService.java` 中定义哈希 Cache Key 生成，应用 `@Cacheable`。
- `ai-service` 的 `AiApplication.java` 开启 `@EnableCaching`。

**不包含：**
- 修改上游 LLM 的 3D 重建核心算法或 3D 渲染器的 WebGL 前端逻辑。

---

## 二、 现状分析

### 2.1 现有代码分析

**涉及文件：**

| 文件路径 | 当前功能 | 与任务的关联 |
|---------|---------|-------------|
| [MemoryService.java](file:///m:/Study/ProjectTest/Mnemoscape/backend/memory-service/src/main/java/com/mnemoscape/memory/service/MemoryService.java) | 记忆主服务，处理记忆基础持久化及异步增强 | 需要将 `runEnrichmentAsync` 拆分成 3 个并行的 `@Async` 调用，重新组织 `triggerAsyncEnrichment` 的分发逻辑。 |
| [IntentHintService.java](file:///m:/Study/ProjectTest/Mnemoscape/backend/ai-service/src/main/java/com/mnemoscape/ai/service/IntentHintService.java) | 智能推荐问题生成服务，基于最新记忆摘要调用 LLM 生成 hints | 编写 MD5 determinist key 算法并给 `generate` 方法添加 `@Cacheable` 缓存注解。 |
| [AiApplication.java](file:///m:/Study/ProjectTest/Mnemoscape/backend/ai-service/src/main/java/com/mnemoscape/ai/AiApplication.java) | AI 服务启动类 | 添加 `@EnableCaching` 注解启用 Spring Cache 代理。 |
| [pom.xml (ai-service)](file:///m:/Study/ProjectTest/Mnemoscape/backend/ai-service/pom.xml) | AI 服务的 Maven 依赖配置 | 添加 `spring-boot-starter-data-redis` 依赖，以支持 Redis 客户端及序列化组件。 |

**现有逻辑描述：**
- `MemoryService.java` 中，`triggerAsyncEnrichment` 触发了唯一的异步方法 `runEnrichmentAsync`。在这个方法里，`enrichWithReconstruction(memory)` (LLM 3D 重建)、`extractAndProjectGraph(memory)` (Neo4j) 以及 `indexMemoryVector(memory)` (Milvus) 是串行被调用的。
- `IntentHintService.java` 的 `generate` 方法每次都会直接发起对 Nvidia / MiniMax AI upstream 的调用，在高频刷新或切换页面时会导致接口响应慢且极易超出 API 速率限制。
- `ai-service` 的 Redis 依赖尚未开启，需要参照 `memory-service` 的缓存安全最佳实践引入并装饰在 `BypassOnFailureCacheManager` 中以防 Redis 宕机击穿。

### 2.2 架构影响评估

**后端影响：**
- 影响的服务：`memory-service`、`ai-service`
- 影响的缓存分区：`intent-hints` (Redis)
- 线程资源：`memory-service` 异步任务现在会在线程池中占用 3 个线程来并发执行，由于系统底层使用的是带有合理核心线程数的 `TaskExecutor`，这对于 I/O 密集型任务能极大提高整体 CPU 与带宽吞吐量。

---

## 三、 详细执行计划

### Phase 1: 分析与设计 (Day 1)
- **1.1 产出设计文档**：输出 `cache-memory/design-async-cache-20260530.md`，对数据一致性、事务隔离性、MD5 缓存 Key 设计、Redis 熔断进行详细论证。
- **1.2 编写任务理解确认**：在对话中呈递给用户，取得同意。

### Phase 2: 后端微服务改造 (Day 1-2)
- **2.1 后端依赖引入**：修改 `ai-service/pom.xml`，引入 `spring-boot-starter-data-redis`。
- **2.2 后端缓存设计与实现**：
  - 创建 `com.mnemoscape.ai.config.AiCacheConfig.java`。
  - 在 `AiApplication.java` 标注 `@EnableCaching`。
  - 在 `IntentHintService.java` 中定义 `cacheKey` 生成函数并使用 `@Cacheable(cacheNames = "intent-hints", ...)` 进行声明。
- **2.3 异步解耦实现**：
  - 修改 `MemoryService.java`，将串行的 `runEnrichmentAsync` 重构为：
    - `runEnrichmentAsync` (仅跑 3D Scene Reconstruction，更新 visualData/fragments)
    - `runGraphProjectionAsync` (仅跑 Neo4j 提取与图投影)
    - `runVectorIndexingAsync` (仅跑 Milvus 向量索引写入)
  - 调整 `triggerAsyncEnrichment` 使得这 3 个任务以完全并发的方式提交给 TaskExecutor 执行。

### Phase 3: 集成与编译验证 (Day 2)
- **3.1 本地编译构建**：执行 `./mvnw clean compile` 确认所有模块 100% 编译成功，无任何依赖与语法缺失。
- **3.2 TypeScript 校验**：执行 `npx vue-tsc --noEmit` 校验前端类型一致性。

---

## 四、 风险评估

| 风险 | 影响 | 概率 | 应对措施 | 预案 |
|------|------|------|---------|------|
| **Redis 服务宕机或连接超时** | 导致 Mascot 获取提示词抛出 Redis 连接异常 (500) | 中 | 采用 `BypassOnFailureCacheManager` 装饰 RedisCacheManager | 当 Redis 故障时静默降级为无缓存模式直接调用 LLM，并在 1.5s 后继续返回 |
| **异步并发写入时 Memory 实体已被删除** | 异步操作找不到实体导致抛出异常 | 低 | `findById` 抛异常时进行捕获并优雅退出异步流 | 直接吞掉 `EntityNotFound` 异常，打 `WARN` 日志即可 |

---

## 五、 验收标准

### 功能验收
- [ ] 记忆创建后，Neo4j 与 Milvus 数据瞬间写入成功，无 3D 重建 30s 延迟。
- [ ] AI Mascot Dock 打开时，提示词能正常从 LLM 生成或从 Redis 瞬间召回。
- [ ] Redis 服务关闭时，AI 提示词接口正常工作（Jaccard fallback）。
