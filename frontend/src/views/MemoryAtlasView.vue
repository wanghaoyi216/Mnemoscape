<script setup lang="ts">
/**
 * 时空记忆地图 · MemoryAtlasView (v9)
 *
 * v8 → v9 的主要改进（接续第六代会话用户反馈）：
 *   1. **三种底图样式可切换**：影像 (Esri World Imagery, 全球可达) / 街道 (高德 + Carto
 *      双源混合, 国内国外都能看到) / 自然分层设色 (Esri World Physical, 海洋深蓝、土地
 *      黄褐、山脉金棕的卫星模拟色)。右上角 segmented control 单击切换。
 *   2. **修复地球顶部黑块**：globe 投影低 zoom 下用 MapLibre 的 sky layer 填满"画外区"，
 *      不再露出棱角的星空 / 棕色三角。
 *   3. **去掉自动旋转抢控制**：首次交互（drag / click / wheel）立刻停止地球自转，用户
 *      可以自由拨弄南半球。地球初始 center 从 [105,35] 改为 [50,15] 让欧洲/非洲/亚洲
 *      都可见，南半球默认可见。
 *   4. **缩小自动回地球**：flat 模式下 zoom 滚到 < 1.5 自动 returnToGlobe()。
 *   5. **右下角组件不再互相遮挡**：详情卡出现时其它右下浮卡自动隐藏；状态卡限高
 *      避免压住空数据 hint。
 *   6. 沿用 v6/v7/v8 的：MinIO 媒体匹配、ResizeObserver 兜底、空状态智能提示、
 *      4 级地理钻取、TripsLayer 时间播放、星空闪烁背景。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MapboxOverlay } from '@deck.gl/mapbox'
import { ScatterplotLayer, ArcLayer, PathLayer } from '@deck.gl/layers'
import { TripsLayer } from '@deck.gl/geo-layers'

import { useAtlasStore, type MemoryWithCoords } from '../stores/atlas'
import { resolveLevel, shouldTilt } from '../composables/useAtlasZoomLevel'
import client from '../api/client'

const atlas = useAtlasStore()
const { t } = useI18n()

/* ============================================================
 * 1) State
 * ============================================================ */
const containerRef = ref<HTMLDivElement | null>(null)
const starsCanvasRef = ref<HTMLCanvasElement | null>(null)
const loading = computed(() => atlas.loading)
const error = computed(() => atlas.error)
const selected = ref<MemoryWithCoords | null>(null)
const hoverInfo = ref<{ x: number; y: number; mem: MemoryWithCoords } | null>(null)
const viewMode = ref<'globe' | 'flat' | 'tilt'>('globe')
const enteredFlat = ref(false)
const hintVisible = ref(true)
const hintDismissed = ref(false)

const layerVis = reactive({
  physical: true,
  aura: true,
  trips: true,
  others: true,
})
const currentZoom = ref(1.6)
const drillLevel = computed(() => resolveLevel(currentZoom.value))

const timeRange = computed(() => atlas.timeRange)
const currentTime = ref(0)
const playing = ref(false)
const playbackSpeed = ref(1)

const hasAnyCoords = computed(() => atlas.memories.length > 0)
const hasTripsData = computed(() => atlas.memories.length >= 2 || atlas.route.length > 0)
const showNoCoordsHint = computed(() =>
  !loading.value && atlas.lastFetched > 0 && !hasAnyCoords.value
)
const showOneCoordHint = computed(() =>
  !loading.value && atlas.lastFetched > 0 && hasAnyCoords.value && !hasTripsData.value
)

/* ============================================================
 * 2) MinIO media
 * ============================================================ */
interface AssetResource {
  name: string
  path: string
  type: string
  source?: string
  size?: number
}
const minioResources = ref<AssetResource[]>([])
const minioLoading = ref(false)

async function refreshMinioResources() {
  minioLoading.value = true
  try {
    const resp = await client.get('/assets/static/resources')
    const list: AssetResource[] = resp.data?.data || []
    minioResources.value = list
  } catch {
    minioResources.value = []
  } finally {
    minioLoading.value = false
  }
}

function isMinioResource(r: AssetResource): boolean {
  return /X-Amz-Signature/i.test(r.path) || r.source === 'minio'
}

function detailMedia(mem: MemoryWithCoords | null): AssetResource[] {
  if (!mem) return []
  const keys: string[] = []
  if (mem.id) keys.push(String(mem.id).toLowerCase())
  if (mem.title) keys.push(mem.title.toLowerCase())
  if (mem.memoryLocation) keys.push(mem.memoryLocation.toLowerCase())
  const hits: AssetResource[] = []
  for (const r of minioResources.value) {
    const n = r.name.toLowerCase()
    if (keys.some((k) => k && n.includes(k))) hits.push(r)
  }
  if (hits.length) return hits.slice(0, 6)
  const photos = minioResources.value.filter((r) => r.type === 'photo' || r.type === 'gif').slice(0, 3)
  const videos = minioResources.value.filter((r) => r.type === 'video').slice(0, 1)
  const audios = minioResources.value.filter((r) => r.type === 'audio').slice(0, 1)
  return [...photos, ...videos, ...audios]
}

const selectedMedia = computed(() => detailMedia(selected.value))
const minioCount = computed(() => minioResources.value.filter(isMinioResource).length)
const resourceCount = computed(() => minioResources.value.length)
const mediaTypeStats = computed(() => {
  const stat = { photo: 0, video: 0, audio: 0, gif: 0 }
  for (const r of minioResources.value) {
    if (r.type === 'photo') stat.photo += 1
    else if (r.type === 'video') stat.video += 1
    else if (r.type === 'audio') stat.audio += 1
    else if (r.type === 'gif') stat.gif += 1
  }
  return stat
})

/* ============================================================
 * 3) 地理层级解析：自由文本 → 国家 / 省 / 市 / 县
 * ============================================================ */
interface GeoBreakdown {
  country: string
  province: string
  city: string
  county: string
}
const PROVINCES = [
  '北京', '上海', '天津', '重庆', '香港', '澳门', '台湾',
  '河北', '山西', '辽宁', '吉林', '黑龙江', '江苏', '浙江', '安徽', '福建',
  '江西', '山东', '河南', '湖北', '湖南', '广东', '海南', '四川', '贵州',
  '云南', '陕西', '甘肃', '青海', '内蒙古', '广西', '西藏', '宁夏', '新疆',
]
const COUNTRY_HINTS: Record<string, string> = {
  '北京': '中国', '上海': '中国', '广州': '中国', '深圳': '中国',
  '东京': '日本', 'tokyo': '日本', '首尔': '韩国', 'seoul': '韩国',
  '纽约': '美国', 'new york': '美国', '旧金山': '美国', 'san francisco': '美国',
  '伦敦': '英国', 'london': '英国', '巴黎': '法国', 'paris': '法国',
  '柏林': '德国', 'berlin': '德国', '悉尼': '澳大利亚', 'sydney': '澳大利亚',
  '曼谷': '泰国', 'bangkok': '泰国', '新加坡': '新加坡', 'singapore': '新加坡',
  '迪拜': '阿联酋', 'dubai': '阿联酋', '莫斯科': '俄罗斯', 'moscow': '俄罗斯',
}

function parseGeo(loc: string | undefined | null): GeoBreakdown {
  const def: GeoBreakdown = { country: '—', province: '—', city: '—', county: '—' }
  if (!loc) return def
  const raw = loc.trim()
  if (!raw) return def

  let country = ''
  let province = ''
  let city = ''
  let county = ''

  const lower = raw.toLowerCase()
  for (const [k, v] of Object.entries(COUNTRY_HINTS)) {
    if (lower.includes(k.toLowerCase()) || raw.includes(k)) { country = v; break }
  }
  if (!country) {
    if (/中国|china/i.test(raw)) country = '中国'
    else if (/日本|japan/i.test(raw)) country = '日本'
    else if (/美国|usa|united states/i.test(raw)) country = '美国'
  }

  for (const p of PROVINCES) {
    if (raw.includes(p)) { province = p; break }
  }

  const cityMatches = raw.match(/[一-龥A-Za-z]{1,14}(?:市|自治州|地区|盟)/g)
  if (cityMatches?.length) {
    city = cityMatches[cityMatches.length - 1]
      .replace(/(?:市|自治州|地区|盟)$/, '')
      .replace(new RegExp(`^${escapeRegExp(province)}(?:省|自治区|市)?`), '')
  }

  const countyMatches = raw.match(/[一-龥A-Za-z]{1,16}(?:自治县|区|县|旗)/g)
  if (countyMatches?.length) {
    county = countyMatches[countyMatches.length - 1]
      .replace(/(?:自治县|区|县|旗)$/, '')
      .replace(new RegExp(`^${escapeRegExp(province)}(?:省|自治区|市)?`), '')
      .replace(new RegExp(`^${escapeRegExp(city)}市?`), '')
  }

  if (!city && province && ['北京', '上海', '天津', '重庆'].includes(province)) {
    city = province
  }
  if (!country && province) country = '中国'

  return {
    country: country || '—',
    province: province || '—',
    city: city || '—',
    county: county || '—',
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const selectedGeo = computed<GeoBreakdown>(() => parseGeo(selected.value?.memoryLocation || ''))
const hoverGeo = computed<GeoBreakdown>(() => parseGeo(hoverInfo.value?.mem.memoryLocation || ''))

/* ============================================================
 * 4) Layers
 * ============================================================ */
const memoriesByTime = computed(() => [...atlas.memories].sort((a, b) => a.ts - b.ts))

function rainbow(t: number): [number, number, number] {
  const hue = 240 - 240 * Math.max(0, Math.min(1, t))
  return hslToRgb(hue, 0.85, 0.55)
}
function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const c = (1 - Math.abs(2 * l - 1)) * s
  const hp = (h % 360) / 60
  const x = c * (1 - Math.abs((hp % 2) - 1))
  let r = 0, g = 0, b = 0
  if (hp < 1) [r, g, b] = [c, x, 0]
  else if (hp < 2) [r, g, b] = [x, c, 0]
  else if (hp < 3) [r, g, b] = [0, c, x]
  else if (hp < 4) [r, g, b] = [0, x, c]
  else if (hp < 5) [r, g, b] = [x, 0, c]
  else [r, g, b] = [c, 0, x]
  const m = l - c / 2
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)]
}

const personalTrips = computed(() => {
  const list = memoriesByTime.value
  const segs: Array<{
    path: Array<[number, number]>
    timestamps: number[]
    color: [number, number, number]
    fromTitle?: string
    toTitle?: string
  }> = []
  for (let i = 0; i + 1 < list.length; i++) {
    const a = list[i]; const b = list[i + 1]
    const ratio = list.length > 1 ? i / (list.length - 1) : 0.5
    segs.push({
      path: [a.coords, b.coords],
      timestamps: [a.ts, b.ts],
      color: rainbow(ratio),
      fromTitle: a.title, toTitle: b.title,
    })
  }
  if (segs.length === 0) {
    for (let i = 0; i < atlas.route.length; i++) {
      const s = atlas.route[i]
      if (!s.from || !s.to) continue
      const ratio = atlas.route.length > 1 ? i / (atlas.route.length - 1) : 0.5
      segs.push({
        path: [s.from, s.to] as Array<[number, number]>,
        timestamps: [s.startTime, s.endTime],
        color: rainbow(ratio),
        fromTitle: s.fromTitle, toTitle: s.toTitle,
      })
    }
  }
  return segs
})

const othersTrips = computed(() => {
  const byName: Record<string, Array<{ coords: [number, number]; ts: number }>> = {}
  for (const p of atlas.others) {
    if (!byName[p.virtualName]) byName[p.virtualName] = []
    byName[p.virtualName].push({ coords: p.coords, ts: p.timestamp })
  }
  const segs: Array<{ path: Array<[number, number]>; timestamps: number[] }> = []
  for (const list of Object.values(byName)) {
    list.sort((a, b) => a.ts - b.ts)
    for (let i = 0; i + 1 < list.length; i++) {
      segs.push({
        path: [list[i].coords, list[i + 1].coords],
        timestamps: [list[i].ts, list[i + 1].ts],
      })
    }
  }
  return segs
})

const intersectionArcs = computed(() => {
  const arcs: Array<{ source: [number, number]; target: [number, number]; weight: number }> = []
  for (const mem of atlas.memories) {
    for (const oth of atlas.others) {
      const dx = Math.abs(mem.coords[0] - oth.coords[0])
      const dy = Math.abs(mem.coords[1] - oth.coords[1])
      if (dx < 0.5 && dy < 0.5 && (dx > 0.001 || dy > 0.001)) {
        arcs.push({ source: mem.coords, target: oth.coords, weight: oth.weight })
      }
    }
  }
  return arcs.slice(0, 200)
})

function memoryHue(m: MemoryWithCoords): [number, number, number] {
  const e = ((m as any).emotionTag || (m as any).emotion || '').toString().toLowerCase()
  if (e.includes('喜') || e.includes('joy') || e.includes('happy')) return [255, 196, 64]
  if (e.includes('怀') || e.includes('nostalg')) return [189, 130, 255]
  if (e.includes('悲') || e.includes('sad')) return [80, 170, 255]
  if (e.includes('愤') || e.includes('anger') || e.includes('rage')) return [255, 90, 110]
  if (e.includes('迷') || e.includes('confused')) return [120, 220, 220]
  const [tMin, tMax] = timeRange.value
  const r = (m.ts - tMin) / Math.max(1, tMax - tMin)
  return rainbow(r)
}

function buildLayers() {
  const layers: any[] = []
  const [tMin, tMax] = timeRange.value
  // v7：trailLength 必须 >= 整段时间跨度，保证从最早一段到 currentTime 都在窗口内 —
  // 否则旧 segment 渲染为空（fadeTrail 默认 true，超出窗口直接消失）。
  const span = Math.max(1, tMax - tMin)
  const trailLength = span * 2 + 365 * 86400  // 时间跨度 ×2 再加一年保险
  const pulse = (Math.sin(Date.now() / 600) + 1) / 2

  if (layerVis.aura) {
    layers.push(
      new ScatterplotLayer({
        id: 'memory-aura-halo',
        data: atlas.memories,
        pickable: false,
        radiusUnits: 'pixels',
        getPosition: (d: MemoryWithCoords) => d.coords,
        getRadius: (d: MemoryWithCoords) => {
          const txt = (d.description ?? '').length + (d.title ?? '').length
          return 22 + Math.min(40, txt / 7) + pulse * 6
        },
        getFillColor: (d: MemoryWithCoords) => {
          const [r, g, b] = memoryHue(d)
          return [r, g, b, 60] as any
        },
        radiusMinPixels: 16,
      }),
    )
    layers.push(
      new ScatterplotLayer({
        id: 'memory-aura',
        data: atlas.memories,
        pickable: true,
        radiusUnits: 'pixels',
        radiusMinPixels: 9,
        radiusMaxPixels: 30,
        getPosition: (d: MemoryWithCoords) => d.coords,
        getRadius: (d: MemoryWithCoords) => {
          const txt = (d.description ?? '').length + (d.title ?? '').length
          const weight = Math.max(0.4, 1 - (d.fadeLevel ?? 0))
          return 10 + Math.min(16, txt / 22) * weight
        },
        getFillColor: (d: MemoryWithCoords) => {
          const [r, g, b] = memoryHue(d)
          return [r, g, b, 245] as any
        },
        stroked: true,
        getLineColor: [255, 255, 255, 235],
        lineWidthMinPixels: 2,
        onClick: (info: any) => {
          if (info?.object) openDetail(info.object as MemoryWithCoords)
        },
        onHover: (info: any) => {
          if (info?.object && info.x != null && info.y != null) {
            hoverInfo.value = { x: info.x, y: info.y, mem: info.object as MemoryWithCoords }
          } else {
            hoverInfo.value = null
          }
        },
      }),
    )
  }

  if (layerVis.trips && personalTrips.value.length > 0) {
    // v7：先用 PathLayer 渲染一条永远可见的彩色路径作为"时空流光"的底（不依赖 currentTime）；
    // 它在 globe / flat / tilt 三种投影下都能稳定渲染，避免 TripsLayer 因时间窗口或
    // 投影特性导致看不见。线宽 10~16 像素，外圈白描边增强对比，颜色按时间索引 hsl 渐变。
    layers.push(
      new PathLayer({
        id: 'personal-path-outline',
        data: personalTrips.value,
        getPath: (d: any) => d.path,
        getWidth: 4,
        widthUnits: 'pixels',
        widthMinPixels: 12,
        widthMaxPixels: 18,
        getColor: [255, 255, 255, 200],
        capRounded: true,
        jointRounded: true,
      } as any),
    )
    layers.push(
      new PathLayer({
        id: 'personal-path-base',
        data: personalTrips.value,
        getPath: (d: any) => d.path,
        getWidth: 3,
        widthUnits: 'pixels',
        widthMinPixels: 9,
        widthMaxPixels: 14,
        getColor: (d: any) => [d.color[0], d.color[1], d.color[2], 235] as any,
        capRounded: true,
        jointRounded: true,
      } as any),
    )
    // 在 PathLayer 之上叠 TripsLayer 形成"流光跑动"效果（动画感）
    layers.push(
      new TripsLayer({
        id: 'personal-trips',
        data: personalTrips.value,
        getPath: (d: any) => d.path,
        getTimestamps: (d: any) => d.timestamps,
        getColor: (d: any) => [
          Math.min(255, d.color[0] + 40),
          Math.min(255, d.color[1] + 40),
          Math.min(255, d.color[2] + 40),
          255,
        ] as any,
        opacity: 1,
        widthMinPixels: 4,
        widthMaxPixels: 7,
        trailLength,
        currentTime: currentTime.value,
        capRounded: true,
        jointRounded: true,
      } as any),
    )
    // 段端点：每条记忆位置加一颗呼吸彩珠，确保即便只有 1~2 个点也能立刻看见
    layers.push(
      new ScatterplotLayer({
        id: 'personal-path-nodes',
        data: memoriesByTime.value,
        pickable: false,
        radiusUnits: 'pixels',
        radiusMinPixels: 5,
        radiusMaxPixels: 12,
        getPosition: (d: MemoryWithCoords) => d.coords,
        getRadius: 6 + pulse * 3,
        getFillColor: (d: MemoryWithCoords) => {
          const [r, g, b] = memoryHue(d)
          return [r, g, b, 255] as any
        },
        stroked: true,
        getLineColor: [255, 255, 255, 255],
        lineWidthMinPixels: 2,
      }),
    )
  }

  if (layerVis.others && othersTrips.value.length > 0) {
    layers.push(
      new TripsLayer({
        id: 'others-particles',
        data: othersTrips.value,
        getPath: (d: any) => d.path,
        getTimestamps: (d: any) => d.timestamps,
        getColor: [255, 170, 0, 170],
        opacity: 0.55,
        widthMinPixels: 4,
        widthMaxPixels: 7,
        trailLength: trailLength * 0.6,
        currentTime: currentTime.value,
        capRounded: true,
      } as any),
    )
    layers.push(
      new ScatterplotLayer({
        id: 'others-points',
        data: atlas.others,
        pickable: false,
        radiusUnits: 'pixels',
        radiusMinPixels: 4,
        radiusMaxPixels: 16,
        getPosition: (d) => d.coords,
        getRadius: (d) => 5 + Math.min(14, d.weight * 14),
        getFillColor: [255, 170, 0, 190],
      }),
    )
  }

  if (layerVis.physical && atlas.myLocation?.coords) {
    const here = atlas.myLocation.coords
    layers.push(
      new ScatterplotLayer({
        id: 'current-location-ripple',
        data: [{ coords: here }],
        radiusUnits: 'pixels',
        radiusMinPixels: 26 + pulse * 32,
        radiusMaxPixels: 26 + pulse * 32,
        getPosition: (d: any) => d.coords,
        getRadius: 0,
        getFillColor: [0, 220, 130, 55 - pulse * 45],
        stroked: true,
        getLineColor: [0, 220, 130, 230 - pulse * 160],
        lineWidthMinPixels: 2.5,
      }),
    )
    layers.push(
      new ScatterplotLayer({
        id: 'current-location-core',
        data: [{ coords: here }],
        radiusUnits: 'pixels',
        radiusMinPixels: 10,
        radiusMaxPixels: 16,
        getPosition: (d: any) => d.coords,
        getRadius: 12,
        getFillColor: [0, 220, 130, 255],
        stroked: true,
        getLineColor: [255, 255, 255, 240],
        lineWidthMinPixels: 1.8,
      }),
    )
  }

  if (layerVis.others && intersectionArcs.value.length > 0) {
    layers.push(
      new ArcLayer({
        id: 'intersection-arcs',
        data: intersectionArcs.value,
        getSourcePosition: (d) => d.source,
        getTargetPosition: (d) => d.target,
        getSourceColor: [0, 220, 130, 220],
        getTargetColor: [255, 170, 0, 220],
        getWidth: (d) => 2 + d.weight * 3,
        greatCircle: false,
      }),
    )
  }

  return layers
}

/* ============================================================
 * 5) MapLibre + deck.gl lifecycle
 * ============================================================ */
let map: maplibregl.Map | null = null
let overlay: MapboxOverlay | null = null
let rafHandle = 0
let autoRotateRaf = 0
let starsAnimId = 0
let containerResizeObserver: ResizeObserver | null = null

/**
 * 三种底图样式（v9）。
 *
 * 设计原则：
 *   1. 每种样式的 `tiles` 数组里既包含国内可达源也包含国外可达源，MapLibre 会
 *      自动按顺序轮询，保证国内国外用户都能看到至少一种瓦片。
 *   2. `projection.type = 'globe'` 放在 style 内（MapLibre v5+ 推荐方式）。
 *   3. 都加 `sky` layer 填充"画外区"，避免低 zoom 时北极外露出三角黑块。
 *   4. `attribution` 必须保留（CartoDB / Esri / OSM / 高德的 ToS 都要求）。
 */

interface BasemapStyle {
  id: 'imagery' | 'street' | 'physical'
  /** 给底图切换器显示的 i18n key 后缀 */
  labelKey: string
  /** MapLibre style JSON */
  style: any
}

/** 影像（Esri World Imagery，全球卫星照片，海外网络可达，国内通过 CDN 也可达） */
const STYLE_IMAGERY: any = {
  version: 8,
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  projection: { type: 'globe' },
  sources: {
    imagery: {
      type: 'raster',
      tiles: [
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      ],
      tileSize: 256,
      attribution:
        '© <a href="https://www.esri.com" target="_blank" rel="noopener">Esri</a> World Imagery',
      maxzoom: 19,
    },
  },
  layers: [
    // sky 必须放在 raster 层之前，作为 globe 投影的"画外区"星空背景，避免低 zoom
    // 时露出三角黑块。
    { id: 'sky', type: 'background', paint: { 'background-color': '#050714' } },
    { id: 'imagery', type: 'raster', source: 'imagery', minzoom: 0, maxzoom: 22 },
  ],
}

/** 街道（高德 webrd 国内 + Carto Voyager 海外，矢量风格，看路名/道路/POI） */
const STYLE_STREET: any = {
  version: 8,
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  projection: { type: 'globe' },
  sources: {
    street: {
      type: 'raster',
      tiles: [
        // 高德地图 webrd —— 国内可达，与 Google Maps 接近的栅格化矢量瓦片
        'https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        // Carto Voyager —— 海外可达兜底（高德海外瓦片质量较差，Voyager 接近 Google Maps）
        'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
      ],
      tileSize: 256,
      attribution:
        '© <a href="https://amap.com" target="_blank" rel="noopener">高德地图</a> · <a href="https://carto.com/attributions" target="_blank" rel="noopener">CARTO</a>',
      maxzoom: 18,
    },
  },
  layers: [
    { id: 'sky', type: 'background', paint: { 'background-color': '#050714' } },
    { id: 'street', type: 'raster', source: 'street', minzoom: 0, maxzoom: 22 },
  ],
}

/** 自然分层设色（Esri World Physical Map，海洋深蓝、土地黄褐、山脉金棕的卫星模拟色） */
const STYLE_PHYSICAL: any = {
  version: 8,
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  projection: { type: 'globe' },
  sources: {
    physical: {
      type: 'raster',
      tiles: [
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Physical_Map/MapServer/tile/{z}/{y}/{x}',
      ],
      tileSize: 256,
      attribution:
        '© <a href="https://www.esri.com" target="_blank" rel="noopener">Esri</a> World Physical Map',
      // World Physical 只有低层级瓦片（最高 z=8），高 zoom 时叠加 World Shaded Relief。
      maxzoom: 8,
    },
    relief: {
      type: 'raster',
      tiles: [
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Shaded_Relief/MapServer/tile/{z}/{y}/{x}',
      ],
      tileSize: 256,
      maxzoom: 13,
    },
  },
  layers: [
    { id: 'sky', type: 'background', paint: { 'background-color': '#050714' } },
    { id: 'physical', type: 'raster', source: 'physical', minzoom: 0, maxzoom: 8 },
    { id: 'relief', type: 'raster', source: 'relief', minzoom: 8, maxzoom: 22, paint: { 'raster-opacity': 0.92 } },
  ],
}

const BASEMAP_STYLES: Record<BasemapStyle['id'], BasemapStyle> = {
  physical: { id: 'physical', labelKey: 'physical', style: STYLE_PHYSICAL },
  imagery: { id: 'imagery', labelKey: 'imagery', style: STYLE_IMAGERY },
  street: { id: 'street', labelKey: 'street', style: STYLE_STREET },
}

/** 当前底图样式 —— 默认 physical（地球分层设色，符合"地球"直觉） */
const currentBasemap = ref<BasemapStyle['id']>('physical')

function rebuildOverlay() {
  if (!overlay) return
  overlay.setProps({ layers: buildLayers() })
}

function startAnimation() {
  let last = performance.now()
  const loop = () => {
    const now = performance.now()
    const dt = (now - last) / 1000
    last = now
    if (playing.value) {
      const [tMin, tMax] = timeRange.value
      const span = Math.max(1, tMax - tMin)
      currentTime.value = currentTime.value + (span / 60) * playbackSpeed.value * dt
      if (currentTime.value > tMax) currentTime.value = tMin
    }
    rebuildOverlay()
    rafHandle = requestAnimationFrame(loop)
  }
  rafHandle = requestAnimationFrame(loop)
}

function startGlobeAutoRotate() {
  const rotate = () => {
    if (!map || viewMode.value !== 'globe') {
      autoRotateRaf = 0
      return
    }
    const c = map.getCenter()
    map.jumpTo({ center: [c.lng + 0.06, c.lat] })
    autoRotateRaf = requestAnimationFrame(rotate)
  }
  cancelAnimationFrame(autoRotateRaf)
  autoRotateRaf = requestAnimationFrame(rotate)
}
function stopGlobeAutoRotate() {
  if (autoRotateRaf) cancelAnimationFrame(autoRotateRaf)
  autoRotateRaf = 0
}

/* ---- 闪烁星空 canvas（globe 模式专属，独立于 MapLibre） ---- */
interface Star { x: number; y: number; r: number; tw: number; phase: number; hue: number }
let stars: Star[] = []
function initStars() {
  const canvas = starsCanvasRef.value
  if (!canvas) return
  const dpr = window.devicePixelRatio || 1
  canvas.width = canvas.clientWidth * dpr
  canvas.height = canvas.clientHeight * dpr
  const count = Math.min(360, Math.floor((canvas.width * canvas.height) / 9000))
  stars = []
  for (let i = 0; i < count; i++) {
    stars.push({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      r: 0.6 + Math.random() * 1.6,
      tw: 0.7 + Math.random() * 2.4,
      phase: Math.random() * Math.PI * 2,
      hue: 180 + Math.random() * 80,
    })
  }
}
function drawStars(t: number) {
  const canvas = starsCanvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // 仅在 globe 模式绘星空，否则清空
  if (viewMode.value !== 'globe') {
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    return
  }
  // 微弱背景渐变（夜空）
  const grad = ctx.createRadialGradient(
    canvas.width / 2, canvas.height / 2, canvas.height * 0.1,
    canvas.width / 2, canvas.height / 2, Math.max(canvas.width, canvas.height) * 0.65,
  )
  grad.addColorStop(0, 'rgba(11,15,34,1)')
  grad.addColorStop(0.6, 'rgba(7,11,24,1)')
  grad.addColorStop(1, 'rgba(2,4,12,1)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  // 流星：每 ~3s 出现一颗短轨迹
  const shootingT = (t / 3000) % 1
  if (shootingT < 0.18) {
    const ax = (canvas.width * 0.05) + shootingT * canvas.width * 5
    const ay = (canvas.height * 0.1) + shootingT * canvas.height * 1.8
    ctx.strokeStyle = 'rgba(180,220,255,' + (1 - shootingT / 0.18) + ')'
    ctx.lineWidth = 1.2
    ctx.beginPath()
    ctx.moveTo(ax, ay)
    ctx.lineTo(ax - 60, ay - 24)
    ctx.stroke()
  }
  // 星点：明暗交替
  for (const s of stars) {
    const a = 0.45 + 0.55 * Math.sin(t / 600 * s.tw + s.phase)
    ctx.beginPath()
    ctx.fillStyle = `hsla(${s.hue}, 80%, 90%, ${a.toFixed(3)})`
    ctx.shadowColor = `hsla(${s.hue}, 90%, 80%, ${(a * 0.9).toFixed(3)})`
    ctx.shadowBlur = s.r * 3.4
    ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.shadowBlur = 0
}
function startStarLoop() {
  const loop = (t: number) => {
    drawStars(t)
    starsAnimId = requestAnimationFrame(loop)
  }
  starsAnimId = requestAnimationFrame(loop)
}
function stopStarLoop() {
  if (starsAnimId) cancelAnimationFrame(starsAnimId)
  starsAnimId = 0
}

onMounted(async () => {
  if (!containerRef.value) return

  map = new maplibregl.Map({
    container: containerRef.value,
    style: BASEMAP_STYLES[currentBasemap.value].style,
    // v9：默认 center 移到 [50, 15]（欧洲/非洲/亚洲交界），让球体的"前面"显示
    // 整个东半球而不是只对着中国。用户可以自由拨弄看到南半球。
    center: [50, 15],
    zoom: 1.6,
    pitch: 0,
    bearing: 0,
    // 注：projection 已经在 style 内声明（MapLibre v5 推荐方式），这里不再重复传。
    attributionControl: { compact: true },
    dragRotate: true,
    // 注：globe 投影下不能用 maxBounds + renderWorldCopies=false，否则地球会被
    // mercator 的世界包围盒裁切，渲染出"一半黑屏"。这两项只在切到 flat 时再加。
    minZoom: 0.4,
    maxZoom: 18,
  })

  // 监听容器尺寸 — onMounted 同步 new Map 时 .atlas-root 可能还没完成 layout，
  // 容器是 0×0 → maplibre 渲染空白；观测到尺寸变化后调 resize() 让其自动适配。
  // 之前没有这道兜底，路由切换 / window resize / DevTools 拖拽都会留下"半屏黑"。
  const ro = new ResizeObserver(() => {
    try { map?.resize() } catch { /* noop */ }
    // 同步重画星空 canvas（它的 width/height 跟着容器走）
    initStars()
  })
  ro.observe(containerRef.value)
  containerResizeObserver = ro
  // 首屏强制 resize 一次（路由切换后偶尔不发尺寸事件）
  setTimeout(() => { try { map?.resize() } catch {} }, 80)

  // 底图瓦片拉不到时（少数极端环境）→ 自动切换到下一个 basemap 候选。每个 style
  // 的 tiles 数组本身已经做了多源轮询；这一层兜底是"如果当前样式整体的所有源都
  // 不可达，自动跳到另一个样式让用户至少看到一张图"。
  let switchedFallback = false
  const trySwitchFallback = () => {
    if (!map || switchedFallback) return
    switchedFallback = true
    // 当前样式整体不可达 → 按 physical → imagery → street 顺序找下一个未尝试过的
    const candidates: BasemapStyle['id'][] = ['physical', 'imagery', 'street']
    const next = candidates.find((c) => c !== currentBasemap.value)
    if (next) {
      currentBasemap.value = next
      try { map.setStyle(BASEMAP_STYLES[next].style) } catch { /* noop */ }
    }
  }
  map.on('error', (e: any) => {
    if (!map || switchedFallback) return
    const status = e?.error?.status as number | undefined
    const msg = (e?.error?.message || '').toLowerCase()
    if (
      (status !== undefined && status >= 400) ||
      msg.includes('tile') ||
      msg.includes('source') ||
      msg.includes('failed to fetch') ||
      msg.includes('network')
    ) {
      trySwitchFallback()
    }
  })
  // 6 秒兜底：如果首屏一片漆黑（当前 source 一张瓦片都没加载成功），强切下一个样式
  window.setTimeout(() => {
    if (!map || switchedFallback) return
    try {
      // 取当前 style 的第一个非 background source 的 id
      const style = map.getStyle()
      const sourceIds = Object.keys(style.sources || {})
      const firstSourceId = sourceIds[0]
      if (firstSourceId && !map.isSourceLoaded(firstSourceId)) trySwitchFallback()
    } catch { /* noop */ }
  }, 6000)

  // v9：用户首次交互（drag / wheel / click）后立刻停止地球自转，把控制权交还。
  // 这样南半球可以通过拖动浏览，不会被自转持续抢回。
  let userInteracted = false
  const stopAutoRotateOnInteract = () => {
    if (userInteracted) return
    userInteracted = true
    stopGlobeAutoRotate()
  }
  map.on('dragstart', stopAutoRotateOnInteract)
  map.on('wheel', stopAutoRotateOnInteract)
  map.on('rotatestart', stopAutoRotateOnInteract)
  map.on('pitchstart', stopAutoRotateOnInteract)

  map.addControl(new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }), 'top-right')
  map.touchZoomRotate.enableRotation()

  map.on('zoom', () => {
    if (!map) return
    currentZoom.value = map.getZoom()
    if (viewMode.value === 'flat' && shouldTilt(currentZoom.value)) {
      switchTo3D()
    }
    // v9：缩小到 zoom < 1.5 自动回地球 globe（与"放大滑入平面"的反向操作）。
    // 触发后立刻 break，防止反复抖动 reset。
    if (viewMode.value !== 'globe' && currentZoom.value < 1.5) {
      returnToGlobe()
    }
  })

  map.on('load', () => {
    if (!map) return
    overlay = new MapboxOverlay({ layers: buildLayers(), interleaved: false })
    map.addControl(overlay as unknown as maplibregl.IControl)
    rebuildOverlay()
    startAnimation()
    startGlobeAutoRotate()
  })

  map.on('click', (e) => {
    if (viewMode.value !== 'globe') return
    const lng = e.lngLat.lng
    const lat = e.lngLat.lat
    enterFlat(lng, lat)
  })

  // 星空 canvas 初始化
  initStars()
  startStarLoop()
  window.addEventListener('resize', initStars)

  await Promise.all([atlas.fetchAll(), refreshMinioResources()])
  const [, tMax] = timeRange.value
  currentTime.value = tMax
})

onBeforeUnmount(() => {
  if (rafHandle) cancelAnimationFrame(rafHandle)
  stopGlobeAutoRotate()
  stopStarLoop()
  rafHandle = 0
  window.removeEventListener('resize', initStars)
  try { containerResizeObserver?.disconnect() } catch { /* noop */ }
  containerResizeObserver = null
  try {
    if (overlay && map) map.removeControl(overlay as unknown as maplibregl.IControl)
  } catch { /* noop */ }
  overlay = null
  try { map?.remove() } catch { /* noop */ }
  map = null
})

watch(
  [
    () => atlas.memories,
    () => atlas.route,
    () => atlas.others,
    () => atlas.myLocation,
    () => layerVis.physical,
    () => layerVis.aura,
    () => layerVis.trips,
    () => layerVis.others,
  ],
  () => rebuildOverlay(),
  { deep: true },
)

/* ============================================================
 * 6) 视图模式切换
 * ============================================================ */
function enterFlat(lng: number, lat: number) {
  if (!map) return
  dismissHint()
  stopGlobeAutoRotate()
  enteredFlat.value = true
  viewMode.value = 'flat'
  map.flyTo({
    center: [lng, lat],
    zoom: 6,
    pitch: 0,
    bearing: 0,
    duration: 1600,
    essential: true,
  })
  window.setTimeout(() => {
    if (!map) return
    try {
      map.setProjection({ type: 'mercator' })
    } catch { /* noop */ }
  }, 1500)
}

function switchTo3D() {
  if (!map) return
  viewMode.value = 'tilt'
  map.easeTo({ pitch: 60, bearing: -15, duration: 800 })
}

function switchTo2D() {
  if (!map) return
  viewMode.value = 'flat'
  map.easeTo({ pitch: 0, bearing: 0, duration: 700 })
}

function returnToGlobe() {
  if (!map) return
  viewMode.value = 'globe'
  enteredFlat.value = false
  hintVisible.value = !hintDismissed.value
  selected.value = null
  try { map.setProjection({ type: 'globe' }) } catch { /* noop */ }
  map.flyTo({ center: [50, 15], zoom: 1.6, pitch: 0, bearing: 0, duration: 1400, essential: true })
  // v9：回到地球后 *不* 自动开旋转。用户已经交互过；旋转抢控制权是 v8 的痛点。
  // 想看自转的话刷新页面就回到初始状态。
}

/** v9：切换底图样式。三选一：physical / imagery / street */
function switchBasemap(id: BasemapStyle['id']) {
  if (!map || currentBasemap.value === id) return
  currentBasemap.value = id
  try { map.setStyle(BASEMAP_STYLES[id].style) } catch { /* noop */ }
  // setStyle 会把 sources / layers 全部 reset，但保留 camera 状态。
  // overlay (deck.gl) 需要在 style.load 后重新挂上。
  map.once('style.load', () => {
    if (!map || !overlay) return
    try { map.removeControl(overlay as unknown as maplibregl.IControl) } catch { /* noop */ }
    overlay = new MapboxOverlay({ layers: buildLayers(), interleaved: false })
    map.addControl(overlay as unknown as maplibregl.IControl)
  })
}

function flyToCurrent() {
  if (!map || !atlas.myLocation?.coords) return
  if (viewMode.value === 'globe') {
    enterFlat(atlas.myLocation.coords[0], atlas.myLocation.coords[1])
    return
  }
  map.flyTo({
    center: atlas.myLocation.coords,
    zoom: 11,
    pitch: viewMode.value === 'tilt' ? 60 : 0,
    bearing: viewMode.value === 'tilt' ? -15 : 0,
    duration: 1400,
    essential: true,
  })
}

function onScrub(ev: Event) {
  const v = Number((ev.target as HTMLInputElement).value)
  if (!Number.isFinite(v)) return
  currentTime.value = v
  rebuildOverlay()
}

function togglePlay() { playing.value = !playing.value }
function setSpeed(s: number) { playbackSpeed.value = s }

function openDetail(m: MemoryWithCoords) {
  selected.value = m
}
function closeDetail() { selected.value = null }

function dismissHint() {
  hintVisible.value = false
  hintDismissed.value = true
}

/* ============================================================
 * 7) 标签格式化
 * ============================================================ */
const fmtDate = (ts: number) => {
  if (!Number.isFinite(ts) || ts <= 0) return '—'
  return new Date(ts * 1000).toLocaleDateString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  })
}
const currentTimeLabel = computed(() => fmtDate(currentTime.value))
const tMinLabel = computed(() => fmtDate(timeRange.value[0]))
const tMaxLabel = computed(() => fmtDate(timeRange.value[1]))

const drillLabel = computed(() => {
  switch (drillLevel.value) {
    case 'world': return '世界'
    case 'country': return '国家'
    case 'province': return '省'
    case 'city': return '市'
    case 'county': return '县'
    default: return '—'
  }
})
const modeLabel = computed(() => {
  switch (viewMode.value) {
    case 'globe': return '球体地球'
    case 'flat': return '2D 平视'
    case 'tilt': return '3D 倾斜'
  }
  return ''
})

const stats = computed(() => ({
  memories: atlas.memories.length,
  others: atlas.others.length,
  segments: personalTrips.value.length,
  minio: minioCount.value,
  resources: resourceCount.value,
}))

const currentLocationLabel = computed(() => {
  const loc = atlas.myLocation as
    | (typeof atlas.myLocation & { city?: string; province?: string; country?: string })
    | null
  if (!loc?.coords) return '未授权 / 未记录'
  // AtlasLocation.name 是后端唯一保证存在的"地点名"；city/province/country 在
  // backend GeocodingService 命中富 anchor 时才会附带，类型上是可选的。
  const name = loc.name || loc.city || loc.province || loc.country
  if (name) return name
  return `${loc.coords[1].toFixed(2)}, ${loc.coords[0].toFixed(2)}`
})

const atlasStatus = computed(() => {
  if (loading.value) return '同步地图数据'
  if (error.value) return '地图数据异常'
  if (!atlas.memories.length) return '等待记忆坐标'
  if (playing.value) return '记忆流转中'
  return '时间游标静止'
})

const routeCoverage = computed(() => {
  if (!atlas.memories.length) return 0
  if (atlas.memories.length === 1) return 35
  return Math.min(100, Math.round((personalTrips.value.length / Math.max(1, atlas.memories.length - 1)) * 100))
})

function assetIcon(r: AssetResource): 'image' | 'video' | 'audio' {
  if (r.type === 'video') return 'video'
  if (r.type === 'audio') return 'audio'
  return 'image'
}

function timeState(mem: MemoryWithCoords): string {
  const delta = currentTime.value - mem.ts
  const windowSeconds = 30 * 86400
  if (delta < -windowSeconds) return '尚未流转到此节点'
  if (Math.abs(delta) <= windowSeconds) return '时间游标正在经过'
  if ((mem.fadeLevel ?? 0) > 0.55) return '已沉淀 · 轻微褪色'
  return '已沉淀 · 状态稳定'
}

function timeMeta(mem: MemoryWithCoords): string {
  const parts = [mem.memoryDate || mem.memoryYear, mem.memorySeason, mem.memoryTimeOfDay]
    .filter(Boolean)
    .map(String)
  return parts.length ? parts.join(' · ') : fmtDate(mem.ts)
}
</script>

<template>
  <div class="atlas-root">
    <!-- v6：球体模式专属的真实星空背景（canvas，明暗交替闪烁） -->
    <canvas
      ref="starsCanvasRef"
      class="atlas-stars"
      :class="{ 'atlas-stars--visible': viewMode === 'globe' }"
    />

    <div ref="containerRef" class="atlas-map" />

    <!-- v9：底图样式切换器，放在右上角紧贴 NavigationControl 下方 -->
    <div class="atlas-basemap" role="tablist" aria-label="底图样式">
      <button
        v-for="b in (['physical', 'imagery', 'street'] as const)"
        :key="b"
        class="atlas-basemap__btn"
        :class="{ 'atlas-basemap__btn--on': currentBasemap === b }"
        :aria-selected="currentBasemap === b"
        role="tab"
        type="button"
        @click="switchBasemap(b)"
      >
        {{ b === 'physical' ? '自然' : b === 'imagery' ? '影像' : '街道' }}
      </button>
    </div>

    <!-- v8：空状态也放在右下角，避免遮挡地图主体；详情卡打开时退场 -->
    <div v-if="showNoCoordsHint && !selected" class="atlas-empty-hint" role="status">
      <div class="atlas-empty-hint__icon" aria-hidden="true">🗺️</div>
      <div class="atlas-empty-hint__body">
        <p class="atlas-empty-hint__title">{{ t('atlas.emptyHints.noCoordsTitle') }}</p>
        <i18n-t keypath="atlas.emptyHints.noCoordsBody" tag="p" class="atlas-empty-hint__sub">
          <template #link>
            <router-link to="/memories/new" class="atlas-empty-hint__link">
              {{ t('atlas.emptyHints.noCoordsLink') }}
            </router-link>
          </template>
        </i18n-t>
        <p class="atlas-empty-hint__sub atlas-empty-hint__sub--small">
          {{ t('atlas.emptyHints.noCoordsFooter') }}
        </p>
      </div>
    </div>

    <div v-else-if="showOneCoordHint && !selected" class="atlas-empty-hint atlas-empty-hint--soft" role="status">
      <div class="atlas-empty-hint__icon" aria-hidden="true">🌊</div>
      <div class="atlas-empty-hint__body">
        <p class="atlas-empty-hint__title">{{ t('atlas.emptyHints.oneCoordTitle') }}</p>
        <i18n-t keypath="atlas.emptyHints.oneCoordBody" tag="p" class="atlas-empty-hint__sub">
          <template #link>
            <router-link to="/memories/new" class="atlas-empty-hint__link">
              {{ t('atlas.emptyHints.oneCoordLink') }}
            </router-link>
          </template>
        </i18n-t>
      </div>
    </div>

    <!-- v5/v6：入口提示卡 → 右下角浮卡，不再遮挡地球；详情卡 / 空状态打开时退场 -->
    <div
      v-if="viewMode === 'globe' && hintVisible && !showNoCoordsHint && !showOneCoordHint && !selected"
      class="atlas-hint"
      role="status"
    >
      <button class="atlas-hint__close" @click="dismissHint" aria-label="关闭提示">✕</button>
      <div class="atlas-hint__title">
        <span class="atlas-hint__pulse"></span>
        时空记忆地图
      </div>
      <p class="atlas-hint__line">拖动旋转地球 · 滚轮缩放</p>
      <p class="atlas-hint__line">单击地球任意位置进入平面</p>
      <div class="atlas-hint__meta">
        记忆 {{ stats.memories }} · 共鸣 {{ stats.others }} · 资源 {{ stats.resources }}
      </div>
    </div>

    <section class="atlas-status-card" :class="{ 'atlas-status-card--shifted': selected }" aria-label="地图状态">
      <div class="atlas-status-card__head">
        <span>记忆状态</span>
        <strong>{{ atlasStatus }}</strong>
      </div>
      <div class="atlas-status-card__row">
        <span>当前位置</span>
        <b>{{ currentLocationLabel }}</b>
      </div>
      <div class="atlas-status-card__row">
        <span>时间游标</span>
        <b>{{ currentTimeLabel }}</b>
      </div>
      <div class="atlas-status-card__row">
        <span>轨迹段</span>
        <b>{{ stats.segments }}</b>
      </div>
      <div class="atlas-status-card__progress" aria-label="记忆流覆盖度">
        <i :style="{ width: routeCoverage + '%' }"></i>
      </div>
      <div class="atlas-status-card__media">
        <span>图 {{ mediaTypeStats.photo }}</span>
        <span>影 {{ mediaTypeStats.video }}</span>
        <span>声 {{ mediaTypeStats.audio }}</span>
        <span>动 {{ mediaTypeStats.gif }}</span>
      </div>
    </section>

    <!-- 左上控制面板 -->
    <section class="atlas-panel">
      <header class="atlas-panel__head">
        <span class="atlas-panel__title">时空记忆地图</span>
        <span class="atlas-panel__chip">{{ modeLabel }} · {{ drillLabel }} · z{{ currentZoom.toFixed(1) }}</span>
      </header>

      <div class="atlas-panel__row">
        <button
          class="atlas-btn"
          :class="{ 'atlas-btn--on': viewMode === 'tilt' }"
          :disabled="viewMode === 'globe'"
          @click="viewMode === 'tilt' ? switchTo2D() : switchTo3D()"
        >
          {{ viewMode === 'tilt' ? '平视' : '3D 倾斜' }}
        </button>
        <button class="atlas-btn" :disabled="viewMode === 'globe'" @click="returnToGlobe">
          回到地球
        </button>
        <button class="atlas-btn" :disabled="!atlas.myLocation?.coords" @click="flyToCurrent">
          当前位置
        </button>
      </div>

      <div class="atlas-panel__row atlas-panel__row--toggles">
        <label class="atlas-toggle">
          <input type="checkbox" v-model="layerVis.physical" />
          <span>实体位置</span>
        </label>
        <label class="atlas-toggle">
          <input type="checkbox" v-model="layerVis.aura" />
          <span>记忆气场</span>
        </label>
        <label class="atlas-toggle">
          <input type="checkbox" v-model="layerVis.trips" />
          <span>七彩记忆流</span>
        </label>
        <label class="atlas-toggle">
          <input type="checkbox" v-model="layerVis.others" />
          <span>他人网络</span>
        </label>
      </div>

      <footer class="atlas-panel__foot">
        <span>记忆 {{ stats.memories }}</span>
        <span>·</span>
        <span>他人 {{ stats.others }}</span>
        <span>·</span>
        <span>轨迹 {{ stats.segments }}</span>
        <span>·</span>
        <span>资源 {{ stats.resources }}</span>
        <span>·</span>
        <span>MinIO {{ stats.minio }}</span>
      </footer>
    </section>

    <!-- 时间轴 -->
    <section v-if="viewMode !== 'globe'" class="atlas-time">
      <div class="atlas-time__head">
        <span>{{ tMinLabel }}</span>
        <span class="atlas-time__cursor">{{ currentTimeLabel }}</span>
        <span>{{ tMaxLabel }}</span>
      </div>
      <input
        class="atlas-time__slider"
        type="range"
        :min="timeRange[0]"
        :max="timeRange[1]"
        :step="86400"
        :value="currentTime"
        @input="onScrub"
      />
      <div class="atlas-time__ctrl">
        <button class="atlas-btn atlas-btn--ghost" @click="togglePlay">
          {{ playing ? '暂停' : '播放' }}
        </button>
        <button
          v-for="s in [0.5, 1, 2, 4]"
          :key="s"
          class="atlas-btn atlas-btn--ghost"
          :class="{ 'atlas-btn--on': playbackSpeed === s }"
          @click="setSpeed(s)"
        >x{{ s }}</button>
      </div>
    </section>

    <!-- 悬停浮卡：地理 + 时间 状态 -->
    <div
      v-if="hoverInfo && !selected"
      class="atlas-hover"
      :style="{ left: hoverInfo.x + 'px', top: hoverInfo.y + 'px' }"
    >
      <div class="atlas-hover__title">{{ hoverInfo.mem.title || '未命名记忆' }}</div>
      <div class="atlas-hover__line">
        {{ fmtDate(hoverInfo.mem.ts) }} · {{ timeState(hoverInfo.mem) }}
      </div>
      <div class="atlas-hover__chips">
        <span class="atlas-hover__chip">{{ hoverGeo.country }}</span>
        <span class="atlas-hover__chip">{{ hoverGeo.province }}</span>
        <span class="atlas-hover__chip">{{ hoverGeo.city }}</span>
        <span class="atlas-hover__chip">{{ hoverGeo.county }}</span>
      </div>
    </div>

    <!-- 详情卡 -->
    <aside v-if="selected" class="atlas-detail">
      <button class="atlas-detail__close" @click="closeDetail">✕</button>
      <h3 class="atlas-detail__title">{{ selected.title || '未命名记忆' }}</h3>

      <div class="atlas-detail__geo">
        <div class="atlas-detail__geo-row">
          <span class="atlas-detail__geo-k">国家</span>
          <span class="atlas-detail__geo-v">{{ selectedGeo.country }}</span>
        </div>
        <div class="atlas-detail__geo-row">
          <span class="atlas-detail__geo-k">省 / 自治区</span>
          <span class="atlas-detail__geo-v">{{ selectedGeo.province }}</span>
        </div>
        <div class="atlas-detail__geo-row">
          <span class="atlas-detail__geo-k">市</span>
          <span class="atlas-detail__geo-v">{{ selectedGeo.city }}</span>
        </div>
        <div class="atlas-detail__geo-row">
          <span class="atlas-detail__geo-k">县 / 区</span>
          <span class="atlas-detail__geo-v">{{ selectedGeo.county }}</span>
        </div>
      </div>

      <p class="atlas-detail__date">
        {{ timeMeta(selected) }} · {{ timeState(selected) }}
      </p>
      <p class="atlas-detail__policy">
        地理展示精度最多到县 / 区级；删除记忆节点时不会展示更细的街道、门牌或私人坐标。
      </p>
      <p v-if="selected.description" class="atlas-detail__desc">{{ selected.description }}</p>

      <div class="atlas-detail__media-head">
        MinIO 媒体（{{ selectedMedia.length }}）
        <span v-if="minioLoading" class="atlas-detail__media-loading">…</span>
      </div>
      <div v-if="selectedMedia.length" class="atlas-detail__media">
        <template v-for="(r, i) in selectedMedia" :key="i">
          <img v-if="assetIcon(r) === 'image'" :src="r.path" class="atlas-detail__img" :alt="r.name" />
          <video
            v-else-if="assetIcon(r) === 'video'"
            :src="r.path"
            class="atlas-detail__video"
            controls
            preload="metadata"
          />
          <audio
            v-else-if="assetIcon(r) === 'audio'"
            :src="r.path"
            class="atlas-detail__audio"
            controls
            preload="metadata"
          />
        </template>
      </div>
      <p v-else class="atlas-detail__media-empty">
        暂未找到与该记忆匹配的媒体。文件名包含记忆 id / 地点关键字时自动关联。
      </p>
    </aside>

    <!-- 横幅 -->
    <div v-if="loading" class="atlas-banner">
      <span class="atlas-banner__spinner"></span>
      正在加载地图数据…
    </div>
    <div v-else-if="error" class="atlas-banner atlas-banner--err">加载失败：{{ error }}</div>
  </div>
</template>

<style scoped>
.atlas-root {
  /* 用 AppHeader 实时发布的 --app-header-h 兜底 88px。这样 1100px 以下 nav wrap
     成两行（实际 header ~140px）时 atlas 也不会被压住。 */
  position: fixed;
  top: var(--app-header-h, 88px);
  bottom: 0;
  left: 0;
  right: 0;
  background: #050714;
  color: #1c2533;
  overflow: hidden;
  font-family: 'Inter', 'PingFang SC', sans-serif;
  z-index: 1;
}
@media (max-width: 768px) {
  /* 移动端 fallback：如果 ResizeObserver 还没跑就先用一个小值 */
  .atlas-root { top: var(--app-header-h, 76px); }
}

/* v6：星空 canvas — 默认隐藏；只在 globe 模式淡入显示在地图后面 */
.atlas-stars {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 0;
  transition: opacity 600ms ease;
  z-index: 0;
}
.atlas-stars--visible { opacity: 1; }

/* ---- v9：底图样式切换器（右上角，NavigationControl 下方） ---- */
.atlas-basemap {
  position: absolute;
  top: 110px;
  right: 22px;
  display: inline-flex;
  padding: 4px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(14, 165, 233, 0.25);
  border-radius: 999px;
  box-shadow: 0 4px 16px rgba(14, 165, 233, 0.12);
  backdrop-filter: blur(8px);
  z-index: 10;
}
.atlas-basemap__btn {
  appearance: none;
  border: none;
  background: transparent;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}
.atlas-basemap__btn:hover { color: #0ea5e9; }
.atlas-basemap__btn--on {
  background: linear-gradient(135deg, #0ea5e9, #38bdf8);
  color: #fff;
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.35);
}

.atlas-map {
  position: absolute;
  inset: 0;
  z-index: 1;
  background: transparent;
}

:deep(.maplibregl-canvas) { outline: none; background: transparent; }
:deep(.maplibregl-canvas-container) { background: transparent; }
:deep(.maplibregl-ctrl-bottom-right) { background: transparent; }
:deep(.maplibregl-ctrl-attrib) {
  background: rgba(255, 255, 255, 0.78);
  color: #475569;
  font-size: 10px;
}
:deep(.maplibregl-ctrl-attrib a) { color: #0ea5e9; }
:deep(.maplibregl-ctrl-group) {
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(14, 165, 233, 0.25);
  backdrop-filter: blur(8px);
  box-shadow: 0 4px 16px rgba(14, 165, 233, 0.12);
}

/* ---- v8：空数据浮卡（右下角，不遮挡地图主体） ---- */
.atlas-empty-hint {
  position: absolute;
  right: 22px;
  bottom: 22px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 8px;
  background: rgba(20, 28, 38, 0.92);
  border: 1px solid rgba(245, 158, 11, 0.45);
  box-shadow: 0 16px 40px rgba(0,0,0,0.4);
  color: #f1f5f9;
  z-index: 14;
  max-width: 430px;
  animation: hint-slide-in 0.4s cubic-bezier(.2,.9,.3,1) both;
}
.atlas-empty-hint__icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.14);
  border: 1px solid rgba(245, 158, 11, 0.32);
  color: #fbbf24;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 800;
}
.atlas-empty-hint--soft {
  border-color: rgba(56, 189, 248, 0.45);
}
.atlas-empty-hint__title {
  margin: 0;
  font-size: 14px; font-weight: 700;
  background: linear-gradient(90deg, #facc15, #f97316);
  -webkit-background-clip: text; background-clip: text;
  color: transparent;
}
.atlas-empty-hint__sub { margin: 4px 0 0; font-size: 12px; color: #cbd5e1; }
.atlas-empty-hint__sub--small { font-size: 11px; color: #94a3b8; }
.atlas-empty-hint__link {
  color: #38bdf8; font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

/* ---- 右下角入口提示卡 ---- */
.atlas-hint {
  position: absolute;
  right: 22px;
  bottom: 22px;
  width: 268px;
  padding: 14px 16px 12px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.82));
  backdrop-filter: blur(18px);
  border: 1px solid rgba(14, 165, 233, 0.3);
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.18);
  z-index: 9;
  animation: hint-slide-in 0.42s cubic-bezier(.2,.9,.3,1) both;
}
@keyframes hint-slide-in {
  from { opacity: 0; transform: translateY(14px); }
  to   { opacity: 1; transform: translateY(0); }
}
.atlas-hint__close {
  position: absolute; top: 6px; right: 8px;
  background: transparent; border: none;
  font-size: 13px; color: #94a3b8; cursor: pointer;
}
.atlas-hint__close:hover { color: #0ea5e9; }
.atlas-hint__title {
  display: flex; align-items: center; gap: 8px;
  font-size: 14px; font-weight: 700;
  letter-spacing: 0;
  color: #0f172a;
  margin-bottom: 8px;
}
.atlas-hint__pulse {
  width: 8px; height: 8px; border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.65);
  animation: hint-pulse 1.8s infinite;
}
@keyframes hint-pulse {
  0%   { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.65); }
  70%  { box-shadow: 0 0 0 10px rgba(16, 185, 129, 0);   }
  100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0);      }
}
.atlas-hint__line { margin: 3px 0; font-size: 12px; color: #475569; }
.atlas-hint__meta {
  margin-top: 8px; padding-top: 8px;
  border-top: 1px dashed rgba(14, 165, 233, 0.2);
  font-size: 11px; color: #64748b;
  font-family: 'JetBrains Mono', monospace;
}

.atlas-status-card {
  position: absolute;
  right: 22px;
  /* v9：状态卡贴在底图切换器下方（top:110+44+8=162），避免被切换器叠盖 */
  top: 162px;
  width: 260px;
  /* v9：限高，避免在低分屏挤掉空状态 hint */
  max-height: calc(100vh - var(--app-header-h, 88px) - 220px);
  overflow-y: auto;
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(29, 78, 216, 0.18);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(16px) saturate(135%);
  z-index: 9;
  transition: transform 240ms ease, opacity 240ms ease;
}
/* v9：详情卡打开时，状态卡左移让出右下角空间 */
.atlas-status-card--shifted {
  transform: translateX(-380px);
}
@media (max-width: 1280px) {
  /* 中屏直接淡出，避免左移后压住地图主视野 */
  .atlas-status-card--shifted {
    opacity: 0;
    pointer-events: none;
  }
}
.atlas-status-card__head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  font-size: 12px;
  color: #475569;
}
.atlas-status-card__head strong {
  color: #0f766e;
  font-weight: 700;
}
.atlas-status-card__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 6px 0;
  font-size: 11px;
  color: #64748b;
}
.atlas-status-card__row b {
  max-width: 150px;
  color: #0f172a;
  font-weight: 650;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.atlas-status-card__progress {
  height: 6px;
  margin: 10px 0 8px;
  border-radius: 6px;
  overflow: hidden;
  background: rgba(15, 23, 42, 0.08);
}
.atlas-status-card__progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #0284c7, #10b981, #f59e0b);
}
.atlas-status-card__media {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
}
.atlas-status-card__media span {
  min-width: 0;
  padding: 5px 4px;
  border-radius: 7px;
  background: rgba(14, 165, 233, 0.08);
  color: #075985;
  text-align: center;
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
}

/* ---- Control panel ---- */
.atlas-panel {
  position: absolute;
  top: 18px; left: 18px;
  width: 298px;
  padding: 14px 16px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(248, 250, 252, 0.86));
  backdrop-filter: blur(18px) saturate(140%);
  border: 1px solid rgba(14, 165, 233, 0.22);
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.18);
  z-index: 10;
}
.atlas-panel__head {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.atlas-panel__title {
  font-size: 13px; font-weight: 700; letter-spacing: 0;
  color: #0f172a;
}
.atlas-panel__chip {
  font-size: 10px; padding: 3px 8px; border-radius: 10px;
  border: 1px solid rgba(14, 165, 233, 0.4);
  color: #0ea5e9; background: rgba(14, 165, 233, 0.08);
  font-family: 'JetBrains Mono', monospace;
}
.atlas-panel__row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.atlas-panel__row--toggles { flex-direction: column; gap: 6px; }
.atlas-toggle {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; color: #334155; cursor: pointer;
}
.atlas-toggle input { accent-color: #0ea5e9; }
.atlas-panel__foot {
  display: flex; flex-wrap: wrap; gap: 8px;
  font-size: 11px; color: #64748b;
  border-top: 1px solid rgba(15, 23, 42, 0.07);
  padding-top: 8px; margin-top: 4px;
  font-family: 'JetBrains Mono', monospace;
}

/* ---- Buttons ---- */
.atlas-btn {
  font-size: 12px; padding: 6px 10px; border-radius: 9px;
  border: 1px solid rgba(14, 165, 233, 0.3);
  background: rgba(255, 255, 255, 0.7);
  color: #0f172a; cursor: pointer;
  transition: transform 0.12s ease, border-color 0.2s, background 0.2s;
}
.atlas-btn:hover:not(:disabled) {
  background: rgba(14, 165, 233, 0.14);
  border-color: rgba(14, 165, 233, 0.6);
  transform: translateY(-1px);
}
.atlas-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.atlas-btn--on {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.18), rgba(217, 70, 239, 0.18));
  border-color: #0ea5e9;
  color: #0369a1;
  box-shadow: 0 0 12px rgba(14, 165, 233, 0.4);
}
.atlas-btn--ghost { background: transparent; border-color: rgba(15, 23, 42, 0.2); }

/* ---- Time slider ---- */
.atlas-time {
  position: absolute;
  left: 50%; bottom: 22px; transform: translateX(-50%);
  width: min(720px, 80vw);
  padding: 12px 18px 14px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(248, 250, 252, 0.84));
  backdrop-filter: blur(18px) saturate(140%);
  border: 1px solid rgba(217, 70, 239, 0.25);
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.16);
  z-index: 10;
}
.atlas-time__head {
  display: flex; justify-content: space-between;
  font-size: 11px; color: #64748b; margin-bottom: 6px;
  font-family: 'JetBrains Mono', monospace;
}
.atlas-time__cursor {
  font-size: 12px; color: #0ea5e9; font-weight: 700;
}
.atlas-time__slider {
  width: 100%; appearance: none; height: 5px;
  background: linear-gradient(90deg,
    hsl(240, 80%, 60%), hsl(200, 85%, 55%), hsl(150, 75%, 50%),
    hsl(60, 85%, 55%), hsl(20, 85%, 55%), hsl(330, 85%, 60%));
  border-radius: 3px; cursor: pointer;
}
.atlas-time__slider::-webkit-slider-thumb {
  appearance: none; width: 16px; height: 16px; border-radius: 50%;
  background: #fff; box-shadow: 0 0 0 3px #0ea5e9, 0 0 12px rgba(14, 165, 233, 0.55);
  cursor: pointer;
}
.atlas-time__slider::-moz-range-thumb {
  width: 16px; height: 16px; border-radius: 50%;
  background: #fff; border: 3px solid #0ea5e9;
  cursor: pointer;
}
.atlas-time__ctrl { display: flex; gap: 6px; margin-top: 10px; justify-content: center; }

/* ---- 悬停浮卡 ---- */
.atlas-hover {
  position: absolute;
  pointer-events: none;
  transform: translate(14px, -100%);
  padding: 10px 12px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(15, 23, 42, 0.94), rgba(30, 41, 59, 0.92));
  border: 1px solid rgba(14, 165, 233, 0.35);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.32);
  color: #e2e8f0;
  z-index: 12;
  max-width: 240px;
}
.atlas-hover__title {
  font-size: 13px; font-weight: 600;
  color: #e0f2fe;
  margin-bottom: 4px;
}
.atlas-hover__line { font-size: 11px; color: #cbd5e1; }
.atlas-hover__chips {
  display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px;
}
.atlas-hover__chip {
  font-size: 10px; padding: 2px 7px; border-radius: 8px;
  background: rgba(14, 165, 233, 0.18);
  border: 1px solid rgba(14, 165, 233, 0.32);
  color: #7dd3fc;
  font-family: 'JetBrains Mono', monospace;
}

/* ---- Detail card ---- */
.atlas-detail {
  position: absolute;
  right: 18px; bottom: 130px;
  width: 360px; max-height: 70vh;
  overflow-y: auto;
  padding: 16px 18px 18px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(248, 250, 252, 0.88));
  backdrop-filter: blur(20px) saturate(150%);
  border: 1px solid rgba(245, 158, 11, 0.32);
  box-shadow: 0 14px 40px rgba(15, 23, 42, 0.22);
  z-index: 11;
  color: #0f172a;
}
.atlas-detail__close {
  position: absolute; top: 8px; right: 10px;
  background: transparent; border: none;
  color: #64748b; font-size: 14px; cursor: pointer;
}
.atlas-detail__title {
  margin: 0 0 10px; font-size: 16px;
  color: #0f172a;
}
.atlas-detail__geo {
  display: grid; grid-template-columns: 80px 1fr;
  gap: 4px 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.08), rgba(217, 70, 239, 0.06));
  border: 1px solid rgba(14, 165, 233, 0.16);
  margin-bottom: 10px;
}
.atlas-detail__geo-row { display: contents; }
.atlas-detail__geo-k {
  font-size: 11px; color: #64748b;
  font-family: 'JetBrains Mono', monospace;
}
.atlas-detail__geo-v {
  font-size: 12px; color: #0f172a; font-weight: 600;
}
.atlas-detail__date { font-size: 11px; color: #64748b; margin: 0 0 8px; }
.atlas-detail__policy {
  margin: 0 0 10px;
  padding: 7px 9px;
  border-radius: 9px;
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.18);
  font-size: 11px;
  line-height: 1.5;
  color: #92400e;
}
.atlas-detail__desc {
  font-size: 12px; line-height: 1.6; color: #334155;
  margin: 0 0 10px; white-space: pre-wrap;
}
.atlas-detail__media-head {
  font-size: 11px; color: #c2410c;
  letter-spacing: 0.08em; margin-top: 10px; margin-bottom: 6px;
  border-top: 1px solid rgba(245, 158, 11, 0.18); padding-top: 8px;
  font-family: 'JetBrains Mono', monospace;
}
.atlas-detail__media-loading { color: #0ea5e9; }
.atlas-detail__media-empty {
  font-size: 11px; color: #64748b; line-height: 1.55; margin: 0;
}
.atlas-detail__media {
  display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-top: 6px;
}
.atlas-detail__img, .atlas-detail__video {
  width: 100%; border-radius: 8px;
  border: 1px solid rgba(14, 165, 233, 0.2);
}
.atlas-detail__audio { width: 100%; grid-column: 1 / -1; }

/* ---- Banner ---- */
.atlas-banner {
  position: absolute; top: 14px; left: 50%; transform: translateX(-50%);
  padding: 6px 14px; border-radius: 10px; font-size: 12px;
  background: rgba(14, 165, 233, 0.14);
  border: 1px solid rgba(14, 165, 233, 0.4);
  color: #0369a1; z-index: 12;
  display: inline-flex; align-items: center; gap: 8px;
}
.atlas-banner__spinner {
  width: 12px; height: 12px;
  border-radius: 50%;
  border: 2px solid rgba(14, 165, 233, 0.25);
  border-top-color: #0ea5e9;
  animation: atlas-spin 0.9s linear infinite;
}
@keyframes atlas-spin {
  to { transform: rotate(360deg); }
}
.atlas-banner--err {
  background: rgba(217, 70, 239, 0.16);
  border-color: rgba(217, 70, 239, 0.5);
  color: #a21caf;
}

@media (max-width: 920px) {
  .atlas-panel {
    left: 12px;
    top: 12px;
    width: min(320px, calc(100vw - 24px));
  }
  .atlas-status-card {
    top: auto;
    right: 12px;
    bottom: 116px;
    width: min(300px, calc(100vw - 24px));
  }
  .atlas-hint,
  .atlas-empty-hint {
    right: 12px;
    bottom: 12px;
    width: min(320px, calc(100vw - 24px));
    max-width: calc(100vw - 24px);
  }
  .atlas-time {
    bottom: 12px;
    width: calc(100vw - 24px);
  }
  .atlas-detail {
    right: 12px;
    left: 12px;
    bottom: 116px;
    width: auto;
    max-height: 56vh;
  }
}
</style>
