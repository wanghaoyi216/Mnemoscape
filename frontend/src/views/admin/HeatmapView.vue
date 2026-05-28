<script setup lang="ts">
/**
 * Global heatmap panel (R9.5 / R9.6).
 *
 * Renders a maplibre-gl globe + deck.gl HexagonLayer over the points returned
 * by /admin/stats/heatmap. Three resolution buckets map to {350km, 80km, 25km}
 * hex radii — the same powers-of-2.5 scale as the design's grid step table.
 *
 * Lifecycle (v2.4 conventions reused from MemoryAtlasView):
 *   - on mount, build map + overlay, attach ResizeObserver to the container so
 *     the canvas re-sizes when the parent layout reflows;
 *   - on resolution change or new heatmap data, rebuild the layer (deck.gl's
 *     setProps is the cheap path; we wrap it in a guarded helper);
 *   - on unmount, disconnect the observer and remove the map.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MapboxOverlay } from '@deck.gl/mapbox'
import { HexagonLayer } from '@deck.gl/aggregation-layers'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminHeatmap } from '../../composables/useAdminHeatmap'
import type { AdminGridResolution } from '../../api/admin'

const { t } = useI18n()
const {
  gridResolution,
  data,
  loading,
  error,
  degraded,
  degradedReasons,
  fetch,
} = useAdminHeatmap()

const resolutions: AdminGridResolution[] = ['LOW', 'MEDIUM', 'HIGH']

const containerRef = ref<HTMLElement | null>(null)
let map: maplibregl.Map | null = null
let overlay: MapboxOverlay | null = null
let resizeObserver: ResizeObserver | null = null

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (loading.value && !data.value) return 'loading'
  if (error.value) return 'error'
  // deck.gl can render an empty list cleanly; we still want the map visible.
  if (!data.value) return 'idle'
  return 'ready'
})

function radiusFor(r: AdminGridResolution): number {
  switch (r) {
    case 'LOW':    return 350_000
    case 'MEDIUM': return 80_000
    case 'HIGH':   return 25_000
  }
}

function buildLayer() {
  return new HexagonLayer<{ lat: number; lon: number; intensity: number }>({
    id: 'admin-heatmap',
    data: data.value ?? [],
    getPosition: (p) => [p.lon, p.lat],
    getColorWeight: (p) => p.intensity,
    getElevationWeight: (p) => p.intensity,
    colorAggregation: 'SUM',
    elevationAggregation: 'SUM',
    radius: radiusFor(gridResolution.value),
    elevationScale: 50,
    pickable: true,
    extruded: true,
    opacity: 0.78,
  })
}

function rebuildOverlay(): void {
  if (!overlay) return
  overlay.setProps({ layers: [buildLayer()] })
}

const STYLE: maplibregl.StyleSpecification = {
  version: 8,
  // v2.2.2 — projection MUST live inside the style object, not as a Map ctor option.
  projection: { type: 'globe' },
  glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  sources: {
    'amap-raster': {
      type: 'raster',
      tiles: [
        'https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
        'https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
      ],
      tileSize: 256,
      attribution: '高德地图',
    },
  },
  layers: [
    {
      id: 'amap-raster-layer',
      type: 'raster',
      source: 'amap-raster',
      minzoom: 0,
      maxzoom: 18,
    },
  ],
}

onMounted(() => {
  if (!containerRef.value) return

  map = new maplibregl.Map({
    container: containerRef.value,
    style: STYLE,
    center: [105.0, 35.0],
    zoom: 1.4,
    pitch: 0,
    bearing: 0,
    minZoom: 0.4,
    maxZoom: 8,
    attributionControl: { compact: true },
  })

  // R9.6 — ResizeObserver guard. onMounted often fires when the layout is still
  // settling (admin-shell padding-top calc, sub-nav scrolling); without this the
  // canvas paints at 0×0 and stays blank until the user resizes the window.
  resizeObserver = new ResizeObserver(() => {
    try {
      map?.resize()
    } catch {
      // noop — map may have been removed before the observer fires.
    }
  })
  resizeObserver.observe(containerRef.value)

  // First-frame nudge: some layout passes don't notify ResizeObserver on the
  // initial frame, so kick a manual resize ~80ms after mount.
  setTimeout(() => {
    try { map?.resize() } catch { /* noop */ }
  }, 80)

  map.on('load', () => {
    if (!map) return
    overlay = new MapboxOverlay({ layers: [buildLayer()], interleaved: false })
    map.addControl(overlay as unknown as maplibregl.IControl)
  })

  map.addControl(
    new maplibregl.NavigationControl({ showCompass: true, visualizePitch: true }),
    'top-right',
  )

  void fetch()
})

onBeforeUnmount(() => {
  try {
    resizeObserver?.disconnect()
  } catch {
    /* noop */
  }
  resizeObserver = null
  try {
    if (overlay && map) {
      map.removeControl(overlay as unknown as maplibregl.IControl)
    }
  } catch {
    /* noop */
  }
  overlay = null
  try {
    map?.remove()
  } catch {
    /* noop */
  }
  map = null
})

// Re-render whenever the data list or resolution changes.
watch([data, gridResolution], () => {
  rebuildOverlay()
})

function selectResolution(r: AdminGridResolution): void {
  if (gridResolution.value !== r) {
    gridResolution.value = r
  }
}
</script>

<template>
  <AdminPanel
    title="admin.heatmap.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-heatmap">
      <header class="admin-panel-controls">
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

      <div ref="containerRef" class="admin-heatmap__map" role="img" :aria-label="t('admin.heatmap.title')" />
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-heatmap {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.admin-panel-controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.admin-panel-subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.86rem;
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
  border-radius: var(--radius-md);
  overflow: hidden;
  position: relative;
  background: #0a0d12;
}

:deep(.maplibregl-canvas) {
  outline: none;
}
:deep(.maplibregl-ctrl-attrib) {
  background: rgba(0, 0, 0, 0.5);
  color: rgba(255, 255, 255, 0.7);
  font-size: 10px;
}
:deep(.maplibregl-ctrl-attrib a) { color: var(--accent); }
</style>
