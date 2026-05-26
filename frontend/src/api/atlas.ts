/**
 * Atlas (时空地图) API client.
 *
 * 这是 MemoryAtlasView 修复后唯一的数据来源。
 * 前端不再持有任何 city gazetteer，所有坐标 / 节点 / 路线 / 他人网络
 * 都来自这里返回的真实接口。
 */
import client from './client'
import type { ApiResponse, MemoryItem, PageResult } from '../types'

export interface AtlasLocation {
  /** [lng, lat] 或 null（用户还没有任何带坐标的记忆） */
  coords: [number, number] | null
  /** 命中的地点名（最近一条记忆的 location） */
  name: string | null
  /** "latest-memory" | "none" — 让前端能区分降级状态 */
  source: 'latest-memory' | 'none' | string
  accuracyMeters: number
}

export interface RouteSegment {
  from: [number, number]
  to: [number, number]
  startTime: number
  endTime: number
  fromTitle?: string
  toTitle?: string
}

export interface OthersPoint {
  virtualName: string
  coords: [number, number]
  weight: number
  timestamp: number
}

/** GET /api/v1/users/me/location — 当前位置近似 */
export function getMyLocation() {
  return client.get<ApiResponse<AtlasLocation>>('/users/me/location')
}

/** GET /api/v1/memories?withCoords=true — 带坐标的记忆 */
export function getMemoriesWithCoords(opts: { page?: number; size?: number } = {}) {
  return client.get<ApiResponse<PageResult<MemoryItem>>>('/memories', {
    params: { withCoords: true, page: opts.page ?? 0, size: opts.size ?? 200 },
  })
}

/** GET /api/v1/memories/route — 时间序的轨迹段 */
export function getRoute() {
  return client.get<ApiResponse<RouteSegment[]>>('/memories/route')
}

/** GET /api/v1/atlas/others — 严格脱敏的他人网络 */
export function getOthers() {
  return client.get<ApiResponse<OthersPoint[]>>('/atlas/others')
}
