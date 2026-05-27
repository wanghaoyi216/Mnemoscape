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

/**
 * 重建场景：让 AI 重新基于记忆描述生成 grounded fragments + visualData。
 * 用于把历史"假" fragment（旧规则版套模板生成的英文/无关内容）刷成紧扣描述的真实碎片。
 * 用 60s timeout 给 LLM 留足时间。
 */
export function regenerateScene(id: string) {
  return client.post<ApiResponse<MemoryItem>>(`/memories/${id}/regenerate-scene`, undefined, {
    timeout: 60_000,
  })
}

export function reconstruct(data: { description: string }) {
  // 场景重建可能要 LLM 跑 10-30s（生成完整 SceneData JSON），
  // 用 axios 默认 15s timeout 会断；这里专门放宽到 60s。
  return client.post<ApiResponse<SceneReconstructionResponse>>('/reconstruct', data, {
    timeout: 60_000,
  })
}
