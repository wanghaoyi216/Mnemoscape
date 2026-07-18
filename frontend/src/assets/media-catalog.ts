/**
 * 前端媒体素材目录。
 *
 * 编号素材严格对应仓库根目录的《AI生成图片素材提示词.md》。只登记已经存在的
 * 编号，缺失素材不会用不相干的图片顶替。原始 PNG 通过
 * scripts/import-numbered-media.py 转为高质量 WebP 与轻量缩略图。
 */
export interface MediaAsset {
  /** 公开 URL（Vite 把 public/ 映射到根路径） */
  src: string
  /** 列表、选择器等小尺寸场景使用的缩略图 */
  thumb?: string
  /** 视频首帧加载前使用的静态封面 */
  poster?: string
  /** 对应素材方案中的编号 */
  number?: number
  /** 可读名称，便于审稿和检索 */
  origin: string
  /** 素材在产品中的叙事角色 */
  role: string
}

const PUBLIC = '/media'
const GENERATED = `${PUBLIC}/generated`

function generatedImage(
  category: string,
  number: number,
  origin: string,
  role: string,
): MediaAsset {
  const stem = String(number).padStart(3, '0')
  return {
    src: `${GENERATED}/${category}/${stem}.webp`,
    thumb: `${GENERATED}/${category}/${stem}-thumb.webp`,
    number,
    origin: `${number}. ${origin}`,
    role,
  }
}

export const loginBackgrounds = [
  generatedImage('login', 1, '淡紫水墨山水', '登录页与全局氛围背景'),
  generatedImage('login', 2, '桃粉晨光河谷', '登录页与全局氛围背景'),
  generatedImage('login', 3, '星空浮岛', '登录页与全局氛围背景'),
  generatedImage('login', 4, '记忆之门', '登录页与全局氛围背景'),
  generatedImage('login', 5, '极光莲湖', '登录页与全局氛围背景'),
  generatedImage('login', 6, '水晶记忆宫殿', '登录页与全局氛围背景'),
  generatedImage('login', 7, '极简桃花水墨', '登录页与全局氛围背景'),
  generatedImage('login', 8, '竹林月亮门', '登录页与全局氛围背景'),
  generatedImage('login', 9, '桃月薰衣草夜', '登录页与全局氛围背景'),
  generatedImage('login', 10, '云海仙鹤', '登录页与全局氛围背景'),
  generatedImage('login', 11, '记忆古树', '登录页与全局氛围背景'),
  generatedImage('login', 12, '古铜圆门', '登录页与全局氛围背景'),
] as const satisfies readonly MediaAsset[]

export const memoryCovers = [
  generatedImage('memory-covers', 13, '温暖', '暖色记忆封面'),
  generatedImage('memory-covers', 14, '欢乐', '暖色记忆封面'),
  generatedImage('memory-covers', 15, '家', '暖色记忆封面'),
  generatedImage('memory-covers', 16, '童年', '暖色记忆封面'),
  generatedImage('memory-covers', 17, '友情', '暖色记忆封面'),
  generatedImage('memory-covers', 18, '爱情', '暖色记忆封面'),
  generatedImage('memory-covers', 19, '庆祝', '暖色记忆封面'),
  generatedImage('memory-covers', 20, '旅行', '暖色记忆封面'),
  generatedImage('memory-covers', 21, '美食', '暖色记忆封面'),
  generatedImage('memory-covers', 22, '宠物', '暖色记忆封面'),
  generatedImage('memory-covers', 23, '成长', '暖色记忆封面'),
  generatedImage('memory-covers', 24, '毕业', '暖色记忆封面'),
  generatedImage('memory-covers', 25, '音乐', '暖色记忆封面'),
  generatedImage('memory-covers', 26, '节日', '暖色记忆封面'),
  generatedImage('memory-covers', 27, '重逢', '暖色记忆封面'),
  generatedImage('memory-covers', 28, '梦想成真', '暖色记忆封面'),
  generatedImage('memory-covers', 29, '忧郁', '冷色记忆封面'),
  generatedImage('memory-covers', 30, '孤独', '冷色记忆封面'),
  generatedImage('memory-covers', 31, '遗憾', '冷色记忆封面'),
  generatedImage('memory-covers', 32, '思念', '冷色记忆封面'),
] as const satisfies readonly MediaAsset[]

export const sceneBackgrounds = [
  generatedImage('scene-nature', 45, '春日田野', '自然场景底图'),
  generatedImage('scene-nature', 46, '雨巷', '自然场景底图'),
  generatedImage('scene-architecture', 59, '古寺钟声', '建筑与空间场景底图'),
  generatedImage('scene-architecture', 60, '江南水乡', '建筑与空间场景底图'),
] as const satisfies readonly MediaAsset[]

export const conceptIllustrations = [
  generatedImage('concepts', 71, '记忆宫殿', '记忆与 AI 概念插画'),
  generatedImage('concepts', 72, '记忆星图', '记忆与 AI 概念插画'),
] as const satisfies readonly MediaAsset[]

export const featureIllustrations = [
  generatedImage('features', 86, '开始记录', '功能引导插画'),
  generatedImage('features', 87, 'AI 重建', '功能引导插画'),
  generatedImage('features', 88, '探索记忆', '功能引导插画'),
  generatedImage('features', 89, '情绪分析', '功能引导插画'),
  generatedImage('features', 90, '时间轴', '功能引导插画'),
  generatedImage('features', 91, '场景漫游', '功能引导插画'),
  generatedImage('features', 92, '记忆分享', '功能引导插画'),
  generatedImage('features', 93, '日记模式', '功能引导插画'),
  generatedImage('features', 94, '记忆图谱', '功能引导插画'),
  generatedImage('features', 95, '漂流瓶投递', '功能引导插画'),
] as const satisfies readonly MediaAsset[]

export const videoPosters = [
  generatedImage('video-posters', 96, '记忆涟漪', '氛围视频封面'),
  generatedImage('video-posters', 97, '星云潮汐', '氛围视频封面'),
  generatedImage('video-posters', 98, '极光记忆河', '氛围视频封面'),
  generatedImage('video-posters', 99, '沙漏倒流', '氛围视频封面'),
  generatedImage('video-posters', 100, '神经共鸣', '氛围视频封面'),
] as const satisfies readonly MediaAsset[]

export const generatedGallery = [
  ...memoryCovers,
  ...sceneBackgrounds,
  ...conceptIllustrations,
  ...featureIllustrations,
] as const satisfies readonly MediaAsset[]

export const videos = {
  chronosFlow: {
    src: `${PUBLIC}/videos/chronos-flow.mp4`,
    poster: videoPosters[2].src,
    origin: '时间之流（Chronos Flow）',
    role: '记忆时间长河，登录与时光轴页全屏氛围层',
  },
  ebbingHourglass: {
    src: `${PUBLIC}/videos/ebbing-hourglass.mp4`,
    poster: videoPosters[3].src,
    origin: '沙漏倒流（Ebbing Hourglass）',
    role: 'AI 重建/版本回滚等待动画',
  },
  neuralResonance: {
    src: `${PUBLIC}/videos/neural-resonance.mp4`,
    poster: videoPosters[4].src,
    origin: '神经共鸣（Neural Resonance）',
    role: '灵魂共鸣大厅、配对动画',
  },
  auroraMemoryRiver: {
    src: `${PUBLIC}/videos/aurora-memory-river.mp4`,
    poster: videoPosters[2].src,
    origin: '极光记忆河（Aurora Memory River）',
    role: '馆藏流动、记忆列表的轻量动态背景',
  },
  nasaAuroraSteve: {
    src: `${PUBLIC}/videos/nasa-aurora-steve-loop.mp4`,
    poster: videoPosters[2].src,
    origin: 'NASA 极光 STEVE（The Aurora Named STEVE）',
    role: 'Profile / Timeline / Memory Atlas 的真实极光氛围层',
  },
  nebulaTide: {
    src: `${PUBLIC}/videos/nebula-tide.mp4`,
    poster: videoPosters[1].src,
    origin: '星云潮汐（Nebula Tide）',
    role: '时光轴深空漂移背景、小体积循环视频',
  },
  memoryRipple: {
    src: `${PUBLIC}/videos/memory-ripple.mp4`,
    poster: videoPosters[0].src,
    origin: '记忆涟漪（Memory Ripple）',
    role: '记忆列表、详情页、空状态的水纹过渡层',
  },
  driftPerception: {
    src: `${PUBLIC}/videos/drift-perception.mp4`,
    poster: videoPosters[1].src,
    origin: '漂移视界（Drift Perception）',
    role: '共鸣搜索与漂流记忆场景的视频纹理',
  },
} as const satisfies Record<string, MediaAsset>

/**
 * 保持既有视图的语义 API 不变，但底层全部切换为本次编号素材。
 */
export const images = {
  memoryCorona: conceptIllustrations[1],
  memoryFoundry: featureIllustrations[1],
  timeGeometry: featureIllustrations[4],
  heartSea: conceptIllustrations[0],
  emotionPrism: featureIllustrations[3],
  resonanceBridge: featureIllustrations[6],
  resonanceTwins: videoPosters[4],
  memoryBeacon: featureIllustrations[9],
  echoWall: featureIllustrations[5],
  memoryGuardian: featureIllustrations[2],
  driftingBubble: videoPosters[0],
  historicalRubbing: featureIllustrations[7],
  goldenAfternoon: sceneBackgrounds[0],
  cyberWatertown: sceneBackgrounds[3],
  neonFields: sceneBackgrounds[1],
} as const satisfies Record<string, MediaAsset>

/** 旧版程序化封面保留为极端离线兜底，不再作为默认画库展示。 */
function svgCover(slug: string, origin: string, role: string): MediaAsset {
  return { src: `${PUBLIC}/covers/${slug}.svg`, origin, role }
}

export const covers = {
  auroraVeil: svgCover('aurora-veil', '极光帷幕', '清冷、辽阔、青蓝极光'),
  amberDusk: svgCover('amber-dusk', '琥珀黄昏', '温暖、落日、怀旧'),
  violetTide: svgCover('violet-tide', '紫罗兰潮', '梦幻、绯紫、情绪'),
  mintMist: svgCover('mint-mist', '薄荷雾霭', '宁静、薄荷绿、治愈'),
  abyssGlow: svgCover('abyss-glow', '深渊微光', '深邃、海蓝、神秘'),
  emberField: svgCover('ember-field', '余烬之原', '热烈、橙红、能量'),
} as const satisfies Record<string, MediaAsset>

/** 根据稳定种子在已生成记忆封面和场景底图中选择兜底图。 */
export function fallbackSceneCover(seedKey: string | null | undefined): MediaAsset {
  const palette = [...memoryCovers, ...sceneBackgrounds] as const
  if (!seedKey) return palette[0]
  let hash = 0
  for (let index = 0; index < seedKey.length; index += 1) {
    hash = (hash * 31 + seedKey.charCodeAt(index)) | 0
  }
  return palette[Math.abs(hash) % palette.length]
}
