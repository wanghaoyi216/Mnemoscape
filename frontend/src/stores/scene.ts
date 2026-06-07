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

  function fallbackSceneFromDescription(description: string): SceneData {
    const text = description.toLowerCase()
    const rainy = /rain|雨|潮湿|伞/.test(text)
    const winter = /snow|winter|雪|冬|冷/.test(text)
    const night = /night|moon|星|夜|晚/.test(text)
    const spring = /flower|花|春|草|香/.test(text)
    const autumn = /autumn|fall|秋|叶|黄昏/.test(text)
    const environment = winter
      ? 'snowy_landscape'
      : rainy
        ? 'rainy_street'
        : night
          ? 'night_courtyard'
          : spring
            ? 'flower_garden'
            : autumn
              ? 'autumn_path'
              : 'flower_garden'

    const accent = night ? '#8b5cf6' : rainy ? '#60a5fa' : winter ? '#dbeafe' : autumn ? '#f59e0b' : '#36d8b4'
    const objects = [
      { id: 'memory-core', type: 'sphere', name: 'Memory core', position: [0, 1.2, 0], color: accent, scale: [1.4, 1.4, 1.4] },
      { id: 'memory-lamp', type: 'lamp', name: 'Memory lamp', position: [-2.8, 0, -1.8], color: '#facc15', scale: [1, 1, 1] },
      { id: 'memory-bench', type: 'bench', name: 'Memory bench', position: [2.2, 0, 1.8], color: '#94a3b8', scale: [1, 1, 1] },
      { id: 'memory-tree', type: spring ? 'cherry_tree' : autumn ? 'autumn_tree' : 'tree', name: 'Memory tree', position: [-3.2, 0, 2.6], color: accent, scale: [1, 1, 1] },
    ]
    const fragments = description
      .split(/[。！？.!?\n]/)
      .map((part) => part.trim())
      .filter(Boolean)
      .slice(0, 4)
      .map((content, index) => ({
        fragmentType: index % 2 === 0 ? 'emotion_flashback' : 'forgotten_detail',
        content,
        position3d: { x: -3 + index * 2, y: 1.2, z: index % 2 === 0 ? -2 : 2 },
        triggerRadius: 1.4,
        isDiscovered: false,
      }))

    return {
      environment,
      lighting: { type: night ? 'moonlight' : rainy ? 'overcast' : 'ambient', color: accent, intensity: 1.2 },
      terrain: { type: rainy ? 'wet_street' : winter ? 'snow' : 'garden', color: night ? '#111827' : '#122018' },
      atmosphere: {
        fogColor: night ? '#0f1024' : rainy ? '#1e293b' : '#0f172a',
        fogDensity: rainy || night ? 0.08 : 0.04,
        backgroundColor: '#070714',
      },
      objects,
      audioData: { ambient: [], positional: [] },
      fragments,
    }
  }

  async function reconstruct(description: string) {
    loading.value = true
    try {
      const { data } = await api.reconstruct({ description })
      const payload = data.data
      sceneData.value = normalizeScene(payload)
      return sceneData.value
    } catch (e) {
      sceneData.value = fallbackSceneFromDescription(description)
      return sceneData.value
    } finally {
      loading.value = false
    }
  }

  function setScene(data: SceneData | SceneReconstructionResponse | null) {
    sceneData.value = normalizeScene(data)
  }

  return { sceneData, loading, reconstruct, setScene, fallbackSceneFromDescription }
})
