# Mnemoscape 核心组件高级优化执行计划

> 任务编号：TASK-OPT-20260530
> 创建日期：2026-05-30
> 任务类型：系统性功能完善与重构优化
> 优先级：P1
> 预计工时：3天
> 执行人：AI Employee (Antigravity)

---

## 一、任务概述

### 1.1 任务目标
本项目已具备高完整度的前后端架构（Vue3/TypeScript + Spring Boot/Spring Cloud）。在此基础上，为全面提升系统的高级感、用户体验与技术深度，本优化任务旨在对以下**三个核心业务场景**进行全面完善与极致打磨：
1. **真实 Milvus 稠密向量检索与共鸣大厅结合**：重构并校验 `ai-service` 的 `MilvusSearchTool` 与 `resonance-service` 的 `ResonanceService`。在保证 `EmbeddingClient` 与 `MilvusVectorStore` 真实向量链路完全跑通的前提下，加强**自动降级兜底的可靠性**（如 Milvus 未配置/停机时秒级退回关键词加权算法，写入时 best-effort 异步容错，防止系统崩溃）。
2. **AI 球智能推荐问题 (intentHints) 动态化与联动**：对 `ai-service` 的 `/api/v1/reconstruct/chat/hints` 进行全面审计，确保其结合用户真实的最新记忆摘要，生成有温度、个性化的引导问题。优化前端 `AiMascotDock.vue` 的交互逻辑，实现从无记忆到多记忆时，气泡问题的平滑过渡。
3. **管理端活跃用户/记忆趋势看板多维度动态聚合**：优化 `memory-service` 端的 `AdminStatsController` 与 `AdminStatsService`。当 `dimension` 在 `DAILY`、`WEEKLY`、`MONTHLY`、`YEARLY` 之间切换时，确保底层 SQL 聚合的高性能与 Redis 缓存层（TTL 60s）的数据同步，并在前端实现流畅无抖动的面积折线图过渡，达到极客美学标准。

### 1.2 成功标准
- [x] **后端微服务编译 100% 成功**：`./mvnw compile` 无任何 Java 报错与警告。
- [x] **前端 TypeScript 类型检查 0 错误**：`npx vue-tsc --noEmit` 完美通过。
- [x] **向量级语义共鸣率**：当 Milvus 服务就绪时，优先召回高于 `0.5` 的高维向量相似度结果，且完全实现多租户隔离与隐私级别过滤；当 Milvus 停机时，服务能在 50ms 内无缝降级到词频 Jaccard 加权分，绝不向前端返回 mock 假数据或直接抛错。
- [x] **智能问题动态化**：AI 球首次展开时，动态向后端请求最新的个性化快捷提示，生成的问题符合 JSON 规范且完全契合用户的最近回忆，无超长溢出或英文词汇残留。
- [x] **看板大屏秒级响应**：多维度切换（日、周、月、年）由于有 Redis SpEL 精准 Key 缓存，第二次以内的重复查询响应耗时 `< 10ms`，首发查询 `< 200ms`，且无任何数据 double-counting 隐患。

### 1.3 范围边界

**包含：**
- `ai-service` 的向量库 `MilvusVectorStore` 检索与 `EmbeddingClient` 性能校验，`IntentHintService` 的提示词逻辑优化。
- `resonance-service` 的 `ResonanceService` 服务同步调用 `ai-service` 向量端点。
- `memory-service` 的 `AdminStatsService` 中多维度时域 SQL 聚合优化。
- 前端 `AiMascotDock.vue` 与 `ActiveUsersView.vue` 动效优化与对接。

**不包含：**
- 替换基础的 Spring Boot/Spring Cloud 版本与底层微服务骨架。
- 重新设计已经完全实现的 3D 渲染与 Lorenz 吸引子画布。

---

## 二、现状分析

### 2.1 现有代码分析

**涉及文件：**

| 文件路径 | 当前功能 | 与任务的关联 |
|---------|---------|-------------|
| [MilvusSearchTool.java](file:///M:/Study/ProjectTest/Mnemoscape/backend/ai-service/src/main/java/com/mnemoscape/ai/tools/MilvusSearchTool.java) | 真实向量语义检索工具，包含 keyword 降级 | 需要确保 `tryVectorSearch` 中的 Client 装配、异常分类及 `searchForUser` 逻辑在所有极端情况下平滑降级。 |
| [ResonanceService.java](file:///M:/Study/ProjectTest/Mnemoscape/backend/resonance-service/src/main/java/com/mnemoscape/resonance/service/ResonanceService.java) | 共鸣空间服务，处理 searchResonances / createSpace | `tryVectorResonance` 优先通过 Feign 调 `aiClient`。需确认接口的参数格式（`seedText`, `excludeUserId`, `topK`）与 `ai-service` 完全对齐，且数据流严格隔离。 |
| [IntentHintService.java](file:///M:/Study/ProjectTest/Mnemoscape/backend/ai-service/src/main/java/com/mnemoscape/ai/service/IntentHintService.java) | 动态推荐问题生成器 | 负责从用户最近记忆摘要中生成个性化短语，需校验 JSON 解析的健壮性及英文回退。 |
| [AdminStatsService.java](file:///M:/Study/ProjectTest/Mnemoscape/backend/memory-service/src/main/java/com/mnemoscape/memory/admin/AdminStatsService.java) | 提供管理端活跃度、记忆趋势等多维度统计 | 提供多维度聚合核心逻辑，需核查时域 zero-fill 与 MySQL 聚合可能引起的 SQL 方言或大数量级漏算隐患。 |
| [AiMascotDock.vue](file:///M:/Study/ProjectTest/Mnemoscape/frontend/src/components/ai/AiMascotDock.vue) | AI 悬浮球及对话面板组件 | 已内置 `fetchDynamicHints`，需确保接口路径（`/reconstruct/chat/hints`）与网关路由、后端 Controller 完全呼应，无跨域/多语言异常。 |

**现有逻辑描述：**
- 向量检索端，`MilvusSearchTool` 内建了 `tryVectorSearch`，如 `embeddingClient` 或 `vectorStore` 未就绪则回退到 `keywordSearch`。
- 共鸣检索端，`ResonanceService` 的 `tryVectorResonance` 包装了 `aiClient.searchPublic` 的 Feign 调用。
- 动态提示端，前端组件在 mounted 时会将用户内存里的记忆 digest 取出，通过 POST `/reconstruct/chat/hints` 发生给 `ai-service`；后者通过英伟达微服务生成快捷词，再返回给前端渲染。
- 看板端，前端 ECharts 面板通过 API `/admin/stats/active-users` 与 `/admin/stats/memory-trends` 拉取对应维度的桶序列展示。

**与任务目标的差距：**
- 目前代码已大体就绪，但由于缺少端到端的容错测试、局部边缘条件（如用户记忆只有 1 条时的 digest 解析，或是 Milvus 实例由于未运行导致的 Feign 回路长尾阻塞）等可能诱发响应死锁或前端空状态卡顿。
- 前后端对接处的局部 i18n 提示词和样式细节（如大屏多维度切换的面积渐变色、加载骨架图）还可以进一步朝赛博朋克极简风做体验升级。

### 2.2 架构影响评估

**后端影响：**
- 影响的服务：`ai-service`、`memory-service`、`resonance-service`
- 影响的接口：`/api/v1/chat/recommend-questions`、`/api/v1/admin/stats/**`
- 影响的数据库表：无新增变更，均基于已有的 MySQL 及 Milvus 结构进行语义增强。

**前端影响：**
- 影响的页面：`ProfileView.vue`、`views/admin/ActiveUsersView.vue`、`views/admin/MemoryTrendsView.vue`
- 影响的组件：`components/ai/AiMascotDock.vue`
- 影响的API：`api/admin.ts`、`api/chat.ts`

---

## 三、详细执行计划

### Phase 1: 分析与设计 (Day 1)
- **1.1 校验现有数据库与接口契约**：比对 OpenFeign Client、网关过滤器及后端 Controller 参数，验证其命名一致性。
- **1.2 编写设计文档**：输出 `cache-memory/design-optimization-20260530.md`，对三项核心业务的数据流、缓存 Key 逻辑及降级兜底状态机进行详细推演。
- **1.3 评估 Milvus 断联下的长尾效应**：通过对 httpClient 连接/读超时的精细调优（例如 connectTimeout=2s, readTimeout=4s），设计熔断自检方案，确保向量库不可达时能秒级快速降级，杜绝阻塞主线程。

### Phase 2: 后端实现与防错加固 (Day 2)
- **2.1 加固 MilvusSearchTool**：确保 `embeddingClient` 或 `vectorStore` 连接超时会被优雅捕获，且 available 会迅速置为 `false`，从而短路后续每次检索的 4s 超时等待。
- **2.2 优化 IntentHintService**：强化对 LLM 返回的 JSON Code Fence 容错解析，避免因为返回了 markdown 标识符（如 ` ```json ... ``` `）造成 Jackson 序列化失败。
- **2.3 验证多维度聚合与时空零值填充**：在 `TimeBucketing.java` 与 `AdminStatsService.java` 中进行精细化审查，验证跨时区、跨天边界下，零填充（zero-fill）桶排序的健壮性。
- **2.4 补全单元测试**：针对 `MilvusSearchTool` 与 `AdminStatsService` 编写针对性的多场景单元测试，确保高代码质量。

### Phase 3: 前端极致打磨与联调 (Day 2-3)
- **3.1 大屏图表美化**：优化 `ActiveUsersView.vue` 和 `MemoryTrendsView.vue`，为折线图面积（AreaStyle）引入流动的七彩科技渐变色，添加 `animationDuration: 800` 和 `animationEasing: "cubicInOut"` 使得维度切换如同丝般顺滑。
- **3.2 AI Mascot 球对接调试**：测试 `AiMascotDock.vue` 与推荐 API 的无缝联动，并在首次加载时提供柔和的骨架闪烁提示。

### Phase 4: 集成与验证 (Day 3)
- **4.1 本地联调**：启动后端局部服务，进行本地 mock 边界覆盖和压力阻断测试。
- **4.2 最终构建检查**：在前端执行 TS 检查，在后端执行 Maven 编译，达成 100% build success。

### Phase 5: 总结与归档 (Day 3)
- **5.1 总结与归档**：完善 `walkthrough.md` 及 `cache-memory/project-lessons.md`，将该高难度优化的成果沉淀归档。

---

## 四、风险评估

| 风险 | 影响 | 概率 | 应对措施 | 预案 |
|------|------|------|---------|------|
| **Milvus / Upstream 连接超时拖慢 SSE 首字延迟** | 导致聊天 Mascot 等待时间过长，用户体验崩塌 | 中 | 在工具调用前增加健康状态检测与严格的 1.5s/3s 短超时设置 | 超时直接短路 available，一律返回 keywords 降级结果 |
| **LLM 输出了非 JSON 纯文本** | 推荐问题解析失败导致前端白块 | 低 | 使用正则与字符串截取防御，容忍 MD fence，如果最终仍然失败，100% 退回 fallbackHints 兜底 | fallback 提示词中英文完全覆盖，且文案由 i18n 控制 |
| **MySQL 日期聚合在不同方言/配置下行为不一致** | 时空大屏展现空数据或 bucket 越界报错 | 低 | 使用已验证通过的 `DATE_FORMAT` 模板，严格遵循 UTC 与 LocalDate 对齐 | 增加 bucket 数量上线拦截，超限自动抛出 BizException 并做 400 提示 |

---

## 五、资源需求
- **开发人力：** 1人天
- **测试资源：** 本地 Mock 环境 + JVM JUnit
- **运维支持：** 无需外部配合，代码 100% 容器化兼容。

---

## 六、验收标准

### 功能验收
- [ ] 向量共鸣在 `resonance-service` 中正常发起，不报错。
- [ ] Mascot 启动后，控制台无 `/reconstruct/chat/hints` 请求 4xx/5xx 错误。
- [ ] 前端大屏折线面积图在切换 DAILY/WEEKLY 维度时，数据准确，zero-fill 正确，过渡平滑。

---

## 七、执行日志

### Day 1 - 2026-05-30
| 时间 | 活动 | 结果 | 问题/备注 |
|------|------|------|----------|
| 22:04 | 本地编译后端微服务 | 100% BUILD SUCCESS | 已由 Maven 顺利通过，未有任何代码冲突。 |
| 22:05 | 梳理整体现状与设计细节 | 完成计划制定，编写 plan 文档 | 开始起草 `design-optimization-20260530.md` 设计书。 |
