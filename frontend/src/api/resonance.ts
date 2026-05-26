import client from './client'
import type { ApiResponse, ResonanceMatch, ResonanceSpace, MemoryNote } from '../types'

export function searchResonances(memoryId: string) {
  return client.get<ApiResponse<ResonanceMatch[]>>('/resonances/search', { params: { memoryId } })
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
