# Mnemoscape v8 — 体验 · AI 智能 · 静态资源 三件套改造计划

> **状态**: 计划中（Plan Mode）
> **执行前提**: 用户已确认"先 git 提交一版作为备份 → 改 → 保留中间件运行 → 关闭前后端 → 留用户自测"
> **重要约束**: 不重塑框架；保留现有 Spring Cloud Alibaba Nacos 微服务架构与 ReAct 协议；只扩展内嵌工具 + 修 CSS + 同步资源。

---

## 0. 用户原始诉求（4 条）

| # | 诉求 | 严重度 | 是否改框架 |
|---|---|---|---|
| 1 | 英文菜单栏依旧超出字体范围 + 出现换行 | 高（视觉崩坏） | ❌ |
| 2 | AI 太死板（瞎编 2024 / 简单问题也走 RAG / 复杂问题没触发 ReAct） | 高（功能故障） | ❌ |
| 3 | 大量静态资源 → 远端 Windows MinIO（图片/视频/音频/ICON/表情包/颜文字） | 中（产品体验） | ❌ |
| 4 | 先 git 备份，再改；改完关闭前后端，留中间件给用户自测 | 操作约定 | — |

---

## 1. 当前状态分析（Phase 1 探索结果）

### 1.1 菜单栏（[AppHeader.vue](file:///m:\Study\ProjectTest\Mnemoscape\frontend\src\components\layout\AppHeader.vue)）

**问题**（用户截图复现）：
- 品牌名 `Mnemoscape` 在 1200-1440px 区段被拆成 `Mnemoscap` + `e`（新行）→ **`.brand__copy` 没有 `white-space: nowrap`，且宽度受 flex 容器挤压时换行**
- `Chat & Discovery` 被截断为 `Chat & Disc` → **7 个 nav link 文字 + icon + 7×9×2=126px padding，1440px 窗口下超出 `.app-nav` 容器宽度**
- `.app-nav__link` 已设 `white-space: nowrap`（v12 后），问题不在 link 本身，而在**容器 `.app-nav` 没有滚动 / 自适应策略**

**已有响应式断点**（.app-header.vue 内部）：
- `≤1440px` → brand 文字隐藏（`.brand__copy { display: none }`）
- `≤1200px` → nav link 文字隐藏（icon-only）
- `≤960px` → user-chip meta 隐藏
- `≤720px` → locale-switch 隐藏

**问题本质**：**1280-1440px 区段**有 brand `Mnemoscap + e` + nav 7 个完整文字 link，但容器宽度不够；1280px 之前已被 nav 撑爆。**需要新增 1280-1440px 区段的中文/英文差异化处理 + 横向滚动 + brand 强制 nowrap**。

### 1.2 AI 工具（[ToolRegistry.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ToolRegistry.java) + [ChatReasoner.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java)）

**当前 6 个内嵌工具**：
| 工具名 | 用途 | 失败兜底 |
|---|---|---|
| `milvusSearchTool` | 关键词召回 topK | 返回 `error: tool unavailable` |
| `memoryStatsTool` | 聚合统计 | 同上 |
| `emotionAnalysisTool` | 8 维情绪向量 | 字典兜底 |
| `timelineNavigationTool` | 时间线 | error |
| `memoryDetailTool` | 单条详情 | error |
| `final` | 终止符 | — |

**问题诊断**：
- ✅ **日期/时间缺失**：模型训练 cutoff 决定它必然瞎编；当前没有任何工具告诉它"现在几点"
- ❌ **简单问题误触发 RAG**：[buildRagPrefix() line 672](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java#L668-L674) 已经用 `isGreetingOrTooShort` 白名单过滤寒暄，**但白名单太短**（仅"你好/hi/hello"等 11 个），普通问句"今天几号"会落进 RAG
- ❌ **ReAct 协议不被触发**：[REACT_PROTOCOL_PROMPT](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java#L79-L95) 告诉模型"需要检索/多步推理/工具辅助时"才用 XML 协议；Gemma 3N-2B-IT 这种小模型对条件式指令遵从率低，**实测基本不会主动调工具**
- ❌ **天气 / 计算器 / 地理 / 知识库** 等基础工具完全缺失
- ❌ **对话自身辅助**（重生成 / 历史 / 总结）走前端 /chat/history，前端无对应 API 调用 → AI 自我重生成能力 0

### 1.3 静态资源（[resource/](file:///m:\Study\ProjectTest\Mnemoscape\resource) + [StorageProperties.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\asset-service\src\main\java\com\mnemoscape\asset\config\StorageProperties.java#L25-L26)）

**当前公共素材白名单**（顶层目录）：
```
photo / video / audio / gif / music / icon / icons
```

**当前 local resource 目录扫描**（[LocalResourceWatcher.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\asset-service\src\main\java\com\mnemoscape\asset\service\LocalResourceWatcher.java#L54-L80)）：
- 解析 `MNEMOSCAPE_RESOURCE_DIR` → `../../resource` → `../resource` → `resource`
- 启动时扫一次 + WatchService 监听；扫到的资源通过 WebSocket 推到前端
- **但 MinIO bucket `mnemoscape-assets` 当前是空的**（项目交付时只 `mc mb` 桶但没 `mc cp` 数据）

**远端 MinIO 位置**：[.env.workpc:50-52](file:///m:\Study\ProjectTest\Mnemoscape\backend\.env.workpc#L50-L52) `MINIO_ENDPOINT=http://100.66.166.46:9000`，access/secret = `minioadmin/minioadmin123`。本机可直连（不需 SSH）。

**资源缺失分类**：
| 类别 | 现状 | 缺口 |
|---|---|---|
| 插画 / 场景图 (photo) | 15 张 | 至少 30+ 张，含节气 / 抽象 / 极简 |
| 视频氛围层 (video) | 10 段 | 至少 12 段 |
| 音频 (audio) | 12 段 | 至少 20 段（多语种白噪 / 主题 BGM） |
| 表情包 GIF (gif) | 2 张 | 至少 30 张中文热词 |
| ICON (icon/icons) | 0 张 | 至少 100 个工具 / 导航 / 状态图标（SVG） |
| 颜文字 / 表情字符 | 在 ChatView 硬编码 16 个 | 至少 200 个，分主题 |

### 1.4 git 当前状态

```
M backend/.env.workpc
M backend/ai-service/...（约 12 个 .java + 2 个 test + yml）
M backend/api-gateway/...
M backend/asset-service/...
M backend/auth-service/...
M backend/memory-service/...
（未显示完整，但 git status 显示大量 M 与 ?）
```

**最后 commit**：`80197fc fix: memory builder validation, geocoding, location picker, premium three.js, admin maintenance`

**这意味着**：
1. 上次会话所有"已完成"的改动都未提交 — 包括 emotion-summary / Milvus 真实统计 / 共鸣池真实平均分等
2. 需要先把所有 M 状态 + 新增 untracked 文件 → 1 个备份 commit，然后才能动

### 1.5 中间件运行状态

按 user_profile.md / project_memory.md：
- 中间件跑在 **workpc**（Windows 10.66.166.46），包括 nacos / postgres / neo4j / rabbitmq / minio
- 前端/后端跑在**本地** (本会话)
- 本地 docker 当前未启动 → 中间件是从 workpc 远程访问

---

## 2. 拟定的改动（Proposed Changes）

### 2.1 菜单栏英文溢出修复 — `.app-header.vue` CSS-only

**改动文件**：[/frontend/src/components/layout/AppHeader.vue](file:///m:\Study\ProjectTest\Mnemoscape\frontend\src\components\layout\AppHeader.vue) — 仅 `<style scoped>` 段

**4 个根因 & 对应修复**：

1. **品牌名断字 "Mnemoscap / e"** → 在 `.brand__copy` 与 `.brand__copy strong` 加：
   ```css
   white-space: nowrap;
   overflow: hidden;
   text-overflow: ellipsis;
   max-width: 200px;
   ```
   并把 1440px 断点的 `display: none` 推迟到 1280px（让 1280-1440px 还能看到完整 brand）

2. **"Chat & Discovery" 截断为 "Chat & Disc"** → 三条策略叠加：
   - `.app-nav` 增加 `overflow-x: auto; scrollbar-width: thin;`（横向滚动条，让用户能滚到看不见的部分）
   - 1280-1440px 区段：把 `nav.chat` 的 `span` 文字长度强制 ≤ 8 字符（`max-width: 8ch; overflow: hidden; text-overflow: ellipsis;`）
   - 新增 `i18n.nav.chat` 翻译缩短为 `"Chat"`（英文模式），保留中文模式 `"聊天 & 发现"`，避免英文过长

3. **"Memory graph" 完整英文** → 在 1280-1440px 区段，`nav.graph / timeline / atlas / resonance` 文字也加 `max-width: 8ch; overflow: hidden; text-overflow: ellipsis;`

4. **整体 nav 在 1280px 之前**沿用现有 `≤1200px` icon-only 策略，**不动**。新增：
   - 1280-1440px：brand 文字 + nav 文字（限宽 + 省略号）+ locale + user-chip
   - ≤1280px：brand 仅图标 + nav 文字（限宽）+ locale + user-chip-meta
   - ≤1200px：brand 仅图标 + nav icon-only + locale + user-chip-meta

**预计改动量**：CSS ~40 行；en-US.json 改 `nav.chat` = `"Chat"`、`nav.graph` = `"Memory graph"` 已有可不动。

---

### 2.2 AI 工具大扩 — 4 大类共 11 个新工具 + 智能路由

**改动文件**：

1. [/backend/ai-service/src/main/java/com/mnemoscape/ai/service/ToolRegistry.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ToolRegistry.java) — 在 `registerBuiltinTools()` 注册 11 个新工具
2. 新建 [/backend/ai-service/src/main/java/com/mnemoscape/ai/tools/builtin/](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\tools) — 11 个新工具的具体实现（每个 1 个 .java，便于审计）
3. [/backend/ai-service/src/main/java/com/mnemoscape/ai/service/ChatReasoner.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java) — 改 `isGreetingOrTooShort` 为 `shouldSkipRAG(question)`（更智能），扩 system prompt 引导模型在新工具上用

**11 个新工具**（按用户 4 选区组织）：

| 工具名 | 类别 | args | 输出 |
|---|---|---|---|
| `currentDateTime` | 基础 | `{}` / `{tz: "Asia/Shanghai"}` | `{now, tz, weekday, weekOfYear, iso}` |
| `getWeather` | 基础 | `{city}` 或 `{lng, lat}` | `{temp, desc, humidity, wind, source}`（先 mock 一周后接真 API） |
| `calculator` | 基础 | `{expr: "12*34+5"}` | `{result, steps}`（用 `exp4j` 或自己写表达式 parser，避免把 eval 暴露给模型） |
| `geocode` | 基础 | `{address}` / `{lng, lat}` | `{address, lng, lat, country, city, district}`（调高德 / Nominatim，本地 mock） |
| `getFriends` | 项目 | `{userId, onlineOnly?}` | `{friends: [...], count}`（调 friend-service） |
| `getUnreadNotifications` | 项目 | `{userId}` | `{notifications: [...], count}`（调 notification-service） |
| `getMemoryStats` | 项目 | `{}` | `{total, byPrivacy, byYear, withCoords}`（与 `memoryStatsTool` 类似但直接调 memory-service） |
| `getResonanceFeed` | 项目 | `{limit}` | `{items: [...], avgScore}`（调 resonance-service） |
| `summarizeConversation` | 对话辅助 | `{messages: [...]}` | `{summary, keyPoints, emotions}`（复用 EmotionAnalysisTool 思想） |
| `regenerateLastAnswer` | 对话辅助 | `{userId, lastQuestion}` | `{candidates: [...]}`（让 AI 给自己 3 个不同风格的备选） |
| `listChatHistory` | 对话辅助 | `{userId, page, size}` | `{items: [...], total}`（调 chat-service / memory-service） |

**智能路由（[ChatReasoner.shouldSkipRAG()](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java#L541-L556) 升级）**：

```java
private boolean shouldSkipRAG(String question) {
    if (isGreetingOrTooShort(question)) return true;  // 现有白名单
    String low = question.toLowerCase(Locale.ROOT).trim();
    // 1) 触发日期/时间/天气/算术/翻译/百科/自我介绍的词 → 走工具不查库
    String[] toolSignals = {
        "今天", "几号", "日期", "时间", "现在几点", "星期几", "几月",
        "天气", "气温", "下雨", "下雪",
        "算", "等于多少", "百分之", "汇率", "+", "−", "×", "÷", "是多少",
        "在哪个国家", "首都是", "经纬度", "海拔",
        "你是谁", "你能做什么", "how to", "what is", "what's",
        "today", "date", "time", "weather", "temperature",
        "calculate", "convert", "translate", "define"
    };
    for (String s : toolSignals) if (low.contains(s.toLowerCase(Locale.ROOT))) return true;
    // 2) 问的是"我"的记忆才走 RAG
    if (!low.contains("我") && !low.contains("my ") && !low.contains("remember")) return true;
    return false;
}
```

**System Prompt 更新**（[ChatReasoner.java:STREAMING_SYSTEM_PROMPT](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java#L44-L71) 第 4 条）：

把"可用工具"从 6 个扩到 17 个，并在第 2 条加：
> "如果用户问今天日期/时间/天气/算术/翻译等通用问题，**先调 currentDateTime / getWeather / calculator / geocode**，不要瞎编；这些工具的输出是 ground truth。"

**ReAct 协议提示**（[REACT_PROTOCOL_PROMPT](file:///m:\Study\ProjectTest\Mnemoscape\backend\ai-service\src\main\java\com\mnemoscape\ai\service\ChatReasoner.java#L79-L95)）末尾增加：
> "通用类问题（日期/天气/算术/翻译/百科）必须用工具，禁止凭训练知识回答。"

**降级策略**：所有新工具在底层异常时返回 `Map.of("error", "...", "tool", name())`，不抛异常 — 与现有 6 工具一致；前端 ReAct UI 可正常显示"工具失败"。

---

### 2.3 静态资源 → 远端 MinIO

**目标 MinIO**：`http://100.66.166.46:9000` (workpc)
**Bucket**：`mnemoscape-assets`
**扩展顶层目录白名单**（[StorageProperties.java](file:///m:\Study\ProjectTest\Mnemoscape\backend\asset-service\src\main\java\com\mnemoscape\asset\config\StorageProperties.java#L25-L26)）：
```java
public static final Set<String> PUBLIC_TOP_LEVEL_DIRS = Set.of(
    "photo", "video", "audio", "gif", "music", "icon", "icons",
    "sticker",  // 新增：颜文字字符
    "kaomoji",  // 新增：颜文字 art
    "emoji",    // 新增：unicode 表情集
    "avatar",   // 新增：用户默认头像池
    "theme"     // 新增：主题背景图（轮询用）
);
```

**资源来源**（4 选全选）：

1. **AI 生成（byted-seedream）项目主题插画** — 30 张
   - 主题清单：共鸣之桥（重制版）/ 冬至初雪 / 谷雨春耕 / 立夏蝉鸣 / 霜降月色 / 大寒炉火 / 极光夜 / 霓虹海 / 黄昏列车 / 茶烟袅袅 / 旧书页 / 街角灯 / 雾林 / 雨巷 / 雪山远眺 / 银河浴 / 流星雨 / 萤火仲夏 / 纸鸢 / 木屋 / 沙漠星轨 / 潮汐 / 时间沙漏 / 记忆之海 / 旧磁带 / 玻璃瓶船 / 风铃 / 古巷 / 烟花 / 帆影
   - 全部 `1024x1024` WebP / JPG，存 `photo/` 下

2. **CC0 资源（国际素材）** — 20 段视频 + 30 个 ICON
   - 视频：Pixabay 的 `river / ocean / forest / city-rain / snow / aurora / space` 主题
   - ICON：Lucide / Heroicons / Tabler Icons 的 CC0 SVG 资源 30 个

3. **中文热门表情包 GIF** — 30 张
   - 主题：`yyds` / `绝绝子` / `emo 了` / `锦鲤` / `打 call` / `真香` / `我太难了` / `裂开` / `好家伙` / `芭比 Q 了` / `退退退` / `栓 Q` / `元宇宙` / `显眼包` / `city 不 city` / `班味` / `搭子` / `哈基米` / `I 人 / E 人` / `多巴胺` / `精神状态` / `显眼` / `嘴替` / `神仙 / 妖怪` / `儿时记忆` / `古早味` / `显眼包` / `丧` / `反差萌` / `古早 OS`
   - 来源：tenor.com / giphy.com（CC-BY）或自制 SVG → GIF 动效
   - 存 `gif/` 下

4. **颜文字 / 表情字符** — 200 个 → 资源化
   - 写一个 [frontend/src/assets/kaomoji-catalog.ts](file:///m:\Study\ProjectTest\Mnemoscape\frontend\src\assets\media-catalog.ts) 风格的 catalog
   - 字段：`{ glyph, name, category, origin }`
   - 分类：`(>_<)/ · ¯\\\_(ツ)_/¯ · ʕ•ᴥ•ʔ · (╯°□°)╯ · (¬‿¬) · ಠ_ಠ · ( ˘ᵕ˘ ) · (⊙_☉) · 等等`
   - 同步把现有 16 个 emoji 扩展到 200+（含分类）
   - 在 ChatView.vue 的 EMOJI_LIST 用 catalog 替代硬编码数组

**同步机制**（重点：不写新框架，扩展现有的）：

- 写一个一次性脚本 [/scripts/sync-assets-to-minio.ps1](file:///m:\Study\ProjectTest\Mnemoscape\scripts)：
  - 输入：`--src <local-dir> --bucket mnemoscape-assets --endpoint http://100.66.166.46:9000 --ak minioadmin --sk minioadmin123`
  - 行为：递归扫描本地目录 → 用 `mc cp`（如果 workpc 装了）或 `MinioClient Java SDK` 把文件传到对应 `bucket/{top-dir}/...`
  - 已存在的同名 + 同 size → 跳过
  - 完成后输出"已上传 N 个，跳过 M 个，失败 K 个"
- 写一个 Java Spring Batch 风格 CLI（[scripts/AssetUploader.java](file:///m:\Study\ProjectTest\Mnemoscape\backend)）：asset-service 启动时如果传 `--sync-assets` 参数就触发一次（复用 LocalResourceWatcher 的解析路径）
- 在 [LocalResourceWatcher](file:///m:\Study\ProjectTest\Mnemoscape\backend\asset-service\src\main\java\com\mnemoscape\asset\service\LocalResourceWatcher.java) 增加 `triggerMinioResync()`：手动从 admin 后台调

**预计改动量**：
- StorageProperties.java 改 1 行
- 新增 PowerShell 同步脚本 ~80 行
- 新增 kaomoji-catalog.ts ~300 行（含 200+ 数据）
- 新增 ChatView.vue 修改（用 catalog）~10 行
- 静态资源文件（实际图片/视频）由 byted-seedream 在执行阶段生成并直接上传

---

### 2.4 Git 备份 + 关闭前后端（执行前置）

**操作顺序**（在所有改动**之前**）：

```powershell
# 1) 提交现状作为 backup commit
cd m:\Study\ProjectTest\Mnemoscape
git add -A
git status  # 确认无遗漏
git commit -m "backup: v7 stable (Nacos fix, Milvus real stats, resonance real avg, emotion-summary, 16 weather fx, memory star hit fix, en overflow CSS) — pre-v8 overhaul"

# 2) 创建 v8 分支
git checkout -b feature/v8-ux-ai-resource

# 3) 在 v8 分支上做 2.1 / 2.2 / 2.3 改动
# 4) 改完后：
#    a) 关闭所有 java 进程
#    b) 关闭 vite dev / build 进程
#    c) 保留中间件（workpc 远程运行，不动）
#    d) 不自动 git commit，让用户审阅
```

**关闭前后端**：
```powershell
# 关闭 java
Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force
# 关闭 node (vite dev)
Get-Process -Name node -ErrorAction SilentlyContinue | Where-Object { $_.MainModule.FileName -like "*node*" } | ForEach-Object {
  Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $_.Id -and $_.LocalPort -in @(5173, 4173) } | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
}
# 保留 Docker 中间件：不动（中间件跑在 workpc 上，本机 docker 已停）
```

**给用户的报告**：
- 改动文件清单
- 重启指引（一行 PowerShell：`Start-LocalDevServices.ps1` + `npm run dev`）
- 自测要点

---

## 3. 假设 & 决定（Assumptions & Decisions）

| # | 假设 | 备注 |
|---|---|---|
| A1 | MinIO 100.66.166.46:9000 当前可达 | 用 `Test-NetConnection` 验证 |
| A2 | 用户接受增加 11 个新工具（4 类全选） | 已 AskUserQuestion 确认 |
| A3 | 用户接受资源来源 4 选全选 | 已 AskUserQuestion 确认 |
| A4 | 改动完成后由用户自测，不自动启动服务 | 计划中已明确 |
| A5 | 保留现有 ReAct 协议 + ToolRegistry 架构 | 不重塑框架 |
| A6 | byted-seedream 可生成 30 张 WebP（每次约 15-30s） | 预计 8-15 分钟完成全部图 |
| A7 | 中文表情包 GIF 来自 tenor/giphy CC-BY 或自制 SVG→GIF | 优先自制避免版权 |
| A8 | 用户电脑有 PowerShell + .NET 5+（mc CLI 可选） | 若无 mc CLI，走 Minio Java SDK |
| A9 | NVIDIA_API_KEY 在 .env.workpc 已配置（`nvapi-BCuT...`） | 已有，不需要改 |
| A10 | 默认 chat model `google/gemma-3n-e2b-it` 不动 | 模型问题不在本次改造范围 |

---

## 4. 验证步骤（Verification）

### 4.1 菜单栏修复
1. `npm run dev` 起前端（user 自测时执行）
2. 浏览器 DevTools 切到 1280 / 1440 / 1920 三种宽度
3. 检查 brand 文字完整 + nav 7 个 link 不换行 + Chat 完整或带省略号
4. 切中/英两语种各看一次

### 4.2 AI 工具
1. 重启 ai-service
2. 浏览器调 chat："今天几月几号？" → 应返回真实日期（`currentDateTime`）
3. 浏览器调 chat："北京天气" → 应返回 mock 天气
4. 浏览器调 chat："12*34+56 等于多少" → 应返回 464（`calculator`）
5. 浏览器调 chat："我去年 7 月去了哪里" → 触发 RAG + milvusSearchTool
6. ReAct UI 在所有 5 步中显示工具名（齿轮 → ✓）

### 4.3 静态资源
1. `pwsh scripts/sync-assets-to-minio.ps1 -Src ./resource -Bucket mnemoscape-assets`
2. 浏览器访问 `http://100.66.166.46:9000/mnemoscape-assets/`（用 mc / 控制台）
3. 后端日志看到 `LocalResourceWatcher: scan complete, n resources ready`
4. 前端 `media-catalog.ts` 列出 ≥ 30 张 photo
5. ChatView 的 emoji picker 展示 ≥ 200 个 emoji

### 4.4 整体回归
1. 用 `mnemo_user_001 / Mnemo@2026#Seed` 登录
2. 6 个微服务都健康（GET /api/v1/resonances/stats → 200）
3. 创建一条记忆 → 立即用 Milvus 搜索能找到
4. AI 聊天 + ReAct → 工具调用正常
5. 共鸣池 → 真实平均分（应 > 0.6）

---

## 5. 风险 & 缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| byted-seedream API 限流 | 中 | 每张 30s 间隔；失败重试 2 次；记录失败清单用户后续补 |
| MinIO 远端不通 | 低 | Test-NetConnection 探活；不通则降级到 local fs + 标记 |
| 新工具注入导致 ReAct 循环超 6 轮 | 中 | 已在 ReActController 有 hard cap 6；新工具无状态 |
| emoji/kaomoji catalog 影响 ChatView 性能 | 低 | 200 个 < 50KB JS，不影响 |
| 11 个新工具的 args 解析对 Gemma-3N 来说太复杂 | 中 | prompt 中提供每个工具的 1 行示例 + 默认值 |
| 改 CSS 不慎破坏现有响应式 | 中 | 改完后跑 Playwright 多宽度截图回归 |

---

## 6. 执行顺序（实施时严格按序）

```
Step 0: git 提交 + 分支          (3 min)
Step 1: 菜单栏 CSS 修复          (10 min)
Step 2: 11 个新工具实现           (90 min)
Step 3: ChatReasoner 智能路由 + system prompt 扩 (30 min)
Step 4: 静态资源目录白名单扩展    (5 min)
Step 5: byted-seedream 生成 30 张 photo  (15-25 min)
Step 6: 下载 CC0 视频 / SVG icon  (20-30 min)
Step 7: 写 kaomoji-catalog.ts     (20 min)
Step 8: 同步 PowerShell 脚本      (15 min)
Step 9: 跑资源同步到 MinIO        (5 min)
Step 10: 改 ChatView 用 catalog   (10 min)
Step 11: 编译/构建验证            (5 min)
Step 12: 关闭前后端，留中间件      (2 min)
Step 13: 给用户报告                (5 min)
```

总计：~3.5 - 4.5 小时（其中 Step 5-9 主要等 AI 生成 / 下载时间）

---

## 7. 不在本次范围

- 重塑前端框架（仍 Vue 3 + Vite + Pinia）
- 改 Nacos 配置 / Sentinel / Seata
- 改 Spring AI / Spring Cloud Alibaba 版本
- 改向量数据库（Milvus 仍用）
- 改 chat LLM 模型
- 迁移 Docker Compose 到 K8s
- 改 GraphQL

---

**Plan ready. 请审阅后批准执行。**
