<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSceneStore } from '../stores/scene'
import { useMemoryStore } from '../stores/memory'
import { usePremiumThree } from '../composables/usePremiumThree'
import { fallbackSceneCover, videos } from '../assets/media-catalog'
import type { SceneData } from '../types'

const reconstructingVideo = videos.ebbingHourglass.src

const { t } = useI18n()

const route = useRoute()
const sceneStore = useSceneStore()
const memoryStore = useMemoryStore()
const containerRef = ref<HTMLElement | null>(null)
const { init, loadScene, applyDrift } = usePremiumThree(containerRef)
const sceneError = ref('')

const scene = computed(() => sceneStore.sceneData)
const objectCount = computed(() => scene.value?.objects.length || 0)
const fragmentCount = computed(() => scene.value?.fragments.length || 0)
// 3D 场景未就绪时的占位封面 — 按 memoryId 稳定哈希到不同的视觉风格
const fallbackCover = computed(() => fallbackSceneCover(memoryStore.current?.id).src)

const sceneKeyMap: Record<string, string> = {
  snowy_landscape: 'winter',
  night_courtyard: 'night',
  rainy_street: 'rain',
  flower_garden: 'spring',
  autumn_path: 'autumn',
}

function getSceneKey(env?: string): string {
  if (!env) return 'summer'
  return sceneKeyMap[env] || 'summer'
}

function normalizeScene(payload: unknown): SceneData | null {
  if (!payload || typeof payload !== 'object') return null
  const candidate = payload as { sceneData?: SceneData }
  if (candidate.sceneData) return candidate.sceneData
  return payload as SceneData
}

onMounted(async () => {
  const id = route.params.id as string
  sceneError.value = ''
  sceneStore.setScene(null)
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
    const key = getSceneKey(sceneStore.sceneData.environment)
    loadScene(sceneStore.sceneData, key)
  }
})

watch(
  () => sceneStore.sceneData,
  (data) => {
    if (data) {
      const key = getSceneKey(data.environment)
      loadScene(data, key)
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

      <div v-else style="position: relative; width: 100%; border-radius: var(--radius-lg); overflow: hidden;">
        <!-- Loading Overlay -->
        <transition name="fade">
          <div v-if="sceneStore.loading" class="reconstruct-veil">
            <video class="reconstruct-veil__video" autoplay muted loop playsinline preload="auto">
              <source :src="reconstructingVideo" type="video/mp4" />
            </video>
            <div class="reconstruct-veil__mask"></div>
            <div class="reconstruct-veil__copy">
              <p class="eyebrow" style="letter-spacing: 0.15em; color: var(--gold);">✨ QUANTUM MEMORY RECONSTRUCTION ✨</p>
              <h2 class="display-title text-aurora" style="font-family: var(--font-art), var(--font-display); font-size: clamp(1.6rem, 2.5vw, 2.6rem); font-weight: 800;">正在重构这片记忆星空...</h2>
              <p class="lead" style="max-width: 52ch; font-family: var(--font-display); font-size: 0.94rem; color: var(--text-soft); line-height: 1.7; margin: 12px 0;">
                "主理人，我们正在从时间长河的文字碎片中抽离出空间、光线、声音和温度。请稍候片刻，这片坍塌的记忆时空即将重回秩序。"
              </p>
              <div class="reconstruct-veil__dots">
                <span class="dot"></span>
                <span class="dot"></span>
                <span class="dot"></span>
              </div>
            </div>
          </div>
        </transition>

        <div ref="containerRef" class="scene-canvas"></div>
      </div>
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

/* AI Reconstruction Loading Veil */
.reconstruct-veil {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  z-index: 10;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background: rgba(5, 7, 11, 0.88);
}

.reconstruct-veil__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.38;
  filter: saturate(110%) contrast(110%);
}

.reconstruct-veil__mask {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(5,7,11,0.3) 0%, rgba(5,7,11,0.92) 85%);
}

.reconstruct-veil__copy {
  position: relative;
  z-index: 2;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 32px;
  background: rgba(10, 14, 20, 0.42);
  border-radius: var(--radius-lg);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.05);
  box-shadow: var(--shadow-lg);
}

.reconstruct-veil__dots {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}

.reconstruct-veil__dots .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 12px var(--primary);
  animation: dotPulse 1.4s infinite ease-in-out;
}

.reconstruct-veil__dots .dot:nth-child(2) {
  animation-delay: 0.2s;
  background: var(--gold);
  box-shadow: 0 0 12px var(--gold);
}

.reconstruct-veil__dots .dot:nth-child(3) {
  animation-delay: 0.4s;
  background: var(--accent);
  box-shadow: 0 0 12px var(--accent);
}

@keyframes dotPulse {
  0%, 100% { transform: scale(0.6); opacity: 0.35; }
  50% { transform: scale(1.25); opacity: 1; }
}

/* Fade Transition */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.5s ease-out;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
