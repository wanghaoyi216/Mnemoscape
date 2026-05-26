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
  const memories = ref<MemoryWithCoords[]>([])
  const route = ref<RouteSegment[]>([])
  const others = ref<OthersPoint[]>([])

  const loading = ref(false)
  const error = ref('')
  const lastFetched = ref(0)

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

  function reset() {
    myLocation.value = null
    memories.value = []
    route.value = []
    others.value = []
    error.value = ''
    lastFetched.value = 0
  }

  return {
    myLocation, memories, route, others,
    loading, error, lastFetched, timeRange,
    fetchAll, reset,
  }
})
