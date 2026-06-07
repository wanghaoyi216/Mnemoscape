<script setup lang="ts">
/**
 * Global heatmap panel (R9.5 / R9.6).
 *
 * Renders a maplibre-gl globe + deck.gl HeatmapLayer over the points returned
 * by /admin/stats/heatmap.
 *
 * ⚠️ CRITICAL lifecycle fix (was the real reason the globe stayed black):
 * the map container MUST always be in the DOM. Previously it lived inside
 * `<AdminPanel>`'s `<slot v-if="state==='ready'">`, but `panelState` starts at
 * `'idle'` (data is null before the first fetch). idle → slot not rendered →
 * `containerRef` null in `onMounted` → early-return → map never built AND
 * `fetch()` never called → data stays null → state stays idle forever. A hard
 * deadlock. We now mount the map unconditionally and render loading / error /
 * empty as overlays *on top of* the always-present map canvas.
 *
 * Rendering approach mirrors MemoryAtlasView:
 *   - Esri World Imagery basemap (+ Carto dark fallback) so the Earth is real
 *     and recognisable, with a `sky` background layer filling the globe's
 *     out-of-frame area;
 *   - a true deck.gl HeatmapLayer for the canonical "热力" gradient bloom,
 *     plus a ScatterplotLayer of bright cores so individual hot cells are
 *     still pinpointable at any zoom;
 *   - ResizeObserver so the canvas re-sizes when the HUD flex column settles.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MapboxOverlay } from '@deck.gl/mapbox'
import { ScatterplotLayer } from '@deck.gl/layers'
import { HeatmapLayer } from '@deck.gl/aggregation-layers'
import { useAdminHeatmap } from '../../composables/useAdminHeatmap'
import type { AdminGridResolution, HeatmapPoint } from '../../api/admin'

const { t } = useI18n()
const {
  gridResolution,
  data,
  loading,
  error,
  fetch,
} = useAdminHeatmap()

const resolutions: AdminGridResolution[] = ['LOW', 'MEDIUM', 'HIGH']

const containerRef = ref<HTMLElement | null>(null)
let map: maplibregl.Map | null = null
let overlay: MapboxOverlay | null = null
let resizeObserver: ResizeObserver | null = null
let mapReady = false

/* 地球持续自转，用户操作时暂停，闲置 4s 后自动恢复 —— 与 MemoryAtlasView 一致。 */
const RESUME_AFTER_MS = 4000
let autoRotateRaf = 0
let resumeRotateTimer: ReturnType<typeof setTimeout> | null = null

function startGlobeAutoRotate(): void {
  const rotate = () => {
    if (!map) { autoRotateRaf = 0; return }
    if (autoRotateRaf === 0) return
    const c = map.getCenter()
    map.jumpTo({ center: [c.lng + 0.05, c.lat] })
    autoRotateRaf = requestAnimationFrame(rotate)
  }
  if (autoRotateRaf) cancelAnimationFrame(autoRotateRaf)
  autoRotateRaf = requestAnimationFrame(rotate)
}
function stopGlobeAutoRotate(): void {
  if (autoRotateRaf) cancelAnimationFrame(autoRotateRaf)
  autoRotateRaf = 0
}
function scheduleResumeRotate(): void {
  if (autoRotateRaf !== 0) return
  if (resumeRotateTimer) { clearTimeout(resumeRotateTimer); resumeRotateTimer = null }
  resumeRotateTimer = setTimeout(() => {
    resumeRotateTimer = null
    if (!map) return
    startGlobeAutoRotate()
  }, RESUME_AFTER_MS)
}
function pauseRotateForInteraction(): void {
  stopGlobeAutoRotate()
  scheduleResumeRotate()
}

/** Localised error code (or null). */
const errorCode = computed(() => error.value?.code ?? null)
const isEmpty = computed(() => !loading.value && !error.value && Array.isArray(data.value) && data.value.length === 0)
const pointCount = computed(() => (Array.isArray(data.value) ? data.value.length : 0))

/** Cyberpunk gradient stops for the HeatmapLayer colorRange (cool → hot). */
const HEAT_COLOR_RANGE: Array<[number, number, number]> = [
  [12, 74, 110],    // deep teal (coolest)
  [6, 182, 212],    // electric cyan
  [34, 211, 238],   // bright cyan
  [139, 92, 246],   // neon purple
  [236, 72, 153],   // hot pink
  [244, 63, 94],    // neon rose (hottest)
]

function intensityColor(intensity: number): [number, number, number] {
  const stops: Array<[number, [number, number, number]]> = [
    [0.0, [6, 182, 212]],
    [0.35, [34, 211, 238]],
    [0.6, [139, 92, 246]],
    [0.8, [236, 72, 153]],
    [1.0, [244, 63, 94]],
  ]
  const v = Math.max(0, Math.min(1, intensity))
  for (let i = 0; i < stops.length - 1; i++) {
    const [t0, c0] = stops[i]
    const [t1, c1] = stops[i + 1]
    if (v >= t0 && v <= t1) {
      const f = (v - t0) / (t1 - t0 || 1)
      return [
        Math.round(c0[0] + (c1[0] - c0[0]) * f),
        Math.round(c0[1] + (c1[1] - c0[1]) * f),
        Math.round(c0[2] + (c1[2] - c0[2]) * f),
      ]
    }
  }
  return [244, 63, 94]
}

/** HeatmapLayer pixel radius per resolution (coarser grid → wider bloom). */
function heatRadiusFor(r: AdminGridResolution): number {
  switch (r) {
    case 'LOW':    return 60
    case 'MEDIUM': return 42
    case 'HIGH':   return 28
  }
}
/** Scatter core pixel radius per resolution. */
function coreRadiusFor(r: AdminGridResolution): number {
  switch (r) {
    case 'LOW':    return 7
    case 'MEDIUM': return 5
    case 'HIGH':   return 4
  }
}

function buildLayers() {
  const points = data.value ?? []
  if (points.length === 0) return []
  const heatRadius = heatRadiusFor(gridResolution.value)
  const coreBase = coreRadiusFor(gridResolution.value)
  return [
    // Canonical heat bloom — smooth density gradient, the real "热力图" look.
    new HeatmapLayer<HeatmapPoint>({
      id: 'admin-heatmap-bloom',
      data: points,
      getPosition: (p) => [p.lon, p.lat],
      getWeight: (p) => p.intensity,
      radiusPixels: heatRadius,
      intensity: 1.2,
      threshold: 0.03,
      colorRange: HEAT_COLOR_RANGE,
      aggregation: 'SUM',
    }),
    // Bright pinpoint cores so individual hot cells stay clickable / visible.
    new ScatterplotLayer<HeatmapPoint>({
      id: 'admin-heatmap-core',
      data: points,
      pickable: true,
      radiusUnits: 'pixels',
      radiusMinPixels: 2.5,
      radiusMaxPixels: 16,
      getPosition: (p) => [p.lon, p.lat],
      getRadius: (p) => coreBase + p.intensity * coreBase * 1.4,
      getFillColor: (p) => {
        const [r, g, b] = intensityColor(p.intensity)
        return [r, g, b, 230]
      },
      stroked: true,
      getLineColor: [255, 255, 255, 210],
      lineWidthMinPixels: 0.8,
    }),
  ]
}

function rebuildOverlay(): void {
  if (!overlay) return
  overlay.setProps({ layers: buildLayers() })
}

const STYLE: maplibregl.StyleSpecification = {
  version: 8,
  projection: { type: 'globe' },
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  sources: {
    imagery: {
      type: 'raster',
      tiles: [
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      ],
      tileSize: 256,
      attribution: '© Esri World Imagery',
      maxzoom: 19,
    },
  },
  layers: [
    { id: 'sky', type: 'background', paint: { 'background-color': '#050714' } },
    { id: 'imagery', type: 'raster', source: 'imagery', minzoom: 0, maxzoom: 22 },
  ],
}

const STYLE_FALLBACK: maplibregl.StyleSpecification = {
  version: 8,
  projection: { type: 'globe' },
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  sources: {
    'carto-dark': {
      type: 'raster',
      tiles: [
        'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
        'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
        'https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
      ],
      tileSize: 256,
      attribution: '© CartoDB',
    },
  },
  layers: [
    { id: 'sky', type: 'background', paint: { 'background-color': '#050714' } },
    { id: 'carto-dark-layer', type: 'raster', source: 'carto-dark', minzoom: 0, maxzoom: 18 },
  ],
}

onMounted(() => {
  // 数据拉取与地图挂载解耦：即便容器（极少数布局时序问题）暂时拿不到，
  // 也要先把数据请求发出去，避免再次出现"never fetch"死锁。
  void fetch()

  // 关键：不再依赖 AdminPanel 的 ready slot —— 容器恒在 DOM 中，map 一定能挂载。
  if (!containerRef.value) return

  map = new maplibregl.Map({
    container: containerRef.value,
    style: STYLE,
    center: [50, 15],
    zoom: 1.55,
    pitch: 0,
    bearing: 0,
    minZoom: 0.4,
    maxZoom: 8,
    attributionControl: { compact: true },
  })

  let switchedFallback = false
  map.on('error', (e: { error?: { status?: number; message?: string } }) => {
    if (!map || switchedFallback) return
    const status = e?.error?.status
    const msg = (e?.error?.message || '').toLowerCase()
    if ((status !== undefined && status >= 400) || msg.includes('tile') || msg.includes('fetch')) {
      switchedFallback = true
      try { map.setStyle(STYLE_FALLBACK) } catch { /* noop */ }
    }
  })

  resizeObserver = new ResizeObserver(() => {
    try { map?.resize() } catch { /* noop */ }
  })
  resizeObserver.observe(containerRef.value)
  setTimeout(() => { try { map?.resize() } catch { /* noop */ } }, 80)
  setTimeout(() => { try { map?.resize() } catch { /* noop */ } }, 400)

  map.on('load', () => {
    if (!map) return
    mapReady = true
    overlay = new MapboxOverlay({ layers: buildLayers(), interleaved: false })
    map.addControl(overlay as unknown as maplibregl.IControl)
    startGlobeAutoRotate()
  })

  map.on('mousedown',   pauseRotateForInteraction)
  map.on('touchstart',  pauseRotateForInteraction)
  map.on('dragstart',   pauseRotateForInteraction)
  map.on('wheel',       pauseRotateForInteraction)
  map.on('rotatestart', pauseRotateForInteraction)
  map.on('pitchstart',  pauseRotateForInteraction)
  map.on('zoomstart',   pauseRotateForInteraction)
  map.on('dragend',     scheduleResumeRotate)
  map.on('zoomend',     scheduleResumeRotate)
  map.on('rotateend',   scheduleResumeRotate)
  map.on('pitchend',    scheduleResumeRotate)
  map.on('moveend',     scheduleResumeRotate)

  map.addControl(
    new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }),
    'top-right',
  )
})

onBeforeUnmount(() => {
  stopGlobeAutoRotate()
  if (resumeRotateTimer) { clearTimeout(resumeRotateTimer); resumeRotateTimer = null }
  try { resizeObserver?.disconnect() } catch { /* noop */ }
  resizeObserver = null
  try { if (overlay && map) map.removeControl(overlay as unknown as maplibregl.IControl) } catch { /* noop */ }
  overlay = null
  try { map?.remove() } catch { /* noop */ }
  map = null
})

// 数据 / 分辨率变化时重建 overlay（map 可能还没 load 完，rebuildOverlay 自带 null 守卫）。
watch([data, gridResolution], () => {
  if (mapReady) rebuildOverlay()
})

function selectResolution(r: AdminGridResolution): void {
  if (gridResolution.value !== r) {
    gridResolution.value = r
  }
}
</script>

<template>
  <!-- 注意：不再把 map 包进 AdminPanel 的 ready-slot；容器恒在 DOM 中。 -->
  <div class="admin-heatmap">
    <header class="admin-panel-controls">
      <h3 class="admin-heatmap__title">{{ t('admin.heatmap.title') }}</h3>
      <p class="admin-panel-subtitle">{{ t('admin.heatmap.subtitle') }}</p>
      <div class="admin-toggle" role="tablist" :aria-label="t('admin.heatmap.title')">
        <button
          v-for="r in resolutions"
          :key="r"
          type="button"
          role="tab"
          class="admin-toggle__btn"
          :class="{ 'admin-toggle__btn--active': gridResolution === r }"
          :aria-selected="gridResolution === r"
          @click="selectResolution(r)"
        >
          {{ t(`admin.heatmap.resolution.${r}`) }}
        </button>
      </div>
    </header>

    <div ref="containerRef" class="admin-heatmap__map" role="img" :aria-label="t('admin.heatmap.title')">
      <!-- loading overlay -->
      <div v-if="loading && pointCount === 0" class="admin-heatmap__overlay">
        <div class="admin-heatmap__spinner"></div>
        <p class="admin-heatmap__overlay-text">{{ t('admin.common.loading') }}</p>
      </div>

      <!-- error overlay -->
      <div v-else-if="errorCode" class="admin-heatmap__overlay">
        <div class="admin-heatmap__overlay-icon">⚠️</div>
        <p class="admin-heatmap__overlay-text">{{ error?.message || errorCode }}</p>
        <button type="button" class="admin-heatmap__retry" @click="fetch">
          {{ t('admin.common.retry') }}
        </button>
      </div>

      <!-- empty overlay -->
      <div v-else-if="isEmpty" class="admin-heatmap__overlay">
        <div class="admin-heatmap__overlay-icon">🌐</div>
        <p class="admin-heatmap__overlay-text">{{ t('admin.heatmap.noData') }}</p>
        <p class="admin-heatmap__overlay-hint">{{ t('admin.heatmap.noDataHint') }}</p>
      </div>

      <!-- point-count badge -->
      <div v-if="pointCount > 0" class="admin-heatmap__count-badge">
        {{ pointCount }} {{ t('admin.heatmap.pointsLabel') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-heatmap {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 0;
}

.admin-panel-controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.admin-heatmap__title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--text);
}

.admin-panel-subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.86rem;
  flex: 1;
}

.admin-toggle {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 4px;
  background: rgba(14, 17, 22, 0.6);
}

.admin-toggle__btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.admin-toggle__btn:hover { color: var(--text); }
.admin-toggle__btn--active {
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.28);
}

.admin-heatmap__map {
  width: 100%;
  height: 540px;
  flex: 1;
  min-height: 320px;
  border-radius: var(--radius-md);
  overflow: hidden;
  position: relative;
  background: #050714;
}

.admin-heatmap__overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: rgba(5, 7, 20, 0.6);
  backdrop-filter: blur(2px);
  pointer-events: none;
}

.admin-heatmap__overlay-icon {
  font-size: 2.5rem;
  opacity: 0.7;
}

.admin-heatmap__overlay-text {
  margin: 0;
  color: rgba(6, 182, 212, 0.95);
  font-size: 0.92rem;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.admin-heatmap__overlay-hint {
  margin: 0;
  color: rgba(255, 255, 255, 0.45);
  font-size: 0.78rem;
  text-align: center;
  max-width: 300px;
  line-height: 1.5;
}

.admin-heatmap__retry {
  pointer-events: auto;
  appearance: none;
  background: rgba(6, 182, 212, 0.12);
  border: 1px solid rgba(6, 182, 212, 0.4);
  color: #22d3ee;
  font-size: 0.8rem;
  font-weight: 600;
  padding: 6px 16px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.admin-heatmap__retry:hover {
  background: rgba(6, 182, 212, 0.25);
  border-color: rgba(6, 182, 212, 0.7);
}

.admin-heatmap__spinner {
  width: 36px;
  height: 36px;
  border: 3px solid rgba(6, 182, 212, 0.2);
  border-top-color: #22d3ee;
  border-radius: 50%;
  animation: heatmap-spin 0.9s linear infinite;
}
@keyframes heatmap-spin {
  to { transform: rotate(360deg); }
}

.admin-heatmap__count-badge {
  position: absolute;
  bottom: 12px;
  left: 12px;
  z-index: 10;
  background: rgba(6, 12, 24, 0.75);
  border: 1px solid rgba(6, 182, 212, 0.3);
  color: #22d3ee;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 4px 10px;
  border-radius: 4px;
  backdrop-filter: blur(8px);
  pointer-events: none;
}

:deep(.maplibregl-canvas) { outline: none; }
:deep(.maplibregl-ctrl-attrib) {
  background: rgba(0, 0, 0, 0.5);
  color: rgba(255, 255, 255, 0.7);
  font-size: 10px;
}
:deep(.maplibregl-ctrl-attrib a) { color: var(--accent); }
</style>
