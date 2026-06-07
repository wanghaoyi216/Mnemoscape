/**
 * 媒体素材目录 — 把 public/media/ 下的视频与插画按"语义角色"暴露。
 *
 * 设计要点：
 *  - 文件名经过 ASCII 化（中文原名 → 语义 slug），避免在浏览器/构建产物中
 *    出现 URL 编码问题；语义保留在 origin 字段里。
 *  - 单一事实源 — 任何视图需要图片或视频，从这里取，不直接写字符串路径。
 *  - 图片走 WebP（PNG → WebP 后体积从 ~89MB 降到 ~3.9MB）。每张图同时附
 *    320px 宽的 `-thumb.webp` 缩略，用于列表项、提示气泡等小尺寸场景。
 *  - 重新压缩流程见 scripts/compress-media.py。
 */
export interface MediaAsset {
  /** 公开 URL（Vite 把 public/ 映射到根 / 路径） */
  src: string
  /** 320px 宽的 WebP 缩略图；列表/提示气泡用 */
  thumb?: string
  /** 文件原中文名 — 便于在控制台/审稿时反查素材意图 */
  origin: string
  /** 一两句话说明这张素材的"叙事角色"，决定它出现在哪个视图 */
  role: string
}

const PUBLIC = '/media'

export const videos = {
  /** 时间长河的流动 — Login / Timeline 全屏氛围层 */
  chronosFlow: {
    src: `${PUBLIC}/videos/chronos-flow.mp4`,
    origin: '时间之流（Chronos Flow）',
    role: '记忆时间长河，登录与时光轴页全屏氛围层',
  },
  /** 沙漏倒流 — AI 重建中等待动画首选 */
  ebbingHourglass: {
    src: `${PUBLIC}/videos/ebbing-hourglass.mp4`,
    origin: '沙漏倒流（Ebbing Hourglass）',
    role: 'AI 重建/版本回滚等待动画',
  },
  /** 神经突触 — 共鸣大厅 / 灵魂匹配视觉锚 */
  neuralResonance: {
    src: `${PUBLIC}/videos/neural-resonance.mp4`,
    origin: '神经共鸣（Neural Resonance）',
    role: '灵魂共鸣大厅、配对动画',
  },
  /** 轻量极光流 — 记忆列表与全局馆藏氛围层 */
  auroraMemoryRiver: {
    src: `${PUBLIC}/videos/aurora-memory-river.mp4`,
    origin: '极光记忆河（Aurora Memory River）',
    role: '馆藏流动、记忆列表的轻量动态背景',
  },
  /** NASA STEVE 极光裁剪短循环 — 深空/极光主题场景锚点 */
  nasaAuroraSteve: {
    src: `${PUBLIC}/videos/nasa-aurora-steve-loop.mp4`,
    origin: 'NASA 极光 STEVE（The Aurora Named STEVE）',
    role: 'Profile / Timeline / Memory Atlas 的真实极光氛围层，来源见 resource/ASSET_SOURCES.md',
  },
  /** 星云潮汐 — 时光轴和星图页深空流体层 */
  nebulaTide: {
    src: `${PUBLIC}/videos/nebula-tide.mp4`,
    origin: '星云潮汐（Nebula Tide）',
    role: '时光轴深空漂移背景、小体积循环视频',
  },
  /** 记忆涟漪 — 卡片 hover / 空状态 / 过渡页动效 */
  memoryRipple: {
    src: `${PUBLIC}/videos/memory-ripple.mp4`,
    origin: '记忆涟漪（Memory Ripple）',
    role: '记忆列表、详情页、空状态的水纹过渡层',
  },
  /** 漂移视界 — 共鸣搜索结果和漂流瓶空间背景 */
  driftPerception: {
    src: `${PUBLIC}/videos/drift-perception.mp4`,
    origin: '漂移视界（Drift Perception）',
    role: '共鸣搜索与漂流记忆场景的视频纹理',
  },
} as const satisfies Record<string, MediaAsset>

function img(slug: string, origin: string, role: string): MediaAsset {
  return {
    src:   `${PUBLIC}/images/${slug}.webp`,
    thumb: `${PUBLIC}/images/${slug}-thumb.webp`,
    origin,
    role,
  }
}

export const images = {
  /** Login Hero 引导画 / 也作记忆星空备用底图 */
  memoryCorona:      img('memory-corona',      '记忆星冕图',        '/memories/graph 星图页 Hero / 登录左侧插画'),
  /** 记忆建造器 Hero */
  memoryFoundry:     img('memory-foundry',     '记忆铸造厂',        'MemoryBuilderView Hero'),
  /** 时光轴 Hero */
  timeGeometry:      img('time-geometry',      '时间的几何',        '时光轴页 Hero'),
  /** Profile Hero —— 情绪与心海 */
  heartSea:          img('heart-sea',          '多维心之海',        'Profile 个人主页 Hero / 数据看板侧景'),
  /** 情绪可视化页面 Hero —— 也作 Lorenz 吸引子卡片背景 */
  emotionPrism:      img('emotion-prism',      '情绪折射棱镜',      '情绪吸引子卡片背景与数据中心 Hero'),
  /** Resonance Hub Hero */
  resonanceBridge:   img('resonance-bridge',   '共鸣之桥',          '共鸣大厅 Hero — 跨灵魂的桥'),
  /** 双人共鸣空间 Hero */
  resonanceTwins:    img('resonance-twins',    '共鸣双子星',        '双人共鸣空间 / 匹配成功'),
  /** 留言信标视觉 */
  memoryBeacon:      img('memory-beacon',      '记忆笔记信标',      '3D 留言信标的 2D 卡片视觉'),
  /** 回音墙 */
  echoWall:          img('echo-wall',          '无声回音壁',        '详情页副 Hero / 评论模块氛围'),
  /** 记忆守护者 */
  memoryGuardian:    img('memory-guardian',    '记忆的守护者',      '锁定记忆视觉标识 / 列表空状态'),
  /** 漂浮气泡 */
  driftingBubble:    img('drifting-bubble',    '漂流的记忆气泡',    '记忆列表项默认缩略图（无场景渲染时）'),
  /** 历史拓片 */
  historicalRubbing: img('historical-rubbing', '历史的拓片',        '版本历史模块底图'),
  /** 三组场景占位 — 当 sceneDataUrl 未生成时按情绪/标签选用 */
  goldenAfternoon:   img('golden-afternoon',   '午后黄金屋',        '场景占位（温暖、怀旧、室内）'),
  cyberWatertown:    img('cyber-watertown',    '失落的赛博水乡',    '场景占位（赛博朋克、雨夜、东方）'),
  neonFields:        img('neon-fields',        '霓虹原野',          '场景占位（开阔、能量、未来）'),
} as const satisfies Record<string, MediaAsset>

/** 在 sceneDataUrl 为空时，根据 memory 的情绪/季节做一个稳定的兜底选择。 */
export function fallbackSceneCover(seedKey: string | null | undefined): MediaAsset {
  const palette = [images.goldenAfternoon, images.cyberWatertown, images.neonFields,
                   images.driftingBubble, images.memoryCorona] as const
  if (!seedKey) return palette[0]
  let h = 0
  for (let i = 0; i < seedKey.length; i++) h = (h * 31 + seedKey.charCodeAt(i)) | 0
  return palette[Math.abs(h) % palette.length]
}
