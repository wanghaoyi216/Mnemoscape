# 静态图片资源采集与验证 Spec

## Why
当前 `resource/photo/` 仅有 15 张图，记忆卡片在素材池较空时频繁撞图。  
`IMAGE-SPEC-NEEDED.md` 已明确 4 组（A 记忆封面 / B 场景占位 / C 氛围动图 / D SVG 图标）共 40~60 张的补充需求，本 spec 负责按其规范**从网络搜集**、**多模态校验**、并按 slug 命名落盘到指定子目录，让 `asset-service` 的 `LocalResourceWatcher` 自动热加载。

## What Changes
- **新增** `resource/photo/`：组 A（24~30 张记忆封面，slug 前缀 `mem-*`）+ 组 B（6~8 张场景占位，slug 前缀 `scene-*`）。
- **新增** `resource/video/` 或 `resource/gif/`：组 C（2~3 个氛围动图/短视频，slug 自拟）。
- **新增** `resource/icon/`：组 D（8~12 个线性 SVG 图标，24×24 viewBox、`currentColor`）。
- **更新** `resource/ASSET_SOURCES.md`：按现有表格格式追加所有新素材的来源 / License / 用途列。
- 不改动后端代码、不动前端构建产物；纯资源投放，前端 `useDynamicMedia` 自动优先使用新素材。

## Impact
- **Affected specs**:
  - 静态资源池（README.md 第 1 节目录约定）
  - ASSET_SOURCES.md 来源追溯表
  - `useDynamicMedia` / `LocalResourceWatcher` 行为（无需改动，仅受益）
- **Affected code / dirs**:
  - `resource/photo/`（写入：组 A、B）
  - `resource/video/` 或 `resource/gif/`（写入：组 C）
  - `resource/icon/`（写入：组 D）
  - `resource/ASSET_SOURCES.md`（追加表格行）

## Style Anchor（所有图通用提示词骨架）
```
深色调、电影感、梦核 / 记忆主题、柔和体积光、轻微颗粒胶片质感、
低饱和但有一处高光色（薄荷绿 #36d8b4 / 暖金 #f2b95c / 天青 #6cc6ff 三选一）、
氛围朦胧、留白克制、no text、no watermark、cinematic、16:9
```
- 色温偏冷为主，暖图作点缀；避免大面积纯白/亮色。
- 记忆封面比例 **16:9**（1600×900 或 1920×1080），单文件 ≤ 5MB。
- 优先 **WebP**；JPG/PNG 也可。图标必须是 **SVG**。
- 命名：全小写 + 连字符 + 语义英文 slug（如 `mem-warm-01.webp`、`scene-misty-lake.webp`）。

## ADDED Requirements

### Requirement: Group A — 记忆封面（24~30 张，photo/）
`asset-service` 必须在 `resource/photo/` 下提供至少 24 张符合情绪 / 季节分桶的封面图，按下表 slug 落盘：

| slug 前缀        | 数量 | 情绪 / 场景关键词 |
|------------------|------|--------------------|
| `mem-warm-`      | 3~4  | warm nostalgia, golden hour interior, dust motes, cozy |
| `mem-melancholy-`| 3~4  | rainy window, blurred city lights, lonely, blue hour, quiet |
| `mem-joy-`       | 3~4  | bright meadow, soft bokeh, childhood summer, airy, hopeful |
| `mem-serene-`    | 3~4  | calm lake at dawn, misty mountains, minimal, meditative, mint tint |
| `mem-nostalgia-` | 3~4  | old photograph texture, faded film, vintage train window, sepia-teal |
| `mem-mystery-`   | 3~4  | deep forest fog, bioluminescence, dreamcore corridor, surreal |
| `mem-night-`     | 3~4  | starry sky, aurora over field, city night from above, cyan glow |
| `mem-autumn-`    | 3~4  | maple leaves, amber light, fading warmth, soft wind |

#### Scenario: 记忆卡片不再撞图
- **WHEN** 前端 `useDynamicMedia` 按 `memoryId` 哈希从 `photo/` 候选池选图
- **THEN** 候选池 ≥ 24 张且分布于 ≥ 6 种情绪，撞图概率显著下降

### Requirement: Group B — 场景重建占位（6~8 张，photo/）
`resource/photo/` 下必须有以下 8 个固定 slug 的场景占位图（每张独立、横构图、空间纵深感强）：

| slug                       | 提示词补充 |
|----------------------------|-----------|
| `scene-courtyard-summer`   | sunlit courtyard, warm stone, summer afternoon, depth |
| `scene-snowy-dusk`         | snowy landscape at dusk, cold blue, lone lamp |
| `scene-rain-alley`         | rainy oriental alley, neon reflections, cyberpunk-lite |
| `scene-spring-field`       | open spring field, soft green, distant hills |
| `scene-night-rooftop`      | rooftop under stars, city bokeh below, cyan glow |
| `scene-autumn-corridor`    | corridor of autumn trees, amber tunnel, perspective |
| `scene-misty-lake`         | misty lake at dawn, reflection, serene mint |
| `scene-desert-night`       | desert under milky way, vast, lonely warm tent light |

#### Scenario: SceneViewer 兜底有图可用
- **WHEN** AI 3D 场景尚未生成，SceneViewer 调用 2D 兜底
- **THEN** 至少 6 个场景 slug 可被命中并展示

### Requirement: Group C — 登录页 / 氛围动图（2~3 个，video/ 或 gif/）
**任选** 2~3 个落盘到 `resource/video/`（.mp4/.webm）或 `resource/gif/`（.gif）：
- 题材：星云流动 / 极光 / 水波 / 粒子飘浮 / 雨夜街景循环
- 暗、慢、循环无缝、≤ 10MB
- 建议来源：NASA 视频库、Pexels/Pixabay CC0 段、Coverr

### Requirement: Group D — 自定义 SVG 图标（8~12 个，icon/）
`resource/icon/` 目录首次创建，**至少 8 个** 单色线性 SVG：
- 24×24 viewBox、`stroke` 1.6~1.8、`currentColor` 跟主题色
- 题材：记忆 / 锁 / 漂流瓶 / 共鸣 / 星图 / 时间 / 情绪 / 相机 / 麦克风 / 地图（≥ 8 选 10）
- 文件名：`icon-<topic>.svg`

### Requirement: 全部素材必须经多模态校验
每张落盘素材（图片 / SVG / 动图）必须经过 AI 多模态识别校验，校验项：
1. **题材匹配**：与该 slug 的情绪 / 场景关键词一致（无"牛头不对马嘴"）。
2. **风格一致**：暗色为主、电影感、无大面积纯白 / 高饱和破坏深色 UI。
3. **可用性**：无明显文字 / 水印 / 角标 / 商业 logo / 人物清晰正脸特写。
4. **技术合规**：图片 ≥ 1280×720 接近 16:9；文件可正常打开；非 0 字节。
5. **不重复**：与 `resource/photo/`、`resource/video/`、`resource/gif/` 现有文件不重名。

#### Scenario: 不合格素材被拒
- **WHEN** 下载的某张图未通过任意一项校验
- **THEN** 删除该文件并触发下一轮搜索，直到该 slug 找到合格替代或经用户确认跳过

### Requirement: 同步更新 ASSET_SOURCES.md
所有新素材（无论本地 photo/、video/、gif/、icon/）必须在 `ASSET_SOURCES.md` 表格中追加一行：
| 列 | 填写要求 |
|---|---|
| Local file | 相对路径（`photo/mem-warm-01.webp`） |
| Source | 来源网站 / 摄影师 / 上传者 |
| License / Usage | CC0 / NASA Media / 自有 等；商用 OK 必须明确 |
| Intended use | 记忆卡片 / 详情 Hero / 登录背景 / 图标 等 |

## MODIFIED Requirements
无（本轮纯资源投放，不改既有功能行为）。

## REMOVED Requirements
无。
