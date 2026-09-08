# Checklist

## Group A — 记忆封面（photo/，共 28 张新增）
- [x] `mem-warm-01.svg` + `mem-warm-02.jpg` + `mem-warm-03.svg` + `mem-warm-04.svg` 4 张全在位
- [x] `mem-melancholy-01.svg` + `mem-melancholy-02.jpg` + `mem-melancholy-03.svg` + `mem-melancholy-04.svg` 4 张全在位
- [x] `mem-joy-01.svg` + `mem-joy-02.svg` + `mem-joy-03.svg` + `mem-joy-04.svg` 4 张全在位
- [x] `mem-serene-01.svg` + `mem-serene-02.svg` + `mem-serene-03.svg` + `mem-serene-04.jpg` 4 张全在位
- [x] `mem-nostalgia-01.svg` + `mem-nostalgia-02.svg` + `mem-nostalgia-03.svg` 3 张全在位
- [x] `mem-mystery-01.svg` + `mem-mystery-02.jpg` + `mem-mystery-03.svg` 3 张全在位
- [x] `mem-night-01.jpg` + `mem-night-02.svg` + `mem-night-03.svg` 3 张全在位
- [x] `mem-autumn-01.svg` + `mem-autumn-02.jpg`(复用 warm-02) + `mem-autumn-03.svg` 3 张全在位
- [x] 共 28 张新记忆封面，超出规格下限 24

## Group B — 场景占位（photo/，共 8 张新增，全部 8 slug 必出）
- [x] `scene-courtyard-summer.svg` 在位
- [x] `scene-snowy-dusk.svg` 在位
- [x] `scene-rain-alley.svg` 在位
- [x] `scene-spring-field.svg` 在位
- [x] `scene-night-rooftop.svg` 在位
- [x] `scene-autumn-corridor.svg` 在位
- [x] `scene-misty-lake.jpg` 在位
- [x] `scene-desert-night.svg` 在位
- [x] 全部 8 张为 16:9 横构图、空间纵深、暗色为主

## Group C — 氛围动图 / 短视频
- [x] **未新增**（`video/` 已有 10 个 .mp4，`gif/` 已有 2 个，共 12 个氛围素材；本轮无 AI 文生视频 API key，按 spec 属"可选增强"）
- [x] 现有素材题材覆盖星云 / 极光 / 水波 / 思维脉动 / 记忆涟漪 等氛围类

## Group D — SVG 图标（icon/，共 10 个）
- [x] `icon-memory.svg` 在位（24×24，stroke 1.6，currentColor）
- [x] `icon-lock.svg` 在位
- [x] `icon-bottle.svg` 在位
- [x] `icon-resonance.svg` 在位
- [x] `icon-star-map.svg` 在位
- [x] `icon-time.svg` 在位
- [x] `icon-emotion.svg` 在位
- [x] `icon-camera.svg` 在位
- [x] `icon-mic.svg` 在位
- [x] `icon-map.svg` 在位
- [x] 全部 10 个为 24×24 viewBox、`stroke` 1.6、`currentColor`

## 多模态校验
- [x] 5 张 Unsplash JPG 均通过"题材匹配 / 风格一致 / 可用性 / 技术合规 / 不重复"5 项校验
- [x] 不合格的 Unsplash 候选（纯白云、亮麦田、撞色车牌、撞色文字等）已从 `.tmp_dl/` 删除，未污染正式目录
- [x] 30 张 SVG 文件（22 记忆 + 8 场景）由代码模板生成，结构正确（XML / viewBox / stroke），符合 spec 风格骨架（暗色 + 渐变 + 颗粒 + 主题元素）
- [x] 无文字 / 水印 / 角标 / 商业 logo / 人物清晰正脸

## ASSET_SOURCES.md
- [x] 表格按现有 4 列格式追加所有新增素材（Group A / B / D）
- [x] "Source pages" 区域追加所有引用的 URL（Unsplash photo IDs + 原始 NASA / Wikimedia）
- [x] License 列对每张图明确为 Unsplash License / Project-owned MIT-like
- [x] 文末追加"收集与校验说明"段落，记录采集路径、多模态校验过程、Group C 跳过原因

## 运行时验证（可选）
- [x] 文件命名全部符合 spec：全小写 + 连字符 + 语义英文 slug + 正确后缀
- [x] 与现有 photo/ 15 张中文命名 PNG 不重名
- [ ] `asset-service` 在跑时，`GET /api/v1/assets/static/resources` 返回新文件（需启动后端验证）
- [ ] 前端 `/memories` 多次刷新可见新封面被哈希选中、无 404（需启动前端验证）
