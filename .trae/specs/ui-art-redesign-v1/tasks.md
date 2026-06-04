# Tasks

## 任务 A · 设计系统基础（必须先做）

- [x] **A1 创建 design tokens**
  - 新建 `frontend/src/design/tokens.css` 含色 / 字号 / 行高 / 字距 / 间距 / 阴影 / 模糊 / 动效缓动 / z-index
  - 改造 `frontend/src/style.css` 只保留 reset + `@import url('design/tokens.css')` + base 字体声明
  - 把 `Fraunces` / `Geist` / `JetBrains Mono` / `Source Han Serif` 通过 Google Fonts + 离线 woff2 引入
- [x] **A2 创建动效系统**
  - 新建 `frontend/src/design/motion.css` 含 `.reveal` / `.reveal-stagger` / `.magnetic` / `.ambient-pulse` / `.shimmer`
  - 加 IntersectionObserver composable：`frontend/src/composables/useReveal.ts`（threshold 0.18, unobserve after first reveal）
- [x] **A3 全局 noise overlay**
  - 改造 `frontend/src/App.vue` 引入 `<div class="noise-overlay">` z-index: 2 pointer-events: none
  - 在 `frontend/src/assets/noise.png` 放 256×256 单色噪点 PNG（用 seedream 生成 1×1 然后 canvas 放大）
- [x] **A4 路由 transition**
  - 改造 `<RouterView v-slot>` 用 `<Transition name="fade" mode="out-in">`
  - 加 CSS `.fade-enter-active/.fade-leave-active` 用 0.45s quint-out

## 任务 B · 静态资源生成（与 A 并行）

- [x] **B1 Seedream 5 张插画**
  - 调用 `byted-seedream-image-generate` 生成 5 张（warm cover / cold cover / mystic cover / mascot portrait / icon set base）
  - 落到 `frontend/public/atmosphere/` 和 `frontend/src/assets/illustrations/`
  - ⚠️ 工具未配置 ARK_API_KEY，已回退：使用 PIL 程序化生成（`frontend/scripts/generate_static_assets.py`）
  - 产物：cover-warm.png (803KB) / cover-cold.png (772KB) / cover-mystic.png (754KB) / mascot-portrait.png (294KB) / icon-set.png (5.8KB)
- [x] **B2 Seedance 1 段氛围视频**
  - 调用 `byted-seedance-video-generate` 生成 6s loop 暗调粒子流
  - 落到 `frontend/public/atmosphere/hero.mp4`
  - ⚠️ 工具未配置 ARK_API_KEY 且 ffmpeg 不可用，已回退：使用 OpenCV 程序化渲染（`frontend/scripts/generate_hero_video.py`）
  - 产物：hero.mp4 (3.2MB, 1280x720 @ 24fps, 6s) — 注：因 mp4v 无 H.264，分辨率从 1080p 降至 720p 以满足 < 5MB
- [x] **B3 Hero fallback 静态图**
  - 已生成 `frontend/public/atmosphere/hero-fallback.png`（1080x1920, 1.5MB）
  - 下一步：在 `frontend/index.html` 加 `<link rel="preload" as="video" href="/atmosphere/hero.mp4">`（属于 E 阶段联调）

## 任务 C · 4 个核心页面艺术化重塑（依赖 A+B）

- [x] **C1 MemoryAtlasView 重设计**
  - 删除原 5 个 hero 标题元素，加 Fraunces display 96px「The Memory Atlas」
  - 顶部嵌入 `<video autoplay loop muted playsinline>` 引用 hero.mp4
  - 200 个静态点背景：保留 3D 渲染，但降噪（少 50% particles）
  - 工具栏改 minimal chip（仅下划线 + 暖橙）
  - 加 reveal-stagger 入场（hero / title / toolbar / grid）
  - 删除/降级 `AmbientFilmStrip` 默认不挂载
  - v11：彻底删除 MapLibre / deck.gl / `useThreeScene` / `AmbientFilmStrip`，改为 CSS-only 编辑海报布局：60vh hero（左侧 7 列视频 + h1 破格溢出 1.5 列）+ 极简工具栏 + 4 列响应式 grid + IntersectionObserver 分页
- [x] **C2 ChatView 重设计**
  - 整页沉浸，删 header
  - 消息泡：AI 左对齐 + 米白 text；用户右对齐 + medium 18px
  - 思维流块：灰色斜体 + 左侧 2px 冷蓝线
  - 工具调用行：mono 字 + 「tool: xxx | ⏱ x.xxs」
  - 输入框：底部 fixed 80px，serif italic placeholder
  - 加 chat-message transition：每条新消息从底部 12px 上滑入
- [x] **C3 ResonanceHubView 重设计**
  - 标题改「Find Your Echo」+「When your memory meets someone else's.」
  - 搜索框：72px 高度，serif italic placeholder
  - 指标卡 3 张：去卡片化，uppercase tracking label + 大数字 mono
  - 结果行式化：左侧 6px 暖橙 vertical bar，分数 mono
  - 保留 `WeatherFxOverlay` 作 ambient backdrop
  - v11：杂志风排版，96px 标题、72px 搜索、3 指标条带带竖向分隔线、行式结果带 6px 暖橙 vertical bar
- [x] **C4 HomeView 新建**
  - 新建 `frontend/src/views/HomeView.vue`：替代 LoginView 之后的默认入口
  - 顶部 hero strip：左侧 60% 放视频 + 大标题；右侧 40% 放登录/注册 CTA 按钮
  - 中段：3 张分块介绍（守护 / 共鸣 / 情绪）各 96px 大数字 + 简短文案
  - 底部：精选手记预览（暖/冷/神秘 3 张 cover 插画）
  - 路由 `path: '/'` 指向 HomeView

## 任务 D · AI Mascot Dock 重设计

- [x] **D1 重写 AiMascotDock**
  - 移到左下角抽屉式
  - 6cm 直径的"守护灵"圆形浮窗（240px 直径，mascot-portrait.png fill）
  - 状态：idle（缓慢呼吸 6s）/ listening（声波环）/ thinking（3 重旋转轨道）/ speaking（光谱脉冲）
  - 入口：单击展开 380×520 玻璃卡片（向上 + 向右，距底 24px 距左 24px）
  - 长按 0.6s 唤起 quick-capture（280×80 mini-popover）
  - hover 浮窗：mascot-portrait scale 1.05，0.4s quint-out + 下方 8px "guardian" 标签淡入
  - 与 ChatView 共存：route.name === 'Chat' 时 Dock 退化为右下角 48px indicator
  - 保留所有现有 send / SSE / 多会话 / 附件 / thoughts 业务逻辑

## 任务 E · 验证

- [x] **E1 编译**
  - `cd frontend && npm run build` → 0 errors
  - `npm run dev` 启动后访问 `/` 看 hero
  - ✅ 实测：`npm run build` 2m40s，exit 0，仅 Rollup 警告（HeatmapView/Mermaid/Cytoscape/three 超过 500KB，非阻塞）
- [x] **E2 Playwright 视觉抓取**
  - 用 `webapp-testing` 启动浏览器抓取 4 个核心页面截图
  - 保存到 `frontend/__screenshots__/home.png` 等
  - ✅ 实测：preview server 127.0.0.1:4173 (PID 14724)，4 张截图已生成
    - home.png      3.5MB · 2880×5256 · bg 采样 (9,9,10) ≈ #0A0A0B ✓
    - atlas.png     2.2MB · 2880×3482 · bg 采样 (8,8,9)  ≈ #0A0A0B ✓
    - chat.png      345KB · 2880×1800 · bg 采样 (9,9,10) ≈ #0A0A0B ✓
    - resonance.png 267KB · 2880×2998 · bg 采样 (9,9,10) ≈ #0A0A0B ✓
  - ✅ **修复 E2 暗主题不生效**：发现 `:root[data-theme="dark"]` 选择器未被任何代码激活，
    截图全为白色。已在 `main.ts` 挂载前 `setAttribute('data-theme', 'dark')` 修复。
- [x] **E3 视觉审查**（基于 Vercel Web Interface Guidelines）
  - 详见本目录 [findings-e3.md](file:///m:/Study/ProjectTest/Mnemoscape/.trae/specs/ui-art-redesign-v1/findings-e3.md)
  - 5 项检查：无障碍 / 焦点 / 排版 / 配色对比 / 动效节奏
  - 9 项发现（3 高 + 3 中 + 3 低），前 3 项建议本轮修复
- [ ] **E4 用户验收**
  - 让用户看 4 张截图，确认符合"高大上"标准
  - 不通过就回到 C/D 继续调

# 任务依赖

- C1-C4 依赖 A1-A4 + B1-B3
- D1 依赖 A2 (motion) + B1 (mascot portrait)
- E2 依赖 C1-C4 + D1
- E3 依赖 E2

# 并行建议

- A 组（design system）和 B 组（资源生成）完全可并行
- C 组各页面：每个独立 vue 文件，可并行
- D 组与 C 组：不同文件，可并行
- E 组必须在 C+D 全部完成
