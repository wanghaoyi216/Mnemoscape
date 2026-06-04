# Mnemoscape 极简·深黑调 艺术化重塑 Spec

> **状态：2026-06-04 已回退**
>
> 本 spec 已在 E4 用户验收阶段被回退。理由：用户认为本次重塑"太难看"，要求回退到修改前的方案。
>
> - 已被 `git checkout HEAD --` 还原的文件：index.html / App.vue / main.ts / router/index.ts / style.css / AiMascotDock.vue / ChatView.vue / MemoryAtlasView.vue / ResonanceHubView.vue
> - 已删除的新增文件：src/views/HomeView.vue / src/design/* / src/composables/useReveal.ts / src/assets/illustrations/* / src/assets/icons/* / src/assets/noise.png / public/atmosphere/* / scripts/* / run-preview.cmd / dist/* / preview*.log
> - 截图 `__screenshots__/` 已删除
> - 保留：`spec.md` / `tasks.md` / `checklist.md` / `findings-e3.md`（作为历史记录）
> - 未触动：上一轮 spec（ai-asset-quality-round3）落地的所有文件

## Why

Mnemoscape 是一个 AI 记忆产品（用户与系统共同构建"记忆星系"），但当前前端视觉停留在"功能能跑"水平：
- 字体：默认 `Inter` / `system-ui`，无字距、字重节奏
- 色：紫蓝渐变 + 玻璃叠层 = "通用 AI 模板"
- 动效：散点微动效、无节奏、无 staggered reveals
- 布局：12 列等距 + 卡片栅格，缺"破格"与负空间
- 资源：单张 hero.png + vite.svg，无主题插画、无氛围视频

**目标**：把前端提升到"走进屏幕时停一下"的艺术级品质，与"AI 记忆"主题相契，但不堆砌。

## What Changes

- **设计系统 v1**：建立 design tokens（颜色 / 字体 / 间距 / 动效 / 阴影 / 模糊），抽出 `frontend/src/design/` 全局可复用
- **4 个核心页面艺术化重塑**：Home / MemoryAtlas / ChatView / ResonanceHub
- **AI Mascot Dock 重设计**：从右下角浮窗 → 左侧抽屉式 AI 共生体
- **静态资源**：
  - 5 张主题插画（生成）：hero / atmosphere / memory-fragment / resonate-burst / mascot-portrait
  - 1 段氛围视频（生成）：6s loop 暗调粒子流
  - Icon 集：lucide + 8 个定制 SVG（星轨/记忆碎片/共鸣波/守护灵/情绪云/月相）
- **动效系统**：
  - 进场：staggered fade + Y-offset（45ms / 90ms / 140ms 三层）
  - 鼠标：3D tilt + magnetic hover（仅主 CTA）
  - 滚动：IntersectionObserver 触发 reveal（threshold 0.18）
  - 切换：view transition API（fallback opacity 0.4s）

## Impact

- Affected specs: 前端视觉系统 / 4 个核心视图 / AI dock
- Affected code:
  - `frontend/src/style.css` (token 化重写)
  - `frontend/src/App.vue` (全局 layout shell)
  - `frontend/src/views/HomeView.vue` (待新建，整合 ChatView 为入口)
  - `frontend/src/views/MemoryAtlasView.vue`
  - `frontend/src/views/ChatView.vue` (页面版，与 AI Dock 协同)
  - `frontend/src/views/ResonanceHubView.vue`
  - `frontend/src/components/ai/AiMascotDock.vue` (重设计)
  - `frontend/src/components/layout/AmbientFilmStrip.vue` / `WeatherFxOverlay.vue` (降级为可选 ambient layer)
  - `frontend/src/design/tokens.css` (新)
  - `frontend/src/design/motion.css` (新)
  - `frontend/src/assets/hero/` / `frontend/public/atmosphere/` (生成后导入)

## Design Direction（已锁）

**调性**：极简 · 深黑调
**色彩**：
- Bg base: `#0A0A0B` (近黑带蓝)
- Bg elev: `#111114` (卡片底)
- Bg glow: `#1A1A20` (hover 态)
- Surface: `#202028` (边框/分割)
- Ink: `#E6E2DA` (主文, 米白) / `#A0A0A8` (次文) / `#5C5C66` (三文)
- Accent: `#FF5C2C` (暖橙) / `#7B9CFF` (冷蓝辅) / `#C7F26B` (记忆绿)
- 错误: `#FF3D5A`, 成功: `#3DD68C`

**字体**：
- Display: `Fraunces` (variable serif, weight 100-900, optical sizing) — 走 AI 记忆主题的"印刷书页感"
- Body: `Geist` (variable grotesque, weight 100-900)
- Mono: `JetBrains Mono` (装饰: 0x01D 标识符 / 序号)
- 中文: `Source Han Serif` (思源宋体 CN, for Chinese display) + `PingFang SC` fallback
- 字号：12 / 14 / 16 / 18 / 22 / 28 / 36 / 48 / 64 / 96，line-height 1.1-1.6
- 字距：display 用 `letter-spacing: -0.04em`；body 用 `-0.01em`；caps 用 `0.18em`

**布局**：
- 12 列等宽 84px gutter 24px；主内容 max-width 1440px
- Hero 区允许内容溢出网格（display 字号 96px 跨越 2-3 列）
- 段落最大 64ch 字符宽（防宽屏断行）
- Z 层：bg / ambient / content / overlay / modal / toast

**动效**：
- 缓动：`--ease-out-quint: cubic-bezier(0.22, 1, 0.36, 1)`，默认 0.6s
- 微动：0.18s hover 反馈
- 节奏：staggered children 45ms 间隔
- 滚动：reveal animation once，0.6s

**装饰纹理**：
- 噪点叠层：0.04 透明度的 `noise.png` 256x256 平铺
- 玻璃：`backdrop-filter: blur(20px) saturate(140%)` + `border: 1px solid rgba(255,255,255,0.06)`
- 渐变光晕：radial-gradient 800px 半径，0.06 透明度，营造"记忆浮出"质感

## ADDED Requirements

### Requirement: 全局 design tokens

系统 SHALL 在 `frontend/src/design/tokens.css` 暴露全部 CSS 变量，所有颜色 / 字号 / 间距 / 阴影 / 模糊 / 动效缓动必须引用 token，禁止硬编码。

#### Scenario: 新增页面需要调色
- **WHEN** 任何 .vue 文件出现 `#xxx` 色值
- **THEN** 应替换为 `var(--ink-*)` / `var(--accent-*)` 等
- **AND** 通过 `grep -rE '#[0-9A-Fa-f]{3,6}\b' frontend/src/views` 仅剩 token 文件和特殊场景

### Requirement: MemoryAtlas 主题视觉

`MemoryAtlasView` SHALL：
- 暗背景 0A0A0B + Fraunces Display 标题「The Memory Atlas」96px，字距 -0.04em
- 顶部 hero strip 跨 12 列（视频循环 6s 暗调粒子流）
- 主图区采用极简 3D 星空 + 200 个静态点 + IntersectionObserver 触发"记忆浮现"动画
- 状态栏 mono 字号，3xl 标识符
- 过滤器栏：minimal chip 风格（无边，仅下划线 + 暖橙 highlight）

### Requirement: ChatView 重设计

`ChatView` SHALL：
- 满屏沉浸（z 层级 modal-1 之上），无 header 干扰
- AI 消息：左对齐 + 米白 text + 灰色 quote block（左侧 2px 暖橙线）
- 用户消息：右对齐 + 18px medium，em-dash 引导符
- 思维流（来自 task C）：`💭 思考：…` 灰色斜体 + 左侧 2px 冷蓝线
- 工具调用：mono 字 + 「tool: milvusSearchTool | ⏱ 0.34s」行
- 输入框：底部 fixed 48px 高度，placeholder 用 Serif italic 灰度 50%

### Requirement: ResonanceHub 重设计

`ResonanceHubView` SHALL：
- Hero：左 6 列放置大标题「Find Your Echo」+ 副标「When your memory meets someone else's.」
- 右 6 列：搜索框（特大，72px 高度，serif italic placeholder）
- 指标卡：3 张只显示数值 + 极小 label（uppercase 0.18em tracking + 11px mono），无圆角无边框，靠负空间分隔
- 结果列表：行式（不卡片化），左侧 6px 暖橙 vertical bar，分数显示 mono
- 整体不要"卡片"，要"杂志"

### Requirement: AI Mascot Dock 重设计

`AiMascotDock` SHALL：
- 位置：右下角 → 左下角抽屉（与 ChatView 的 24/80 锚定结合）
- 形态：6cm 直径的"记忆守护灵"圆形浮窗
- 状态：idle (静态 SVG + 缓慢呼吸动) / listening (声波环) / thinking (3 重旋转轨道) / speaking (光谱脉冲)
- 入口：从守护灵点击展开 380×520 的聊天卡片（背景：glass + noise overlay）
- 触发：长按守护灵 0.6s 唤起 quick-capture（草稿模式）
- 不再覆盖其他浮层（与场景视野冲突时降级为右下角 indicator）

### Requirement: 静态资源生成与集成

系统 SHALL 用 `byted-seedream-image-generate` 生成：
- `frontend/public/atmosphere/hero.mp4` （6s loop 暗调粒子流，由 seedance 生成）
- `frontend/public/atmosphere/hero-fallback.webp` （退化静态图，1080×1920 移动优先）
- `frontend/src/assets/illustrations/cover-warm.jpg` （暖色记忆封面插画 800×800）
- `frontend/src/assets/illustrations/cover-cold.jpg` （冷色记忆封面 800×800）
- `frontend/src/assets/illustrations/cover-mystic.jpg` （神秘渐变 800×800）
- `frontend/src/assets/icons/` 下 8 个定制 SVG（星轨 / 碎片 / 共鸣 / 守护 / 情绪 / 月相 / 锚 / 流光）

所有资源 SHALL 走 Vite 静态引用，体积 > 500KB 时必须加 `<link rel="preload">` 优化。

### Requirement: 动效系统

系统 SHALL 在 `frontend/src/design/motion.css` 暴露：
- `.reveal`（进场：opacity 0→1, translateY 24px→0, 0.6s quint-out）
- `.reveal-stagger > *`（children 45ms 间隔触发）
- `.magnetic`（3D tilt on pointermove, max 6°）
- `.ambient-pulse`（6s 无限呼吸：scale 1.00→1.02）
- `.shimmer`（背景渐变扫光 2.4s loop，仅加载状态）

#### Scenario: 进入 MemoryAtlas
- **WHEN** 路由到 `/atlas`
- **THEN** 页面元素按 hero → 标题 → 工具栏 → 网格 staggered 进场（0ms / 90ms / 180ms / 270ms）
- **AND** hero 视频在第二段加载时显示 1.4s 的 shimmer skeleton

## MODIFIED Requirements

### Requirement: App.vue 全局 layout

`App.vue` SHALL：
- 顶部 64px 高度 header（`AppHeader`），磨砂玻璃背景
- 主内容 `<RouterView v-slot="{ Component }">` 用 `<Transition name="fade" mode="out-in">`
- 全局噪点叠层（`<div class="noise-overlay">` z=2, opacity 0.04, pointer-events:none）
- 暗色 baseline 强制（`<html data-theme="dark">`，即使后续切浅色也走 token）

### Requirement: AmbientFilmStrip / WeatherFxOverlay 降级

这两个组件 SHOULD 在 MemoryAtlas/ChatView 中默认 disabled（不引入额外粒子），避免与新的设计语言冲突。ResonanceHub 可保留 WeatherFxOverlay 作 ambient backdrop。

## REMOVED Requirements

### Requirement: 旧 hero.png

**Reason**：`frontend/src/assets/hero.png` 是 v1 单张静态图，与新主题不匹配。
**Migration**：删除原文件，引用新生成的 `frontend/public/atmosphere/hero-fallback.webp`。

### Requirement: vue.svg / vite.svg

**Reason**：v1 模板残留，不在新设计系统中。
**Migration**：从 `frontend/src/assets/` 移除；`App.vue` favicon 改用生成的 mascot-portrait SVG。

## 资源生成清单（byted-seedream + byted-seedance 调用）

- Seedream prompt 1 (warm cover): `minimal cinematic memory fragment, warm amber and deep black, single floating shard with subtle glow rays, negative space, editorial poster style, ultra high contrast, 8k --aspect 1:1`
- Seedream prompt 2 (cold cover): `minimal cinematic memory fragment, cool blue and deep black, geometric ice crystal suspended in dark void, neon edge highlight, editorial poster, 8k --aspect 1:1`
- Seedream prompt 3 (mystic cover): `mystic gradient from deep violet to amber, single glowing orb, dust particles, editorial poster, ultra minimal, 8k --aspect 1:1`
- Seedream prompt 4 (mascot portrait): `stylized guardian spirit silhouette, soft round form, glowing eyes, minimal linework, dark background with amber rim light, character concept art, 8k --aspect 1:1`
- Seedream prompt 5 (icon set base): `set of 8 minimal icons, star orbit, memory shard, echo wave, guardian, emotion cloud, moon phase, anchor, flowing light, monochrome amber on black, line icons, consistent stroke width 2px --aspect 1:1`
- Seedance prompt (hero video): `subtle 6s loop of warm dust particles drifting through deep black void, slow camera push-in, ambient cinematic grain, seamless loop, no text --duration 6 --ratio 16:9 --quality pro`

所有生成资源最终落到 `frontend/public/atmosphere/` 和 `frontend/src/assets/illustrations/` 目录。
