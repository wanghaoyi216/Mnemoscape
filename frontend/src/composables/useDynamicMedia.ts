/**
 * useDynamicMedia — 把 asset-service 暴露的"本地 + MinIO"全部静态资源
 * 当成单例缓存，注入到任何视图。
 *
 *  - 单例：第一次调用时拉取 `/assets/static/resources`，后续直接复用
 *  - 类型分桶：videos / photos / gifs / audios / icons
 *  - 稳定随机：`pickForSeed(bucket, seed)` 用 fnv-1a 哈希做一致选择，
 *    让"同一条记忆"每次拿到同一张图，而不是抖动
 *  - 兜底：网络/后端不可用时仍返回空数组，调用方继续走静态 media-catalog
 *  - 热刷新：暴露 refresh()，文件夹/MinIO 内容变动后由 UI 主动触发
 */
import { reactive, readonly } from 'vue'
import client from '../api/client'

export interface DynamicAsset {
  /** 后端返回的展示名（保留中文） */
  name: string
  /** 可直接 <img>/<video>/<audio> 用的 URL；MinIO 走 presigned，本地走 /api/v1/assets/static/... */
  url: string
  /** 'video' | 'photo' | 'gif' | 'audio' | 'icon' | 'other' */
  type: string
  /** 字节 */
  size: number
  /** epoch ms */
  lastModified: number
}

interface Bucket {
  videos: DynamicAsset[]
  photos: DynamicAsset[]
  gifs: DynamicAsset[]
  audios: DynamicAsset[]
  icons: DynamicAsset[]
  others: DynamicAsset[]
  total: number
  loaded: boolean
  error: string | null
}

const state = reactive<Bucket>({
  videos: [],
  photos: [],
  gifs: [],
  audios: [],
  icons: [],
  others: [],
  total: 0,
  loaded: false,
  error: null,
})

let inflight: Promise<void> | null = null

async function refresh(): Promise<void> {
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const resp = await client.get('/assets/static/resources', { timeout: 8000 })
      const list: Array<{ name: string; path: string; type: string; size: number; lastModified: number }> =
        resp.data?.data || []
      const v: DynamicAsset[] = []
      const p: DynamicAsset[] = []
      const g: DynamicAsset[] = []
      const a: DynamicAsset[] = []
      const i: DynamicAsset[] = []
      const o: DynamicAsset[] = []
      for (const item of list) {
        const asset: DynamicAsset = {
          name: item.name,
          url: item.path,
          type: item.type || 'other',
          size: item.size || 0,
          lastModified: item.lastModified || 0,
        }
        switch (asset.type) {
          case 'video': v.push(asset); break
          case 'photo': p.push(asset); break
          case 'gif':   g.push(asset); break
          case 'audio': a.push(asset); break
          case 'icon':  i.push(asset); break
          default:      o.push(asset); break
        }
      }
      state.videos = v
      state.photos = p
      state.gifs = g
      state.audios = a
      state.icons = i
      state.others = o
      state.total = v.length + p.length + g.length + a.length + i.length + o.length
      state.loaded = true
      state.error = null
    } catch (e: any) {
      state.error = e?.message || 'asset-service unreachable'
      state.loaded = true // 标记尝试过；让上层用兜底
    } finally {
      inflight = null
    }
  })()
  return inflight
}

/* FNV-1a 32-bit — 让 seed 稳定映射到同一资源（避免每次刷新都换图） */
function hashSeed(seed: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0
  }
  return h >>> 0
}

function pickFromBucket(bucket: DynamicAsset[], seed: string | null | undefined): DynamicAsset | null {
  if (!bucket.length) return null
  if (!seed) return bucket[Math.floor(Math.random() * bucket.length)]
  return bucket[hashSeed(seed) % bucket.length]
}

export function useDynamicMedia() {
  // 首次调用时启动拉取（不 await）
  if (!state.loaded && !inflight) void refresh()

  return {
    state: readonly(state) as Readonly<Bucket>,
    refresh,
    pickPhoto: (seed?: string | null) => pickFromBucket(state.photos, seed),
    pickGif:   (seed?: string | null) => pickFromBucket(state.gifs, seed),
    pickVideo: (seed?: string | null) => pickFromBucket(state.videos, seed),
    pickAudio: (seed?: string | null) => pickFromBucket(state.audios, seed),
    pickIcon:  (seed?: string | null) => pickFromBucket(state.icons, seed),
    /** 任意视觉素材（gif 优先，其次 photo） — 用于 AI 多模态卡片 */
    pickAnyVisual(seed?: string | null): DynamicAsset | null {
      if (state.gifs.length && Math.random() < 0.4) return pickFromBucket(state.gifs, seed)
      return pickFromBucket(state.photos, seed) || pickFromBucket(state.gifs, seed)
    },
  }
}
