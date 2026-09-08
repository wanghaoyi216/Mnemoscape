# Tasks

## Task 1：环境与来源盘点
- [x] 1.1 确认 `resource/photo/`、`video/`、`gif/` 现有文件清单（避免 slug 冲突）
- [x] 1.2 确认 `resource/icon/` 是否存在；不存在则 `mkdir -p`
- [x] 1.3 确认 `ASSET_SOURCES.md` 现有表格行数与格式

## Task 2：CC0 资源池搜索与下载工具准备
- [x] 2.1 准备 `Invoke-WebRequest` (PowerShell) 下载脚本，统一命名 + 错误重试 + 哈希去重
- [x] 2.2 为每组（A/B/C/D）准备"主题 + 风格关键词"清单
- [x] 2.3 准备临时目录 `resource/.tmp_dl/` 用于未校验素材暂存

## Task 3：Group A — 记忆封面（24~30 张）
- [x] 3.1 `mem-warm-01~04`（4 张）：3 张 SVG + 1 张 Unsplash（林间暖光柱）
- [x] 3.2 `mem-melancholy-01~04`（4 张）：3 张 SVG + 1 张 Unsplash（雾中山岭）
- [x] 3.3 `mem-joy-01~04`（4 张）：4 张 SVG 全部生成（春日暖光 bokeh）
- [x] 3.4 `mem-serene-01~04`（4 张）：3 张 SVG + 1 张 Unsplash（晨山）
- [x] 3.5 `mem-nostalgia-01~03`（3 张）：3 张 SVG 全部生成
- [x] 3.6 `mem-mystery-01~03`（3 张）：2 张 SVG + 1 张 Unsplash（云海日出）
- [x] 3.7 `mem-night-01~03`（3 张）：2 张 SVG + 1 张 Unsplash（星夜银河 / 极光）
- [x] 3.8 `mem-autumn-01~03`（3 张）：2 张 SVG + 1 张 Unsplash 复用（暖光柱图）

> 验收：5 张 Unsplash JPG 通过多模态识别（题材/风格/可用性/技术合规），未通过者已剔除；其余 22 张为程序化生成 SVG（暗调 + 渐变 + 颗粒 + 主题元素）。

## Task 4：Group B — 场景占位（6~8 张，全部 8 个 slug 必出）
- [x] 4.1 `scene-courtyard-summer`（暖色庭院 / 阳光石墙）— SVG
- [x] 4.2 `scene-snowy-dusk`（雪暮 / 路灯）— SVG
- [x] 4.3 `scene-rain-alley`（雨巷 / 霓虹反光）— SVG
- [x] 4.4 `scene-spring-field`（春日原野 / 远山）— SVG
- [x] 4.5 `scene-night-rooftop`（星空天台 / 城市 bokeh）— SVG
- [x] 4.6 `scene-autumn-corridor`（秋林长廊 / 透视隧道）— SVG
- [x] 4.7 `scene-misty-lake`（晨雾湖 / 倒影 / 薄荷）— Unsplash JPG
- [x] 4.8 `scene-desert-night`（沙漠银河 / 孤灯暖帐）— SVG

> 验收：全部 8 张 16:9 横构图、空间纵深、无文字水印、符合 spec 风格。

## Task 5：Group C — 氛围动图 / 短视频（2~3 个）
- [x] 5.1 **未新增**。`video/` 已有 10 个 .mp4（含 NASA 极光 / 星云潮汐 / 银河旋臂等），`gif/` 已有 2 个。
- [x] 5.2 已说明：按 `IMAGE-SPEC-NEEDED.md` 属"可选增强"且无 AI 文生视频 API key，留待后续。
- [x] 5.3 现有 12 个文件已能覆盖登录 / 详情背景场景。

## Task 6：Group D — SVG 图标（8~12 个）
- [x] 6.1 检索 CC0 / 开源线性图标集（Heroicons / Phosphor / Lucide 等 MIT 类）— 跳过（自写即可避免 license 风险）
- [x] 6.2 写 10 个：memory / lock / bottle / resonance / star-map / time / emotion / camera / mic / map
- [x] 6.3 全部 24×24 viewBox、`stroke` 1.6、`currentColor`、文件名 `icon-<topic>.svg`

## Task 7：多模态校验 + 落盘
- [x] 7.1 对 6 张 Unsplash JPG 用 IDE `Read` 多模态识别逐张确认
- [x] 7.2 通过 → 移到 `photo/` 并重命名为匹配 slug；失败 → 删除（如纯白云、亮麦田、撞色车牌）
- [x] 7.3 SVG 文件由代码模板生成，结构正确（XML / viewBox / stroke），通过生成脚本验证
- [x] 7.4 输出清单：5 张 Unsplash 落盘 + 22 张 SVG 记忆封面 + 7 张 SVG 场景 + 1 张 Unsplash 场景 + 10 个 SVG 图标

## Task 8：ASSET_SOURCES.md 同步
- [x] 8.1 按现有 4 列表格（Local file / Source / License / Intended use）追加所有新增素材
- [x] 8.2 "Source pages" 区域追加所有引用过的 URL（Unsplash photo IDs + 原始 NASA / Wikimedia）
- [x] 8.3 在文末追加"收集与校验说明"段落，记录采集路径、多模态校验过程、Group C 跳过原因

## Task 9：最终核查
- [x] 9.1 各组最终数量 ≥ 规格下限（28 张 mem / 8 张 scene / 10 个 icon）
- [x] 9.2 文件名符合 spec：全小写 + 连字符 + 语义英文 slug + 正确后缀
- [x] 9.3 与现有 photo/ 15 张中文命名 PNG 不重名

# Task Dependencies
- Task 1 → Task 2 → (Task 3, Task 4, Task 5, Task 6 并行) → Task 7 → Task 8 → Task 9
- Task 7 依赖 Task 3~6 的素材产出
- Task 9 依赖 Task 8
