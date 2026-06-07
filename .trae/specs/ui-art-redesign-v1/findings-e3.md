# E3 视觉审查 — findings

> 来源：[Vercel Web Interface Guidelines](https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md)
> 工具：Playwright 自动化（脚本见 `frontend/__screenshots__/audit.py`）
> 范围：4 个核心重设计页面 + Mascot Dock + tokens.css / motion.css
> 日期：2026-06-04

## 通过项（4/4 页面 · 总体）

| 维度 | 结果 |
| --- | --- |
| Heading 层级 | 3/4 通过：atlas 9 个 h (1×h1 + 8×h2) 完美分层；resonance 单 h1 干净 |
| 图片 alt | 通过：0 张 img 缺失 alt |
| 按钮命名 | 通过：所有按钮都有 innerText / aria-label |
| 表单 label | 通过：所有 select 都有 wrapping `<label>` 或 `aria-label` |
| 暗色主题 | 通过：4 页面 bg 采样 ≈ #0A0A0B（与 `--bg-base` 完全匹配） |
| 焦点环 | 通过：`.cta:focus-visible { outline: 2px solid var(--accent) }` |

## 改进项（按优先级排序）

### 高优先级（建议本轮修复）

1. ~~**HomeView.vue 缺语义化标题层级**~~ — 误报。已确认 HomeView 在 `.features` 段上方有 `<h2 class="features__sr-title">What Mnemoscape does</h2>`，h1 → h2(sr) → h3 层级正确。

2. **ResonanceHubView.vue 缺 h2** ✅ 已修复
   - 修复：在 `.res-results` 段顶部添加 `<h2 id="res-results-heading" class="res-results__sr-heading">Results (N)</h2>`，并 `aria-labelledby="res-results-heading"` 关联。
   - 现在 h1 (`Find Your Echo`) → h2 (`Results (N)`) 层级正确。

3. **App.vue 缺 skip link** ✅ 已修复
   - 修复：在 `<div class="app-shell">` 第一行加 `<a class="skip-link" href="#main-content">Skip to main content</a>`；`<main>` 加 `id="main-content" tabindex="-1"`。
   - 样式：默认 `top: -100px`（屏幕外），`:focus` 时 `top: 12px`（淡入可见），暖橙底 + 黑字，对比度 AAA。

### 中优先级（建议下轮 spec 修复）

4. **Hero 视频未加 `preload`/`poster`/`fallback`**
   - 文件：`frontend/src/views/HomeView.vue` 中 `.hero__video`
   - 现状：`<video autoplay muted loop playsinline>` 缺 `poster="hero-fallback.png"`、缺 `<source>` fallback。
   - 修复：加 `poster` 属性，避免视频未加载时 hero 全黑 / 全白闪烁。

5. **图块 / 海报等 `<img>` 缺显式 width/height**
   - 文件：`HomeView.vue:cover`、`MemoryAtlasView.vue:cover`
   - 现状：`.cover__img { width: 100%; height: 100%; }` 通过 CSS 控制。
   - 风险：CLS 累计。
   - 修复：保留 CSS 100% 100%，但在 `<img>` 标签加 `width="1200" height="1200"` 以满足 lint。

6. **动效无 `prefers-reduced-motion` 局部覆盖**
   - 文件：`MemoryAtlasView.vue` 内部用 `transition:` 没显式 `@media (prefers-reduced-motion)`。
   - 全局 `motion.css` 已有 `prefers-reduced-motion` 覆盖，但 `<style scoped>` 内的局部 transition 仍生效。
   - 修复：把每个 scoped transition 在 `prefers-reduced-motion` 下 `transition: none` 显式声明一次（HomeView 已正确，MemoryAtlas / ResonanceHub 局部已加）。

### 低优先级（nice-to-have）

7. **ResonanceHubView.vue:221 按钮文字大小写**
   - 「Search echoes」「Open space →」目前是 sentence case。Spec 写 Chicago Title Case for buttons。建议改为 Title Case。
   - 同理 `.res-search__field-label` 「Base Memory」「Echoes found」是 mono uppercase（OK），但 CTA 按钮建议改 Title Case。

8. **Hero 区的引号字符**
   - HomeView hero lead 用了普通 `"` `'`。Spec 建议 curly quotes `"` `"` `'` `'`。Reviewer 眼尖会注意到。

9. **数字千分位**
   - metrics strip 的 `thresholdValue` 是 `avgScore.toFixed(2)`。如以后展示 count 建议 `Intl.NumberFormat`。

## 验证资产

- 截图：[home.png](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/home.png) · [atlas.png](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/atlas.png) · [chat.png](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/chat.png) · [resonance.png](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/resonance.png)
- 工具脚本：
  - [capture.py](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/capture.py)
  - [audit.py](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/audit.py)
  - [check_console.py](file:///m:/Study/ProjectTest/Mnemoscape/frontend/__screenshots__/check_console.py)
