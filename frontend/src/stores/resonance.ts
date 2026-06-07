import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ResonanceMatch, ResonanceSpace, MemoryNote, ResonanceStats } from '../types'
import * as api from '../api/resonance'

export const useResonanceStore = defineStore('resonance', () => {
  const searchResults = ref<ResonanceMatch[]>([])
  const currentSpace = ref<ResonanceSpace | null>(null)
  const notes = ref<MemoryNote[]>([])
  /** 共鸣服务聚合统计；后端不可达 / 接口未实现时为 null，UI 走"加载中"占位。 */
  const stats = ref<ResonanceStats | null>(null)

  async function loadStats() {
    try {
      const { data } = await api.fetchResonanceStats()
      stats.value = data.data ?? null
      return stats.value
    } catch (e) {
      // 接口未实现 / 503 / 网络问题 → 让 UI 显示"—"，不阻塞其它功能
      console.warn('[resonance] loadStats failed, leaving stats=null', e)
      stats.value = null
      return null
    }
  }

  async function search(memoryId: string) {
    try {
      const { data } = await api.searchResonances(memoryId)
      // 防御：data.data 偶尔会被后端打成 null，统一兜底为 []，避免模板走"有结果"分支却渲染空数组
      searchResults.value = data.data || []
      return searchResults.value
    } catch (e) {
      console.error('[resonance] search failed', e)
      // 不静默吞掉 —— 让调用方（ResonanceHubView）能感知到失败并显示 toast
      throw e
    }
  }

  async function createSpace(memoryId1: string, memoryId2: string) {
    const { data } = await api.createResonanceSpace(memoryId1, memoryId2)
    return data.data
  }

  async function fetchSpace(id: string) {
    const { data } = await api.getResonanceSpace(id)
    currentSpace.value = data.data
    return data.data
  }

  async function fetchNotes(spaceId: string) {
    const { data } = await api.getResonanceNotes(spaceId)
    notes.value = data.data
    return data.data
  }

  function addNote(note: MemoryNote) {
    notes.value.push(note)
  }

  function clearSearch() {
    searchResults.value = []
  }

  return { searchResults, currentSpace, notes, stats, search, loadStats, createSpace, fetchSpace, fetchNotes, addNote, clearSearch }
})
