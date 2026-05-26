import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SceneData, SceneReconstructionResponse } from '../types'
import * as api from '../api/memory'

export const useSceneStore = defineStore('scene', () => {
  const sceneData = ref<SceneData | null>(null)
  const loading = ref(false)

  function normalizeScene(payload: SceneData | SceneReconstructionResponse | null): SceneData | null {
    if (!payload) return null
    if ('sceneData' in payload) return payload.sceneData
    return payload
  }

  async function reconstruct(description: string) {
    loading.value = true
    try {
      const { data } = await api.reconstruct({ description })
      const payload = data.data
      sceneData.value = normalizeScene(payload)
      return sceneData.value
    } finally {
      loading.value = false
    }
  }

  function setScene(data: SceneData | SceneReconstructionResponse | null) {
    sceneData.value = normalizeScene(data)
  }

  return { sceneData, loading, reconstruct, setScene }
})
