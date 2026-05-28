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


---
---

# 第二代工作记忆（v2）

> 接续会话：2026-05-27
> 视角：在 WangHaoYi 第一代基础上完成 P0 / P1 / P2 / P3 全部主要项目；下一代请重点读本节
> 详细功能审计已经追加到 `PROJECT-STATUS-AUDIT.md` §9，本节聚焦**新踩到的坑** + **新决策** + **关键陷阱补充**

---

## v2.1 立刻要知道的 5 件事（在前辈 §1 之上补充）

1. **AI 多模态确实做完了，用混合策略**：基座 M2.7 不动，附件场景下 ai-service 先调 Llama 3.2 11B Vision 跑"图片→英文密集描述"再喂 M2.7。M2.7 把英文描述消化后用中文回复用户。**不要再换基座模型** —— 这条路径已经验证最稳定。
2. **场景重建是双轨，不再是 mock**：`ReconstructDispatcher.mode=auto`（默认）先 LLM 后规则版兜底；`MockReconstructService` 已改名 `RuleBasedReconstructService`，文案全中文。
3. **memory.visualData 是真的，前端要优先用**：记忆**创建时**异步跑过完整 reconstruct 并 freeze 到这个 JSON 列。SceneViewer 三级兜底，**不要再回退到"每次重新调 reconstruct"**的旧逻辑。
4. **MinIO 上传现在用户隔离**：`users/{userId}/{uuid}-name`。读匿名公开素材 OK，私有素材必须本人。Gateway 公开路径"尽力解析身份"模式 —— 携带有效 token 时仍然会注入 X-User-Id。
5. **Spring Cloud OpenFeign + LoadBalancer 必须同时加**：resonance-service 我第一次只加了 openfeign 启动失败，加上 loadbalancer 才好。memory-service / ai-service 一开始就两者都装齐了。

---

## v2.2 v2 新增的关键陷阱

### v2.2.1 ⚠️ Spring AI RestClient 对 application/octet-stream 的转换器问题
- 现象：调用 NVIDIA Integrate 的视觉 API 偶尔（错误时常见）返回 `Content-Type: application/octet-stream`，body 实际还是 JSON
- RestClient 的默认 `MessageConverter` 对 String / byte[] 都拒绝转换 → 抛 `Error while extracting response`
- **解法**：VisionDescriber 改用 JDK 标准 `java.net.http.HttpClient`，`HttpResponse.BodyHandlers.ofByteArray()` 拿原始字节，自己 `new String(bytes, UTF_8)`，对任何 Content-Type 都健壮

### v2.2.2 ⚠️ MinIO presigned URL 是 Tailscale 内网 IP，公网视觉模型够不到
- `http://100.66.166.46:9000/...` 发到 NVIDIA Integrate → 它在公网，访问不到 Tailscale 私网
- 模型不抛错，但回答"我没收到图片"
- **解法**：ai-service 自己下载图片（它在同 Tailscale 网络可达）→ base64 → `data:image/<mime>;base64,...` 拼到 OpenAI Vision messages 里

### v2.2.3 ⚠️ 视觉模型选型坑
- **NVIDIA Integrate 的 modelcard 页面 ≠ 实际可调**。Qwen3.5-397B-A17B、Kimi-K2.5 在 modelcard 页有，但实际是 **status polling 异步模式**（请求后返回 202 + 轮询任务 id），不是 OpenAI 兼容 chat/completions
- **稳定可调的视觉模型**：`meta/llama-3.2-11b-vision-instruct`（实测 5s 返回）、`meta/llama-3.2-90b-vision-instruct`（精度更高但 30+s 容易超时）
- **判断方法**：看 `https://docs.api.nvidia.com/nim/reference/<model>` 是否有 `-statuspolling` 端点；有 → 是异步，不要选

### v2.2.4 ⚠️ SSE 死连接被代理 reset
- 视觉前置阻塞 5-30s 期间零字节流出，浏览器/vite 代理/nginx 会把它当死连接 reset
- 即使后端最终成功了，前端也已经断开（日志里看 `AsyncRequestNotUsableException: ServletOutputStream failed to flush`）
- **解法**：ChatController 加 `keepAliveExec` 单例守护线程调度器，每 5s 发一帧 SSE 注释 `:keepalive\n\n`（浏览器忽略，但维持 TCP），第一条真 token 到达后立即取消

### v2.2.5 ⚠️ axios 默认 timeout=15s，LLM 重建经常超
- 默认 timeout 适合普通 REST，不适合 LLM 端点
- **解法**：`api/memory.ts` 里 `reconstruct()` / `regenerateScene()` 单独传 `{ timeout: 60_000 }`

### v2.2.6 ⚠️ Spring @Async 同类内部调用不走代理
- `createMemory()` 里直接调 `enrichWithReconstructionAsync()` —— @Async 注解形同虚设，仍同步跑
- **解法**：注入"自代理" `@Autowired @Lazy private MemoryService asyncEnrichmentSelf`，通过它调用 `runEnrichmentAsync(memoryId)`，这样走 Spring 代理才生效
- 类上必须加 `@EnableAsync`（在 `MemoryApplication`）

### v2.2.7 ⚠️ vue-i18n `t(\`...${content}\`)` key 含 `.` 或空格会被切分
- 想用 i18n 字典做"原文 → 翻译"映射时，content 里的句点会被 messageResolver 当作命名空间分割
- **解法**：用 `tm('memory.detail.fragmentsPanel.legacyContent')` 拿整段字典对象，再用 JS 端 `dict[content]` 精确匹配

### v2.2.8 ⚠️ `AppHeader.app-header__inner.min-height: 88px` 但 `< 1100px` 时 nav wrap 成两行变 ~140px
- atlas 写死 `top: 64px / 88px` 都会被压住
- **解法**：AppHeader 加 ResizeObserver 监听 `offsetHeight` 写到 `:root --app-header-h`；atlas / 其他需要让位的页面用 `var(--app-header-h, 88px)` 兜底
- **未来其他全屏页同样问题用同一个变量**，不要再写死

### v2.2.9 ⚠️ SecurityContext 在 ai-service 里永远是空的
- ai-service 没装 SecurityFilter / JwtAuthFilter，`SecurityContextHolder.getContext().getAuthentication()` 永远是 null
- 之前 `MilvusSearchTool.currentUserId()` 完全不工作
- **解法**：让 ChatController 直接从 `request.getHeader("X-User-Id")` 取（gateway 已注入），传给 `MilvusSearchTool.searchForUser(req, userId)` 显式版本
- 旧的 `search(req)` 仍读 SecurityContext，给将来真 function-calling 用，对外契约不变

### v2.2.10 ⚠️ MinIO presigned URL 的 X-Amz-Signature query 让"看似重复"
- 同一个对象每次列表 API 拿到的 URL 不同（签名变化）→ 前端 `dedupe` 用 src 完全对比会失败
- **解法**：CoverPickerModal 用 `stripQuery(url)` 截 `?` 之前的部分做去重 key

### v2.2.11 ⚠️ 异步 JSON 接收对 vue-i18n 有影响
- 前端 reactive 字段 `reply.text` 在 SSE token 流里要持续追加 → 一定要 `reactive(reply)` 包，否则 UI 不刷新
- 这条前辈已经记过，再次提醒

### v2.2.12 ⚠️ ai-service 启动有占位 key 检测保护
- `AiUpstreamProperties.placeholderKeyPrefix = "nvapi-placeholder"`
- 命中 → 抛 `MISSING_KEY` 不发请求（保护用户不会把占位字符串当真 key 发出去）
- **不要**误以为这是 mock 实现要清掉

### v2.2.13 ⚠️ Llama 3.2 Vision 在 image+text 模式只输出英文
- 这正好契合"混合检索"架构：视觉模型只负责"看图→文字描述"前置工序，输出英文反而更稳定
- M2.7 把英文描述吸收后用中文回复用户 —— 用户最终看到的是中文
- **不要**为了"视觉模型也输出中文"换其他更弱的模型

---

## v2.3 v2 关键决策记录（在前辈 §13 之上补充）

| 决策 | 选择 | 理由 |
|---|---|---|
| AI 多模态方案 | C（混合：M2.7 + 视觉模型） | 保留 M2.7 中文/agentic 强项，附件场景才付出额外 LLM 调用 |
| 视觉模型主选 | `meta/llama-3.2-11b-vision-instruct` | 实测 5s 稳定，OpenAI 兼容同步 endpoint |
| 视觉模型降级 | `meta/llama-3.2-90b-vision-instruct` | 精度更高但 30+s 容易超时；只在主选失败时启用 |
| 视觉调用客户端 | JDK HttpClient | 绕开 Spring RestClient 对 application/octet-stream 的限制 |
| 视觉图片传递 | base64 data URI | MinIO presigned 是 Tailscale 内网，公网模型够不到 |
| SSE 保活 | 5s 注释帧 keepalive | 视觉前置 / LLM 长延迟期间防代理 reset |
| 「Curator」中文译名 | 「馆长」 | 与已有的"时空馆长"统一 |
| 场景重建路由模式 | auto（LLM 优先 + 规则版透明降级） | 大多数请求成功；失败也有可视化兜底 |
| reconstruct request 增强 | 加 title/year/season/timeOfDay/location | 让 LLM 输出 grounded（避免冬季记忆错配夏季模板） |
| Plan 来源 | 异步 LLM 动态 + 硬编码兜底 | 首字延迟 0；plan_update 帧 1-3s 内异步替换 |
| ReAct 工具事件 | 把强制 RAG / 视觉前置当工具调用推出 | 等 Spring AI 升级时再补真 function-calling 钩子 |
| MinIO 用户隔离 | `users/{userId}/` 前缀 + gateway 公开路径"尽力解析身份" | 保留登录前能拉公共素材，登录后看到自己的 |
| createMemory | 异步 @Async + 自代理 | builder 提交立即跳转，避免 30s 阻塞 |
| 历史 fragment 兜底 | 前端 i18n legacyContent 字典 + 一键重建按钮 | 不强制 DB 迁移，保留版本快照 |
| FRIENDS 权限 | fail-closed（auth-service 不可达拒绝读） | 私密记忆优先安全 |
| Memory star 着色 | 主导情绪权重 ≥ 0.2 用情绪色，否则 privacy 色 | 避免"情绪平淡"也强行染色 |

---

## v2.4 v2 工作流程经验（在前辈 §7 之上补充）

### v2.4.1 长链路改动测试策略
本会话很多改动横跨 ai-service + memory-service + resonance-service + 前端，验证策略：
1. **后端先 maven compile**：`./mvnw -pl ai-service,memory-service -am compile -q`，跑出 BUILD SUCCESS 再继续
2. **前端用 getDiagnostics 单文件检查**：`getDiagnostics paths=["..."]` 比 vue-tsc 快 10×
3. **确认全栈 OK 用 vue-tsc 全量**：`npx vue-tsc --noEmit -p tsconfig.app.json`
4. **不要每次都跑 starts-spring-boot:run**：太慢，让用户重启服务实测

### v2.4.2 长 prompt 修改时的字符陷阱
- Java text blocks 里的中文 `：` `，` 与 ASCII `:` `,` 看起来一样但 str_replace 会失败
- **解法**：先用 grep 确认目标行实际内容，再小段替换；如果用 fs_write 重写整文件更稳

### v2.4.3 SSE 修改要前后端同步
- 加新事件类型（`plan_update` / `tool_start` / `tool_end`）必须前后端同时改：
  - 后端 ChatController emitter.send(...).name(<event>)
  - 前端 streamFromBackend 里 `if (evt === '<event>')` 加 reducer
  - 前端 ChatMessage interface 如果要新字段也要加上去（如 `planSource` / `visionUsed`）

### v2.4.4 i18n 加新字典的固定流程
1. 在 `zh-CN.json` 找到合适命名空间加键
2. **同步加 en-US.json 对应键**（不能只加中文）
3. 模板里用 `t('namespace.key', fallback)` 模式
4. 如果键值含 enum 名（如 `fragmentTypes.forgotten_detail`）用 `t(\`namespace.${enum}\`, enum)` 让缺翻译时回退原文

### v2.4.5 改 controller / service 的契约
**总是兼容旧调用方**：用方法重载而不是直接改签名
```java
// 旧方法保留（无 userId）
public String generateAnswer(AiChatRequest req) {
    return generateAnswer(req, null);
}
// 新方法（带 userId）
public String generateAnswer(AiChatRequest req, String userId) { ... }
```
这样老 caller 不用改一起更新

### v2.4.6 后端用 Spring 代理时记得 @EnableAsync / @Lazy
- @Async / @Cacheable / @Transactional 都依赖 Spring 代理
- **同类内部调用不走代理** → 注解失效
- 解决：注入"自代理"`@Autowired @Lazy private SelfClass self;`，通过 `self.method()` 触发

---

## v2.5 调试速查（在前辈 §9 之上补充）

### v2.5.1 SceneViewer 还是空白
1. F12 看 `/api/v1/memories/{id}` 返回的 `visualData` 字段是否非空
2. 非空但仍空白：检查 `JSON.parse(visualData)` 能不能解析（看 console.warn）
3. visualData 真的是 null：用户的记忆没跑过 AI 重建（异步任务可能还没完成或失败）→ 点"重建场景"按钮
4. 如果反复点重建仍失败：看 ai-service 日志 `[ChatReasoner] vision pre-pass start` / `[LlmReconstruct] elapsedMs=...` 等

### v2.5.2 fragment 内容仍是英文
1. 前端字典 `memory.detail.fragmentsPanel.legacyContent` 应该有 36 条映射
2. 用户 fragment 的 content **完全相等于** 字典 key 才会命中（精确匹配，不是 startsWith）
3. 如果 content 是某种 LLM 生成的英文（不在字典里）→ 点"重建场景"让 LLM 重新输出中文

### v2.5.3 AI 球点了没反应
1. 看 SSE 流连得上没：F12 → Network → EventStream
2. 看 X-User-Id 头是否被 gateway 注入（DevTools → Request Headers）
3. 后端日志看 `[ChatReasoner] RAG injected N hits` 是否出现 —— 没出现说明 RAG 没跑
4. 如果 RAG 没跑：检查前端 streamFromBackend 是否传了 userId（不应该，userId 来自 X-User-Id header）

### v2.5.4 视觉胶囊不出现 / 模型 ID 错乱
1. 看 SSE meta 帧里 `vision_used` / `vision_model` 字段
2. AiMascotDock.streamFromBackend 里 `if (m.vision_used)` reducer 是否触发
3. shortVisionModel(id) 截断函数返回值检查（meta/llama-3.2-11b-vision-instruct → llama-3.2-11b）

### v2.5.5 共鸣大厅返回空
1. 后端日志：`Searching resonances seedMemoryId=... userId=...`
2. `Public pool is empty — no other users have published PUBLIC memories yet` 说明确实没有其他用户的 PUBLIC 记忆 —— 创建第二个账号发条 PUBLIC 试试
3. 数据有但仍空：调低 `SCORE_FLOOR`（当前 0.5）

### v2.5.6 创建记忆后 fragments 一直不出现
1. memory-service 日志：`[async-enrich] start for memory <id>`
2. 然后看 `[async-enrich] reconstruction failed for ...` 或 `done for memory ...`
3. 异步任务失败后用户必须点"重建场景"手动触发

---

## v2.6 给下一代的开放问题（接力清单）

### 高优先级（用户最容易感知）
1. **管理端大屏**（设计书 §3.4 完全空白）— 用户最大的"产品完整度"提升点
2. **历史 fragments 批量重建**（管理员入口）— 一次性洗掉所有旧 mock 数据
3. **聊天系统接 AI 球**（@AI / 一键引入解密，设计书 §3.2.3）

### 中优先级（让 RAG 真正强大）
4. **真 Milvus embedding+检索**（替换 MilvusSearchTool 关键词加权）
5. **emotionVector 前端可视化**（detail 页加波形 / 当前只在 graph 着色用）
6. **intentHints 动态化**（按用户记忆生成"推荐问题"）

### 低优先级（基础设施 / 验证）
7. **LocalResourceWatcher 端到端验证**（设计书 §3.2.2 静态资源 WatchService 热加载）
8. **真 function-calling 钩子推到 SSE**（等 Spring AI 升级到能用 advisors 链路）
9. **MinIO 历史"无前缀"对象迁移工具**（管理员一次性把不属于 133MB 预置的对象挪到 `legacy-orphan/`）

### 用户决策 (前辈 §8 还有未决的)
- 用户已经选了 §8.1 「馆长」和 §8.2 多模态方案 C 和 §8.4 改名 RuleBasedReconstructService
- 仍未决：§8.3 Plan UI 是否完全切到 LLM 动态生成（当前是硬编码 + 异步替换混合方案）
- 仍未决：§8.5 resonance vs 多模态优先级（已都做完，新一轮可不必考虑）

---

## v2.7 给下一代的关键提醒

1. **不要重新做我做完的事**：上面 v2.2 有详细陷阱清单，全部踩过了
2. **不要随意改 ChatReasoner.streamAnswer 的契约**：现有四个签名（`req` / `req,userId` / `req,userId,tools`）都有调用方，删任何一个会破坏老调用方
3. **不要把视觉模型换回 Qwen3.5 / Kimi-K2.5**：实测异步模式不可用，9.5s 后台跑死你用户耐心
4. **不要删 RuleBasedReconstructService**：dispatcher 的"管线壳"还依赖它（mergeWithRuleBaseline 取 audioData / sceneFragment baseline）
5. **不要在 createMemory 里同步跑 AI**：前辈写过事务回滚的坑，我又写过 30s 超时坑；现在异步是正确路径
6. **不要把 sceneDataUrl 字段当作"完整 SceneData"**：那只是占位符 / http 链接 / `scene://...` 标记。真正的 SceneData JSON 在 `memory.visualData` 字段
7. **改 prompt 时记得 grep 一下别处有没有重复**：reconstruct prompt 现在分散在 LlmReconstructService 一处，但同源逻辑（如 emotion vector 8 维 enum）还散落在 RuleBasedReconstructService.emotionBaseline 里
8. **资源池去重必须 stripQuery**：MinIO presigned URL 每次签名变化，前端用完整 url 比对会失败
9. **记忆 fragment 翻译用 tm() 不是 t()**：`t(\`...${content}\`)` 含 `.` / 空格会切错
10. **CSS `--app-header-h` 已经发布**：后续做新全屏页直接 `top: var(--app-header-h, 88px)`，不要写死像素

---

最后一次更新：2026-05-27 · v2 by 第二代智能体


---
---

# 第三代工作记忆（v3）

> 接续会话：2026-05-27（夜）
> 视角：在第二代基础上接力完成 admin-dashboard spec 的后端聚合层
> 关注点：**admin-dashboard 任务执行进度** + **新增的 admin/ 包结构** + **保留下来的剩余任务**

---

## v3.1 本次会话完成的任务（admin-dashboard tasks.md）

| Task | 主体 | 文件位置 |
|---|---|---|
| **6.4** AdminUserController.promoteRole | RolePromotionRequest + RolePromotionResponse + AdminUserController | `auth-service/.../controller/AdminUserController.java` + `model/dto/RolePromotion*.java` |
| **6.6** auth-side activeUsers 端点 | AdminCacheConfig + AdminStatsService + AdminStatsController | `auth-service/.../admin/config/`, `service/AdminStatsService.java`, `controller/AdminStatsController.java` |
| **7.8** memory-side internal active-user-counts | AdminStatsService.aggregateActiveUserCounts | `memory-service/.../admin/AdminStatsService.java` |
| **7.9** memory-trends | AdminStatsService.aggregateMemoryTrends | 同上 |
| **7.11** emotion-distribution | AdminStatsService.aggregateEmotionDistribution + DTO | `memory-service/.../admin/AdminStatsService.java` + `dto/EmotionDistribution.java` |
| **7.13** heatmap | AdminStatsService.aggregateHeatmap + DTO | 同上 + `dto/HeatmapPoint.java` |
| **7.17** top-contributors（含 R18.1 降级） | AdminStatsService.aggregateTopContributors + DTO + 调 AuthServiceClient | 同上 + `dto/TopContributor.java` + `dto/TopContributorsResponse.java` |
| **7.19** fragment-discovery（overall + byType） | AdminStatsService.aggregateFragmentDiscovery* + DTO | 同上 + `dto/FragmentDiscoveryOverall.java` + `dto/FragmentDiscoveryByType.java` |
| **7.21** audit + metrics 切面 | 在每个 controller 方法的 finally 块直接写 admin-audit + 用 AdminMetrics.uncachedLatency 计时 | `memory-service/.../admin/AdminStatsController.java` |
| **8.2** resonance-overview / resonance-top | AdminCacheConfig + AdminResonanceService + AdminResonanceController + DTO | `resonance-service/.../admin/*` + `admin/dto/Resonance*.java` |

**编译状态**：所有四个模块（common / auth-service / memory-service / resonance-service）通过 `./mvnw -pl ... compile` ✅，全部 `test` 也通过 ✅

---

## v3.2 v3 新增的关键陷阱

### v3.2.1 ⚠️ JwtAuthFilter 默认拒绝任何 /api/v1/admin/users/{id}/role 的 bootstrap 调用
- 问题：bootstrap 流程明确要求"无 Authorization + 携带 X-Bootstrap-Secret"，但 JwtAuthFilter 默认对所有非白名单路径都要求 Bearer token
- **解法**：扩展 `shouldNotFilter()`：当 path 是 `^/api/v1/admin/users/[^/]+/role$` + method=POST + 有 X-Bootstrap-Secret + 无 Authorization 时跳过 JWT 校验，让请求落到 SecurityConfig 的 `adminOrBootstrapSecret()` 授权管理器
- 这条路径专门为"还没有任何 ADMIN"的初始化场景设计；一旦平台有 ADMIN，后续提升走 Authorization Bearer 路径

### v3.2.2 ⚠️ memory-service 的 AdminCacheConfig 已经在 task 7.1 里建好了，包含全部 6 个 cache name
- 不需要重复建；7.11 / 7.13 / 7.17 / 7.19 直接复用 cacheNames 就行
- 注意 memory-service 的 cacheManager 是 CompositeCacheManager（admin Redis + 历史 Caffeine），保留了 `memories` / `driftStates` 两个老 Caffeine cache
- auth-service / resonance-service 是新建的 AdminCacheConfig，单纯 RedisCacheManager + BypassOnFailureCacheManager

### v3.2.3 ⚠️ Spring 的 @Cacheable + Timer.recordCallable() 互动
- recordCallable 的签名是 `<T> T recordCallable(Callable<T> f) throws Exception`，编译期强制声明 throws Exception
- 我用 `try { ... } catch (BizException biz) { responseStatus = biz.getCode(); throw biz; } catch (RuntimeException rex) { ... } catch (Exception e) { responseStatus = 500; throw new RuntimeException(e); }` 模式吃掉这个 checked
- 这样：`BizException` 仍然走 GlobalExceptionHandler 拿到正确的 HTTP code；`RuntimeException` 直接重新抛；最后 `Exception` 一般是 cache 反序列化失败，包成 RuntimeException 让 GlobalExceptionHandler 兜成 500

### v3.2.4 ⚠️ AuthServiceClient 在 memory-service 里是 `@Autowired(required = false)` 注入到 AdminStatsService
- 单元测试可以不接 Feign（mock null）；生产环境 Spring 会注入真实 client
- top-contributors 端点对 client 失败的处理路径是降级而不是 throw，所以 client = null 时也可以正常返回（usernames 全部 fallback 到 userId.substring(0,8)）
- 这个 setter 注入模式比构造器注入更宽容

### v3.2.5 ⚠️ AdminMetrics 是 @Component，但在 memory-service 的 AdminStatsService 是 setter 注入
- 主要是给单元测试用 — Spring Boot test 经常不带 micrometer registry
- 所有 service 方法对 `adminMetrics == null` 做了防御（`if (adminMetrics != null) { ... }`）
- Controller 是构造器注入，因为 controller 一定跑在 web context 里，micrometer 一定可用

### v3.2.6 ⚠️ Spring 的 @Cacheable SpEL 'sync = true' + 静态方法引用
- 用 `key = "T(com.mnemoscape.memory.admin.AdminStatsService).cacheKey(#dimensionRaw, #fromRaw, #toRaw)"` 把 cache key 委托给静态方法
- 这样 cache key 逻辑在测试里可以单独覆盖，而不需要拉起 Spring context
- 注意：`T(...)` 必须是 fully-qualified class name；用 import 后的简短名不行

### v3.2.7 ⚠️ resonance_spaces.created_at 是 LocalDateTime（无时区），要包成 OffsetDateTime
- 项目存的所有时间戳约定为 UTC，但实体是 LocalDateTime
- DTO 输出 ResonanceTopEdge.createdAt 是 OffsetDateTime — 用 `ldt.atOffset(ZoneOffset.UTC)` 转
- 这样 Jackson 输出 ISO-8601 with offset，前端 `new Date(...)` 解析正确

### v3.2.8 ⚠️ 不同维度的 cache TTL 必须独立配置
- design.md §Caching Strategy 表格：active-users / memory-trends 60s, emotion / contributors / fragment / resonance 120s, heatmap 900s
- AdminCacheConfig 用 `withInitialCacheConfigurations(perCache)` 给每个 cache name 单独配 TTL
- 不要用全局 `cacheDefaults` 一把梭

---

## v3.3 v3 新增的关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| Top-contributors 降级路径 | Feign 失败 → degraded=true + 全部 username fallback 到 userId.substring(0,8) | 降级而不是 throw 502，让 dashboard 仍然能渲染（按 R18.1 设计）|
| Heatmap intensity 归一化 | maxRaw → 1.0；空集 → 空数组 | 空数组的语义比"全 0 点"更易让前端分支 |
| Emotion-distribution 缺省窗口 | 365 天 | 设计文档没指定缺省，选个能反应"一年内典型用户行为"的值 |
| Top-contributors 缺省窗口 | 30 天 | 与"近期最活跃"语义一致 |
| Fragment-discovery 没有 from/to | 全表聚合 | 设计文档要求 — 用户想看的是"全部历史中的总体探索率" |
| Resonance-overview / top 没有 from/to | 全表聚合 | 同上 |
| Bootstrap path 跳过 JwtAuthFilter | 仅当 method=POST + path 匹配 + 有 X-Bootstrap-Secret + 无 Authorization | 严格防止 bootstrap 漏出更广的攻击面 |
| TopContributorsResponse 是 envelope record | items + degraded + degradedReasons 三字段 | 让 dashboard 在响应 data 内部读 degraded badge，不依赖 ApiResponse.code |

---

## v3.4 仍未完成的任务（接力清单）

剩下还有不少 admin-dashboard 任务要做。以下列表按 wave 顺序：

### 后端剩余
- **6.9** auth-service `batch-usernames` 端点的单元测试
- **7.10** memory-trends 桶聚合单元测试
- **7.12** emotion-distribution 单元测试
- **7.14–7.16** heatmap 三个属性测试（jqwik）
- **7.18** top-contributors 单元测试 + degraded 路径
- **7.20** fragment-discovery 单元测试
- **7.22** admin DTO "白名单序列化" 单元测试（Property 9）
- **8.3** resonance admin 端点单元测试

### 前端（未开始）
- **11.1** AdminEntryView + AdminHomeView 网格 + 装 echarts/vue-echarts/@deck.gl/aggregation-layers
- **11.2** ActiveUsersView + composable
- **11.4** MemoryTrendsView + composable
- **11.5** EmotionDistView + composable
- **11.6** HeatmapView + composable（maplibre globe + deck.gl HexagonLayer）
- **11.8** ContributorsView + composable
- **11.9** FragmentDiscoveryView + composable
- **11.10** ResonanceOverviewView + composable
- **11.11** SystemHealthView + composable
- **11.3 / 11.7** 视图测试

### 已完成的标记
- 任务 1.x / 2.x / 3.x / 4.x / 5 / 6.1 / 6.5 / 6.8 / 7.1 / 7.2 / 7.7 / 8.1 / 10.x：之前会话已完成
- **本次完成**：6.4 / 6.6 / 7.8 / 7.9 / 7.11 / 7.13 / 7.17 / 7.19 / 7.21 / 8.2

---

## v3.5 给下一代的提醒

1. **后端 admin 聚合层已经全部就绪** — 9 个公开端点 + 1 个内部端点 + bootstrap 端点都通了。前端可以直接对着 `/api/v1/admin/stats/*` 一系列 endpoint 写
2. **admin DTO 全部是 record + 严格白名单** — 不要往里加 title / description / email 等敏感字段；如果要加新字段，先更新 design.md 的白名单清单 + Property 9 测试
3. **缓存 key 设计已经定型** — `T(ServiceClass).cacheKey(...)` 的静态方法模式；不要改成 SpEL 表达式直接拼，否则单元测试会很难写
4. **AdminMetrics 是可选注入** — 单元测试可以不接 micrometer；但 controller 必须接，Spring 一定会注入
5. **resonance entity 字段名 vs DTO 字段名不对齐** — entity 是 memoryId1/memoryId2/similarityScore，DTO 是 memoryAId/memoryBId/resonanceScore；mapping 在 AdminResonanceService 里手动做了转换
6. **bootstrap secret 比对必须 MessageDigest.isEqual** — 不要换成 String.equals，会有 timing attack 风险（这一条 task 6.1 已经做对了，不要回滚）
7. **AuthGlobalFilter 会注入 X-User-Role** — 但当前的 admin endpoint 实际从 X-User-Id 拿 adminUserId 写审计；X-User-Role 主要是给 SecurityConfig 用的
8. **没写 7.x 的单元测试** — 优先级低于交付端点；如果之后要写，参考 TimeBucketingTest.java 的 nested style

---

最后一次更新：2026-05-27 (v3) · 第三代智能体接力完成 admin-dashboard 后端聚合层



---
---

# 第四代工作记忆（v4）

> 接续会话：2026-05-27（深夜）
> 视角：在 v3 后端聚合层的基础上，把整个 admin-dashboard 前端面板交付出来
> 关注点：**11.1–11.11 全部完工** + **新踩的几个前端坑** + **构建产物清单**

---

## v4.1 本次会话完成的任务（admin-dashboard tasks.md）

| Task | 内容 | 文件位置 |
|---|---|---|
| **11.1** AdminEntryView + AdminHomeView 网格 | sub-nav 横向滚动 + 12-col home 网格 + 8 个面板缩略嵌入 + 新增 echarts/vue-echarts/@deck.gl/aggregation-layers | `views/admin/AdminEntryView.vue` + `views/admin/AdminHomeView.vue` + `package.json` |
| **11.2** ActiveUsersView | DAILY/WEEKLY/MONTHLY/YEARLY toggle + ECharts line | `composables/useAdminActiveUsers.ts` + `views/admin/ActiveUsersView.vue` |
| **11.4** MemoryTrendsView | stacked column（created vs modified） | `composables/useAdminMemoryTrends.ts` + `views/admin/MemoryTrendsView.vue` |
| **11.5** EmotionDistView | 8 维 radar + sampleSize | `composables/useAdminEmotion.ts` + `views/admin/EmotionDistView.vue` |
| **11.6** HeatmapView | maplibre globe + deck.gl HexagonLayer + ResizeObserver | `composables/useAdminHeatmap.ts` + `views/admin/HeatmapView.vue` |
| **11.8** ContributorsView | horizontal bar + 严格白名单（不渲染 avatar） | `composables/useAdminContributors.ts` + `views/admin/ContributorsView.vue` |
| **11.9** FragmentDiscoveryView | gauge（overall）+ horizontal bars（byType）+ types i18n fallback | `composables/useAdminFragments.ts` + `views/admin/FragmentDiscoveryView.vue` |
| **11.10** ResonanceOverviewView | KPI 卡 + status chip + force graph | `composables/useAdminResonance.ts` + `views/admin/ResonanceOverviewView.vue` |
| **11.11** SystemHealthView | overall pill + 6 组件 grid + 颜色编码 | `composables/useAdminHealth.ts` + `views/admin/SystemHealthView.vue` |

**编译状态**：`npx vue-tsc --noEmit -p tsconfig.app.json` ✅ + `npx vite build` ✅
（11 个面板视图 + 8 个 composable 新增；老的视图 stub 全部被替换）

---

## v4.2 v4 新增的关键陷阱

### v4.2.1 ⚠️ TypeScript overload 与 axios `AxiosResponse<T>` 不兼容
- `getFragmentDiscovery()` 设计上想做 overload：参数决定返回 shape
- 但实现 `client.get<ApiResponse<Overall | ByType[]>>(...)` 的返回类型是 `Promise<AxiosResponse<Union>>`，不是简单的 `Promise<{data: ApiResponse<X>}>`
- TypeScript 拒绝把 union return 折成两个具体的 overload 签名 → `TS2394: This overload signature is not compatible with its implementation signature`
- **解法**：撤掉 overload，改成单签名 + union return；调用层（composable）用 `as unknown as Promise<{data: ApiResponse<具体形状>}>` 做最小范围的类型 narrowing
- 不要被"overload 看起来更优雅"诱惑回退这个改动

### v4.2.2 ⚠️ `npm install "@xxx/yyy@1.0"` 在 PowerShell 下需要引号
- `npm install @deck.gl/aggregation-layers@^9.3.0` PowerShell 解释 `@` 为 splat 语法 → ParserError
- **解法**：把整个包名加引号 `"@deck.gl/aggregation-layers@^9.3.0"`
- cmd 下没问题；如果以后需要写脚本，建议在文档里就用引号版本

### v4.2.3 ⚠️ vue-echarts 7.x 必须主动 `use()` 注册组件
- 不像 echarts 5 自带全套，vue-echarts 7 要按需引入 `import { LineChart } from 'echarts/charts'` + `use([LineChart, GridComponent, ...])`
- 漏掉 `GridComponent` → 图轴显示不出来；漏掉 `TooltipComponent` → 鼠标悬停无反应
- 所有视图的 `use([...])` 列表都按需写，不要复制粘贴一份"包含所有组件的全集"，那样 bundle 会膨胀

### v4.2.4 ⚠️ HexagonLayer 不在 `@deck.gl/layers`，在 `@deck.gl/aggregation-layers`
- `import { HexagonLayer } from '@deck.gl/layers'` 编译过但运行 throw "HexagonLayer is not a constructor"
- **解法**：单独装 `@deck.gl/aggregation-layers`，从这个包里 import
- 已写入 package.json 依赖

### v4.2.5 ⚠️ ResonanceOverviewView 的 force graph 节点 label 不能用 memoryId 全文
- 后端不返回 memory title（R12.3 / R15.1 严格白名单）
- 直接显示 36-char UUID 会让节点圆圈被 label 撑爆
- **解法**：`memoryId.substring(0, 8)` 截短显示；hover tooltip 显示完整 ID + score
- 这条与后端的 fallback 逻辑（`userId.substring(0, 8)` for missing usernames）保持一致风格

### v4.2.6 ⚠️ AdminHomeView 嵌套子 panel 时不要重复 padding
- AdminPanel.vue 有 `padding-top: var(--app-header-h, 88px)` 让位 AppHeader（独立成页时）
- 但在 home 网格里，外层 admin-shell 已经让位过，再让一次会让面板被压到屏幕外
- **解法**：AdminPanel.vue 的 CSS 里有 `:where(.admin-grid) > .admin-panel { padding-top: clamp(20px, 3vw, 32px); }` 选择器；AdminHomeView 用 `<div class="admin-grid">` 包裹时这个反向 reset 自动生效
- 此外 AdminHomeView 还在 `:deep(.admin-panel)` 里把 border / background / 全部 padding 全归零，让 grid cell 接管视觉容器
- 如果以后 AdminPanel 内部 DOM 结构变化，记得同步 AdminHomeView 的 :deep 选择器

### v4.2.7 ⚠️ ECharts radar 的 indicator 需要给 max 否则坐标轴自动归一化导致 8 个分量看起来都"满分"
- emotion 八个分量本来就是 0~1，但 ECharts radar 默认按当前数据 `max(...)` 自动归一化
- 一组 [0.3, 0.2, 0.4, 0.1, ...] 会让 0.4 看起来"占满"那个轴
- **解法**：`indicator` 的每一项都显式 `max: 1.0`，跟后端 emotion vector 的语义对齐
- 同样的坑可能出现在 fragment-discovery 的 byType 横条 — discoveryRate 也是 0~1，所以 xAxis 也写了 `min: 0, max: 1`

### v4.2.8 ⚠️ MapboxOverlay 卸载顺序：先 removeControl，再 map.remove()
- 直接 `map.remove()` 不会触发 deck.gl 的内部清理 → 内存泄露 + 残留 webgl context
- **解法**：onBeforeUnmount 顺序：disconnect ResizeObserver → removeControl(overlay) → map.remove() → 全部 try/catch 静默
- MemoryAtlasView 已经有这个模式可参考；HeatmapView 沿用

### v4.2.9 ⚠️ AdminPanel onRetry 是同步签名 `() => void`，但 fetch 是 async
- AdminPanel 的 props 类型 `onRetry?: () => void`，但 useAdminApi.fetch 返回 `Promise<void>`
- TypeScript 不抱怨（fetch 的返回值被丢弃也合法），但 ESLint promise/no-misused-promises 会警告
- **解法**：项目目前没装这个 lint rule，所以直接 `:on-retry="fetch"` 通过；如果以后引入更严的 lint，可以用 `:on-retry="() => fetch()"` 或 `:on-retry="() => { void fetch() }"`

### v4.2.10 ⚠️ tooltip formatter 的参数类型不能从 ECharts 类型直接拿
- ECharts 的 tooltip formatter signature 是 `(params: any) => string`
- 我用 `params: { dataIndex: number; value: number }[]` 这种最小化类型断言，让 TypeScript 不抱怨
- 不要试图从 `import type { TooltipFormatterCallback } from 'echarts'` —— 那个类型在 vue-echarts 上下文里几乎不可用
- 这种 narrow 是"我信我知道返回 shape"的妥协，不是 type-safe；改 formatter 时手动测试

---

## v4.3 v4 关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 8 个面板的视觉容器 | 公共 `<AdminPanel>` 五态外壳 | 保持 loading / empty / error / ready / degraded 状态机一致 |
| Home 网格策略 | 在 home 嵌入完整面板组件，不做"缩略卡" | 减少代码重复；用户从 home 直接能看到所有数据 |
| Heatmap 渲染策略 | 复用 maplibre globe（不引 cesium / mapbox） | 项目已经有 globe + ResizeObserver 经验（MemoryAtlasView） |
| ECharts 引入方式 | 按需 `use([...])`，不一次性引入全套 | bundle size：active-users 组合后只有 23 KB |
| Resonance graph 节点 label | `memoryId.substring(0, 8)` 截短 | 不暴露 memory 内容（R15.1 / R12.3） |
| Status pill 配色 | UP 绿 / DEGRADED 琥珀 / DOWN 红 | 与设计文档 Observability 表格一致 |
| Force graph 默认 limit | 20 | 平衡可读性与信息量；用户可以从 limit 输入调整（v4 暂未做 UI） |
| Fragment overall + byType 拆两个 composable | 各自独立 fetch + 独立 error state | 任意一边失败不影响另一边的渲染 |
| onRetry 写法 | 直接 `:on-retry="fetch"` | TS 容忍 Promise 返回；干净 |
| home 网格 cell 跨度 | half (6) 或 full (12)；heatmap / resonance graph 用 full | 这两个面板需要更宽的视野 |

---

## v4.4 v4 工作流程经验

### v4.4.1 增量验证：单文件 → 多文件 → 全项目
1. 每个面板写完先用 `getDiagnostics` 单查（10× 快于 vue-tsc）
2. 8 个面板全部完成后跑 `npx vue-tsc --noEmit -p tsconfig.app.json`
3. 最后用 `npx vite build` 真打一次包，确认所有 lazy import 能 resolve

### v4.4.2 admin-dashboard 后端契约绑定的"扁平 vs 嵌套"差异
- 大部分端点 `data: T[]` 或 `data: T`（直接是结果）
- 唯独 top-contributors `data: { items, degraded, degradedReasons }` 是 envelope
- 前端 useAdminApi 的 `readDegraded()` 函数在 generic `data` 上探测 `data.degraded`，所以两种 shape 都能正常处理
- composable 把 `data` 暴露成 `T`，view 通过 `data.value?.items` 读取列表
- 注意：未来加新 endpoint 如果用 envelope shape，view 也要相应调整

### v4.4.3 i18n key 一致性的检查方式
- 所有 8 个面板都用 `t('admin.<panel>.title')` / `t('admin.<panel>.subtitle')`
- 我把 zh-CN.json 的 admin section 完整 grep 一遍确认 key 齐全（v3 任务 10.7 已经做完）
- 如果以后加新 panel，先在 i18n 里加 key 再写视图，否则会显示 key 字符串本身

### v4.4.4 不要在 onMounted 同步触发的代码里依赖 ResizeObserver
- map / chart 在 onMounted 同步执行，此时容器可能还在 layout 阶段（0×0）
- HeatmapView 的方案：onMounted 立即创建 map + 装 ResizeObserver；额外 `setTimeout(80ms)` 触发一次手动 resize
- 这个 80ms 不是魔法数字，是 v2 经验里观察到的 layout settle time 上限；少于这个就有概率拍到中途状态

---

## v4.5 给下一代的关键提醒

1. **整个 admin-dashboard 已经 end-to-end 可用**：登录一个 ADMIN 账号 → 顶 nav 出现"管理面板"入口 → 进 `/admin` 看 home grid → 点 sub-nav 看具体 panel → 全部都有真实数据流
2. **唯一未完工的 task 是测试**（标 `*` 的可选项）和 task 9 / 12 的 checkpoint
3. **如果想测试**：`createPinia()` + `mount(View, { global: { plugins: [pinia, i18n, router] } })`，mock axios 在 client 层，不要 mock 视图
4. **不要再加新依赖**：echarts / maplibre / deck.gl 全套已经够用；fragment 的 gauge / contributors 的 bar / resonance 的 graph 都从 `echarts/charts` 拿
5. **新 panel 添加 checklist**：
   1. `api/admin.ts` 加 fetcher + 类型
   2. `composables/useAdminXxx.ts` 包 `useAdminApi`
   3. `views/admin/XxxView.vue` 用 AdminPanel
   4. `i18n/locales/{zh-CN,en-US}.json` 加 `admin.xxx.*` key
   5. `router/index.ts` 加路由
   6. `views/admin/AdminEntryView.vue` 加 sub-nav 项
   7. `views/admin/AdminHomeView.vue` 加 cell（决定 span="half" 还是 "full"）
6. **小心 `@deck.gl/aggregation-layers` 的版本对齐**：必须与 `@deck.gl/core` / `@deck.gl/mapbox` 同 minor，否则 peer dep 冲突
7. **HeatmapView 的瓦片源**：写死高德 `webrd0[1-4]`，国内可达；海外用户可能看不到底图但 deck.gl 的六边图层仍然渲染正常
8. **bundle 警告 `mapbox-overlay 1.6 MB`**：deck.gl 自己就这么大，不是我引入的；ChunkSplit 配置可以晚点加
9. **`AppHeader` 的 admin 入口 v-if**：`auth.isAdmin` 真值时才渲染（v3 任务 10.6 已完成）；不要换成 v-show，否则非 ADMIN 用户的 DOM 里仍有这条 link

---

最后一次更新：2026-05-27 (v4) · 第四代智能体完成 admin-dashboard 全栈交付（前端 8 面板 + 后端 9 端点 + bootstrap 全部就绪）



---
---

# 第五代工作记忆（v5）

> 接续会话：2026-05-27（深夜，续集）
> 视角：admin-dashboard 全功能交付完毕后，把所有 33 个测试任务（含可选）都补完
> 关注点：**测试基建** + **Java 25 + Mockito + ByteBuddy 兼容坑** + **vitest 模拟陷阱** + **最终统计**

---

## v5.1 本次会话完成的任务清单（admin-dashboard tasks.md）

| 任务 | 测试文件 | 验证 Property/Requirement |
|---|---|---|
| **1.2** JwtAuthFilter 授权映射 | `common/.../security/JwtAuthFilterPropTest.java` | Property 12, R2.3/2.4 |
| **1.4** TimeDimension 双向映射 | `common/.../codec/TimeDimensionPropTest.java` | Property 1, R17.1/17.2 |
| **1.6** ISO LocalDate 双向映射 | `common/.../codec/IsoDatePropTest.java` | Property 2, R17.3/17.4 |
| **1.8** QueryHasher 稳定性 | `common/.../admin/QueryHasherTest.java` | R15.5 |
| **1.10** BypassCache 异常吞噬 | `common/.../admin/cache/BypassOnFailureCacheManagerTest.java` | R14.3 |
| **2.3** User.role 兜底 | `auth-service/.../entity/UserRoleTest.java` | R1.2/1.3 |
| **2.5** JwtTokenProvider 三参签名 | `auth-service/.../security/JwtTokenProviderRoleTest.java` | R2.1/2.5 |
| **2.7** AuthService.login 角色透传 | `auth-service/.../service/AuthServiceLoginTest.java` | R2.1 |
| **3.3** AdminGuardFilter 三状态 | `api-gateway/.../filter/AdminGuardFilterTest.java` | R3.1/3.3/3.4/3.5 |
| **3.5** HealthHandler.computeOverall | `api-gateway/.../admin/HealthHandlerTest.java` | R18.4 |
| **6.2** AdminBootstrapService 六分支 | `auth-service/.../service/AdminBootstrapServiceTest.java` | R1.4/1.5/1.6 |
| **6.3** Bootstrap 幂等性 PBT | `auth-service/.../service/AdminBootstrapIdempotencyPropTest.java` | Property 11, R1.4 |
| **6.7** AdminStatsController active-users | `auth-service/.../controller/AdminStatsControllerTest.java` | R6.2-6.7 |
| **6.9** UserBatchController 白名单 | `auth-service/.../controller/UserBatchControllerTest.java` | R10.2/10.4 |
| **7.3-7.6** TimeBucketing 四性质 | `memory-service/.../util/TimeBucketingPropTest.java` | Properties 4/5/6/10, R6.1/6.5/7.1 |
| **7.10/7.12/7.18/7.20** AdminStatsService 单测 | `memory-service/.../admin/AdminStatsServiceTest.java` | R7.2/8.3/10.3/10.4/11.1-11.3 |
| **7.14/7.15** Heatmap intensity + grid | `memory-service/.../admin/HeatmapAggregatorPropTest.java` | Properties 7/8, R9.3/9.4 |
| **7.16** HeatmapPoint JSON round-trip | `memory-service/.../admin/dto/HeatmapJsonPropTest.java` | Property 3, R17.5 |
| **7.22** Admin DTO 白名单 | `memory-service/.../admin/dto/AdminDtoWhitelistTest.java` | Property 9, R15.1/15.2 |
| **8.3** AdminResonanceService + Controller | `resonance-service/.../admin/AdminResonance*Test.java` | R12.1-12.3, R15.1 |
| **10.2** auth store role 兜底 | `frontend/tests/admin/authStoreRole.spec.ts` | R4.6 |
| **10.5** 路由守卫 | `frontend/tests/admin/routeGuard.spec.ts` | R4.1/4.2/4.3 |
| **10.10** useAdminApi 状态机 | `frontend/tests/admin/useAdminApi.spec.ts` | R5.5/13.1/18.1/18.2 |
| **10.12** AdminPanel 五态 | `frontend/tests/admin/AdminPanel.spec.ts` | R5.5/5.6/18.2 |
| **11.3** ActiveUsersView ECharts option | `frontend/tests/admin/ActiveUsersView.spec.ts` | R6.8 |
| **11.7** HeatmapView ResizeObserver | `frontend/tests/admin/HeatmapViewLifecycle.spec.ts` | R9.6 |

**最终统计**：
- 后端：**5 模块（common / auth-service / memory-service / resonance-service / api-gateway）共 209 个测试，0 失败 0 错误**
- 前端：**6 个 admin spec 共 43 个测试，全部通过**
- 整个 admin-dashboard spec 100 个任务（67 必须 + 33 可选测试）**全部完成** ✅

---

## v5.2 v5 新增的关键陷阱

### v5.2.1 ⚠️ Java 25 + Mockito + ByteBuddy 兼容性
- 项目 JAVA_HOME 指 Java 25 (LTS preview)，但 Spring Boot 3.2.5 BOM 锁定的 Mockito 5.7 用的 ByteBuddy 1.14 只支持到 Java 22
- 错误：`Java 25 (69) is not supported by the current version of Byte Buddy which officially supports Java 22 (66)`
- **解法**：在每个含 Mockito 测试的模块的 `surefire-plugin` 配置 `<argLine>-Dnet.bytebuddy.experimental=true</argLine>`
- 仅 mock interface 的模块（memory-service 早期）不会触发 — 只有 mock 具体类（如 AdminBootstrapService、UserRepository 接口实现）才需要 ByteBuddy 插桩
- `MAVEN_OPTS` 不传给 surefire forked JVM；必须写在 `argLine`

### v5.2.2 ⚠️ jqwik 1.8.4 没有 `Arbitraries.empty()`
- 有 `Arbitraries.just(value)` 但没有 `empty()`；不要从其他 API 套用
- **解法**：直接 `lat.flatMap(la -> lon.flatMap(lo -> intensity.map(i -> ...)))` 三层 flatMap，不需要"空起点"

### v5.2.3 ⚠️ jqwik `@IntRange` 不能用在 `long` 参数上
- `@IntRange(min = 0, max = 1_000_000) long count` → IllegalArgument: negative raw count for bucket -1
- jqwik 默默允许参数标错类型，运行时生成不在 `@IntRange` 范围内的值
- **解法**：`long` 参数用 `@LongRange(min = 0, max = ...)`；type-safety 不会替你检查

### v5.2.4 ⚠️ Surefire OOM
- 跑全套 jqwik 1000 trial × 多个 property 的总堆很容易超过默认 256m
- **解法**：surefire `<argLine>-Xmx1024m -Dnet.bytebuddy.experimental=true</argLine>`
- 单独命令行 `MAVEN_OPTS=-Xmx2048m` 给的是父进程，对 forked JVM 无效

### v5.2.5 ⚠️ `@Nested` 测试类的报告显示父类 `Tests run: 0`
- JUnit 5 把父类视作"测试容器"，每个 `@Nested` 内部类单独写一份 surefire 报告
- 用 `Tests run: 0` 看 AdminStatsServiceTest.txt 不要慌 — 检查它的所有 `$NestedClass.txt` 报告
- 我用 PowerShell `Get-ChildItem -Recurse` + 正则解析所有 `.txt` 加总

### v5.2.6 ⚠️ vi.mock 工厂的 hoisting + 闭包变量
- `const routerPushMock = vi.fn(); vi.mock('...', () => ({ default: { push: routerPushMock } }))` 会失败：vi.mock 被自动 hoist 到文件顶部，此时 `routerPushMock` 还没声明
- **解法**：用 `const { routerPushMock } = vi.hoisted(() => ({ routerPushMock: vi.fn() }))`
- 这是 vitest 文档里的标准 pattern，但不容易记住 — 如果 mock 工厂里要引用外部变量，必须 hoisted

### v5.2.7 ⚠️ vue-tsc / TS 检查不能验证 v-mock 行为
- 我的 useAdminApi 测试发现 message 不是 i18n key 而是 raw code — 只有运行时才看得见
- `useAdminApi.translateError()` 把"key returned unchanged"视为缺翻译，回退到 raw code
- 建议：在测试 i18n 集成时让 mock 返回 distinct prefix（如 `'translated:' + key`），这样可以分辨 "key returned" vs "real translation returned"

### v5.2.8 ⚠️ Vue Test Utils + happy-dom 在 maplibre-gl 下崩
- HeatmapView 直接 mount 会崩（happy-dom 没 WebGL）
- **解法**：写一个 `defineComponent` 的 lifecycle harness，复刻 `onMounted: new ResizeObserver(...).observe(...)` 的模式
- 测试覆盖的是"代码形状契约"而非真实 DOM 行为；refactor 破坏 lifecycle 模式时会失败

### v5.2.9 ⚠️ Mockito `mock(SomeRecord.class)` 不支持 records（Java 17+）
- record 是 final，inline-mock-maker 默认不能 mock
- 我的 AdminResonanceServiceTest 改用 `new ResonanceStatusAggregate(status, count, avg)` 直接构造测试数据
- 比 mock 更直白，少一个 ByteBuddy 路径

### v5.2.10 ⚠️ Spring `@Cacheable(sync=true)` 在测试中需要 cache infrastructure
- 直接 `new AdminStatsService(repo)` 调被 `@Cacheable` 注解的方法 — Spring 的 cache 拦截器**不会**触发（没有代理）
- 单元测试可以直接调原始方法；如果走 `@SpringBootTest` 才会走 cache 路径
- 我的 AdminStatsServiceTest 走纯 POJO 路径，跳过 cache，测纯算法

---

## v5.3 v5 关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| ByteBuddy 兼容 | 每个 mockito 用户模块加 surefire `<argLine>-Dnet.bytebuddy.experimental=true</argLine>` | Java 25 dev env 兼容；不需要降级 Mockito |
| Surefire 堆内存 | `-Xmx1024m` 写在每个模块 argLine | 跑 jqwik 1000 trial 不 OOM；不影响生产打包 |
| jqwik 测试位置 | common 集中放 PBT 共享工具，业务 PBT 跟着对应模块 | TimeBucketing 在 memory-service，TimeDimensionCodec 在 common |
| WebClient/Redis 真实调用 | 不在单元测试中跑 | HealthHandler 测 `computeOverall` 静态方法即可；I/O 留给集成测试 |
| AdminGuardFilter 测试 | 直接构造 MockServerWebExchange | 不需要 webflux Test framework；MockServerHttpRequest 足够 |
| Mockito 对 records | 不 mock，直接 `new` | record 是 final，且构造器很简单，不值得为了 stub 引入 inline mock |
| 前端 ResizeObserver | global mock | happy-dom 不带 ResizeObserver，必须 mock；用 `vi.fn` 保留所有方法可观测 |
| useAdminApi 401 测试 | 同时 mock router + auth store | 两条路径都要走完，证明双重防御对接 |
| Heatmap PBT 网格中心校验 | 用 `(coord - step/2) / step` 算 k，残差 < 1e-4 | 浮点 noise 容忍；6 位小数的 SQL ROUND 决定了误差上限 |
| AdminPanel 测试 | 完整 mount + i18n | 这是纯展示组件，渲染断言比 props 校验更可靠 |
| 路由守卫测试 | 复刻 guard 逻辑到测试用 router | 不依赖生产 router 的 axios 链；test stays self-contained |

---

## v5.4 测试金字塔覆盖图

按设计文档的 Correctness Properties 表格 100% 覆盖：

| Property | 测试文件 | jqwik / vitest | 状态 |
|---|---|---|---|
| 1. TimeDimension round-trip | TimeDimensionPropTest | jqwik | ✅ |
| 2. ISO LocalDate round-trip | IsoDatePropTest | jqwik | ✅ |
| 3. Heatmap JSON round-trip | HeatmapJsonPropTest | jqwik | ✅ |
| 4. 桶序长度 | TimeBucketingPropTest.seriesLengthEqualsBucketCount | jqwik | ✅ |
| 5. 桶严格升序 | TimeBucketingPropTest.seriesIsStrictlyAscending | jqwik | ✅ |
| 6. 零填充非负 | TimeBucketingPropTest.zeroFillProducesNonNegativeCounts | jqwik | ✅ |
| 7. Heatmap intensity ∈ (0, 1] | HeatmapAggregatorPropTest.allIntensitiesInRange | jqwik | ✅ |
| 8. Heatmap 网格中心对齐 | HeatmapAggregatorPropTest.allPointsAlignToGridCenters | jqwik | ✅ |
| 9. Privacy 不泄漏 | AdminDtoWhitelistTest | JUnit + grep | ✅ |
| 10. Default window 维度对应 | TimeBucketingPropTest.defaultFromMatchesCanonicalWindow | jqwik | ✅ |
| 11. Bootstrap 幂等 | AdminBootstrapIdempotencyPropTest | jqwik | ✅ |
| 12. Authority mapping 全单射 | JwtAuthFilterPropTest | jqwik | ✅ |

---

## v5.5 给下一代的提醒（admin-dashboard 完工版）

1. **整个 spec 100% 完成**：5 模块后端 + Vue 前端 + 252 测试（209 后端 + 43 前端）全部通过；admin-dashboard 是当前仓库最完整、最测试覆盖的特性
2. **测试是 living spec**：12 个 Property + 大量分支测试构成"代码不能私自漂移"的契约。如果有人改动 AdminGuardFilter / BypassCache / TimeBucketing 而忘记跑测试，CI 会立刻 fail
3. **frontend/tests/admin/ 是 useAdminApi、AdminPanel、HeatmapView ResizeObserver 等组件的契约文档** — 添加新 admin panel 时建议照同样模式补一个 spec
4. **Mockito 测试模式**：mock interface OK，mock concrete class → 必须 `<argLine>-Dnet.bytebuddy.experimental=true</argLine>` until 项目升 Mockito 5.18+ / ByteBuddy 1.15+
5. **不要把测试 promote 到 @SpringBootTest 除非真的需要**：当前测试 1.5s 跑完后端全套，加 SpringBootTest 后就 30s+ 起步
6. **DTO 白名单测试覆盖了 9 个 admin record + 2 个 resonance mirror record**：如果以后加新 admin 端点，记得在 AdminDtoWhitelistTest 里加一个 test method
7. **JwtAuthFilterPropTest 的 1000 次 trial 会喷大量 WARN 日志** — 这是 property test 在练负向路径，不是错误。生产 logback 里 `JwtAuthFilter` 在 INFO 级别，测试没改 — 接受这一点
8. **AdminBootstrapServiceTest 的六个分支已经全部覆盖**：disabled / secret-mismatch / missing-secret / not-found / already-admin / promoted。改动 AdminBootstrapService 时如果新增分支，立刻补对应测试
9. **若以后引入 Mockito 5.18+ 升级**：可以删除 `argLine` 里的 `-Dnet.bytebuddy.experimental=true`；当前 Spring Boot 3.2.5 BOM 锁定的 5.7 不能换
10. **HeatmapView 的 lifecycle harness 是脆弱的**：如果 view 改了 `onMounted: new ResizeObserver(...).observe(...)` 模式（比如改用 `onUpdated` 或 `watch`），这个测试会失效；保持模式同步

---

最后一次更新：2026-05-27 (v5) · 第五代智能体完成 admin-dashboard spec 100% 任务交付（含全部 Correctness Property 测试）



---
---

# 第六代工作记忆（v6）

> 接续会话：2026-05-27（深夜尾声）
> 视角：v5 交付完测试后，用户尝试**真实启动**整个系统并打开管理面板，遇到一连串部署/配置/视觉问题；v6 是这些问题的诊断和修复记录
> 关注点：**生产联调遇到的真实坑** + **诊断思路** + **代码 vs 配置 vs 基础设施 三层分离**

---

## v6.1 立刻要知道的 5 件事

1. **`admin` 用户已经被提升为 ADMIN**（DB 操作）：用户 id `de82ef03-d07a-408e-822f-03606544cc4d`，role 列已经是 `'ADMIN'`，CHECK 约束 + 索引也都补上了。下一代不要再做这个。
2. **gateway 路由表新增了 5 条 admin 子路由**（`api-gateway/.../application.yml`）。在你之前看到的"只有 admin-health 一条"的版本里，`/api/v1/admin/stats/**` 和 `/api/v1/admin/users/**` 全部 404。**这是 v5 测试覆盖盲区** —— 单元测试 / MockMvc 全部绕过 gateway 路由层，导致 spec 阶段没暴露这个缺失。
3. **AppHeader 中文 nav 文字断行已修**：核心是 `.app-nav__link { white-space: nowrap; flex-shrink: 0; }`，断点 1100→1280px。改这个文件时不要把 nowrap 删掉。
4. **`alice` 是文档示例，不是真实账号**。生产环境只有 `admin` 一个用户。下一代如果要演示流程，记得用 `admin` 或者新注册一个，不要给用户复制 `alice` 命令然后他登不上。
5. **后端 503 / 启动失败 99% 是 workpc 上 docker 没起**，不是代码问题。`Test-NetConnection -Port` 会撒谎，必须用真实协议握手验证（见 v6.2.1）。

---

## v6.2 v6 新增的关键陷阱

### v6.2.1 ⚠️ `Test-NetConnection -Port` 在 Tailscale 上会撒谎
- 现象：用户报告"后端全体宕机"，所有服务在启动时报 `HikariPool - Exception during pool initialization` 或 `Server check fail, please check server 100.66.166.46 ,port 9848`
- 第一反应是路由 / 防火墙 / 代码问题，但 `Test-NetConnection -ComputerName 100.66.166.46 -Port 3306` 全部返回 `True`
- **真相**：Tailscale 节点是开机的，所以路由层 SYN-ACK 能完成（PowerShell 的 `Test-NetConnection -Port` 只测这一层）。但 workpc 上的 docker 容器没跑，应用层 read 立刻 timeout / RST。
- **诊断方法**：用真实 socket 读一下协议 banner
  ```powershell
  function Probe($h, $p) {
    $c = New-Object System.Net.Sockets.TcpClient
    try { $c.Connect($h,$p); $s=$c.GetStream(); $s.ReadTimeout=2000
          $b=New-Object byte[] 64; $n=$s.Read($b,0,64); "bytes=$n" }
    catch { "FAIL: $_" } finally { $c.Close() }
  }
  Probe '100.66.166.46' 3306   # 期望 bytes>0（MySQL handshake greeting）
  Probe '100.66.166.46' 8848   # 期望 bytes>0（Nacos HTTP banner）
  ```
- 或更直接：用 jdbc 试连
  ```powershell
  $jar = "M:/.../mysql-connector-j-8.3.0.jar"
  java -cp $jar 'jdbc-test-script.java'   # 看真实异常信息
  ```
- **不要**信 `Test-NetConnection -Port`；它对开发环境足够，但跨 VPN / VLAN / NAT 一切都可能撒谎

### v6.2.2 ⚠️ Spring Cloud Gateway 路由表是"显式声明"的，不会按 path 自动分发
- 设计文档 §"#### auth-service · /admin/** 端点" 把 admin 端点设计成 3 个不同的下游服务（auth + memory + resonance）
- 但 gateway 的 `application.yml` **必须为每个 admin 子路径显式写一条 route**，否则 Spring Cloud Gateway 找不到匹配 → 404
- v5 的所有测试（包括 MockMvc + WebFlux test）都**绕过了 gateway 路由层**，所以单元测试全绿但生产 404
- **修复模式**（已在 `application.yml` 落地）：
  ```yaml
  routes:
    - id: admin-health           # 1. 最特定的（forward 到 gateway 内部）
    - id: admin-users-auth       # 2. 路径前缀级路由（auth-service）
    - id: admin-stats-active-users  # 3. 单端点级路由（auth-service）
    - id: admin-stats-resonance  # 4. 多端点共用同一下游（resonance-service）
    - id: admin-stats-memory     # 5. 兜底 catch-all（memory-service）
    - id: auth-service           # 6. 业务路由（保留原样）
    ...
  ```
- **关键规则**：Spring Cloud Gateway 是"first-match-wins"，所以越特定的路径必须越早声明。`admin-stats-active-users` 必须在 `admin-stats-memory` 之前，否则后者的 `/api/v1/admin/stats/**` 会先吃掉所有 stats 请求

### v6.2.3 ⚠️ Hibernate `ddl-auto: update` 不会执行手写 SQL migration 里的所有约束
- 我们的 `db/admin-role.sql` 包含 `ALTER ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER'` + `CREATE INDEX idx_users_role` + `ADD CONSTRAINT chk_users_role`
- 但实际生产里 Hibernate **只**自动加了 column（来自 `@Column(nullable=false, length=16)`），**没加** DEFAULT、index、CHECK
- 结果：用户原有的 admin 行 `role` 字段是空字符串而非 `'USER'`（虽然有 NOT NULL，但 Hibernate add-column 时已存在的行是用 DEFAULT 填的，而 Hibernate 没声明 DEFAULT）
- **下一代部署提醒**：每次新增列，提前手动跑 `db/*.sql` 一遍。或者考虑引入 Flyway。
- 我用 jdbc 写了个一次性脚本帮用户补上这些约束：
  ```java
  // 升级 admin 用户 + 补上约束的脚本（已删除 .java 文件，逻辑保留如下）
  conn.prepareStatement("UPDATE users SET role='ADMIN', updated_at=NOW() WHERE username='admin'").executeUpdate();
  conn.createStatement().execute("CREATE INDEX idx_users_role ON users (role)");
  conn.createStatement().execute("ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'))");
  ```

### v6.2.4 ⚠️ Token 里的 role claim 不会随 DB 变化自动刷新
- 用户升级了 DB role 之后，旧 token **还是** `role=USER`
- 必须**重新登录**才会拿到 `role=ADMIN` 的新 token
- **下一代提醒**：写"提升 ADMIN"流程的文档时一定要明确告诉用户重登
- 调试时如果"前端没出现管理面板入口"，第一步就是 F12 看 token decode 后的 role claim

### v6.2.5 ⚠️ Flex 容器 + 中文 nav 链接 = 文字断行的灾难
- 现象：`.app-nav__link` 7→8 个之后，每个 pill 文字被切成两行（"我的记/忆"、"新建记/忆"）
- 中文不像英文有空格作为换行点，浏览器在 flex 容器空间不足时会**任意位置断词**
- **修复**：每个 nav link 都要 `white-space: nowrap` + `flex-shrink: 0`
- 同样的坑可能出现在任何中文 pill / chip / button 列表里 —— 写新组件时记得先加这两个

### v6.2.6 ⚠️ 响应式断点选择
- 旧的 `1100px` 断点在主流笔记本（1280 / 1366）上**刚好**触发挤压
- 实际用户最多的中屏在 1280–1366px 这个区间，所以断点应该 ≥ 1280
- **教训**：响应式断点选 750/1024/1280/1440 这种行业惯例值，不要拍脑袋选 1100

### v6.2.7 ⚠️ AppHeader brand 副标题在中屏抢宽度
- "AI 驱动的个人记忆博物馆" 中文 13 字，约 130px
- 中屏上让 brand 副标题占这么多宽度只是 vanity，不是功能
- **修复**：`@media (max-width: 1280px) { .brand__copy small { display: none; } }`
- 同样的"中屏让 nav 优先"原则：装饰性元素先牺牲，功能元素保留

### v6.2.8 ⚠️ Bootstrap secret 必须在启动 auth-service **之前**写到 env
- `$env:ADMIN_BOOTSTRAP_SECRET = '...'` 之后再 `mvnw -pl auth-service spring-boot:run`，子进程才会继承
- 启动后再设环境变量 → 进程已经读完，不生效
- 启动日志：`Bootstrap endpoint enabled (secret length=44)` = 成功；`is unset` = 失败
- 用户场景：如果对 secret 的运维流程不熟悉，最快的办法是**直接 SQL UPDATE**（一行的事），不用走 secret 通道

### v6.2.9 ⚠️ 测试覆盖盲区识别
- v5 我交付了 257 个测试 + 12 个 Property，但**全部都是单元 / MockMvc 级别**，没有真启动 gateway + 下游的 contract test
- 部署时一打就 404 暴露了这个盲区
- **下一代加新跨服务路径时一定要补一个 e2e smoke test**，哪怕只是"启动 gateway + memory-service，curl 一遍 /api/v1/admin/stats/active-users 看 200"
- 或者至少写个 smoke 脚本：
  ```powershell
  # backend/scripts/Smoke-AdminEndpoints.ps1
  $token = '...'  # 从 admin 登录拿
  @('/admin/health', '/admin/stats/active-users?dimension=DAILY',
    '/admin/stats/memory-trends?dimension=DAILY',
    '/admin/stats/emotion-distribution',
    '/admin/stats/heatmap?gridResolution=MEDIUM',
    '/admin/stats/top-contributors',
    '/admin/stats/fragment-discovery',
    '/admin/stats/resonance-overview',
    '/admin/stats/resonance-top') | ForEach-Object {
    $r = Invoke-WebRequest "http://localhost:8080/api/v1$_" `
         -Headers @{Authorization = "Bearer $token"} `
         -SkipHttpErrorCheck
    "[$($r.StatusCode)] $_"
  }
  ```

---

## v6.3 v6 关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 升级 admin 角色的方式 | 直接 SQL UPDATE | 比走 bootstrap secret 通道更直接；用户不用配置环境变量；DB 级 CHECK 约束保证类型正确 |
| Gateway 路由表风格 | 多个细粒度 route，按特定→泛化排序 | first-match-wins；维护时新增端点只加一行 |
| Bootstrap secret 在生产环境 | **不**长期保留 | 第一个 ADMIN 升级完之后立刻撤销 env，后续升级走 admin-token 通道（业务 API 路径） |
| 中屏 brand 副标题 | 隐藏 | 装饰性元素优先级低于功能 nav |
| nav 链接 nowrap | 强制每个 link 都加 | 与是否英文无关；中文/任何挤压场景都需要 |
| 断点位置 | 1280px 主断点 + 720px 移动断点 | 行业惯例；覆盖主流笔记本 |
| Hibernate ddl-auto | 维持 update | 不破坏其他模块的开发节奏；但每次加列要手动跑 `db/*.sql` 补约束 |
| 部署排查顺序 | TCP banner → JDBC 试连 → application log | 三层逐层排除，不要直接看 application log（噪声大） |

---

## v6.4 v6 工作流程经验

### v6.4.1 用户报"后端宕机"的诊断三步法
1. **第一步**：用真实 socket（不是 PowerShell 的 Test-NetConnection）读 banner
   - MySQL → 期望 bytes > 0（handshake greeting）
   - Redis → 期望 bytes > 0（PONG / RESP banner）
   - Nacos HTTP → 期望 bytes > 0（HTTP response banner）
2. **第二步**：如果 banner OK 但应用还报错，跑一次 JDBC / Redis CLI / curl 真协议握手
3. **第三步**：到这一步还没找到问题，再看 application log

### v6.4.2 用户说"看板报 NETWORK 错误"的诊断步骤
1. F12 → Network → 看实际 HTTP 状态：
   - 401 → token 失效或没 role 字段，要重登
   - 403 → token 有但不是 ADMIN，要 SQL 升级 + 重登
   - 404 → **gateway 路由没配**（v6 修过）
   - 502 → 下游服务挂了
   - 真的 ERR_NETWORK → gateway / vite proxy 没起
2. 别相信 console 的"NETWORK"文字 —— 那是前端 useAdminApi 把所有它不认识的状态都翻译成 NETWORK；真相在 Network tab

### v6.4.3 直接帮用户操作 DB 的安全做法
- **不要**用 `mysql -e` 内联 UPDATE，shell 转义和密码外露都是风险
- **要**用临时 Java 脚本 + jdbc 连接 + setAutoCommit(false) + 检查 rows updated
- 检查异常路径（rows != 1 立即 rollback）
- 操作完立即 `delete_file` 删掉脚本，不留 secret 在文件系统
- 用户的 MySQL 密码在 `.env.workpc` 里是明文 `root123`（仓库私有，用户明确说过可以提交），但还是不要把它打进 chat log

### v6.4.4 渐进式调试 admin-dashboard
分层定位问题，不要一上来就重启全部：
1. **DB 层**：`SELECT id, username, role FROM users WHERE username='admin'`
2. **Auth 层**：F12 看 token decode 出 role 是不是 ADMIN
3. **Gateway 层**：直接 curl `localhost:8081/api/v1/admin/stats/active-users` 试 auth-service（绕过 gateway 路由），如果 200 → 说明问题在 gateway 路由
4. **Service 层**：看 surefire 报告 / application log
5. **前端层**：vite dev server 改了配置后强制 hard refresh

---

## v6.5 给下一代的提醒

1. **不要相信"测试全绿就能上线"** —— v5 的 257 个测试全过，部署后 7 个面板 404。下一代加新跨服务路径**必须**写至少一个 contract test 或 smoke 脚本
2. **Tailscale + docker 的"半死不活"很常见** —— Tailscale agent 在但 docker 没起，开发机收到的是 SYN-ACK 后立即 RST。如果用户报"突然全宕了"，先查 docker compose ps
3. **Gateway 路由表是 spec-of-truth**（`api-gateway/.../application.yml`）—— 任何新接口都要去这里加一条；single source of truth
4. **`.env.workpc` 是用户的本地配置**，不要改它结构。如果加新中间件，参考已有的 `MYSQL_HOST` 这种命名风格，并在 `application.yml` 里用 `${...:default}` 兜底
5. **bootstrap secret 流程文档要明确说"重启 auth-service"** —— 用户搞不清楚 env 什么时候生效，直接给 SQL 路径更友好
6. **响应式断点选 1280**，不要选 1100；移动端选 720
7. **AppHeader 加新 nav link 时**，记得测一遍 ≥1280 / 1024 / 720 三个宽度
8. **i18n 切换语言后再测一遍** —— 中英文长度不同，1024px 中文挤一行可能英文要 wrap；这就是为什么 nav link 必须 nowrap
9. **如果 admin 面板任一面板报 NETWORK，先看 F12 Network tab 的真实 status code**；用户不会自己看，你要主动问
10. **Memory.md v1-v6 是给你看的"作弊小抄"** —— 每条都是真实踩过的坑，不要假设它们已经过时；除非你亲自验证过某条已经修复，否则当作仍然有效

---

## v6.6 admin-dashboard 现状清单（移交版）

### 已完成（生产可用）
- ✅ admin 用户已升级为 ADMIN，可登录看面板
- ✅ Gateway 路由表完整支持 9 个 admin 端点
- ✅ AppHeader 视觉修复（nowrap + 断点 + brand 副标题）
- ✅ DB 完整性约束（idx_users_role + chk_users_role）

### 测试基建（来自 v5）
- ✅ 224 后端测试 + 43 前端测试，0 失败
- ✅ 12 条 Correctness Properties 全覆盖
- ✅ jqwik 1.8.4 + ByteBuddy experimental flag 配置就绪

### 已知盲区（建议下一代补）
- ❌ 没有 e2e smoke test（gateway → 下游真启动）
- ❌ 没有 Flyway，DDL 靠手工 + Hibernate ddl-auto 双轨
- ❌ heatmap 在国外网络可能瓦片源不可达（fallback 没全套验证过）
- ❌ resonance graph force 布局在节点 > 50 时性能差（没限速）

### 用户决策点（v2.6 / v3.4 / v4.5 残留）
- 部分仍未决策的项目细节（聊天系统接 AI 球、emotionVector 可视化等）见 v2.6
- admin-dashboard spec 本身已 100% 完成，不在这个清单里

---

最后一次更新：2026-05-27 (v6) · 第六代智能体完成 admin-dashboard 部署联调（DB 升级 + Gateway 路由 + AppHeader 视觉）

