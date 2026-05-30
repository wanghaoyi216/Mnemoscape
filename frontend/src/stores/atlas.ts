/**
 * Atlas store —— 真实地图数据的唯一编排者。
 *
 * 视图 (MemoryAtlasView) 只读这个 store；store 只调 atlas API；
 * 没有任何客户端 geocoding，没有任何 cliennt-side coordinate fabrication。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as atlasApi from '../api/atlas'
import type { MemoryItem } from '../types'
import type {
  AtlasLocation,
  RouteSegment,
  OthersPoint,
} from '../api/atlas'

export interface MemoryWithCoords extends MemoryItem {
  coords: [number, number]
  /** 派生的 epoch-seconds，用于 TripsLayer 时间轴 */
  ts: number
}

export const useAtlasStore = defineStore('atlas', () => {
  const myLocation = ref<AtlasLocation | null>(null)
  /** 浏览器 Geolocation API 返回的真实当前位置（用户授权后）。优先级高于后端
   *  "最近一条记忆" 的近似值。 */
  const browserLocation = ref<AtlasLocation | null>(null)
  const memories = ref<MemoryWithCoords[]>([])
  const route = ref<RouteSegment[]>([])
  const others = ref<OthersPoint[]>([])

  const loading = ref(false)
  const error = ref('')
  const lastFetched = ref(0)

  /**
   * 当前位置：浏览器实时定位优先；用户未授权 / 不支持时回退到后端的
   * "最近一条带坐标的记忆" 近似值（latest-memory）。
   */
  const effectiveLocation = computed<AtlasLocation | null>(() =>
    browserLocation.value ?? myLocation.value,
  )

  /** 时间范围：用于时间轴 slider [min, max]（秒） */
  const timeRange = computed<[number, number]>(() => {
    const ts = memories.value.map((m) => m.ts)
    if (!ts.length) {
      const now = Math.floor(Date.now() / 1000)
      return [now - 365 * 86400, now]
    }
    return [Math.min(...ts), Math.max(...ts) + 30 * 86400]
  })

  function deriveTs(m: MemoryItem): number {
    if (m.memoryDate) return Math.floor(new Date(m.memoryDate).getTime() / 1000)
    if (m.memoryYear) return Math.floor(new Date(`${m.memoryYear}-06-15`).getTime() / 1000)
    if (m.createdAt) return Math.floor(new Date(m.createdAt).getTime() / 1000)
    return Math.floor(Date.now() / 1000)
  }

  async function fetchAll() {
    loading.value = true
    error.value = ''
    try {
      const [locResp, memsResp, routeResp, othersResp] = await Promise.allSettled([
        atlasApi.getMyLocation(),
        atlasApi.getMemoriesWithCoords({ page: 0, size: 200 }),
        atlasApi.getRoute(),
        atlasApi.getOthers(),
      ])
      if (locResp.status === 'fulfilled') {
        myLocation.value = locResp.value.data.data
      }
      if (memsResp.status === 'fulfilled') {
        const items = memsResp.value.data.data.items as MemoryItem[]
        memories.value = items
          .map((m) => {
            const c = (m as any).coords as [number, number] | undefined
            if (!c || c.length !== 2) return null
            return { ...m, coords: c, ts: deriveTs(m) } as MemoryWithCoords
          })
          .filter((x): x is MemoryWithCoords => x !== null)
      }
      if (routeResp.status === 'fulfilled') {
        route.value = routeResp.value.data.data
      } else {
        route.value = []
      }
      if (othersResp.status === 'fulfilled') {
        others.value = othersResp.value.data.data
      } else {
        others.value = []
      }
      lastFetched.value = Date.now()
    } catch (e: any) {
      error.value = e?.response?.data?.message || e?.message || 'atlas data fetch failed'
    } finally {
      loading.value = false
    }
  }

  /**
   * 请求浏览器实时地理定位（HTML5 Geolocation）。用户授权后写入 browserLocation，
   * 它会通过 effectiveLocation 自动取代后端的 latest-memory 近似值。
   * 拒绝 / 不支持 / 超时都静默失败，回退到后端近似值。
   */
  function requestBrowserLocation(): void {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        browserLocation.value = {
          coords: [pos.coords.longitude, pos.coords.latitude],
          name: null,
          source: 'browser-geolocation',
          accuracyMeters: pos.coords.accuracy ?? 0,
        }
      },
      () => {
        // 用户拒绝 / 定位失败 —— 保持 null，effectiveLocation 回退到 latest-memory。
        browserLocation.value = null
      },
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 300000 },
    )
  }

  function reset() {
    myLocation.value = null
    browserLocation.value = null
    memories.value = []
    route.value = []
    others.value = []
    error.value = ''
    lastFetched.value = 0
  }

  return {
    myLocation, browserLocation, effectiveLocation, memories, route, others,
    loading, error, lastFetched, timeRange,
    fetchAll, requestBrowserLocation, reset,
  }
})
