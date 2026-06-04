import client from './client'
import type { ApiResponse, ResonanceMatch, ResonanceSpace, MemoryNote, ResonanceStats } from '../types'

export function searchResonances(memoryId: string) {
  return client.get<ApiResponse<ResonanceMatch[]>>('/resonances/search', { params: { memoryId } })
}

/**
 * 拉取共鸣服务聚合统计（avgScore / totalMatches / algorithmName），
 * 用于 ResonanceHub 顶部三张 metric 卡片。
 */
export function fetchResonanceStats() {
  return client.get<ApiResponse<ResonanceStats>>('/resonances/stats')
}

export function createResonanceSpace(memoryId1: string, memoryId2: string) {
  return client.post<ApiResponse<ResonanceSpace>>('/resonances/spaces', { memoryId1, memoryId2 })
}

export function getResonanceSpace(id: string) {
  return client.get<ApiResponse<ResonanceSpace>>(`/resonances/spaces/${id}`)
}

export function getResonanceNotes(id: string) {
  return client.get<ApiResponse<MemoryNote[]>>(`/resonances/spaces/${id}/notes`)
}

export function placeNote(resonanceId: string, data: { content: string; mood: string; position: Record<string, number> }) {
  return client.post<ApiResponse<MemoryNote>>(`/resonances/spaces/${resonanceId}/notes`, data)
}
