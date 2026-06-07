# 🎨 静态图片资源需求清单（给王浩毅）

> 本文件由 AI 在本轮优化后生成。我（AI）无法联网下载图片（运行沙箱屏蔽了出站网络），
> 所以把"需要哪些图 / 什么风格 / 放哪个文件夹 / 文件名怎么取"全部写清楚，你按提示
> 去**生成（推荐 AI 文生图）或下载（CC0 图库）**，丢进对应目录即可。`asset-service`
> 的 `LocalResourceWatcher` 会 1~2 秒内热加载，**无需重启后端**。

---

## 0. 一句话总览

当前 `resource/photo/` 只有 **15 张**图，记忆卡片在素材池较空时容易撞图。
我已经先用 **6 张程序化矢量氛围封面**（`frontend/public/media/covers/*.svg`）兜底扩容，
但**真实照片/插画的质感无法用 SVG 替代**。下面分 4 组，共建议补 **40~60 张**。

投放位置二选一（都行，可混用）：
- **A. 本地资源池**：直接拖进 `resource/photo/`（或 `gif/`、`video/`）→ 热加载，前端
  `useDynamicMedia` 自动按记忆 id 稳定哈希选用，并标 `本地` 徽章。
- **B. MinIO**：上传到 bucket `mnemoscape-assets`（minio console 或 `mc cp`）→ 同样
  通过 `/api/v1/assets/static/resources` 合并返回，前端标 `MinIO` 徽章。

> 大批量（几十上百张）建议走 **B（MinIO）**；十来张兜底走 **A（本地）** 即可。

---

## 1. 统一风格基调（所有图都遵守）

让新图和现有 UI（深色玻璃拟态 + 薄荷绿/金/青主题）协调，请套以下"提示词骨架"：

```
深色调、电影感、梦核 / 记忆主题、柔和体积光、轻微颗粒胶片质感、
低饱和但有一处高光色（薄荷绿 #36d8b4 / 暖金 #f2b95c / 天青 #6cc6ff 三选一）、
氛围朦胧、留白克制、no text、no watermark、cinematic、16:9
```

- **色温**：偏冷为主，暖图（落日/室内）作点缀，避免大面积纯白/亮色（破坏深色 UI）。
- **比例**：记忆封面 **16:9**（1600×900 或 1920×1080）；图标 **1:1**。
- **格式**：优先 **WebP**（体积小）；JPG/PNG 也行。单文件 ≤ 5MB（封面）。
- **命名**：全小写 + 连字符 + 语义英文 slug，例：`memory-rainy-window.webp`。
  中文名也能识别，但英文 slug 在 URL / 构建产物里更稳。
- **缩略图（可选但推荐）**：若想列表更快，给每张配 320px 宽的 `<slug>-thumb.webp`。
  没有也没关系，前端会直接用原图。

---

## 2. 分组需求

### 🗂 组 A：记忆封面 / 通用占位（最缺，优先做）— 建议 24~30 张
**放** `resource/photo/`
**用途**：记忆卡片封面、详情页 Hero、地图节点详情、时光机贴图（当记忆没有自定义场景图时）。
**要点**：题材要"泛情绪、可复用"，不绑定具体人/地标，覆盖多种情绪与季节。

按情绪/季节各来 3~4 张（提示词在统一骨架基础上加下面的关键词）：

| slug 前缀建议 | 情绪 / 场景关键词（加到提示词里） |
|---|---|
| `mem-warm-` | warm nostalgia, golden hour interior, dust motes in sunlight, cozy |
| `mem-melancholy-` | rainy window, blurred city lights, lonely, blue hour, quiet |
| `mem-joy-` | bright meadow, soft bokeh, childhood summer, airy, hopeful |
| `mem-serene-` | calm lake at dawn, misty mountains, minimal, meditative, mint tint |
| `mem-nostalgia-` | old photograph texture, faded film, vintage train window, sepia-teal |
| `mem-mystery-` | deep forest fog, bioluminescence, dreamcore corridor, surreal |
| `mem-night-` | starry sky, aurora over field, city night from above, cyan glow |
| `mem-autumn-` | maple leaves, amber light, fading warmth, soft wind |

> 每个前缀 3~4 张、编号 `-01 -02 -03`，共约 24~30 张。这样卡片几乎不会撞图。

### 🗂 组 B：场景重建占位（SceneViewer / 3D 空间 2D 兜底）— 建议 6~8 张
**放** `resource/photo/`，slug 前缀 `scene-`
**用途**：AI 3D 场景重建尚未生成时，给 SceneViewer 一个有质感的 2D 兜底大图。
**要点**：要"可当作环境背景"的横构图，空间纵深感强。

| slug | 提示词补充 |
|---|---|
| `scene-courtyard-summer` | sunlit courtyard, warm stone, summer afternoon, depth |
| `scene-snowy-dusk` | snowy landscape at dusk, cold blue, lone lamp |
| `scene-rain-alley` | rainy oriental alley, neon reflections, cyberpunk-lite |
| `scene-spring-field` | open spring field, soft green, distant hills |
| `scene-night-rooftop` | rooftop under stars, city bokeh below, cyan glow |
| `scene-autumn-corridor` | corridor of autumn trees, amber tunnel, perspective |
| `scene-misty-lake` | misty lake at dawn, reflection, serene mint |
| `scene-desert-night` | desert under milky way, vast, lonely warm tent light |

### 🗂 组 C：登录页 / 全屏氛围动图或短视频（可选增强）— 建议 2~3 个
**放** `resource/video/`（`.mp4 .webm`）或 `resource/gif/`（`.gif`）
**用途**：登录页 18s 自动轮播背景、AI 球 ambient 层。
**要点**：暗、慢、循环无缝、≤ 10MB。题材：星云流动 / 极光 / 水波 / 粒子飘浮。
CC0 来源推荐：NASA 视频库、Pexels/Pixabay（CC0 段）、Coverr。

### 🗂 组 D：自定义 SVG 图标（可选锦上添花）— 建议 8~12 个
**放** `resource/icon/`（`.svg`）
**用途**：替换/补充界面里的线性图标，统一描边风格。
**要点**：单色线性、`stroke` 1.6~1.8、24×24 viewBox、`currentColor`（跟随主题色）。
题材：记忆/锁/漂流瓶/共鸣/星图/时间/情绪/相机/麦克风/地图。

---

## 3. 怎么生成（两条路）

### 路线 1：AI 文生图（推荐，质量最高、最可控）
拿组 A/B 的"统一骨架 + 该行关键词"丢给你的文生图工具（如 NVIDIA 的图像模型 /
SD / Midjourney），分辨率出 **1600×900**，导出 WebP/JPG，按 slug 命名即可。

### 路线 2：CC0 图库下载
- Unsplash / Pexels / Pixabay（注意选 CC0 / 免费商用）
- Wikimedia Commons（CC0 段）
- 下完用 `frontend/scripts/compress-media.py` 压成 WebP（项目已有该脚本）

> ⚠️ 版权：竞赛/公开展示请只用 **CC0 或你自己生成的图**，并在
> `resource/ASSET_SOURCES.md` 追加来源行（已有表格，照格式补即可）。

---

## 4. 投放后如何验证生效

1. 把图丢进 `resource/photo/`。
2. 刷新前端任意用到封面的页面（记忆列表 `/memories`）。
3. 1~2 秒内新图就会进入候选池；多刷新几次记忆卡片，应能看到新图被哈希选中。
4. 想确认后端已识别：`GET /api/v1/assets/static/resources` 的返回里应出现新文件名。

---

## 5. 已由 AI 完成的兜底（你不用再做）

- ✅ `frontend/public/media/covers/` 下 6 张矢量氛围封面（极光/黄昏/紫潮/薄荷/深渊/余烬），
  已并入 `fallbackSceneCover()` 调色板（共 11 项），即刻降低撞图。
- ✅ 这些 SVG 零体积负担、随主题色协调，作为"真实图到位前"的过渡。
- ✅ 你投放的真实照片会通过 `useDynamicMedia` **自动优先**于这些兜底图。

---

最后更新：2026-06-07 · 本轮 AI 优化生成
