import client from './client'
import type {
  ApiResponse,
  MemoryItem,
  DriftState,
  MemoryVersion,
  MemoryFragment,
  PageResult,
  SceneReconstructionResponse,
} from '../types'

export function listMemories(params: { page?: number; size?: number; privacyLevel?: string } = {}) {
  return client.get<ApiResponse<PageResult<MemoryItem>>>('/memories', { params })
}

export function getMemory(id: string) {
  return client.get<ApiResponse<MemoryItem>>(`/memories/${id}`)
}

export function createMemory(data: {
  title: string
  description: string
  memoryYear?: number
  memoryDate?: string
  memorySeason?: string
  memoryTimeOfDay?: string
  memoryLocation?: string
  privacyLevel?: string
}) {
  return client.post<ApiResponse<MemoryItem>>('/memories', data)
}

export function updateMemory(id: string, data: Partial<MemoryItem>) {
  return client.put<ApiResponse<MemoryItem>>(`/memories/${id}`, data)
}

export function deleteMemory(id: string) {
  return client.delete<ApiResponse<void>>(`/memories/${id}`)
}

export function lockMemory(id: string) {
  return client.post<ApiResponse<void>>(`/memories/${id}/lock`)
}

export function unlockMemory(id: string) {
  return client.delete<ApiResponse<void>>(`/memories/${id}/lock`)
}

export function getDrift(id: string) {
  return client.get<ApiResponse<DriftState>>(`/memories/${id}/drift`)
}

export function getVersions(id: string) {
  return client.get<ApiResponse<MemoryVersion[]>>(`/memories/${id}/versions`)
}

export function restoreVersion(id: string, versionNumber: number) {
  return client.post<ApiResponse<MemoryItem>>(`/memories/${id}/restore/${versionNumber}`)
}

export function getFragments(id: string) {
  return client.get<ApiResponse<MemoryFragment[]>>(`/memories/${id}/fragments`)
}

export function discoverFragment(fragmentId: string) {
  return client.post<ApiResponse<void>>(`/memories/fragments/${fragmentId}/discover`)
}

export function reconstruct(data: { description: string }) {
  return client.post<ApiResponse<SceneReconstructionResponse>>('/reconstruct', data)
}
