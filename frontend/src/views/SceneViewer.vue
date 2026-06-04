<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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
const { init, loadScene, applyDrift, syncFragments, findNearestFragment, ready } = usePremiumThree(containerRef)
const sceneError = ref('')

const scene = computed(() => sceneStore.sceneData)
const objectCount = computed(() => scene.value?.objects.length || 0)
const fragmentCount = computed(() => scene.value?.fragments.length || 0)
// 3D 场景未就绪时的占位封面 — 按 memoryId 稳定哈希到不同的视觉风格
const fallbackCover = computed(() => fallbackSceneCover(memoryStore.current?.id).src)

const proximityHint = ref(false)
const nearestFragment = ref<any | null>(null)
let proximityTimer = 0

const sceneKeyMap: Record<string, string> = {
  snowy_landscape: 'winter',
  night_courtyard: 'night',
  rainy_street: 'rain',
  flower_garden: 'spring',
  autumn_path: 'autumn',
  schoolyard: 'spring',
  indoor_room: 'summer',
  city_street: 'rain',
  seaside: 'summer',
  mountain_path: 'autumn',
  kitchen: 'summer',
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
    await memoryStore.fetchFragments(id) // Fetch real database fragments
  } catch (e: any) {
    sceneError.value = e.response?.data?.message || t('scene.loadError')
    return
  }

  // 优先用记忆创建期已经 freeze 的 visualData（完整的 SceneReconstructionResponse）
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

  await init()
  if (sceneStore.sceneData) {
    const key = getSceneKey(sceneStore.sceneData.environment)
    loadScene(sceneStore.sceneData, key)
    syncFragments(memoryStore.currentFragments)
  }

  // 500ms 频率碰撞/靠近碎片检测，呼出 HUD 气泡
  proximityTimer = window.setInterval(() => {
    const nearest = findNearestFragment(2.6)
    nearestFragment.value = nearest
    proximityHint.value = !!nearest
  }, 500)

  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  if (proximityTimer) {
    window.clearInterval(proximityTimer)
  }
  window.removeEventListener('keydown', onKeyDown)
})

async function onKeyDown(ev: KeyboardEvent) {
  if (ev.key !== 'e' && ev.key !== 'E') return
  const tag = (ev.target as HTMLElement | null)?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA') return
  
  if (nearestFragment.value) {
    const fId = nearestFragment.value.id
    try {
      await memoryStore.discover(fId)
      const id = route.params.id as string
      await memoryStore.fetchFragments(id)
      syncFragments(memoryStore.currentFragments)
    } catch (err) {
      console.error('Failed to discover fragment:', err)
    }
  }
}

watch(
  () => sceneStore.sceneData,
  (data) => {
    if (data) {
      const key = getSceneKey(data.environment)
      loadScene(data, key)
      syncFragments(memoryStore.currentFragments)
    }
  },
)

watch(
  () => memoryStore.currentFragments,
  (list) => {
    if (list) syncFragments(list)
  },
  { deep: true }
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
    <div class="detail-nav-bar" style="margin-bottom: 16px;">
      <RouterLink :to="`/memories/${route.params.id}`" class="button button--ghost" style="backdrop-filter: blur(10px); background: rgba(255, 255, 255, 0.05); display: inline-flex; align-items: center; gap: 8px;">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="19" y1="12" x2="5" y2="12"></line>
          <polyline points="12 19 5 12 12 5"></polyline>
        </svg>
        <span>返回记忆档案</span>
      </RouterLink>
    </div>

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
      <div v-if="scene" class="scene-hud">
        <div>
          <h2 class="section-title">
            {{ t(`scene.environments.${scene.environment}`, scene.environment) }}
          </h2>
          <p class="subtitle">
            {{ t(`scene.lighting.${scene.lighting?.type || 'ambient'}`, scene.lighting?.type || 'ambient') }}
            {{ t('scene.hud.lightingSuffix') }} ·
            {{ t(`scene.terrain.${scene.terrain?.type || 'terrain'}`, scene.terrain?.type || 'terrain') }}
            {{ t('scene.hud.terrainSuffix') }}
          </p>
        </div>
        <div class="chip-grid">
          <span class="chip">{{ t('scene.hud.objects', { count: objectCount }) }}</span>
          <span class="chip">{{ t('scene.hud.fragments', { count: fragmentCount }) }}</span>
          <span v-if="memoryStore.currentDrift" class="chip">{{ t('scene.hud.saturation', { pct: Math.round((memoryStore.currentDrift.colorSaturation || 0) * 100) }) }}</span>
        </div>
      </div>
      <div v-else class="scene-hud">
        <div>
          <h2 class="section-title">{{ t('scene.hud.reconstructing') }}</h2>
          <p class="subtitle">{{ t('scene.lead') }}</p>
        </div>
      </div>

      <div v-if="sceneError" class="scene-error empty-state" role="alert">
        <h3 class="empty-state__title">{{ t('scene.errorTitle') }}</h3>
        <p class="empty-state__text">{{ sceneError }}</p>
      </div>

      <div v-else style="position: relative; width: 100%; border-radius: var(--radius-lg); overflow: hidden;">
        <!-- Loading Overlay — shown while 3D renderer acquires real dimensions -->
        <transition name="fade">
          <div v-if="!ready && !sceneStore.loading" class="reconstruct-veil" style="background: rgba(7, 7, 20, 0.95);">
            <div class="reconstruct-veil__copy">
              <p class="eyebrow" style="letter-spacing: 0.15em; color: var(--primary);">INITIALIZING 3D ENGINE</p>
              <div class="reconstruct-veil__dots">
                <span class="dot"></span>
                <span class="dot"></span>
                <span class="dot"></span>
              </div>
            </div>
          </div>
        </transition>

        <!-- Loading Overlay — shown during AI reconstruction -->
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

      <!-- Proximity Hint overlay -->
      <transition name="proximity">
        <div
          v-if="proximityHint && nearestFragment"
          class="beacon-proximity"
          role="status"
        >
          <kbd class="beacon-proximity__key">E</kbd>
          <span>按 E 键打捞遗忘的记忆碎片</span>
        </div>
      </transition>
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

/* 「按 E 键打捞」提示 */
.beacon-proximity {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px 8px 10px;
  border-radius: 999px;
  background: rgba(10, 14, 22, 0.78);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: var(--text);
  font-size: 0.86rem;
  z-index: 18;
  pointer-events: none;
  box-shadow: 0 16px 40px -20px rgba(0, 0, 0, 0.6);
}
.beacon-proximity__key {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(255,255,255,0.14), rgba(255,255,255,0.06));
  border: 1px solid rgba(255, 255, 255, 0.18);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  font-size: 0.78rem;
  color: var(--gold, #f2b95c);
  letter-spacing: 0.02em;
}

.proximity-enter-active,
.proximity-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}
.proximity-enter-from,
.proximity-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}
</style>
