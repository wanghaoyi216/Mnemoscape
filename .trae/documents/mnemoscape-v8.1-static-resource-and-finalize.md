# Mnemoscape v8.1 — 收尾 & 静态资源灌入计划

> **状态**: Plan Mode（等待批准后进入执行）
> **基线分支**: `feature/v8-ux-ai-resource`（v8 改动的 7 个 M + 4 个 untracked 全部在工作区，**未提交**）
> **最后稳定 commit**: `8d9525e backup: v7 stable ... — pre-v8 overhaul`
> **不重塑框架**：保留 ReAct / ToolRegistry / Feign 体系；只补强 + 灌资源

---

## 0. 用户原始诉求（4 条复盘）

| # | 诉求 | v8 已做 | v8.1 仍需 |
|---|---|---|---|
| 1 | 英文菜单栏 1280-1440px 依旧超出 + 换行 | 5 档断点 + en-US 短词 + span max-width 8ch | **继续强化**：8ch 仍可能不够 + brand 140px 抢空间；再收一遍 padding + 字号 + brand hide 提前 |
| 2 | AI 瞎编 2024、简单问题走 RAG、复杂问题不触发 ReAct | 11 个新工具 + shouldSkipRAG 工具白名单 + 17 工具 prompt | **加力**：在 system prompt 顶部加"硬前置"提示；新增 date-anchor "今日"判断；用 curl 实际测一次 |
| 3 | 大量静态资源（photo/video/audio/icon/gif/kaomoji）→ 远端 MinIO | 资源已就位 39 个 + sync 脚本 + StorageProperties 12 目录 + kaomoji catalog 200+ | **实际跑同步**：mc.exe 不可用 → 改用 Python `urllib` 拼 S3v4 签名的轻量级 uploader；30+ 张 photo 用 `byted-seedream-image-generate` AI 补齐；30 个 SVG icon 用 Python 程序化生成 |
| 4 | git 备份 + 关闭前后端 + 保留中间件 + 等用户自测 | backup commit `8d9525e` + v8 分支已建 + java 进程已 kill | **最后动作**：把所有 v8.1 改动一次性 commit 到 `feature/v8-ux-ai-resource`；二次确认 java 进程为 0；输出报告 |

---

## 1. 现状盘点（Phase 1 探索结果）

### 1.1 菜单栏 CSS（`[AppHeader.vue](file:///m:\Study\ProjectTest\Mnemoscape\frontend\src\components\layout\AppHeader.vue)`）
**v8 已做**（v14 重写）：
- `.brand__copy` + `strong` 都加了 `white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px;`
- 5 档断点：1600 / 1440 / 1280 / 960 / 720
- 1600-1280 区段：nav__link span 加 `max-width: 8ch; overflow: hidden; text-overflow: ellipsis;`
- 1440-1280 区段：brand__copy 缩到 140px
- 1280 以下：nav icon-only + brand 仅图标
- 960 以下：user-chip meta 隐藏
- 720 以下：locale-switch 隐藏

**v8 改 en-US 短词**：
```json
"create": "New",       // 原 "New memory"
"graph": "Graph",      // 原 "Memory graph"
"chat": "Chat",        // 原 "Chat & Discovery"
```

**用户仍报告"换行 / 超出"** — 推测原因：
1. `flex-shrink: 0` 在 link 上让 link 不收缩，但 link 的 span 已经被 max-width: 8ch 限宽 → link 总宽 = icon(16) + gap(6) + span(8ch) + padding(22) ≈ 56+8ch px
2. 7 个 link × ~100px = 700px；brand(140) + locale(70) + user-chip(140) + 按钮(100) + gap ≈ 420px；**总 ≈ 1120px**
3. 1280px 窗口下 .page-shell--wide 通常 padding 32+32 = 64px → 主内容区 1216px，应该装得下
4. **但** `.app-nav { overflow: hidden }` → 第 7 个 link 末尾会被截断成"Chat & D…" —— 这恰好是用户截图的样子
5. 根因：**在 1280-1440 区段，nav 7 个 link 总宽 = 700+px 几乎贴满主内容区，没有 brand 让位的余裕**；换言之 brand+nav+actions 同时显示已经超出容器 → 必须二选一

**v8.1 修复策略**（"宁可隐藏、绝不换行"）：
- **1280-1440px 区段**：**brand 文字隐藏**（仅图标）→ 给 nav 腾出 140px 空间 → 7 link 完整可显
- **1280-960 区段**：维持 icon-only nav + brand 仅图标
- **< 960px**：维持 user-chip meta 隐藏
- 在 `1280-1440` 区段额外加 `nav__link` 的 `font-size: 0.88rem; padding: 8px 9px;` 进一步保险

### 1.2 AI 工具（`[ToolRegistry.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ToolRegistry.java)` + `[ChatReasoner.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java)`）
**v8 已做**（17 工具）：
| 类别 | 工具 |
|---|---|
| 基础（4）| currentDateTime / getWeather / calculator / geocode |
| 记忆（6）| milvusSearchTool / memoryDetailTool / timelineNavigationTool / memoryStatsTool / emotionAnalysisTool / getMemoryStats |
| 关系（3）| getFriends / getResonanceFeed / getUnreadNotifications |
| 对话（3）| summarizeConversation / listChatHistory / regenerateLastAnswer |
| 终止（1）| final |

**v8 已做**（智能路由）：
- `shouldSkipRAG()` 升级（取代 `isGreetingOrTooShort`），三层短路：寒暄/太短 → 工具白名单（日期/天气/算术/翻译/百科）→ 不涉及"我" → 走 RAG
- 工具白名单覆盖中英双语，30+ 关键词
- `STREAMING_SYSTEM_PROMPT` 顶部第 4 步列 17 工具清单 + 第 5 段 v8 强制问工具边界
- `REACT_PROTOCOL_PROMPT` 独立常量，同步 17 工具 + args 示例

**v8.1 仍需做的 3 个补强**：
1. **Prompt 顶部加"硬前置"指令**（避免模型忽略 system prompt）：在 system prompt 第 1 条前加 `你的每次回答前**必须先**判断：用户问题是否属于[日期/时间/天气/算术/翻译/经纬度/好友/共鸣/记忆统计/总结/历史/重生成/好友]中的任何一类？若是，你**只能**通过工具获取答案；禁止凭训练知识回答。`
2. **加 date-anchor**：在 `shouldSkipRAG` 增加 "昨天/前天/明天/后天/上周/这周" 等相对时间词 → 走 currentDateTime 而非记忆搜索
3. **实际验证**：用 PowerShell 调 `POST http://localhost:8080/chat` 发 "今天几月几号" → 校验 ai-service 实际返回真实日期（用户重启后能复现）

### 1.3 静态资源（`[resource/](file:///m:\Study\ProjectTest\Mnemoscape\resource)` + `[StorageProperties.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\asset-service\src\main\java\com\mnemoscape\asset\config\StorageProperties.java)`）

**v8 已做**：
- `StorageProperties.PUBLIC_TOP_LEVEL_DIRS` 从 7 扩到 12（photo/video/audio/gif/music/icon/icons/sticker/kaomoji/emoji/avatar/theme）
- 本地 `resource/` 现存 39 个文件（15 photo + 10 video + 12 audio + 2 gif）
- `frontend/src/assets/kaomoji-catalog.ts` 200+ 条目 14 分类（happy/sad/angry/cute/love/shrug/think/greet/food/animal/object/symbol/meme/kaomoji）
- `scripts/sync-assets-to-minio.ps1` 250 行就绪，参数化（Src/Bucket/Endpoint/AK/SK/DryRun/PublicBaseUrl）
- `ChatView.vue` 升级 v8 emoji picker（搜索 + 14 分类 tabs + 8 列网格 + 兜底 16 个）

**v8 阻塞 & v8.1 解决方案**：

| # | 阻塞 | 解决方案 |
|---|---|---|
| A | `mc.exe` 不在 PATH | 用 Python + `urllib` 拼 S3v4 签名做一个轻量级 uploader（不依赖任何外部包） |
| B | `boto3`/`minio` Python 库不可用 | 同上：用 stdlib `urllib` + `hashlib` + `hmac` 手写 S3 PUT 单文件上传（仅 PUT object + multipart init） |
| C | MinIO bucket `mnemoscape-assets` 当前空 | 脚本先 `mc mb` 等价操作（用 `requests` 调 MinIO admin API 创建 bucket；或假定已建） |
| D | 30 张 photo 需 AI 生成 | 调 `byted-seedream-image-generate` 技能（火山引擎），需要 `ARK_API_KEY`；**用户暂未提供** → 改用本地程序化生成 15 张（Python PIL：渐变 + 几何 + 项目主题词文字）作为兜底 |
| E | 30 个 SVG icon 缺失 | Python 程序化生成（geometric SVG：圆形 + 路径 + 项目主题）|
| F | 30 张 GIF 表情包缺失 | Python PIL 程序化生成（彩色文字 + 简单动画）|

### 1.4 运行环境
- **MinIO @ 100.66.166.46:9000**：可连通（`Test-NetConnection` → True）
- **中间件在 workpc（远端 Windows）**：本机不动
- **本地 java 进程**：0（v8 已 kill 12 个）
- **本地 node 进程**：0
- **NVIDIA_API_KEY**：已配置（`nvapi-BCuT...`）
- **ARK_API_KEY**：**未配置**（无法调火山引擎 → photo 用 PIL 兜底）
- **mc.exe / boto3 / minio**：**不可用**（用 stdlib urllib 自写 uploader）

---

## 2. 拟定的改动（Proposed Changes）

### 2.1 菜单栏 v8.1 CSS 强化（[AppHeader.vue](file:///m:\Study\ProjectTest\Mnemoscape\frontend\src\components\layout\AppHeader.vue)）

**改动 1**：1440-1600px 区段，brand 文字 `display: none`（仅图标），给 nav 腾出 140px
**改动 2**：1600-1440px 区段 nav__link 字号降到 `0.92rem; padding: 8px 9px; gap: 5px;`
**改动 3**：1600-1280px 区段 brand 整体 `max-width: 0; display: none;`（更早隐藏）
**改动 4**：在 `.app-nav` 上加 `min-width: 0; flex: 1 1 auto;` 让它有最小宽度并参与分配
**改动 5**：保留 8ch max-width + ellipsis 兜底

**预计改动量**：`<style scoped>` 段 ~15 行

### 2.2 AI Prompt 强化 + date-anchor（[ChatReasoner.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java)）

**改动 1**：`STREAMING_SYSTEM_PROMPT` 顶部加"硬前置"段（避免模型忽略工具）：
```text
⚠️ 硬前置（每次回答前必读）：
当用户问题匹配以下任何关键词（中英双语都算）时，你**只能**通过调工具获取答案，
禁止凭训练知识回答：
[日期/时间/天气/算术/经纬度/好友/共鸣/记忆统计/总结/历史/重生成/翻译/汇率/百科]
对应工具：currentDateTime / getWeather / calculator / geocode /
getFriends / getResonanceFeed / getMemoryStats / summarizeConversation /
listChatHistory / regenerateLastAnswer / 翻译（待实现）
```

**改动 2**：`shouldSkipRAG()` 工具白名单增补相对时间词：
```java
// 相对时间 → 走 currentDateTime
"昨天", "前天", "明天", "后天", "上周", "这周", "下周",
"去年", "前年", "明年", "后年",
"yesterday", "tomorrow", "last week", "next week",
"last year", "next year", "last month", "next month"
```

**预计改动量**：2 段文本 + 1 个数组

### 2.3 静态资源实际同步（[scripts/sync-assets-to-minio-py.py](file:///m:\Study\ProjectTest\Mnemoscape\scripts) 新建）

**新建**：`scripts/upload_to_minio.py` —— stdlib-only 的 S3 PUT 上传器

```python
# 用 urllib + hashlib + hmac 实现 S3v4 签名的 PUT object
# 参数: --src <file_or_dir> --bucket mnemoscape-assets --endpoint http://100.66.166.46:9000 --ak minioadmin --sk minioadmin123
# 单文件最大 5GB（不算 multipart，大于 5MB 自动 multipart init）
# 目录递归：把 src/photo/a.png → bucket/photo/a.png
# 跳过：size 一致的同名文件
# 输出：上传 N / 跳过 M / 失败 K
```

**新建**：`scripts/generate-static-assets.py` —— 兜底资源生成器
```python
# 不依赖 ARK_API_KEY，用 PIL 程序化生成
# 30 张 photo 主题：memory / resonance / time / season / aurora / ocean ... 
# 30 个 SVG icon：圆形 + 路径 + 项目主题词
# 30 张 GIF 表情包：彩色文字 + 帧动画（中文热词）
# 输出到 resource/{photo,icon,gif}/ 下
```

**新建**：`scripts/sync-all.ps1` —— 一键同步
```powershell
# 1. 跑 generate-static-assets.py
# 2. 跑 upload_to_minio.py --src ./resource --bucket mnemoscape-assets
# 3. 写 index.json
```

**预计改动量**：3 个新脚本共 ~300 行

### 2.4 最终 commit + 收尾

```powershell
cd m:\Study\ProjectTest\Mnemoscape
git add -A
git status  # 确认所有 v8.1 改动都被 add
git commit -m "v8.1: menu CSS hardening, AI prompt reinforcement, static resource upload (39→90+ files, kaomoji 200+, AI tools 17, photo/icon/gif programmatic generation)"
# 不 push，让用户审阅
```

二次确认 java 进程为 0 + node 进程为 0 + 中间件不被打扰（workpc 远程未动）。

---

## 3. 假设 & 决定（Assumptions & Decisions）

| # | 假设 | 备注 |
|---|---|---|
| A1 | MinIO 100.66.166.46:9000 当前可达 | `Test-NetConnection` → True |
| A2 | 用户接受 30 张 photo + 30 个 icon + 30 张 gif 用程序化生成（兜底）| 无 ARK_API_KEY → 不调火山引擎 |
| A3 | 不重塑框架；保留 ReAct 协议 + ToolRegistry | 不改 SSE、不改 ChatController |
| A4 | v8.1 改动合并到 `feature/v8-ux-ai-resource` 分支的 1 个新 commit | 不再细分小 commit |
| A5 | 保留中间件，关闭前后端，等用户自测 | 计划中已明确 |
| A6 | Python stdlib 可用（urllib/hashlib/hmac/json/base64）| 系统自带 |
| A7 | PIL/Pillow 可用 | 若不可用则降级到只用 SVG icon + 现有 39 个文件 |
| A8 | 8ch max-width + ellipsis + 1280-1440 brand 隐藏可彻底解决菜单溢出 | 若不行再加横向滚动 |

---

## 4. 验证步骤（Verification）

### 4.1 菜单栏
1. 启动前端 `npm run dev`（用户执行）
2. 浏览器 DevTools 切到 1280 / 1440 / 1600 / 1920 四档
3. 中/英两语种各看一次
4. 检查：brand 不换行 + nav 7 link 完整或省略号 + 用户名 / 按钮不换行

### 4.2 AI
1. 重启 ai-service（`Start-LocalDevServices.ps1`）
2. curl 测：`POST /chat` body=`{"question":"今天几月几号？","locale":"zh-CN"}` → 应返回 2025 真实日期
3. curl 测：`{"question":"12*34+56 等于多少"}` → 应返回 464
4. curl 测：`{"question":"我去年 7 月去了哪里"}` → 应触发 RAG（不被白名单拦截）

### 4.3 静态资源
1. `pwsh scripts/sync-all.ps1` → 输出 "上传 N / 跳过 M / 失败 K"
2. 浏览器访问 `http://100.66.166.46:9000/mnemoscape-assets/photo/` → 看到 30+ 张
3. 前端 admin 后台 → 资源面板 → 显示 90+ 资源
4. ChatView → emoji picker → 14 分类 + 搜索 → 命中

### 4.4 整体回归
1. 用 `mnemo_user_001 / Mnemo@2026#Seed` 登录
2. 6 个微服务都健康
3. 菜单栏在 1280-1920 区间不换行
4. AI chat 三类问题（日期 / 算术 / 记忆）均正常
5. emoji picker 200+ 表情正常展示

---

## 5. 风险 & 缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| 1280-1440 仍换行 | 低 | 已 1280 起 brand 隐藏；若仍换行 → 引入横向滚动条 |
| PIL 不可用 | 中 | 降级到纯 SVG icon 生成（不依赖 PIL）+ 现有 39 文件继续用 |
| S3v4 自写签名错 | 中 | 走 PUT object 单段（≤5GB）；用 MinIO 控制台 / mc 命令交叉验证一个文件 |
| ARK_API_KEY 缺失 | 高 | 已知 → 用 PIL 兜底生成 30 张主题 photo；不调火山引擎 |
| AI 模型不调工具 | 中 | system prompt 顶部加"硬前置"段；不调工具就拒绝回答 |

---

## 6. 执行顺序（实施时严格按序）

```
Step 0: 二次确认现状（git status / java 进程 / MinIO 连通）     (2 min)
Step 1: 菜单栏 CSS 强化                                       (8 min)
Step 2: AI Prompt 加固 + date-anchor                          (8 min)
Step 3: 写 scripts/upload_to_minio.py（stdlib S3v4 上传）     (20 min)
Step 4: 写 scripts/generate-static-assets.py（PIL/SVG 兜底）  (25 min)
Step 5: 写 scripts/sync-all.ps1（一键同步）                    (5 min)
Step 6: 实际跑 sync-all → MinIO                              (3 min)
Step 7: 前端 typecheck + build 验证                            (2 min)
Step 8: 后端 mvn compile 验证                                  (2 min)
Step 9: 二次确认 java 进程为 0                                 (1 min)
Step 10: git add -A + commit v8.1                              (2 min)
Step 11: 输出最终报告                                          (3 min)
```

总计：~80 分钟（其中 Step 4 程序化生成图占大头）

---

## 7. 不在本次范围

- 改 chat LLM 模型（Gemma-3N 仍为默认）
- 改 Spring AI / Spring Cloud Alibaba 版本
- 重构 ReAct 协议
- 改 Nacos / Sentinel / Seata
- 写"写"类工具（AI 依然只读）
- 接真天气 API（仍 mock）
- 接真地理 API（仍 mock）
- 调火山引擎（无 ARK_API_KEY）

---

**Plan ready. 请审阅后批准执行。**
