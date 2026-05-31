import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { MemoryItem, DriftState, MemoryVersion, MemoryFragment } from '../types'
import * as api from '../api/memory'

export const useMemoryStore = defineStore('memory', () => {
  const memories = ref<MemoryItem[]>([])
  const current = ref<MemoryItem | null>(null)
  const currentDrift = ref<DriftState | null>(null)
  const currentVersions = ref<MemoryVersion[]>([])
  const currentFragments = ref<MemoryFragment[]>([])
  const loadingList = ref(false)
  const loadingDetail = ref(false)
  const error = ref('')
  const errorStatus = ref<number | null>(null)
  const errorRequestId = ref('')

  async function fetchList(page = 0, size = 12, privacyLevel?: string) {
    loadingList.value = true
    error.value = ''
    errorStatus.value = null
    errorRequestId.value = ''
    try {
      const { data } = await api.listMemories({ page, size, privacyLevel })
      memories.value = data.data.items
      return data.data
    } catch (e: any) {
      error.value = e.response?.data?.message || 'Unable to load memories'
      errorStatus.value = e.response?.status ?? null
      errorRequestId.value = e.response?.data?.requestId || ''
      throw e
    } finally {
      loadingList.value = false
    }
  }

  async function fetchOne(id: string) {
    loadingDetail.value = true
    error.value = ''
    errorStatus.value = null
    errorRequestId.value = ''
    try {
      const { data } = await api.getMemory(id)
      current.value = data.data
      return data.data
    } catch (e: any) {
      error.value = e.response?.data?.message || 'Unable to load memory'
      errorStatus.value = e.response?.status ?? null
      errorRequestId.value = e.response?.data?.requestId || ''
      throw e
    } finally {
      loadingDetail.value = false
    }
  }

  async function create(memory: {
    title: string
    description: string
    memoryYear?: number
    memoryDate?: string
    memorySeason?: string
    memoryTimeOfDay?: string
    memoryLocation?: string
    memoryLng?: number
    memoryLat?: number
    privacyLevel?: string
    sceneDataUrl?: string
  }) {
    const { data } = await api.createMemory(memory)
    memories.value.unshift(data.data)
    return data.data
  }

  async function update(id: string, updates: Partial<MemoryItem>) {
    const { data } = await api.updateMemory(id, updates)
    current.value = data.data
    const idx = memories.value.findIndex((m) => m.id === id)
    if (idx >= 0) memories.value[idx] = data.data
  }

  async function remove(id: string) {
    await api.deleteMemory(id)
    memories.value = memories.value.filter((m) => m.id !== id)
  }

  async function toggleLock(id: string, lock: boolean) {
    if (lock) await api.lockMemory(id)
    else await api.unlockMemory(id)
  }

  async function fetchDrift(id: string) {
    const { data } = await api.getDrift(id)
    currentDrift.value = data.data
    return data.data
  }

  async function fetchVersions(id: string) {
    const { data } = await api.getVersions(id)
    currentVersions.value = data.data
  }

  async function restoreVersion(id: string, versionNumber: number) {
    const { data } = await api.restoreVersion(id, versionNumber)
    current.value = data.data
  }

  /** 让 AI 重新基于记忆描述生成 grounded scene + fragments。 */
  async function regenerateScene(id: string) {
    const { data } = await api.regenerateScene(id)
    current.value = data.data
    return data.data
  }

  async function fetchFragments(id: string) {
    const { data } = await api.getFragments(id)
    currentFragments.value = data.data
  }

  async function discover(fragmentId: string) {
    await api.discoverFragment(fragmentId)
    const f = currentFragments.value.find((f) => f.id === fragmentId)
    if (f) f.isDiscovered = true
  }

  return {
    memories, current, currentDrift, currentVersions, currentFragments, loadingList, loadingDetail,
    error, errorStatus, errorRequestId,
    fetchList, fetchOne, create, update, remove, toggleLock,
    fetchDrift, fetchVersions, restoreVersion, regenerateScene, fetchFragments, discover,
  }
})
