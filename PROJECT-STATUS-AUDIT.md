# Mnemoscape · 项目功能审计与待办清单

> 生成时间：2026-05-26
> 范围：整个 monorepo（backend 9 个微服务 + frontend Vue3 单页 + docker / scripts / openspec）
> 目的：在新窗口继续开发前，把"已实现 / 半实现 / mock / 残缺 / 体验问题"全部列清楚

---

## 0. 一页速览

| 大模块 | 状态 | 主要问题 |
|---|---|---|
| 鉴权 (auth-service) | ✅ 真实 | FRIENDS 隐私级别未对接 |
| 记忆 CRUD (memory-service) | ✅ 真实 | 创建/更新/版本/漂移都通真实 SQL |
| 记忆漂移 (DriftCalculator) | ✅ 真实 | 艾宾浩斯遗忘曲线，非 mock |
| 记忆碎片 (memory_fragments) | ⚠️ 半 mock | DB 真，但内容来自 `MockReconstructService` 写死的英文文本 |
| 版本历史 (memory_versions) | ⚠️ 半 mock | DB 真，但 `changeDescription` 是后端硬编码英文常量 |
| 时空地图 (Atlas / MapLibre) | ✅ 已修复 | 之前一半黑屏问题已修复（ResizeObserver + globe 投影） |
| 资源 (asset-service / MinIO) | ✅ 真实 | 上传 fallback 用 blob URL（离线降级，可改进） |
| 共鸣 (resonance-service) | 🔴 全 mock | searchResonances / createSpace 都是写死的 mock-memory-1/2/3 + 随机相似度 |
| 场景重建 (ai-service /reconstruct) | 🔴 全 mock | 类名直接叫 `MockReconstructService`，6 套硬编码 SceneProfile 随机挑文案 |
| AI 对话 (ai-service /chat) | ✅ 真实 LLM | 已接 NVIDIA Integrate · MiniMax M2.7，SSE 流式可用，但**不支持多模态** |
| AI 球前端 (AiMascotDock) | ⚠️ 视觉 + 功能 | 内核仍偏紫；可拖动；intentHints 写死；缺多模态附件上传入口 |
| 前端 i18n | ⚠️ 多处缺失 | 至少 30 处中英文混排（fragment_type、changeType、SceneViewer HUD 等） |
| 共鸣便签 (NoteComposer) | 🔴 整组件无 i18n | 全英文 + mood 数组直显原始 enum |
| 个人页情绪积分 | ⚠️ 显式 mock | `fakeEmotion` 硬编码，已加 mockNote 提示 |

颜色含义：✅ 真实可用 ｜ ⚠️ 部分缺陷或半 mock ｜ 🔴 全 mock 或严重问题

---

## 1. 后端微服务详细状态

### 1.1 auth-service ✅
**已实现**：
- 用户名/密码注册、登录、JWT 颁发、刷新
- 用户资料 (`/users/me/profile`)
- 用户搜索 (`/users/search`)
- 好友列表、好友请求、聊天主题持久化

**TODO / 缺陷**：
- **FRIENDS 隐私级别**：在 `MemoryService.checkAccess`（`backend/memory-service/.../MemoryService.java:431`）有 TODO 注释，目前 FRIENDS 等同 PRIVATE，未调 auth-service 的好友 API 做权限验证。
- 没有"邮箱验证 / 密码重置"流程。

### 1.2 memory-service ✅
**已实现**：
- 记忆 CRUD（含 sceneDataUrl 字段）
- `/memories/route` 时间序轨迹段
- `/memories/{id}/drift` 实时计算（艾宾浩斯曲线）
- `/memories/{id}/versions` + `/restore`
- `/memories/{id}/fragments`
- `/users/me/location` 用最近一条带坐标的记忆做近似当前位置
- GeocodingService（anchor 表 + 自由文本解析坐标）

**TODO / 缺陷**：
- 第 97/332/349/360/392 行的 `createVersion(memory, X, "Memory created")` 等英文常量描述需要改成 i18n key 或在前端做枚举翻译。
- FRIENDS 隐私权限未实装（同 1.1）。

### 1.3 ai-service · /chat（对话） ✅ 真实
**已实现**（详见 `ChatReasoner.java` + `ChatController.java`）：
- 真实 NVIDIA Integrate API 调用（base-url: `https://integrate.api.nvidia.com`，OpenAI 兼容协议）
- 同步 `POST /chat` 与流式 `POST /chat/stream`
- SSE 事件序列：`meta` → `tool_start` → `tool_end` → `token`(多个) → `done` / `error`
- 三道屏障：prompt-injection guard、占位 key 检测、上游异常归一化
- 意图分类 v2：寒暄白名单 + 短句保底 chat，避免"你是谁?"被误判为 plan
- 默认 system prompt 描述「星空使者 / Echo Envoy」角色 + 防泄漏提示词

**TODO / 缺陷**：
- **多模态支持缺失**（详见 §3）— MiniMax M2.7 自身不支持图片/音频输入。
- ChatClient.stream() 在 Spring AI 1.0.0-M4 暂未原生暴露 tool_start / tool_end 回调，目前 SSE 实际只发 token；前端预留了事件名但当前不会触发。
- 工具集（milvusSearchTool / neo4jRelationTool / minioMediaFetchTool）已通过 `@Bean FunctionCallback` 注册到 ChatClient.Builder，但**实际调用频率取决于模型**；MiniMax M2.7 的工具调用稳定性未做端到端验证。

### 1.4 ai-service · /reconstruct（场景重建） 🔴 全 mock
**问题**：`ReconstructController` 直接注入 `MockReconstructService`（**类名就叫 Mock**）。
- `backend/ai-service/.../service/MockReconstructService.java`：13 行类签名
- 6 套写死的 SceneProfile（summer/winter/night/rain/spring/autumn），每套含：
  - 写死的英文 environment 名（如 `"summer_courtyard"`、`"snowy_landscape"`）
  - 写死的 lighting / terrain / atmosphere
  - 写死的 4-5 条英文 forgottenDetails
  - 写死的 4 条英文 emotionFlashbacks（"A moment of pure childhood joy flashes through" 即此）
  - 写死的 visual / auditory / olfactory / tactile 文案
- `detectScene(description)` 仅做关键词正则匹配选 profile

**用户感知**：截图里"打开记忆文档" 后看到的"记忆碎片"内容、"进入 3D 空间"后的 HUD 文案，全部源自这里。

**改造方向**：
- 短期：把 `MockReconstructService` 改名 `RuleBasedReconstructService`，明确"规则版"定位；继续保留作为 LLM 不可用时的降级
- 中期：新增 `LlmReconstructService`，接 NVIDIA Integrate 的视觉模型（如 `nvidia/llama-3.2-nemotron-vision`）做"用户描述 → SceneData JSON"
- 长期：接 Stable Diffusion / Suno 生成真实可视化资产存到 MinIO，sceneDataUrl 指向 MinIO 资源

### 1.5 ai-service · 工具集状态

| Tool Bean | 文件 | 实现度 | 备注 |
|---|---|---|---|
| MilvusSearchTool | `backend/ai-service/.../tools/MilvusSearchTool.java` | ⚠️ 半真实 | 注释自陈"打分占位"，实际从 memory-service 拉数据后做关键词排序 |
| Neo4jRelationTool | 同目录 | ⚠️ 待验证 | 依赖 entity_extractor 输出，后者也是规则版 |
| MinioMediaFetchTool | 同目录 | ✅ 真实 | 走 asset-service `/assets/static/resources` |

### 1.6 resonance-service 🔴 全 mock
**问题**：`ResonanceService.java`
- 第 30-46 行 `searchResonances`：返回 3 条 mock-memory-1/2/3，title 是 `"A warm summer evening"` / `"Childhood playground memories"` / `"Grandma's kitchen"` 写死英文，相似度 `ThreadLocalRandom`
- 第 48-60 行 `createSpace`：相似度纯随机，sceneDataUrl 是 `"scene://resonance/" + UUID`（伪 URL，不对应任何资源）

**用户感知**：「灵魂共鸣大厅」点搜索按钮，看到的 3 条结果全是假数据。

**改造方向**：
- searchResonances 应调用 MilvusSearchTool 做向量相似度搜索
- createSpace 的相似度应从 Milvus 距离/cosine 算
- sceneDataUrl 应该指向真实 MinIO 资源（reconstruct API 返回的链接）

### 1.7 asset-service ✅
- `/assets/static/resources` 列出 MinIO + 本地 resource/ 文件
- `/assets/upload` 上传到 MinIO，返回 presigned URL
- 唯一可改进点：上传失败时前端 ChatView 用 `URL.createObjectURL(file)` 当 fake URL 发消息，blob URL 一刷新就失效（行 220-232）

### 1.8 entity-extractor 服务 ⚠️ 半实现
- 文件：`backend/ai-service/.../service/EntityExtractor.java`
- 现状：注释明确"在没有真正接入大模型之前先用规则跑通"，靠正则字典提取 `entities/keyTerms/locations/timeRanges`
- 用途：把 memory.description 提取关键词后写到 Neo4j（memory ↔ entity 关系图）
- 改造方向：换成 Spring AI 的结构化输出（OpenAiChatClient + JSON schema）让 MiniMax M2.7 做 NER

### 1.9 gateway / common-utils
均为基础设施，无 mock 数据。

---

## 2. 前端模块详细状态

### 2.1 路由与页面清单
路由定义见 `frontend/src/router/index.ts`：

| 路径 | 视图 | 状态 | 主要问题 |
|---|---|---|---|
| `/login` | LoginView | ✅ 已修复居中 | 无 |
| `/register` | RegisterView | ✅ 已修复居中 | 无 |
| `/memories` | MemoryListView | ✅ 真实 | 无 |
| `/memories/new` | MemoryBuilderView | ✅ 已升级 LocationPicker | 封面选择器很窄、有重复（详 §2.4） |
| `/memories/:id` | MemoryDetailView | ⚠️ 部分中英混排 | fragmentType / changeType 直显 enum |
| `/memories/:id/scene` | SceneViewer | 🔴 数据全 mock + 中英混排 | 9 处 HUD 中英混排 + 后端 reconstruct 全 mock |
| `/atlas` | MemoryAtlasView | ✅ 已修复 | MAP/FLOW 占位英文图标 |
| `/timeline` | MemoryTimelineView | ✅ 真实 | 无 |
| `/graph` | MemoryGraphView | ✅ 真实 | metric 卡片硬写 "WebGL" |
| `/resonance` | ResonanceHubView | 🔴 后端全 mock | 用户搜索结果是假的 |
| `/resonance/:id` | ResonanceSpaceView | ⚠️ NoteComposer 全英文 | 弹窗未 i18n |
| `/chat` | ChatView | ⚠️ tab 名混排 | 「寻找 Curator」+ avatar/wallpaper placeholder 英文 |
| `/profile` | ProfileView | ⚠️ fakeEmotion mock | mockNote 已提示 |

### 2.2 AI 浮窗 (AiMascotDock.vue)
**已实现**：
- 球可拖动 + 面板可拖动（已修复）
- SSE 流式输出（已修复 reactive bug）
- 多会话历史（localStorage）
- 意图分类 v2（已修复"你是谁?"误规划）
- 上游不可用时渲染明确错误卡片

**当前问题**：
1. **球内核仍偏紫**（用户反馈核心 bug）
   - 文件：`frontend/src/components/ai/AiMascotDock.vue`
   - CSS 在 1059-1206 行
   - 根因：`.ai-orb__core` 的白色高光层 `inset: 18px` + `opacity: 0.8` 把中央 36px 区域全盖住；`.ai-orb` 自身 `box-shadow: 0 0 50px rgba(192,132,252,0.45)` 又透紫色光晕进来
   - 修复方向：
     - `.ai-orb__core { opacity: 0.8 → 0.25 }` 或 `inset: 18px → 28px`
     - `.ai-orb` box-shadow 中那条紫色 50px 内透改成中性青色或减半
     - 给 `.ai-orb__core` 加 `mix-blend-mode: overlay` 让它对下层 conic 是高光而不是纯白覆盖
2. **快捷提示 (intentHints) 写死**
   - 文件：`AiMascotDock.vue:117-121`
   - 应该按用户实际记忆动态生成（需后端配合提供 "推荐问题" API）
3. **缺多模态附件入口**（用户提的核心需求）
   - 当前 `<textarea>` 只能输入文本
   - 没有上传图片 / 音频按钮
   - 详见 §3 多模态方案
4. **遗留死代码**：`buildPlan(text)` / `buildTools(text)` 函数体保留但 `void` 掉，建议删除

### 2.3 SceneViewer (3D 空间)
**用户截图**：`outdoor_courtyard / warm_sunset lighting · flat terrain / 4 objects / 3 fragments / 97% saturation` 后是空白大框。

**根因 1：HUD 9 处直接吐后端 enum**
- 文件：`frontend/src/views/SceneViewer.vue`，行 113-122
```vue
<h2>{{ scene?.environment || 'Reconstructing...' }}</h2>           <!-- 'outdoor_courtyard' -->
<p>
  {{ scene?.lighting?.type || 'ambient' }} lighting ·              <!-- 'warm_sunset lighting' -->
  {{ scene?.terrain?.type || 'terrain' }} terrain                  <!-- 'flat terrain' -->
</p>
<span>{{ objectCount }} objects</span>                              <!-- '4 objects' -->
<span>{{ fragmentCount }} fragments</span>                          <!-- '3 fragments' -->
<span>{{ Math.round(...) }}% saturation</span>                      <!-- '97% saturation' -->
```
- 修复：加 `t('scene.environments.' + env, env)` / `t('scene.lighting.' + type, type)` 等查表，缺翻译时回退原值。

**根因 2：3D 容器空白**
- `useThreeScene.ts` 真的初始化了 Three.js（WebGLRenderer + OrbitControls + GridHelper）
- 但 `init()` 只用一次的尺寸初始化，**没有 ResizeObserver 兜底**（对比 MemoryAtlasView 已修复）
- 父容器 `.scene-canvas { min-height: min(74vh, 780px) }` 在 onMounted 同步阶段 clientWidth 可能为 0
- 修复参照 `MemoryAtlasView.vue` 的 ResizeObserver 模式

**根因 3：场景数据是 mock**
- 见 §1.4，整个 `/api/v1/reconstruct` 都是 `MockReconstructService`
- 即使 Three.js 渲染正常，渲染的也是 6 套写死 profile 之一
- "Scene unavailable" 标题（行 133）也是硬编码英文

### 2.4 MemoryBuilderView 封面选择器
**用户反馈**：
1. 选择窗口很窄（cover-art-gallery 是行内网格）
2. 默认图片有重复
3. 希望能从本地上传图片

**当前实现**（行 12-30）：
```ts
const allCoverOptions = computed(() => {
  const builtIn = Object.entries(images).map(([k, v]) => ({ ... }))   // 内置 media-catalog
  const dyn = [...dynamicMedia.state.photos, ...dynamicMedia.state.gifs].slice(0, 40).map(...)
  return [...builtIn, ...dyn]
})
```
- 内置 + MinIO 两源相加，可能出现同一资源在 builtIn 和 dynamicMedia 都出现 → 视觉上"重复"
- 网格 grid-template-columns 列数小（约 65px 一项）

**建议改造**：
1. 改为独立"封面选择器对话框"组件 `CoverPickerModal.vue`，全屏 / 大尺寸
2. 三 tab：内置库 / MinIO / 上传新图（走 asset-service `/assets/upload`）
3. 去重：对每个候选用 `src` 做 dedupe
4. 上传成功后立即 push 进 dynamicMedia.state，让用户能直接选

### 2.5 MemoryDetailView 中英文混排
**用户反馈**：
- 「记忆碎片」chip：`emotion_flashback 待探索` / `forgotten_detail 待探索`
- 「版本历史」chip：`v1 CREATE Memory created`

**位置**：
- `frontend/src/views/MemoryDetailView.vue`
- 行 169-176（fragmentType 直显）
- 行 196-203（changeType + changeDescription 直显）

**修复**：
1. 在 `frontend/src/i18n/locales/zh-CN.json` 增补 6 个 key：
   ```json
   "memory": {
     "detail": {
       "fragmentTypes": {
         "forgotten_detail": "被遗忘的细节",
         "emotion_flashback": "情感闪回"
       },
       "versionTypes": {
         "CREATE": "创建",
         "MODIFY": "修改",
         "LOCK": "封存",
         "RESTORE": "回滚"
       },
       "versionMessages": {
         "Memory created": "记忆已创建",
         "Memory updated": "记忆已修改",
         "Memory locked": "记忆已封存",
         "Memory unlocked": "记忆已解封",
         "restored": "已回滚到 v{version}"
       }
     }
   }
   ```
2. 模板里替换：
   ```vue
   <span class="status-pill">
     {{ t(`memory.detail.fragmentTypes.${fragment.fragmentType}`, fragment.fragmentType) }}
   </span>
   <span>{{ t(`memory.detail.versionTypes.${version.changeType}`, version.changeType) }}</span>
   <p>{{ t(`memory.detail.versionMessages.${version.changeDescription}`, version.changeDescription) }}</p>
   ```
3. 后端 `MemoryService.createVersion(...)` 的英文常量同时改成 i18n key（如 `"versionMessages.created"`），让前端做最终翻译。

### 2.6 ChatView 「寻找 Curator」中英混排
**位置**：`frontend/src/i18n/locales/zh-CN.json` 第 549/555/582 行
```json
"chat": {
  "tabs": { "discover": "寻找 Curator" },
  "sidebar": {
    "noFriends": "暂无好友。请前往「寻找 Curator」搜索并添加好友。",
    ...
  },
  "body": {
    "noSelectionSubtitle": "...通过「寻找 Curator」搜索其他时空馆长..."
  }
}
```
- en-US 那边对应 key 翻译为 "Discover"
- 中文版用了英文专有词 "Curator" 替代，没决定中文译名

**建议译名**：
- "寻找馆长"（与 "时空馆长" 一致）
- "寻找记忆者"
- "搜索忆者"

### 2.7 NoteComposer.vue 整组件无 i18n 🔴
**位置**：`frontend/src/components/resonance/NoteComposer.vue`

整个组件的 6 处硬编码英文：
```vue
<h3>Leave a note</h3>
<p>Attach a brief thought to the current resonance space.</p>
<textarea placeholder="What do you feel in this space?"></textarea>
<button v-for="m in moods" @click="mood = m">{{ m }}</button>     <!-- warm/melancholic/joyful/contemplative/grateful -->
<button>Place note</button>
<button>Cancel</button>
```

修复优先级：高（共鸣空间留言每次都看到）。

### 2.8 其他 placeholder 英文遗漏
| 文件 | 行 | placeholder 英文 |
|---|---|---|
| `views/ProfileView.vue` | 165 | `"HTTP(S) Link to Avatar"` |
| `views/ProfileView.vue` | 169 | `"HTTP(S) Link to Wallpaper"` |
| `views/ChatView.vue` | 495 | `"HTTP Link to Avatar"` |
| `views/ChatView.vue` | 500 | `"HTTP Link to Wallpaper WebP"` |
| `views/MemoryAtlasView.vue` | 1050 | 占位"图标" `MAP` |
| `views/MemoryAtlasView.vue` | 1064 | 占位"图标" `FLOW` |
| `views/MemoryGraphView.vue` | 422 | 指标值 `WebGL`（技术名词，可保留） |

---

## 3. AI 多模态：MiniMax M2.7 现状与可行方案

### 3.1 关键事实（2026-05 验证）
经核对 [NVIDIA NIM 文档](https://docs.api.nvidia.com/nim/reference/minimaxai-minimax-m2.7) 与 [MiniMax 官方平台](https://platform.minimax.io/docs/api-reference/api-overview)：

- **MiniMax M2.7（含 highspeed 版）目前不接受图像/音频/视频输入**，是**纯文本对话模型**
- 模型定位：agentic workflows + coding + reasoning（230B total / 10B active MoE）
- NVIDIA Integrate 上 `minimaxai/minimax-m2.7` endpoint 仅支持 OpenAI 兼容的 chat/completions 文本请求

> 内容已为合规改述（参考来源："MiniMax M2.7 currently only supports text input and cannot directly process images, audio, or video" — apiyi 帮助文档）

### 3.2 三种多模态接入方案（按工作量从小到大）

#### 方案 A：换/补一个视觉模型，前端 AI 球继续用 M2.7
最小代价。在前端附件上传时：
1. 用户上传图片 → asset-service → 拿到 MinIO presigned URL
2. 后端 ai-service 在 SSE 流前**先**调一个视觉模型（NVIDIA Integrate 上的 `nvidia/llama-3.2-nemotron-vision` 或 `meta/llama-3.2-90b-vision`）做"图片→中文描述"
3. 把描述拼到用户问题前面：`[图片描述：xxx]\n\n用户问：yyy` → 喂 M2.7
4. M2.7 输出文本回答

**优点**：M2.7 不动；视觉模型用同一个 NVIDIA_API_KEY；改动局限在 ai-service。
**缺点**：图片信息会丢细节（被压缩成文本）。

#### 方案 B：直接换底座为多模态模型
NVIDIA Integrate 上有真正的多模态聊天模型，比如 `meta/llama-3.2-90b-vision-instruct`、`google/gemini-2.5-pro` 之类。把 `application.yml` 的 `model` 配置项换掉就能直接走多模态。
- 改 `backend/ai-service/src/main/resources/application.yml`：
  ```yaml
  spring.ai.openai.chat.options.model: ${NVIDIA_MODEL:meta/llama-3.2-90b-vision-instruct}
  ```
- 前端 `AiChatRequest` 加 `images: string[]`（presigned URLs），后端 `ChatReasoner.buildUserPrompt` 拼成 OpenAI Vision 格式
- 注意：MiniMax M2.7 的 agentic 能力 + 中文偏好会换走，需要权衡

**优点**：原生多模态，体验最好。
**缺点**：失去 M2.7 的 agentic / 中文 / 工具调用强项。

#### 方案 C：混合策略（推荐）
- **默认**用 M2.7（纯文本场景）
- **检测到附件**时切到 vision-capable 模型
- 后端在 `ChatReasoner.streamAnswer` 里做模型选择：
  ```java
  String model = req.getImages() != null && !req.getImages().isEmpty()
                 ? props.getVisionModel()    // "meta/llama-3.2-90b-vision-instruct"
                 : props.getDefaultModel();  // "minimaxai/minimax-m2.7"
  ```
- Spring AI 1.0.0-M4 支持 `OpenAiChatOptions.builder().model(...)` 在调用时覆写

**优点**：保留 M2.7 优势 + 必要时多模态。
**缺点**：两套 prompt 模板需要维护。

### 3.3 前端附件上传入口设计
不论选哪个方案，前端 `AiMascotDock.vue` 都需要：
1. `<textarea>` 旁边加"📎 附件"按钮
2. 弹窗选择 / 拖拽 → 走 asset-service `/assets/upload` → 拿 MinIO URL
3. 在消息泡里显示缩略图 chip
4. send 时把 URLs 加到 SSE POST body 的 `images` / `audios` / `videos` 字段

---

## 4. Plan-and-Execute / ReAct 模式优化方向

### 4.1 当前实现状态
- **Plan**：前端启发式 + 后端硬编码 4 步模板（已在上一轮修掉前端预填，全交给后端 `event: meta`）
- **Execute**：依赖 Spring AI 的 function calling（FunctionCallback）由模型自己决定调用哪个 tool
- **ReAct**：当前没有显式的"思考 → 行动 → 观察"循环 UI；Spring AI 1.0.0-M4 内部多轮 tool calling 是黑盒

### 4.2 改进方向

**A. 让 Plan 由模型生成而非硬编码**
- 后端 ChatController 在 SSE 第一帧前先调一次 LLM："基于用户问题输出 JSON plan steps"
- 用 Spring AI 的 [Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html) 拿 `List<PlanStep>`
- 把 `event: meta` 改成发动态 plan
- 缺点：多一次 LLM 调用 → 首字延迟变长（可在用户问题为 PLAN 时才做）

**B. 把 ReAct 步骤暴露给前端**
- 当前 SSE 只发 `token` 一种数据帧
- 改造：用 Spring AI 的 `ChatClient.advisors()` 链路，在 `BeforeFunctionCallingAdvisor` / `AfterFunctionCallingAdvisor` 钩子里把工具调用名 + 入参 + 出参塞进 SSE
- 前端 `tool_start` / `tool_end` 事件已经有 reducer，加上去就能渲染 ReAct UI

**C. 流式 tool 调用 UI**
- 现有 `.ai-tool` 组件已经支持展开看 input/output（`AiMascotDock.vue:850-880`）
- 缺一条"思考链"展示：把模型每轮的 reasoning 文本（如果 M2.7 暴露 thinking tokens）渲染成"AI 正在思考：..."

**D. 长上下文检索增强**
- 当前 `buildMemoryDigest()` 只取最近 20 条记忆的 240 字符摘要
- 应该上 RAG：用户问题 → embedding → Milvus 向量检索 top-K → 注入 prompt
- MilvusSearchTool 已经搭好骨架，把 `searchResonances` 切到这个工具就能动

---

## 5. 优先级清单（建议在新窗口的执行顺序）

### P0 · 用户立刻能感知的体验问题
1. **AI 球内核改色**（CSS 改动）— `AiMascotDock.vue` `.ai-orb__core` 与 `.ai-orb` box-shadow
2. **MemoryDetailView 中英混排**（i18n key 6 个 + 模板替换）
3. **「寻找 Curator」译名**（zh-CN.json 3 处替换）
4. **NoteComposer i18n**（整组件加 t()，~10 个 key）
5. **SceneViewer HUD 9 处中英混排**

### P1 · 功能完整性
6. **AI 多模态附件上传**（详 §3，建议方案 C）
7. **MemoryBuilderView 封面选择器改对话框**（独立 CoverPickerModal）
8. **resonance-service 接 Milvus**（替换 mock searchResonances）
9. **SceneViewer ResizeObserver**（修复 3D 空白）

### P2 · 后端 mock 重构
10. **MockReconstructService → 规则版 + LLM 版双轨**
11. **EntityExtractor 接 Spring AI 结构化输出**
12. **MemoryService FRIENDS 隐私接 auth-service**

### P3 · 长期改造
13. **Plan-and-Execute 让 LLM 生成动态 plan**
14. **ReAct 步骤通过 advisors 钩子暴露到 SSE**
15. **RAG：MilvusSearchTool 接入 ChatReasoner.buildUserPrompt**

---

## 6. 不应改动 / 误判清单

以下是审计中**不是 bug** 的项，避免下个智能体误改：

1. `frontend/src/views/ProfileView.vue` 的 `fakeEmotion` — 已有 mockNote 提示，是诚实的"等接口"占位
2. `frontend/src/i18n/locales/zh-CN.json:248` 中的"前端 mock"字样 — 这是给开发者看的错误说明文案
3. `frontend/src/components/ai/AiMascotDock.vue` 上游不可用时渲染的"⚠ AI 暂不可用"卡片 — 不是 mock，是诚实降级
4. `backend/ai-service/.../config/application.yml` 的 `nvapi-placeholder-not-configured` 默认值 — 故意的，让 Spring AI 自动装配能完成；ChatReasoner 会在调用前检测出占位符并抛 MISSING_KEY
5. `backend/.env.workpc` 里的密码/token — 用户已声明仓库私有，不需要脱敏
6. 「记忆漂移」面板数据 — 后端 DriftCalculator 真算的艾宾浩斯曲线，不是 mock；只是新建记忆当天看着像静态值
7. MilvusSearchTool 当前实现 — 注释自陈"打分占位"但**架构是对的**，只是评分函数待替换；不是从零重写的对象

---

## 7. 已完成的修复记录（本次会话）

为避免下个智能体重复劳动，列出已完成项：

| 修复 | 文件 | 说明 |
|---|---|---|
| AI 上游 404 | `application.yml` + `.env.example` + `.env.workpc` | base-url 去掉重复的 `/v1` |
| 地图瓦片黑屏 | `MemoryAtlasView.vue` | 主底图换高德地图，fallback 多镜像 |
| 地图 globe 投影 | `MemoryAtlasView.vue` | projection 从构造参数移到 style 内 |
| 地图一半黑屏 | `MemoryAtlasView.vue` | 加 ResizeObserver + 移除 maxBounds |
| AI 流式不刷 UI | `AiMascotDock.vue` | `appendMessage` 用 `reactive()` 包对象 |
| AI 误规划"你是谁?" | 前后端 `classify` | 寒暄白名单 + 短句保底 |
| AI 球纯紫 | `AiMascotDock.vue` | 改 conic-gradient 8 色彩虹（**球边可以了，球心还是紫**） |
| AI 面板不可拖 | `AiMascotDock.vue` | header 加 PointerEvents drag |
| 登录表单不居中 | `LoginView.vue` + `RegisterView.vue` | 移除负 margin |
| 地址输入 mock | `MemoryBuilderView.vue` + 新组件 | 加 LocationPicker（国家/省/市级联 + 浏览器定位） |
| Git 仓库初始化 | `.gitignore` + 远端 | 推送到 https://github.com/wanghaoyi216/Mnemoscape |

---

## 8. 待办的开放问题（需用户决策）

1. **「Curator」中文译名**：馆长 / 记忆者 / 忆者 / 时空旅人？
2. **AI 多模态方案**：选 A/B/C 哪个？（§3.2）
3. **Plan UI**：是否保留写死的 4 步模板做"快速展示"，还是完全切到 LLM 动态生成？（首字延迟权衡）
4. **MockReconstructService 命名**：要保留作降级（改名 `RuleBasedReconstructService`）还是直接删除？
5. **resonance-service 真实化优先级**：是这一阶段做，还是先做 AI 多模态？
