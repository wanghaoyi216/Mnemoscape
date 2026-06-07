# 资产 / AI 智能体 / 数据库 三位一体质量修复 Spec

## Why

经过对项目代码的深入排查，Mnemoscape 在 v2 基础上仍存在四类相互关联的体验/正确性问题，需要一次性集中修复：

1. **资产污染**：`asset-service` 当前的"公共素材"判定规则只排除 `users/` 前缀的对象。Windows 远端 MinIO bucket 中仍残留 v1 时代的用户聊天/客服工单图片（位于 `chat/` `tickets/` `support/` 等任意子目录），被当作公共素材对所有人展示。
2. **数据库/向量库查询失效**：`application.yml` 中 `embedding-model=nvidia/nv-embed-v1` + `embedding-dimension=4096`，但 `VectorStoreProperties.java` 类的默认值是 `nvidia/nv-embedqa-e5-v5` + 1024 维 —— **两套默认值不一致**，加上 Milvus 集合索引 metric/index 未显式声明，造成向量检索间歇性"返回空/降级 false"。
3. **AI Agent 自主性弱**：`ChatReasoner.STREAMING_SYSTEM_PROMPT` 第 4 条写"无法直接调用工具"——直接把 Agent 锁死在单轮纯文本模式。`generateDynamicPlan` 只生成"伪 plan 列表"，不触发真正的"思考→工具→观察→再思考"循环。Spring AI 1.0.0-M4 内部 function-calling 是黑盒，前端只能看到硬编码 4 步。
4. **前端 mock 残留**：`ResonanceHubView` 指标 `0.75` / `rankingValue` / `outputValue` 硬编码；`ProfileView.fakeEmotion` 仍是 mock；共鸣搜索结果里 `ResonanceMatch.ownerUsername` 字段名约定与后端不一致时按钮会无响应。

## What Changes

- **资产层**：用 allowlist 替换"反 users/ 前缀"的过滤逻辑；同时提供一次性管理员迁移接口扫走历史 chat/support/tickets/ 子目录。
- **向量库层**：统一 class default 与 yml default，显式声明 Milvus index 参数（HNSW + COSINE），加 /admin/vector/status 自检端点暴露就绪状态。
- **AI Agent 层**：引入 `<thought>/<action>/<observation>` ReAct 协议 + 后端独立循环控制器，前端暴露 thought 流；保留 SSE 协议向前兼容。
- **前端 mock 清理**：把所有"硬编码 0.75/3 条假数据/fakeEmotion"替换为后端真实接口或显式 placeholder 提示。

## Impact

- Affected specs: 资产 / AI 对话 / 共鸣 / 管理端可观测性 / 前端 i18n
- Affected code:
  - `backend/asset-service/.../service/AssetService.java`
  - `backend/asset-service/.../controller/AssetController.java` + `AdminAssetController.java`
  - `backend/ai-service/.../service/ChatReasoner.java`
  - `backend/ai-service/.../controller/ChatController.java`
  - `backend/ai-service/.../service/MilvusVectorStore.java` + `VectorIndexService.java` + `EmbeddingClient.java`
  - `backend/ai-service/.../config/VectorStoreProperties.java` + `application.yml`
  - `backend/ai-service/.../service/ReActController.java` (新)
  - `backend/ai-service/.../service/ToolRegistry.java` (新)
  - `backend/memory-service/.../controller/MemoryController.java` (字段命名一致性)
  - `frontend/src/components/ai/AiMascotDock.vue` (ReAct thought 流)
  - `frontend/src/views/ResonanceHubView.vue` (硬编码 → 真实)
  - `frontend/src/views/ProfileView.vue` (fakeEmotion)
  - `frontend/src/api/*.ts` (新端点 client)

## ADDED Requirements

### Requirement: MinIO 公共素材严格 allowlist

系统 SHALL 在 `AssetService.listMinioStaticForUser` 中用「allowlist 白名单」替换「反 users/ 前缀」过滤：
- 仅放行顶层目录 ∈ `{ photo, video, audio, gif, music, icon, icons }` 的对象，以及 `users/{userId}/` 下的私有对象。
- 顶层目录是 `chat` / `support` / `tickets` / `legacy-orphan` / `tmp` / `__pycache__` 等白名单外目录 → 一律不放行（即便没有 `users/` 前缀）。
- 根级裸文件（无任何 `/` 分隔）→ 不放行（兜底防 v1 时代 upload 的孤儿对象）。

#### Scenario: 用户访问素材库，bucket 中混有 chat/ 子目录图片
- **WHEN** 调用 `GET /api/v1/assets/static/resources`
- **THEN** 返回的列表中不包含 `chat/*` / `support/*` / `tickets/*` 任何对象
- **AND** 包含 `photo/`, `video/`, `audio/`, `gif/`, `music/`, `icon/`, `icons/` 下的对象
- **AND** 当前登录用户 `users/{userId}/` 下的对象

#### Scenario: 管理员执行历史污染对象迁移
- **WHEN** 管理员调用 `POST /api/v1/admin/assets/migrate-legacy-images?dryRun=true`
- **THEN** 系统扫描 bucket，列出所有顶层非白名单子目录 / 根级裸文件 / `users/` 外的可疑对象
- **AND** 统计返回 `{scanned, candidates, wouldMigrate, samples}`
- **AND** `dryRun=false` 时把它们移动到 `legacy-orphan/` 前缀

### Requirement: Milvus 向量库就绪自检

系统 SHALL 提供 `GET /api/v1/admin/vector/status` 接口，输出：
- 当前 `embedding-model` 名称、`embedding-dimension`、实际维度（最近一次 embed 的返回）
- Milvus 集合是否存在、`vector` 字段的 index 类型（FLAT/HNSW/IVF_FLAT）、`metric_type`
- 最近一次 upsert/search 成功时间、失败时间
- collection 中实际 entity 数量（按 `user_id` 分桶）

#### Scenario: 管理员排查为什么 AI 检索总是空
- **WHEN** 管理员 `GET /api/v1/admin/vector/status`
- **THEN** 接口返回 JSON `{enabled, available, model, dim, milvus: {collection, ready, indexType, metricType, entityCount, errorMessage}}`
- **AND** `milvus.errorMessage` 非空时表示存在就绪问题（描述是哪一步失败）

### Requirement: AI Agent 自主 ReAct 循环

系统 SHALL 在 `ChatReasoner` 之上提供"ReAct 自主循环"模式，让模型自主决定调哪个工具、何时停止：

- **协议**：在 streaming system prompt 注入"ReAct 协议" —— 模型在每轮输出中按 `<thought>...</thought><action>tool_name(args)</action>` 格式表达意图，工具结果以 `<observation>...</observation>` 注入下一轮 prompt。
- **后端循环器** (`ReActController`)：最多 6 轮，每轮解析 `<action>` 标签 → 在 `ToolRegistry` 查对应工具 → 执行 → 把结果作为 `<observation>` 拼回 prompt。`final` 工具或达到 6 轮 → 输出最终回答。
- **可注册工具**：`milvusSearchTool` / `memoryStatsTool` / `emotionAnalysisTool` / `timelineNavigationTool` / `memoryDetailTool` / `supportTicketTool` (6 个内置) + 自定义扩展点。
- **前端协议** (兼容旧前端)：在 SSE 中新增 `event: thought` 帧携带模型思考文本；`event: action` 帧携带工具调用；`event: observation` 帧携带工具结果。`event: tool_start` / `event: tool_end` 保留作为 action 的高阶表达。

#### Scenario: 用户问"帮我找 2023 年在云南的记忆，整理成时间线"
- **WHEN** 发送 `POST /api/v1/reconstruct/chat/stream`，intent=plan，附 question
- **THEN** SSE 流依次发出 `meta` → `thought`("我需要先按关键词搜记忆") → `action`(milvusSearchTool, query="2023年云南") → `observation`(top-5 命中) → `thought`("筛选后需要时间线") → `action`(timelineNavigationTool, ids=[...]) → `observation`(时间线数据) → `thought`("我已经有足够材料回答") → `token` 流
- **AND** 最多 6 轮；超过 6 轮自动 `final` 并把已有 observation 喂模型生成最终回答
- **AND** 单轮思考>12s 或工具调用失败 → 该轮跳过，模型用 partial observation 继续

#### Scenario: 用户问"你好"（寒暄）
- **WHEN** intent=chat
- **THEN** 不进入 ReAct 循环，直接走单轮 chat 流（保留旧行为，向后兼容）
- **AND** SSE 流只有 `meta` → `token` → `done`

### Requirement: 前端 mock 数据全面清理

系统 SHALL 把所有前端"硬编码 0.75/3 条假数据/fakeEmotion"换成后端真实接口或显式"等待接口"占位：

- `ResonanceHubView` 三个 metric 卡片 → 走 `resonance-service` 真实统计接口（avgScore / totalMatches / matchingAlgorithmName）
- `ProfileView.fakeEmotion` → 走 `auth-service` `/users/me/emotion-summary`（如后端未实现，先显式 placeholder "情绪画像功能开发中"）
- `ResonanceHubView` 搜索按钮无响应 → 排查：后端返回 200 但 `ResonanceMatch.ownerUsername` 字段缺失 → 补字段或前端容错

#### Scenario: 打开共振大厅
- **WHEN** 进入 `/resonance` 页面
- **THEN** 三个 metric 卡片显示 `resonance-service` 真实统计（不固定为 0.75/项目内/多模态输出）
- **AND** 调用搜索时按钮在 200ms 内进入 loading，30s 内拿到结果或显示"AI 暂不可用"明确文案

## MODIFIED Requirements

### Requirement: VectorStoreProperties 默认值

把 `application.yml` 与 `VectorStoreProperties.java` 类的默认值统一为 `nvidia/nv-embed-v1` + 4096 维（yml 当前值；类默认值是 `nvidia/nv-embedqa-e5-v5` + 1024 维）。

#### Scenario: 启动 ai-service 不设环境变量
- **WHEN** `EMBEDDING_MODEL` 和 `EMBEDDING_DIMENSION` 都未设置
- **THEN** 运行时使用 `nvidia/nv-embed-v1` + 4096 维（yml 默认）
- **AND** `/admin/vector/status` 返回 `{model: "nvidia/nv-embed-v1", dim: 4096}`

### Requirement: Milvus collection 显式 index

`MilvusVectorStore.createCollection` SHALL 在 quick-setup 后显式调用 `POST /v2/vectordb/indexes/create`，参数 `{indexName, fieldName: "vector", indexType: "HNSW", metricType: "COSINE", params: {M: 16, efConstruction: 200}}`。原本依赖 Milvus quick-setup 默认 index，遇到 dim=4096 时偶发 index 类型为 IVF_FLAT → 检索质量下降。

#### Scenario: 首次启动，Milvus collection 不存在
- **WHEN** `ensureCollection()` 触发，且 `has` 返回 false
- **THEN** 先 `createCollection`(quick-setup) → 立刻 `createIndex`(HNSW + COSINE)
- **AND** 任何一步失败 → `available = false`，降级到关键词检索

## REMOVED Requirements

### Requirement: 旧 `STREAMING_SYSTEM_PROMPT` 第 4 条

**Reason**：第 4 条"无法直接调用工具"是 v2 妥协产物（当时 Spring AI 1.0.0-M4 stream() 暴露 function-calling 不稳定）。现在通过 `<thought>/<action>/<observation>` 协议在 prompt 层显式表达 ReAct，比依赖 Spring AI 黑盒更可控。

**Migration**：把 system prompt 第 4 条改为"自主 ReAct 协议"说明，告诉模型按 `<thought>...</thought><action>tool(args)</action>` 格式输出，由后端循环控制器执行工具。
