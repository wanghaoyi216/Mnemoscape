# Tasks

## 任务 A · 资产污染 allowlist（高优先）

- [x] **A1 MinIO 顶层目录白名单定义**
  - 路径：`backend/asset-service/.../config/StorageProperties.java` 加 `Set<String> publicTopLevelDirs = Set.of("photo","video","audio","gif","music","icon","icons")` 字段
  - 暴露 `isPublicObject(String objectName)` 方法：判断 `objectName` 是否以白名单目录开头（`name.startsWith(dir + "/")`）
  - 验证：白名单外目录（如 `chat/foo.png`）→ 返回 false
- [x] **A2 AssetService.listMinioStaticInternal 改造**
  - 路径：`backend/asset-service/.../service/AssetService.java` 第 152-205 行
  - 用 `isPublicObject(name)` 替换 `name.startsWith(USER_PREFIX)` 之外的"默认放行"逻辑
  - 私有对象分支保留（`users/{userId}/` 才放行）
  - 根级裸文件 → 全部不放行（用 `name.contains("/") == false` 判定后跳过）
- [x] **A3 AdminAssetController 加迁移接口**
  - 路径：`backend/asset-service/.../controller/AdminAssetController.java`
  - 新端点 `POST /api/v1/admin/assets/migrate-legacy-images?dryRun=true`
  - 扫所有白名单外子目录（递归），把 `chat/` `support/` `tickets/` `tmp/` 等的对象搬到 `legacy-orphan/{originalPath}`
  - 返回 `{scanned, candidates, wouldMigrate, samples: [...]}`
  - 复用 `AssetService.migrateLegacyOrphans(dryRun)` 模式，但加新方法 `migrateOffAllowlist(dryRun)`
- [x] **A4 单元测试：allowlist 判定**
  - 文件：`backend/asset-service/src/test/java/com/mnemoscape/asset/service/AssetServiceAllowlistTest.java`
  - 用例：`photo/foo.png` → true；`chat/bar.png` → false；`users/uuid/img.png` → 走 owner 检查（owner 一致 → true）；`legacy-orphan/old.png` → false；根级 `bare.png` → false

## 任务 B · Milvus 向量库修复（高优先）

- [x] **B1 统一 embedding 模型默认值**
  - `backend/ai-service/.../config/VectorStoreProperties.java`：把 `embeddingModel` 默认值改为 `"nvidia/nv-embed-v1"`，`embeddingDimension` 改为 `4096`
  - `application.yml` 不变（yml 与类默认值统一）
  - 验证：读这两个值时输出 `nvidia/nv-embed-v1` / 4096
- [x] **B2 MilvusVectorStore.createCollection 显式建 index**
  - 路径：`backend/ai-service/.../service/MilvusVectorStore.java` 第 304-332 行
  - quick-setup 后立刻 `POST /v2/vectordb/indexes/create`，body:
    ```json
    {
      "collectionName": "...",
      "indexParams": [{
        "fieldName": "vector",
        "indexName": "vector_hnsw",
        "indexType": "HNSW",
        "metricType": "COSINE",
        "params": {"M": 16, "efConstruction": 200}
      }]
    }
    ```
  - 失败 → `available = false`，记 ERROR 日志
- [x] **B3 VectorIndexService.isReady 加入"实际维度对齐"检查**
  - 路径：`backend/ai-service/.../service/VectorIndexService.java`
  - `isReady()` 在原 `props.isEnabled() && embeddingClient.isConfigured() && vectorStore.isEnabled()` 之外，加一次"最近一次 embed 维度 == 配置维度"断言；不匹配 → 返回 false 并记 WARN
  - 缓存 lastObservedDimension（volatile 字段）以避免每次 embed 都重读
- [x] **B4 /admin/vector/status 自检端点**
  - 新文件：`backend/ai-service/.../controller/VectorAdminController.java`
  - 路径：`GET /api/v1/admin/vector/status`（需 admin 角色）
  - 返回 JSON：
    ```json
    {
      "enabled": true, "available": true,
      "model": "nvidia/nv-embed-v1", "dim": 4096, "observedDim": 4096,
      "milvus": {
        "collection": "mnemoscape_memories",
        "ready": true,
        "indexType": "HNSW", "metricType": "COSINE",
        "entityCount": 1234, "userCount": 56,
        "lastUpsertOkAt": "2026-06-03T10:00:00Z",
        "lastUpsertFailAt": null,
        "lastError": null
      }
    }
    ```
  - 暴露 `MilvusVectorStore.status()` 已有信息 + 扩 entityCount 查询
- [x] **B5 MilvusVectorStore.status() 增强**
  - 加 `entityCount` / `userCount` 字段（调 `POST /v2/vectordb/entities/query` `outputFields: ["count(*)"]` 或 `outputFields: ["user_id"]` 后聚合）
  - 加 `lastUpsertOkAt` / `lastUpsertFailAt` / `lastError` 时间戳字段
- [x] **B6 单元测试：VectorStoreProperties 默认值**
  - 文件：`backend/ai-service/src/test/java/com/mnemoscape/ai/config/VectorStorePropertiesTest.java`
  - 验证默认值是 `nvidia/nv-embed-v1` / 4096

## 任务 C · AI Agent ReAct 自主循环（高优先）

- [x] **C1 ReAct 协议 prompt**
  - 路径：`backend/ai-service/.../service/ChatReasoner.java`
  - 新增常量 `REACT_PROTOCOL_PROMPT`：
    ```
    你是一个自主 ReAct Agent。回答用户问题时按以下协议输出：
    1. 先用 <thought>...</thought> 表达你打算做什么（一句话）
    2. 决定调工具时输出 <action tool="tool_name">{"arg":"value"}</action>
    3. 拿到 <observation>...</observation> 后再写 <thought>...</thought>
    4. 信息足够时输出 <action tool="final">{"answer":"..."}</action> 结束
    5. 不要在 <thought> 之外使用自然语言；不要编造工具结果
    ```
  - `STREAMING_SYSTEM_PROMPT` 第 4 条改引用该常量
- [x] **C2 ToolRegistry bean**
  - 新文件：`backend/ai-service/.../service/ToolRegistry.java`
  - 接口 `Tool`：`String name(); String description(); Object execute(String argsJson, ReActContext ctx)`
  - 内置 6 个工具：
    1. `milvusSearchTool` — 调 `MilvusSearchTool.searchForUser`
    2. `memoryStatsTool` — 调 `MemoryService.listMemories` 后算条数/情绪分布
    3. `emotionAnalysisTool` — 调 LLM 做情绪分析（用同一个 ChatClient 短 prompt）
    4. `timelineNavigationTool` — 调 memory-service 取时间线段
    5. `memoryDetailTool` — 按 id 取单条记忆
    6. `supportTicketTool` — 调 `SupportService` 创建工单
  - 启动时 `@PostConstruct` 把这 6 个注册到内存 Map
- [x] **C3 ReActController 循环器**
  - 新文件：`backend/ai-service/.../service/ReActController.java`
  - 方法 `run(String userQuestion, String userId, ToolEventListener listener): Flux<ReActEvent>`
  - 内部：
    1. 把 user question + system prompt + RAG prefix 拼成 `messages`
    2. 调 ChatClient.stream() 拿流
    3. 解析 `<thought>` `<action tool="...">{...}</action>` 标记
    4. 命中 action → 调 ToolRegistry.execute() → 把结果作为 `<observation>` 注入下一轮 messages
    5. 最多 6 轮；超 → 强制 final
  - 事件类型：`THOUGHT` / `ACTION_START` / `OBSERVATION` / `TOKEN` / `DONE` / `ERROR`
- [x] **C4 ChatController 接入 ReAct**
  - 路径：`backend/ai-service/.../controller/ChatController.java` 第 140-255 行 `stream()` 方法
  - 当 `intent == PLAN` 且模型支持 ReAct → 走 `ReActController.run()` 路径
  - SSE 事件映射：`THOUGHT` → `event: thought`；`ACTION_START` → `event: tool_start`；`OBSERVATION` → `event: tool_end`；`TOKEN` → `event: token`；`DONE` → `event: done`
  - 当 `intent == CHAT` → 走原 `reasoner.streamAnswer()`（旧逻辑，向后兼容）
- [x] **C5 前端 thought 流渲染**
  - 路径：`frontend/src/components/ai/AiMascotDock.vue` 的 SSE 解析器
  - 加 `event: thought` 处理：把 m.text 之前插入一段半透明斜体"💭 思考：…"
  - 现有 `event: tool_start` / `event: tool_end` 不变
  - 加 `m.thoughts: string[]` 字段（数组，让多轮 thought 都保留）
- [x] **C6 ToolRegistry 单元测试**
  - 文件：`backend/ai-service/src/test/java/com/mnemoscape/ai/service/ToolRegistryTest.java`
  - 用例：注册一个 mock tool → execute 返回预设值；未注册工具 → 抛 `ToolNotFoundException`；JSON args 解析失败 → 抛 `ToolArgsParseException`
- [x] **C7 ReActController 端到端测试**
  - 文件：`backend/ai-service/src/test/java/com/mnemoscape/ai/service/ReActControllerIntegrationTest.java`
  - 用 `@MockBean` 替掉 `ChatClient` 和 `MilvusSearchTool`
  - 模拟模型两轮输出（搜记忆 → final）
  - 验证：THOUGHT × 2 + ACTION_START × 1 + OBSERVATION × 1 + TOKEN × 1 + DONE × 1 全部发出

## 任务 D · 前端 mock 清理（中优先）

- [x] **D1 ResonanceHubView 三个 metric 卡片接真实接口**
  - 路径：`frontend/src/views/ResonanceHubView.vue` 第 71-84 行
  - 新增 `frontend/src/api/resonance.ts` 的 `getResonanceStats()` 方法
  - 端点：`GET /api/v1/resonance/stats`（后端先实现，或临时走 `GET /api/v1/resonance/search` 返回值聚合）
  - 三卡片分别显示 `avgScore` / `totalMatches` / `algorithmName`（真实接口数据，无接口时显示"—"）
- [x] **D2 resonance-service 暴露 /stats 端点**
  - 路径：`backend/resonance-service/.../controller/ResonanceController.java`
  - 新增 `GET /api/v1/resonance/stats`：返回 `{ avgScore, totalMatches, algorithmName }`
  - 实现：调 memory-service `public-pool`，按 keyword+year+season 加权打分后聚合
- [x] **D3 ProfileView fakeEmotion 改为占位**
  - 路径：`frontend/src/views/ProfileView.vue` 第 165 行附近
  - 把 `fakeEmotion` 显式标 `mockNote: "情绪画像功能开发中"` + 中文版"待接入 ai-service 情绪聚合接口"
  - 当 `/users/me/emotion-summary` 接口存在时（任务 D4 完成），自动切到真实
- [x] **D4 auth-service 暴露 /users/me/emotion-summary**（可选）
  - 路径：`backend/auth-service/.../controller/AvatarProfileController.java` 或新 UserController
  - 端点：`GET /api/v1/users/me/emotion-summary`
  - 返回最近 30 天 memory-service 聚合（先返回 `{ enabled: false, message: "情绪画像功能开发中" }`）
- [x] **D5 ResonanceMatch 字段一致性**
  - 路径：`frontend/src/types/index.ts` + `backend/resonance-service/.../model/dto/ResonanceMatch.java`
  - 确认 `ownerUsername` 字段名一致（snake_case `owner_username` vs camelCase `ownerUsername`）；Jackson 默认 camelCase
  - 后端实际返回 `ownerUsername` → 前端 types 用 `ownerUsername`；后端返回 `owner_username` → 同步改
- [x] **D6 共鸣搜索按钮"无响应"诊断**
  - 排查 `frontend/src/stores/resonance.ts` 的 `search` action：是否在 200 响应时正确 set searchResults
  - 加显式 `try/catch` 抛出 toast 错误
  - 后端 `resonance-service/.../service/ResonanceService.searchResonances` 验证：返回结构含 `memoryId / title / location / year / score / ownerUsername`

## 任务 E · 端到端验证（必须）

- [ ] **E1 编译**
  - 后端：`./mvnw -pl ai-service,memory-service,resonance-service,asset-service,api-gateway,auth-service -am compile` → BUILD SUCCESS
  - 前端：`npx vue-tsc --noEmit -p tsconfig.app.json` → 0 errors
- [ ] **E2 资产 allowlist 验证**
  - 手动：往 MinIO 上传一张 `chat/test.png` + `photo/test.png` → 调 `/api/v1/assets/static/resources` → 列表中只有 `photo/test.png`
  - 调 `POST /api/v1/admin/assets/migrate-legacy-images?dryRun=true` → 列出 `chat/test.png` 为候选
  - `dryRun=false` → `chat/test.png` 移到 `legacy-orphan/chat/test.png`，列表中消失
- [ ] **E3 Milvus 状态验证**
  - 启动后调 `GET /api/v1/admin/vector/status` → 看到 `indexType: "HNSW"`, `metricType: "COSINE"`, `entityCount: N`
  - 创建 5 条记忆 → 5 分钟后 entityCount == 5
  - 用 ChatReasoner 问"我最近有什么记忆" → RAG 召回非空
- [ ] **E4 ReAct 循环验证**
  - 启动前端，开 AI 球，问"帮我找 2023 年在云南的记忆，整理成时间线"
  - SSE 流依次看到 `thought`（"我需要先按关键词搜记忆"）→ `tool_start` → `tool_end` → `thought`（"现在做时间线"）→ `tool_start` → `tool_end` → `token` 流
  - 前端聊天泡渲染 "💭 思考：…" 灰色斜体行
- [ ] **E5 前端 mock 验证**
  - 打开 `/resonance` 页面 → 三个 metric 卡片显示真实统计（不再是 0.75 / "项目内"/"多模态输出"）
  - 搜索按钮点击 → loading 状态明确，30s 内返回结果
  - 打开 `/profile` 页面 → 情绪积分卡片显示"待接入"占位（不再有 `fakeEmotion` 字样）

# 任务依赖

- A2 依赖 A1（白名单定义）
- A3 依赖 A2（迁移接口基于新 allowlist 判定）
- B2 依赖 B1（默认值统一）
- B3 依赖 B1
- B4 依赖 B5（status 接口返回 status() 增强字段）
- C3 依赖 C2（ReAct 用 ToolRegistry）
- C4 依赖 C3
- C5 独立（前端 thought 渲染）
- D1 依赖 D2（前端 stats 卡片需要后端接口）
- D3 依赖 D4（可选）
- D6 独立
- E2 依赖 A1-A4
- E3 依赖 B1-B6
- E4 依赖 C1-C5
- E5 依赖 D1-D6

并行建议：
- 任务 A、B、C、D 各组内任务可并行（不同文件）
- A 组（资产）和 B 组（向量库）可同时开工
- C 组（ReAct）依赖 B 组就绪（ToolRegistry 调 MilvusSearchTool）
- D 组（前端）独立
- E 组验证必须等 A-D 全部完成
