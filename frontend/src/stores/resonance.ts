import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ResonanceMatch, ResonanceSpace, MemoryNote } from '../types'
import * as api from '../api/resonance'

export const useResonanceStore = defineStore('resonance', () => {
  const searchResults = ref<ResonanceMatch[]>([])
  const currentSpace = ref<ResonanceSpace | null>(null)
  const notes = ref<MemoryNote[]>([])

  async function search(memoryId: string) {
    const { data } = await api.searchResonances(memoryId)
    searchResults.value = data.data
    return data.data
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

  return { searchResults, currentSpace, notes, search, createSpace, fetchSpace, fetchNotes, addNote, clearSearch }
})
