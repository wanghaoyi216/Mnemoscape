# Mnemoscape · Agent 工作记忆 (Memory.md)

> 给下一个接手这个项目的智能体看的"经验交接"。
> 详细的功能审计在同目录 `PROJECT-STATUS-AUDIT.md`，本文件聚焦**协作经验** + **关键陷阱** + **现场速查**。

---

## 1. 立刻要知道的 5 件事

1. **环境**：Windows + cmd shell（CRLF！），仓库根 `m:\Study\ProjectTest\Mnemoscape`，Git 远端是 GitHub HTTPS（已配置）
2. **AI 上游**：NVIDIA Integrate API + MiniMax M2.7（**纯文本，不支持多模态**）— 详 §6
3. **API key**：环境变量 `NVIDIA_API_KEY`（用户级），未设时 ai-service 抛 `MISSING_KEY` 走结构化降级
4. **GitHub token**：环境变量 `Mnemoscape_Github_Token`（用户级，**不要打印它的值**）— 详 §3
5. **整个 `/api/v1/reconstruct` 是 mock**（类名 `MockReconstructService`），整个 `resonance-service` 也是 mock

---

## 2. 项目结构速查

```
m:\Study\ProjectTest\Mnemoscape\
├── backend\                                  # Spring Boot 微服务（Java 17, Spring Cloud Alibaba）
│   ├── auth-service\         端口 8081       # JWT + 用户 + 好友
│   ├── memory-service\       端口 8082       # 记忆 CRUD + drift + versions + atlas
│   ├── ai-service\           端口 8083       # /chat (真) + /reconstruct (mock)
│   ├── asset-service\        端口 8084       # MinIO + 本地 resource/ 静态资源
│   ├── resonance-service\    端口 8085       # 共鸣大厅（mock）
│   ├── gateway\              端口 8080       # Spring Cloud Gateway
│   ├── common-utils\                         # ApiResponse / BizException 等共享
│   ├── pom.xml                               # parent POM，Spring AI 1.0.0-M4
│   ├── .env.workpc                           # 远端中间件指向 100.66.166.46（Tailscale）
│   └── mvnw / mvnw.cmd                       # Maven Wrapper
├── frontend\                                 # Vue 3 + TS + Vite + Pinia
│   ├── src\
│   │   ├── views\                            # 路由级页面（13 个）
│   │   ├── components\
│   │   │   ├── ai\AiMascotDock.vue           # ★ AI 球 + 对话面板（1500 行）
│   │   │   ├── common\LocationPicker.vue     # 我加的国家/省/市级联组件
│   │   │   ├── auth\LiquidMemoryBackground   # 登录背景 WebGL
│   │   │   └── resonance\NoteComposer.vue    # ★ 整组件无 i18n
│   │   ├── stores\                           # Pinia: auth/memory/atlas/resonance/scene
│   │   ├── composables\                      # 共享逻辑 + chinaRegions.ts(我加的)
│   │   ├── api\                              # axios client + 各 endpoint 封装
│   │   ├── i18n\locales\                     # zh-CN.json + en-US.json
│   │   └── router\                           # vue-router
│   ├── package.json                          # maplibre-gl 5.5 / deck.gl 9.3 / three 0.160
│   └── vite.config.ts                        # 代理 /api → backend gateway
├── docker\                                   # docker-compose + Dockerfiles
├── resource\                                 # 静态媒体资源（133MB，MinIO 启动时同步）
│   ├── audio\ gif\ music\ photo\ video\
├── scripts\                                  # PowerShell 启动脚本
├── openspec\                                 # 已废弃的 spec 设计文档
├── .kiro\                                    # 当前 IDE 配置（不要碰）
├── .gitignore                                # 排除 node_modules / target / dist
├── .env.example                              # 全部环境变量参考（NVIDIA_BASE_URL 不带 /v1）
├── HOW-TO-RUN.md                             # 启动顺序
├── Mnemoscape-Design-Document.md             # 整体设计文档
├── PROJECT-STATUS-AUDIT.md                   # 详细功能审计（同目录）
└── Memory.md                                 # 本文件
```

---

## 3. Git 操作（用户已通过此流程推送过一次）

### 3.1 GitHub 远端
- URL：`https://github.com/wanghaoyi216/Mnemoscape.git`
- 协议：HTTPS（不是 SSH，本机没配 SSH key）
- 认证：通过 `Mnemoscape_Github_Token` 用户级环境变量
- 用户信息：`WangHaoYi <wanghaoyi@home.hpu.edu.cn>`
- 主分支：`main`
- **仓库私有，不要脱敏**：用户明确说过 `.env.workpc` 等含密码文件可以提交

### 3.2 推送命令模板（不打印 token）
```powershell
$tok = [Environment]::GetEnvironmentVariable('Mnemoscape_Github_Token','User')
if (-not $tok) { Write-Error 'token env var not found in User scope'; exit 2 }
$pair = "x-access-token:$tok"
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$tok = $null; $pair = $null
$hdr = "AUTHORIZATION: Basic $b64"
$b64 = $null
git -c "http.extraHeader=$hdr" push origin main
$hdr = $null
```

**关键点**：
- 用 `git -c http.extraHeader=...` 而不是 `git config`，避免持久化到 git config
- 命令结束立即把变量置 null
- 永远不要 `Write-Output $env:Mnemoscape_Github_Token`

### 3.3 检查 token 是否存在（不暴露值）
```powershell
$user = [Environment]::GetEnvironmentVariable('Mnemoscape_Github_Token','User')
Write-Output ('User-level: ' + ([bool]$user))
```

### 3.4 .gitignore 重点
- 排除：`**/node_modules/`、`**/target/`、`frontend/dist/` `frontend/dist.tar.gz`、`.idea/`、`.vscode/`、`*.log`
- **不**排除：`.env*`（私有仓库）、`backend/.env.workpc`、`.kiro/`（用户主动维护）

---

## 4. 启动开发环境

### 4.1 后端（PowerShell）
```powershell
# 1. 加载远端中间件指向（Tailscale IP）
Get-Content .\backend\.env.workpc | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
  }
}

# 2. 启动单个服务（在 backend 目录运行）
.\mvnw -pl ai-service spring-boot:run
.\mvnw -pl memory-service spring-boot:run
# ...
```

### 4.2 前端
```powershell
cd frontend
npm install        # 首次
npm run dev        # http://localhost:5173
```

### 4.3 类型检查
```powershell
cd frontend
npx vue-tsc --noEmit -p tsconfig.app.json
```

### 4.4 已知中间件位置（Tailscale 100.66.166.46）
- MySQL `100.66.166.46:3306`
- Redis `100.66.166.46:6379`
- Nacos `100.66.166.46:8848`
- RabbitMQ `100.66.166.46:5672`
- Neo4j `100.66.166.46:7687`
- MinIO `100.66.166.46:9000`
- Milvus `100.66.166.46:19530`

---

## 5. AI 服务关键陷阱

### 5.1 ⚠️ NVIDIA base-url **不要**带 `/v1`
Spring AI 的 OpenAI 客户端会自动拼 `/v1/chat/completions`，base-url 带了 `/v1` 就变成 `/v1/v1/...` → 404。

正确：`spring.ai.openai.base-url=https://integrate.api.nvidia.com`

错误：`https://integrate.api.nvidia.com/v1`

### 5.2 ⚠️ MiniMax M2.7 不支持多模态
- 仅文本输入
- 来源：[NVIDIA NIM 文档](https://docs.api.nvidia.com/nim/reference/minimaxai-minimax-m2.7) + [apiyi 多模态分析](https://help.apiyi.com/en/minimax-m27-no-image-input-analysis-en.html)
- 要支持多模态，参考 `PROJECT-STATUS-AUDIT.md` §3 三种方案

### 5.3 ⚠️ Spring AI 1.0.0-M4 流式 API 不暴露 tool_start/tool_end
- ChatClient.stream() 当前只能拿到 token Flux
- 后端 `ChatController` 已经预留事件名，前端 reducer 已就绪
- 想要 ReAct UI，需要走 `advisors()` 链路或等 Spring AI 升级

### 5.4 ⚠️ 占位 key 检测
`AiUpstreamProperties.placeholderKeyPrefix = "nvapi-placeholder"`，命中即抛 `MISSING_KEY`。
**不要**误删这个保护，否则会把占位字符串当真 key 发到 NVIDIA。

### 5.5 后端意图分类与前端必须保持一致
- `ChatReasoner.classify()`（Java）和 `AiMascotDock.classifyIntent()`（TS）现在都用相同的保守规则：寒暄白名单 + 短句保底 + 收紧关键词
- 改动一边的话另一边也要同步

### 5.6 SSE 事件序列
```
event: meta        — { intent, plan?, traceId, requestId }
event: token       — "..."（多个）
event: done        — { ok: true, requestId }
出错时：
event: error       — { code: "AI_UPSTREAM_UNAVAILABLE", reason, detail, requestId }
```
前端 `streamFromBackend()` 在 `frontend/src/components/ai/AiMascotDock.vue` 中解析。

---

## 6. 前端关键陷阱

### 6.1 ⚠️ 响应式陷阱：`reactive()` vs 普通对象
```ts
// 错误：reply.text += token 不会触发 UI 更新
const created: ChatMessage = { id: shortId(), ...msg }

// 正确：
const created: ChatMessage = reactive<ChatMessage>({ id: shortId(), ...msg })
```
任何后续要修改其内部属性的对象，必须用 `reactive()` 包，不能依赖"塞进 reactive 数组"的浅响应。

### 6.2 ⚠️ MapLibre globe projection 放 style 内
```ts
// 不要：
new maplibregl.Map({ projection: { type: 'globe' } })

// 要：
const style = { ..., projection: { type: 'globe' } }
new maplibregl.Map({ style })
```
v5 起官方推荐方式。

### 6.3 ⚠️ MapLibre globe + maxBounds 不兼容
globe 投影下不要设 `maxBounds` / `renderWorldCopies: false`，会让地球被矩形 viewport 裁掉一半。

### 6.4 ⚠️ Three.js / MapLibre 容器初始化必加 ResizeObserver
- onMounted 同步执行时父容器可能还是 0×0
- `MemoryAtlasView.vue` 已经做了，可作模板
- `SceneViewer.vue` **还没做**，是 3D 空白的根因之一

### 6.5 ⚠️ 国内瓦片源
- 默认用高德地图 `webrd0[1-4].is.autonavi.com`
- Fallback 是 CartoDB + OSM
- 不要用 Mapbox 的（需要 token）

### 6.6 中英 i18n 复合 key 的回退技巧
```vue
<!-- 缺翻译时回退原值，避免界面空白 -->
{{ t(`memory.detail.fragmentTypes.${type}`, type) }}
```

### 6.7 axios baseURL = `/api/v1`
所有前端 API 调用都默认走 `/api/v1`，由 vite.config.ts 代理到 gateway。

---

## 7. 一般工作流程经验

### 7.1 上手新任务前先用 sub-agent 调查
对不熟悉的代码区域，用 `invoke_sub_agent name=context-gatherer` 让它跑一次广度调查，把相关文件 + 行号 + 关键片段先聚集到上下文里，省得用 grep 一遍遍试。

### 7.2 修改后必跑类型检查
```powershell
cd frontend
npx vue-tsc --noEmit -p tsconfig.app.json
```
**注意**：项目当前有约 4-7 个**历史遗留** TS 错误（不是当前任务引入的），区分清楚再修。

### 7.3 修改 Vue 文件后用 getDiagnostics
比 vue-tsc 快很多，只针对单文件：
```
getDiagnostics paths=["m:/.../File.vue"]
```

### 7.4 大文件读取策略
- `package.json` 之类小文件直接 `read_file`
- 大 Vue 组件用 `start_line` / `end_line` 分段读
- 不熟悉的 1000+ 行文件用 `readCode selector=...` 找符号

### 7.5 不要相信文件名
- `MockReconstructService` ≠ 测试文件（生产代码）
- `useThreeScene.ts` 是真的 Three.js（不是 mock）
- `LiquidMemoryBackground.vue` 是真的 WebGL shader

### 7.6 用户偏好（重要！）
1. **不需要脱敏**：仓库私有，DB 密码 / token / Tailscale IP 都可以提交
2. **直接动手**：可以直接改文件，不要每次都问"要不要"
3. **中文回复 + 解释清楚**：用户喜欢看到根因分析 + 代码片段
4. **不要写 .md 除非用户要求**：用户说过"只有用户要求才写 md"
5. **测试不主动加**：用户没要求测试就不要写 unit test
6. **遇到失败两次就换路子**：不要打补丁循环

---

## 8. 已发现但**未修复**的高优先级问题

下面这些是当前会话已经识别但还没动的，下个智能体可以直接接手：

### 8.1 AI 球内核仍偏紫（CSS）
- `AiMascotDock.vue` 1059-1206 行
- 修：`.ai-orb__core { opacity: 0.8 → 0.25 }` + `.ai-orb` box-shadow 减弱紫色 + `.ai-orb__core` 加 `mix-blend-mode: overlay`

### 8.2 MemoryDetailView 中英混排
- 行 169-176 + 196-203
- 修：补 i18n key 6 个 + 模板用 `t(\`memory.detail.fragmentTypes.${type}\`, type)`

### 8.3 SceneViewer HUD 9 处中英混排
- 行 113-122
- 修：i18n + ResizeObserver

### 8.4 NoteComposer 整组件无 i18n
- `frontend/src/components/resonance/NoteComposer.vue`
- 修：加 t() 调用 ~10 个

### 8.5 「寻找 Curator」3 处
- `zh-CN.json` 549/555/582
- 修：决定中文译名后批量替换

### 8.6 MemoryBuilderView 封面选择器
- 用户要求：独立对话框 + 上传图片入口
- 新建 `CoverPickerModal.vue`，三 tab：内置 / MinIO / 上传

### 8.7 SceneViewer 3D 空白
- `useThreeScene.ts` 加 ResizeObserver
- 参考 `MemoryAtlasView.vue` 的实现

### 8.8 AI 多模态附件入口
- 详 `PROJECT-STATUS-AUDIT.md` §3
- 推荐方案 C（混合模型）

---

## 9. 调试速查

### 9.1 AI 不回复
1. 检查 `NVIDIA_API_KEY` 是否设置
2. 检查 `application.yml` 的 `base-url` 不带 `/v1`
3. 看 `ai-service` 日志有无 `MISSING_KEY` 警告
4. F12 看 SSE 流是否真的有 `event: token` 数据

### 9.2 地图黑屏
1. F12 看瓦片 URL 是否能访问（国内网络高德可达）
2. 看 `console.error` 有无 MapLibre error
3. 检查 `.atlas-root` 容器是否拿到了高度（top:64px 假设 header 64px）
4. 6 秒兜底应该自动切 fallback style

### 9.3 流式不刷 UI
- 99% 是 `reactive()` 没包

### 9.4 类型错误暴增
- 大概率是改了 store 字段类型但没改 `frontend/src/types/`
- 注意 `AtlasLocation` 类型只有 `coords/name/source/accuracyMeters`，没有 `city/province/country`

---

## 10. 提示词 / 系统消息

### 10.1 ai-service 默认 system prompt
位置：`AiClientConfig.java:25-43`，定义"星空使者 / Echo Envoy"角色 + 5 条硬规则（防越权、防泄漏、防伪造记忆、强制工具调用、locale）。

### 10.2 不要修改这些注释
项目里很多注释解释了**为什么这样写**而不是**写了什么**，删了会丢历史。比如：
- `MockReconstructService.java` 头注释
- `EntityExtractor.java` 头注释
- `ChatReasoner.ensureRealKeyOrThrow` 的长警告
- `MemoryAtlasView.vue` 的 v5→v6→v7→v8 改进点说明

---

## 11. 用户的常用沟通词汇

为了快速理解用户：

| 用户说 | 意思 |
|---|---|
| "记忆星图" | MemoryAtlasView (时空记忆地图) |
| "记忆星空全景" | MemoryGraphView |
| "新建记忆" | MemoryBuilderView |
| "记忆漂移" | DriftCalculator + drift-panel |
| "聊天发现" | ChatView 的 discover tab |
| "灵魂共鸣大厅" | ResonanceHubView |
| "时空使者" / "星空使者" | AI 球（Echo Envoy） |
| "Mock" | 写死的假数据 |
| "TODO" | 未完成功能 |

---

## 12. 风险提示

- **不要删 `MockReconstructService`**：还有半个前端依赖它（Scene profile）。改名可以，删除会让 SceneViewer 全崩。
- **不要把 `.env.workpc` 加到 `.gitignore`**：用户明确要求保留
- **不要在 commit message 里加 emoji 或 bot 签名**：用户没要求过
- **不要在 git push 命令里直接拼 token 进 URL**：用 http.extraHeader 比较干净
- **不要改 `Mnemoscape-Design-Document.md` 和 `HOW-TO-RUN.md`**：是用户的主文档

---

## 13. 本次会话的关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| AI base-url 默认值 | `https://integrate.api.nvidia.com`（不带 /v1） | Spring AI 自动拼路径 |
| 地图主底图 | 高德地图 webrd | 国内可达 |
| 地图 fallback | CartoDB + OSM 多镜像 | 海外环境 |
| 地址输入 | 自建 LocationPicker，不引第三方 | 避免依赖膨胀 |
| 浏览器定位反查 | OpenStreetMap Nominatim 公共 API | 免费、无 key、中文支持 |
| GitHub 协议 | HTTPS + token | SSH key 未配 |
| 意图分类策略 | 寒暄白名单 + 短句保底 chat | 用户报告"你是谁?"误规划 |
| Plan 来源 | 全交后端 SSE meta | 避免前端硬编码 4 步 |
| AI 上游不可用 | 渲染明确错误卡 | 不能用本地模板冒充 AI |
| token 管理 | http.extraHeader 单次注入 | 不持久化到 git config |

---

最后一次更新：2026-05-26 · WangHaoYi 项目
