<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSceneStore } from '../stores/scene'
import { useMemoryStore } from '../stores/memory'
import { useThreeScene } from '../composables/useThreeScene'
import { fallbackSceneCover } from '../assets/media-catalog'
import type { SceneData } from '../types'

const { t } = useI18n()

const route = useRoute()
const sceneStore = useSceneStore()
const memoryStore = useMemoryStore()
const containerRef = ref<HTMLElement | null>(null)
const { init, loadScene, applyDrift } = useThreeScene(containerRef)
const sceneError = ref('')

const scene = computed(() => sceneStore.sceneData)
const objectCount = computed(() => scene.value?.objects.length || 0)
const fragmentCount = computed(() => scene.value?.fragments.length || 0)
// 3D 场景未就绪时的占位封面 — 按 memoryId 稳定哈希到不同的视觉风格
const fallbackCover = computed(() => fallbackSceneCover(memoryStore.current?.id).src)

function normalizeScene(payload: unknown): SceneData | null {
  if (!payload || typeof payload !== 'object') return null
  const candidate = payload as { sceneData?: SceneData }
  if (candidate.sceneData) return candidate.sceneData
  return payload as SceneData
}

onMounted(async () => {
  const id = route.params.id as string
  sceneError.value = ''
  try {
    await memoryStore.fetchOne(id)
    await memoryStore.fetchDrift(id)
  } catch (e: any) {
    sceneError.value = e.response?.data?.message || t('scene.loadError')
    return
  }

  // 优先用记忆创建期已经 freeze 的 visualData（完整的 SceneReconstructionResponse）
  // —— 这避免了每次进 SceneViewer 都重跑 /reconstruct 的 30s 等待 + 失败风险，
  // 且和创建当时 AI 输出保持一致（fragments 来自当时的描述，永远 grounded）。
  const visualData = memoryStore.current?.visualData
  if (visualData && typeof visualData === 'string') {
    try {
      const parsed = JSON.parse(visualData)
      const data = normalizeScene(parsed)
      if (data) {
        sceneStore.setScene(data)
      }
    } catch (e) {
      console.warn('Failed to parse memory.visualData; will try /reconstruct fallback', e)
    }
  }

  // sceneDataUrl 二级兜底（http/https 链接指向预先生成的场景资源）
  if (!sceneStore.sceneData && memoryStore.current?.sceneDataUrl
      && (memoryStore.current.sceneDataUrl.startsWith('http')
          || memoryStore.current.sceneDataUrl.startsWith('/'))) {
    try {
      const response = await fetch(memoryStore.current.sceneDataUrl)
      const data = normalizeScene(await response.json())
      if (data) {
        sceneStore.setScene(data)
      }
    } catch {
      sceneError.value = ''
    }
  }

  // 终极兜底：现场调一次 reconstruct（只在前两条都失败时）
  if (!sceneStore.sceneData && memoryStore.current) {
    try {
      await sceneStore.reconstruct(memoryStore.current.description)
    } catch (e: any) {
      sceneError.value = e.response?.data?.message || t('scene.reconstructError')
      return
    }
  }

  init()
  if (sceneStore.sceneData) {
    loadScene(sceneStore.sceneData)
  }
})

watch(
  () => sceneStore.sceneData,
  (data) => {
    if (data) {
      loadScene(data)
    }
  },
)

watch(
  () => memoryStore.currentDrift,
  (drift) => {
    if (drift) applyDrift(drift.fadeLevel)
  },
)
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      class="hero-card hero-card--split scene-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.82) 0%, rgba(8,10,14,0.42) 55%, rgba(8,10,14,0.92) 100%), url(${fallbackCover})` }"
    >
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('scene.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ memoryStore.current?.title || t('scene.loading') }}</h1>
        <p class="lead">{{ t('scene.lead') }}</p>
      </div>

      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.objects') }}</span>
          <strong class="metric-card__value">{{ objectCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.fragments') }}</span>
          <strong class="metric-card__value">{{ fragmentCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.drift') }}</span>
          <strong class="metric-card__value">{{ Math.round((memoryStore.currentDrift?.fadeLevel || 0) * 100) }}%</strong>
        </div>
      </div>
    </section>

    <section class="section-card stack" style="margin-top: 24px; position: relative;">
      <div class="scene-hud">
        <div>
          <h2 class="section-title">
            {{ scene?.environment
              ? t(`scene.environments.${scene.environment}`, scene.environment)
              : t('scene.hud.reconstructing') }}
          </h2>
          <p class="subtitle">
            {{ t(`scene.lighting.${scene?.lighting?.type || 'ambient'}`, scene?.lighting?.type || 'ambient') }}
            {{ t('scene.hud.lightingSuffix') }} ·
            {{ t(`scene.terrain.${scene?.terrain?.type || 'terrain'}`, scene?.terrain?.type || 'terrain') }}
            {{ t('scene.hud.terrainSuffix') }}
          </p>
        </div>
        <div class="chip-grid">
          <span class="chip">{{ t('scene.hud.objects', { count: objectCount }) }}</span>
          <span class="chip">{{ t('scene.hud.fragments', { count: fragmentCount }) }}</span>
          <span class="chip">{{ t('scene.hud.saturation', { pct: Math.round((memoryStore.currentDrift?.colorSaturation || 0) * 100) }) }}</span>
        </div>
      </div>

      <div v-if="sceneError" class="scene-error empty-state" role="alert">
        <h3 class="empty-state__title">{{ t('scene.errorTitle') }}</h3>
        <p class="empty-state__text">{{ sceneError }}</p>
      </div>

      <div v-else ref="containerRef" class="scene-canvas"></div>
    </section>
  </div>
</template>

<style scoped>
.scene-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.scene-hud {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.scene-canvas {
  min-height: min(74vh, 780px);
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border);
  background:
    radial-gradient(circle at top, rgba(34, 211, 238, 0.08), transparent 30%),
    rgba(2, 6, 23, 0.38);
}

.scene-error {
  min-height: min(48vh, 520px);
  display: grid;
  place-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(8, 9, 8, 0.36);
}

@media (max-width: 768px) {
  .scene-hud {
    flex-direction: column;
  }
}
</style>
