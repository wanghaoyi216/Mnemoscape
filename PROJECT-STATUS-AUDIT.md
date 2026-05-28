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


---

## 9. v2 状态更新（本次会话补充）

> 生成时间：2026-05-27
> 范围：在前辈 v1 基础上，本会话围绕 §5 优先级清单逐项推进，并修复了诸多 v1 漏报 / 后续浮现的问题
> 注意：上面 §0-§8 是 v1 历史快照，请配合本节阅读

### 9.1 一页速览（v2 重排）

| 大模块 | v1 状态 | v2 状态 | 关键变化 |
|---|---|---|---|
| 鉴权 (auth-service) | ✅ | ✅ | 加 `GET /friends/{otherUserId}/status` 探针 |
| 记忆 CRUD | ✅ | ✅ | 创建走异步增强（先返回 → 后台 LLM 重建） |
| 记忆漂移 | ✅ | ✅ | 不变 |
| 记忆碎片 | ⚠️ 半 mock | ✅ 真实（+历史兼容） | 规则版改中文 + LLM 路径优先 + 36 条历史英文字典兜底 + 一键重建按钮 |
| 版本历史 | ⚠️ 半 mock | ✅ 真实 | 加 `versionTypes` / `versionMessages` 双向字典 |
| 时空地图 (Atlas) | ✅ | ✅ | 加 `--app-header-h` CSS 变量解决 nav 重叠 |
| 资源 (asset-service) | ✅ | ✅ + 用户隔离 | 上传强制 `users/{userId}/` 前缀，public 资源仍可匿名读 |
| 共鸣 (resonance-service) | 🔴 全 mock | ✅ 真实 | 接 memory-service `public-pool`，关键词 + 季节/年/地点加权 |
| 场景重建 (/reconstruct) | 🔴 全 mock | ✅ 双轨 + grounded | LLM 优先 + Rule 兜底 + 接收年/季节/时段/地点等结构化上下文 |
| AI 对话 (/chat) | ✅ 真实文本 | ✅ + 多模态 + RAG + ReAct | Hybrid Vision (Llama 3.2 11B+90B) + 强制 RAG + 工具事件推到 SSE + 动态 Plan |
| AI 球前端 | ⚠️ | ✅ | 球纯紫修了、附件入口加了、登录默认开新对话、blob 缩略图永远可见 |
| 前端 i18n | ⚠️ 30 处混排 | ✅ 全部清完 | placeholder / SceneViewer HUD / 寻找馆长 / atlas 空状态 / NoteComposer / graph 度量 |
| EntityExtractor | ⚠️ 半实现 | ✅ 双轨 | 新建 `LlmEntityExtractor` + Controller 三档路由 |
| FRIENDS 隐私 | 🔴 TODO | ✅ 真实 | memory-service 调 auth-service Feign 探针，fail-closed |

剩余 ⚠️ / 🔴 项已全部下沉到 §9.6"未完成"。

---

### 9.2 v2 已完成清单（按 P0/P1/P2/P3 对齐 §5）

#### P0 · 用户立刻能感知
- [x] **P0-1 AI 球内核改色** · `AiMascotDock.vue`
  - `.ai-orb__core` opacity 0.8→0.35、inset 18→26px、加 `mix-blend-mode: overlay`
  - `.ai-orb` box-shadow 紫色 50px 内透换青+金双层；呼吸峰值同步去紫
  - **附加 root cause 修复**：`discoverMascotAsset()` 的 fallback `files.find(f.type === 'gif')` 会捡资源池里第一个 GIF 当吉祥物（盖到球上），删掉这条 fallback
- [x] **P0-2 MemoryDetailView 中英混排**：补 `fragmentTypes` (4 项) + `versionTypes` (4 项) + `versionMessages` (5 项) 字典
- [x] **P0-3「寻找 Curator」译名 → 「寻找馆长」**：3 处替换
- [x] **P0-4 NoteComposer 整组件 i18n**：8 个 key + 复用 `resonance.beacon.moods` mood 字典
- [x] **P0-5 SceneViewer HUD 9 处中英混排** + 所有 mock service 输出 enum 都翻译（environments/lighting/terrain 各 6 + 7 + 6 项）
- [x] **P0 补 1 启动每次开新对话**：`loadConversations()` 不再从 `CONV_ACTIVE_KEY` 恢复，默认调 `startNewChat(true)`
- [x] **P0 补 2 用户隔离上传图片**：MinIO 对象 key 加 `users/{userId}/` 前缀；gateway 公开路径"尽力解析身份"；download/delete/url 全鉴权
- [x] **P0 补 3 placeholder 英文遗漏**：ProfileView / ChatView 头像壁纸 placeholder、MemoryAtlasView 空状态 `MAP`/`FLOW` → 🗺️/🌊 emoji + i18n、MemoryGraphView 度量 i18n
- [x] **P0 补 4 顶部 nav 与 atlas 重叠**：AppHeader ResizeObserver 写 `--app-header-h` CSS 变量，atlas 用 `var(--app-header-h, 88px)` 让位

#### P1 · 功能完整性
- [x] **P1-6 AI 多模态混合检索（hybrid retrieval）**
  - 选型：Llama 3.2 11B Vision 主、90B Vision 备（实测稳定的 OpenAI 兼容 chat/completions endpoint；Qwen3.5/Kimi-K2.5 是异步 statuspolling 模式不可用）
  - **`VisionDescriber` 用 JDK 标准 HttpClient**（避开 Spring RestClient 对 `application/octet-stream` 的 converter 限制，byte[] 原始字节流）
  - **base64 内联**：MinIO presigned URL 指向 Tailscale 内网，公网视觉模型够不到，所以 ai-service 自己下载图片 → base64 → `data:` URI
  - **SSE keepalive**：每 5s 发一帧 `:keepalive`，避免视觉前置阻塞期间被代理 reset
  - 前端 AiMascotDock 加 📎 按钮 + 附件队列 chip + vision 标签胶囊（model id 用 `shortVisionModel(id)` 缩短显示）
  - 用户消息泡里图片用本地 blob URL 显示（永远可见，独立于 MinIO 网络可达性）
- [x] **P1-7 封面选择器对话框** · `CoverPickerModal.vue`
  - 全屏 modal、三 tab（内置 / 资源池 / 上传）、stripQuery 严格去重、上传成功立即推到 dynamicMedia 并自动切到资源池 tab
- [x] **P1-8 resonance-service 真实化**
  - memory-service 加 `GET /memories/public-pool`（跨用户公共记忆池查询）
  - resonance-service 加 OpenFeign + LoadBalancer 依赖、`MemoryServiceClient`
  - `searchResonances` 用关键词 jaccard + 年/季节/地点加权打分，fail-closed 不退化 mock
  - `createSpace` 复用 seed memory 的真 `sceneDataUrl`，相似度从 search 结果重算
- [x] **P1-9 SceneViewer ResizeObserver**：`useThreeScene.ts` init 防 0×0、加 ResizeObserver、dispose 时正确断开

#### P2 · 后端 mock 重构
- [x] **P2-10 MockReconstructService 双轨化**
  - 重命名 → `RuleBasedReconstructService`（前辈警告"不要删"仍生效，它是兜底）
  - 全部英文文案换中文（72 条）+ 描述长度下限从 20→5 字符，与前端 builder 对齐
  - 新建 `LlmReconstructService.sketch(req)`：Spring AI 输出严格 JSON SceneSketch，强约束枚举白名单
  - 新建 `ReconstructDispatcher`：模式 `auto`(默认) / `llm` / `rule`，LLM 失败透明降级 + Rule 提供管线壳
  - **后续强化**：prompt 接收 `title/year/season/timeOfDay/location` 结构化上下文，要求 environment/lighting/fragments 紧扣原文
- [x] **P2-11 EntityExtractor LLM 化**
  - 新建 `LlmEntityExtractor`，输出 schema 与现有 `EntityExtractResponse` 对齐
  - `EntityExtractController` 加三档路由（auto/llm/rule），LLM 失败降规则版
- [x] **P2-12 MemoryService FRIENDS 隐私接 auth-service**
  - auth-service 加 `GET /api/v1/friends/{otherUserId}/status` 轻量探针
  - memory-service 加 Feign client `AuthServiceClient`
  - `MemoryService.checkAccess` FRIENDS 分支调探针，fail-closed（auth 不可达拒绝读）

#### P3 · 长期改造
- [x] **P3-13 Plan 由 LLM 动态生成**
  - `ChatReasoner.generateDynamicPlan(question, zh, userId)` 输出 JSON 数组形式 plan
  - `ChatController` 加 `planExec` 线程池：PLAN 意图下**异步**跑规划（不阻塞首字延迟）
  - 新 SSE 事件 `plan_update`：1-3s 内推到前端，覆盖之前 meta 帧硬编码 4 步
  - 前端 reducer 保留已 done 步骤的状态、新步骤设 pending
- [x] **P3-14 ReAct 步骤暴露 SSE**
  - `ChatReasoner.ToolEventListener` 接口（onStart/onEnd）
  - 强制 RAG（`milvusSearchTool`）和视觉前置（`visionPrePass`）都包成 `tool_start` / `tool_end` 帧
  - 前端已有 reducer，自动渲染齿轮 → ✓ 动效，可点开看 input/output JSON
  - **限制**：Spring AI 1.0.0-M4 ChatClient.stream() 仍不暴露真实 function-calling 钩子。当上层升级时只需加更多 `tools.onStart/onEnd` 调用，对外契约不变
- [x] **P3-15 RAG 接入 ChatReasoner.buildUserPrompt**
  - `MilvusSearchTool` 拆出 `searchForUser(req, userId)`（显式 userId，不依赖 SecurityContext）
  - `ChatReasoner.buildRagPrefix(req, userId, tools)`：每次对话主动召回 top-K 命中记忆 prepend 到 prompt（与前端 context 去重）
  - `ChatController` 从 `X-User-Id` header 取 caller 透传

---

### 9.3 v2 数据流大修：解决"假" fragment + SceneViewer 空白

**根因复盘**：
1. 记忆**创建时** ai-service 跑过完整 reconstruct，结果 freeze 到 `memory.visualData` JSON 列；但 `MemoryResponse` 没暴露这个字段
2. SceneViewer **每次进页面都重跑** `/reconstruct`，赌一次 LLM 成功率 + 等 30s
3. axios 默认 timeout=15s，LLM 重建经常超过 → 前端报 "场景重建当前不可用"
4. 旧 mock 服务套模板，与用户描述无关

**v2 修复链路**：
- `MemoryResponse` 暴露 `visualData` / `emotionProfile`
- `frontend/types/index.ts` 同步加字段
- **SceneViewer 三级兜底**：
  1. `memory.visualData`（直接 JSON.parse，0 网络请求）
  2. `sceneDataUrl` http 链接 fetch
  3. 现场调 `/reconstruct`（保留 60s timeout 兜底）
- LLM grounding prompt 强化：禁止"长椅旧照片 / 鸟巢"等套模板话术
- 新端点 `POST /memories/{id}/regenerate-scene` + 前端"重建场景"按钮，让用户一键脱离假数据
- **createMemory 异步化**：`@EnableAsync` + `asyncEnrichmentSelf` 自代理，主线程立即返回；AI 重建在后台 10-30s 完成
- 历史 36 条英文 fragment 文案 → 中文 i18n 字典兜底（`fragmentsPanel.legacyContent`），用 `tm()` 取整段后 JS 端精确匹配

---

### 9.4 v2 新增/重要修改文件清单

#### 后端
| 文件 | 类型 | 用途 |
|---|---|---|
| `ai-service/.../service/RuleBasedReconstructService.java` | 重命名+重写 | 原 MockReconstructService，文案全中文 |
| `ai-service/.../service/LlmReconstructService.java` | 新建 | LLM 输出 SceneSketch JSON |
| `ai-service/.../service/ReconstructDispatcher.java` | 新建 | auto/llm/rule 三档路由 + 合并 |
| `ai-service/.../service/LlmEntityExtractor.java` | 新建 | LLM 版 NER |
| `ai-service/.../service/VisionDescriber.java` | 新建 | JDK HttpClient + base64 内联视觉 |
| `ai-service/.../tools/MilvusSearchTool.java` | 修改 | 加 `searchForUser(req, userId)` |
| `ai-service/.../service/ChatReasoner.java` | 大改 | RAG/Vision/ToolEventListener/动态 plan |
| `ai-service/.../controller/ChatController.java` | 大改 | SSE keepalive + plan_update + tool 事件桥 |
| `ai-service/.../config/AiUpstreamProperties.java` | 加字段 | visionModel/visionFallbackModel/visionMaxImages/visionTimeoutMs |
| `ai-service/.../model/dto/AiChatRequest.java` | 加字段 | `images: List<String>` |
| `ai-service/.../model/dto/ReconstructRequest.java` | 重写 | 加 title/year/season/timeOfDay/location |
| `asset-service/.../service/AssetService.java` | 大改 | 用户隔离 USER_PREFIX/uploadForUser/listMinioStaticForUser/checkRead/Write |
| `asset-service/.../controller/AssetController.java` | 修改 | 端点全部接 RequestContext.userId |
| `api-gateway/.../filter/AuthGlobalFilter.java` | 修改 | 公开路径"尽力解析身份"模式 |
| `auth-service/.../controller/FriendController.java` | 加端点 | `GET /friends/{otherUserId}/status` |
| `memory-service/.../client/AuthServiceClient.java` | 新建 | Feign 调 friend status 探针 |
| `memory-service/.../client/MemoryServiceClient.java`（在 resonance 包） | 新建 | resonance 调 memory public-pool |
| `memory-service/.../service/MemoryService.java` | 大改 | 异步 `runEnrichmentAsync` + checkAccess 接 auth-service + `regenerateScene` |
| `memory-service/.../controller/MemoryController.java` | 加端点 | `/memories/public-pool` + `/memories/{id}/regenerate-scene` |
| `memory-service/.../repository/MemoryRepository.java` | 加查询 | `findPublicPoolExcludingUser(excludeUserId, Pageable)` |
| `memory-service/.../repository/MemoryFragmentRepository.java` | 加查询 | `deleteByMemoryId(memoryId)` |
| `memory-service/.../model/dto/MemoryResponse.java` | 加字段 | `visualData` / `emotionProfile` |
| `memory-service/MemoryApplication.java` | 加注解 | `@EnableAsync` |
| `resonance-service/pom.xml` | 加依赖 | `spring-cloud-starter-openfeign` + `spring-cloud-starter-loadbalancer`（**两者必须同时加**） |
| `resonance-service/.../service/ResonanceService.java` | 重写 | 接 memory-service public-pool，关键词加权 |
| `resonance-service/.../controller/ResonanceController.java` | 修改 | 加 RequestContext.userId 注入 |

#### 前端
| 文件 | 类型 | 用途 |
|---|---|---|
| `components/common/CoverPickerModal.vue` | 新建 | 三 tab 全屏封面选择器 |
| `components/ai/AiMascotDock.vue` | 大改 | 附件队列 / vision 胶囊 / plan_update / tool 事件 / 启动开新对话 |
| `components/layout/AppHeader.vue` | 修改 | ResizeObserver 写 --app-header-h |
| `views/SceneViewer.vue` | 修改 | 三级 visualData 兜底 + i18n + ResizeObserver |
| `views/MemoryDetailView.vue` | 修改 | i18n 字典查表 + 重建场景按钮 + tm() 历史字典兜底 |
| `views/MemoryAtlasView.vue` | 修改 | --app-header-h 让位 + i18n-t 空状态 |
| `views/MemoryGraphView.vue` | 修改 | 按情绪着色 + i18n metrics |
| `views/MemoryBuilderView.vue` | 修改 | 接入 CoverPickerModal、地点 LocationPicker 已有 |
| `composables/useThreeScene.ts` | 修改 | ResizeObserver + 0×0 防御 |
| `composables/useDynamicMedia.ts` | 不变 | refresh() 在 modal 打开时被调用 |
| `api/memory.ts` | 加 fn | `regenerateScene(id)` + `reconstruct` 60s timeout |
| `stores/memory.ts` | 加 fn | `regenerateScene(id)` |
| `types/index.ts` | 加字段 | `visualData` / `emotionProfile` on MemoryItem |
| `i18n/locales/zh-CN.json` | 大改 | atlas.emptyHints / scene.environments等 / fragmentsPanel.legacyContent (36 条) / coverPicker / chat.sidebar.*Placeholder |
| `i18n/locales/en-US.json` | 大改 | 同步全部新字典 + memory.graph 完整翻译 |

---

### 9.5 v2 配置项变更

`backend/ai-service/src/main/resources/application.yml`：
```yaml
mnemoscape:
  ai:
    upstream:
      vision-model: ${NVIDIA_VISION_MODEL:meta/llama-3.2-11b-vision-instruct}
      vision-fallback-model: ${NVIDIA_VISION_FALLBACK_MODEL:meta/llama-3.2-90b-vision-instruct}
      vision-max-images: ${NVIDIA_VISION_MAX_IMAGES:4}
      vision-timeout-ms: ${NVIDIA_VISION_TIMEOUT_MS:45000}
    reconstruct:
      mode: ${RECONSTRUCT_MODE:auto}     # auto / llm / rule
    extract:
      mode: ${EXTRACT_MODE:auto}          # 同上
```

`.env.example` 同步新增 6 个变量；前端 `frontend/src/api/memory.ts` `reconstruct()` / `regenerateScene()` 单独 60s timeout。

---

### 9.6 仍未完成 / 仍有缺陷的项（给下个迭代）

#### 长期改造（受上游限制）
- **真 function-calling 钩子推到 SSE**：Spring AI 1.0.0-M4 的 ChatClient.stream() 仍不直接暴露 function 调用 callback。我做的 ReAct UI 是把"强制 RAG / 视觉前置"包装成工具事件，模型自己 function-call 时仍然黑盒。等 Spring AI 升级到能用 advisors 链路再补
- **真 Milvus 向量检索**：MilvusSearchTool 仍是"基于 memory-service 真实数据 + 关键词 jaccard 加权"的 RAG 替身。架构是对的，把 `searchForUser` 内部实现换成真实 embedding+Milvus 检索即可，对外契约不变

#### 设计书 v1.1 中描述但本会话未做的
- **管理端大屏可视化**（设计书 §3.4 / 当前完全没有）— 需要新增 admin route + ECharts/AntV 大屏 + 后端聚合接口
- **emotionVector 前端波形 / 动效**：现在只在 MemoryGraphView 用情绪着色；设计书 §3.4.4 的"全球记忆热力图（3D Heatmap）"没做
- **聊天系统 @AI助手 / 一键引入 AI 解密**（设计书 §3.2.3）— 当前 ChatView 与 AI 球是分离的
- **管理端"系统活跃用户多维度切换"看板**（设计书 §3.4.1）— 后端尚无 `dimension=DAILY/WEEKLY/MONTHLY/YEARLY` 聚合端点

#### 体验小问题
- **intentHints 写死**（前辈 §2.2.2）：仍硬编码 4 条；应该按用户实际记忆动态生成（需 ai-service 提供"推荐问题" API）
- **LocalResourceWatcher**（设计书 §3.2.2 静态资源 WatchService 热加载）：asset-service 已有 `LocalResourceWatcher.java`，但**未端到端验证** —— 需要确认放新文件到 `resource/` 后前端能瞬间感知
- **个人页 fakeEmotion**（前辈 §2.1）：仍是写死的 mock，等 ai-service 加情绪聚合接口
- **聊天历史漫游 / @AI**（前辈 §2.1 chat tab）：未规划
- **emotion vector 在 MemoryDetailView 的 drift-panel**：drift 是真的，但 emotionProfile 当前没在 detail 页可视化（只在 graph 用）

#### 历史数据兼容
- **DB 里既有的 fragments**（一部分仍是英文 mock 内容）：用户必须主动点"重建场景"才能刷成中文 grounded 内容。可以做：
  - 后台批量任务（管理员入口）一次性重建所有 fragments
  - 或者保留前端 `legacyContent` 字典作长期兜底
- **DB 里既有的 visualData**（部分为 null 或旧 mock）：SceneViewer 第三级 fallback 会现场调 reconstruct 自动修复

#### 安全 / 数据残留
- **MinIO bucket 中已残留的旧上传**（用户隔离修复**之前**的对象，无 `users/` 前缀）：当前会被错误地当成"公共内置素材"对所有人可见。我**没有**自动迁移 —— 需要管理员手动到 MinIO console 把不属于"项目预置 133MB resource 同步"的对象处理掉

---

### 9.7 v2 验证状态

#### 编译
- 后端：`./mvnw -pl ai-service,memory-service,resonance-service,asset-service,api-gateway,auth-service -am compile` → BUILD SUCCESS
- 前端：`npx vue-tsc --noEmit -p tsconfig.app.json` → 0 errors

#### 用户实测过的（用户报告 ✓）
- AI 球颜色（球边 + 球心都 OK）
- 启动开新对话
- 文本对话 SSE 流
- 图片上传 + Llama 3.2 11B Vision 视觉前置 + base座 M2.7 中文回答
- 视觉胶囊不溢出消息泡
- 用户消息泡里图片可见

#### 用户实测过仍有问题的
- ~~SceneViewer 一直空白~~ → 通过 visualData 三级兜底 + LLM 60s timeout 修复
- ~~记忆碎片是英文/无关内容~~ → 通过中文规则版 + LLM grounding + 重建按钮 + legacyContent 字典修复

#### 已修但未实测的
- FRIENDS 隐私接 auth-service（需双账号联调）
- regenerate-scene 端点
- 动态 plan 与 tool 事件 SSE
- 共鸣 createSpace 真 sceneDataUrl

---

### 9.8 给下个智能体的建议优先级

如果用户没有特别要求，建议按这个顺序：

1. **管理端大屏看板**（设计书 §3.4，目前完全空白）— 用户最容易新感知到的"产品完整度"
2. **真 Milvus embedding+检索**（替换 MilvusSearchTool 的关键词加权实现）— 让 RAG 更准
3. **历史 fragments 批量重建工具**（管理员入口）— 把残留旧 mock 数据一次性洗干净
4. **聊天系统 @AI**（设计书 §3.2.3）— 把 AI 球和 ChatView 联通
5. **intentHints 动态化** — 给 AI 球加"推荐问题"API
6. **LocalResourceWatcher 端到端验证 + 前端 WebSocket 推送**
7. **管理端 dashboard 多维度时间聚合**（DAILY/WEEKLY/MONTHLY/YEARLY）

---

最后一次更新：2026-05-27 · v2 by 第二代智能体（接续 WangHaoYi 项目）
