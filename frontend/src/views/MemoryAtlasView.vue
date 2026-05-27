<script setup lang="ts">
/**
 * 时空记忆地图 · MemoryAtlasView (v6)
 *
 * 改进点（v5 → v6）：
 *   1. 底图改为 **真实栅格瓦片**：CartoDB Voyager raster（与 Google Maps 风格接近，
 *      可全球离线缓存）。地球仪模式下显示完整地球；切到 mercator 平面时显示同一份瓦片。
 *   2. **世界有边界**：maxBounds 限制经度 [-180, 180] / 纬度 [-85, 85]，禁用瓦片 wrap
 *      (renderWorldCopies=false)，地图不再无限滚动 — 与"球"的直觉一致。
 *   3. **默认状态：旋转的地球 + 闪烁星空**：球体后面叠一层 canvas 星空，明暗交替闪
 *      烁，仅在 globe 模式显示。地球缓慢自转，单击地球任意位置才转为平面。
 *   4. **记忆节点 / 流光线条 / 4 级地理 / 时间状态**：均沿用 v5 的设计。
 *   5. **空数据自检**：若用户记忆完全没有坐标，顶部 banner 直接提示「请在记忆创建
 *      时填写地点（如 北京 / 大理 / 东京）」 — 不再让用户看到一张空地图。
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
 * 真实栅格瓦片底图。
 *
 * 设计要点：
 *  1. 多组镜像并入 tiles 数组让 MapLibre 自动轮询，单镜像失败不影响整体渲染。
 *  2. 国内网络环境下 carto / OpenStreetMap 官方瓦片经常不可达，所以**优先用国内
 *     可达的镜像**（高德地图 webrd / 天地图 tianditu 影像 / OSM 中国镜像）。海外环境
 *     由 fallback style 兜底。
 *  3. `projection.type` 放在 style 内（MapLibre v5 起从 style 读 projection；放在
 *     `new Map({ projection })` 构造参数里不是稳定 API，部分版本会被忽略）。
 */
const REAL_TILES_STYLE: any = {
  version: 8,
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  projection: { type: 'globe' },
  sources: {
    'real-raster': {
      type: 'raster',
      tiles: [
        // 高德地图 webrd（国内默认可达，矢量化栅格瓦片，与 Google Maps 风格相近）
        'https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
      ],
      tileSize: 256,
      attribution:
        '© <a href="https://amap.com" target="_blank" rel="noopener">高德地图 AutoNavi</a>',
      maxzoom: 18,
    },
  },
  layers: [
    { id: 'background', type: 'background', paint: { 'background-color': '#0b1224' } },
    { id: 'real-raster', type: 'raster', source: 'real-raster', minzoom: 0, maxzoom: 22 },
  ],
}

// 兜底栅格瓦片：CartoDB Voyager + OSM 官方双镜像（海外环境用）。
// MapLibre 会按顺序轮询，任意一个域名能拉到瓦片就能渲染。
const FALLBACK_TILES_STYLE: any = {
  version: 8,
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  projection: { type: 'globe' },
  sources: {
    osm: {
      type: 'raster',
      tiles: [
        'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      ],
      tileSize: 256,
      attribution:
        '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> · <a href="https://carto.com/attributions" target="_blank" rel="noopener">CARTO</a>',
      maxzoom: 19,
    },
  },
  layers: [
    { id: 'background', type: 'background', paint: { 'background-color': '#0b1224' } },
    { id: 'osm', type: 'raster', source: 'osm', minzoom: 0, maxzoom: 19 },
  ],
}

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
    style: REAL_TILES_STYLE,
    center: [105.0, 35.0],
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

  // 底图瓦片拉不到时（国内网络常见）→ 自动切到 fallback 多镜像 style。
  // 任何瓦片相关错误都触发；同时设一个兜底超时：6 秒内 source 还没就绪就强切。
  let switchedFallback = false
  const trySwitchFallback = () => {
    if (!map || switchedFallback) return
    switchedFallback = true
    try { map.setStyle(FALLBACK_TILES_STYLE) } catch { /* noop */ }
  }
  map.on('error', (e: any) => {
    if (!map || switchedFallback) return
    // MapLibre 在瓦片失败时会发 status >= 400 或 networkError，message 多变；
    // 这里统一兜底：任何被监听到的 error 都视为底图不可用 → 走 fallback。
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
  // 6 秒兜底：如果首屏一片漆黑（real source 一张瓦片都没加载成功），强切 fallback。
  window.setTimeout(() => {
    if (!map || switchedFallback) return
    try {
      if (!map.isSourceLoaded('real-raster')) trySwitchFallback()
    } catch { /* noop */ }
  }, 6000)

  map.addControl(new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }), 'top-right')
  map.touchZoomRotate.enableRotation()

  map.on('zoom', () => {
    if (!map) return
    currentZoom.value = map.getZoom()
    if (viewMode.value === 'flat' && shouldTilt(currentZoom.value)) {
      switchTo3D()
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
  map.flyTo({ center: [105.0, 35.0], zoom: 1.6, pitch: 0, bearing: 0, duration: 1400, essential: true })
  window.setTimeout(() => startGlobeAutoRotate(), 1500)
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

    <!-- v8：空状态也放在右下角，避免遮挡地图主体 -->
    <div v-if="showNoCoordsHint" class="atlas-empty-hint" role="status">
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

    <div v-else-if="showOneCoordHint" class="atlas-empty-hint atlas-empty-hint--soft" role="status">
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

    <!-- v5/v6：入口提示卡 → 右下角浮卡，不再遮挡地球 -->
    <div
      v-if="viewMode === 'globe' && hintVisible && !showNoCoordsHint && !showOneCoordHint"
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

    <section class="atlas-status-card" aria-label="地图状态">
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
  top: 88px;
  width: 260px;
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(29, 78, 216, 0.18);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(16px) saturate(135%);
  z-index: 9;
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
