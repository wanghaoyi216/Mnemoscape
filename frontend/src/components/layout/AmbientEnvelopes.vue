<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useMemoryStore } from '../../stores/memory'
import { useDynamicMedia } from '../../composables/useDynamicMedia'
import { useI18n } from 'vue-i18n'
import { fallbackSceneCover } from '../../assets/media-catalog'

const store = useMemoryStore()
const dynamicMedia = useDynamicMedia()
const { t } = useI18n()

// Default photos to use as fallbacks if the user has no memories
const DEFAULT_MEMORIES = [
  {
    id: 'placeholder-1',
    title: '星野记忆 · Starry Night',
    description: '“仰望同一片星空，在浩瀚的宇宙中找寻失去的时间。”',
    coverUrl: 'https://images.unsplash.com/photo-1506318137071-a8e063b4bec0?q=80&w=600',
    memoryYear: '2025',
    memoryLocation: '星空观测站',
    memorySeason: 'WINTER',
    fadeLevel: 0.12,
    privacyLevel: 'PUBLIC',
    isPlaceholder: true
  },
  {
    id: 'placeholder-2',
    title: '旧日时光 · Polaroid Cam',
    description: '“按下快门的那一秒，我们便成为了彼此的永恒记忆。”',
    coverUrl: 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?q=80&w=600',
    memoryYear: '2023',
    memoryLocation: '街角照相馆',
    memorySeason: 'AUTUMN',
    fadeLevel: 0.35,
    privacyLevel: 'PRIVATE',
    isPlaceholder: true
  },
  {
    id: 'placeholder-3',
    title: '远行足迹 · Nostalgic Train',
    description: '“旅途的终点也许并不重要，重要的是记忆中最温暖的那段时光。”',
    coverUrl: 'https://images.unsplash.com/photo-1475924156734-496f6cac6ec1?q=80&w=600',
    memoryYear: '2024',
    memoryLocation: '林间铁轨',
    memorySeason: 'SPRING',
    fadeLevel: 0.22,
    privacyLevel: 'FRIENDS',
    isPlaceholder: true
  },
  {
    id: 'placeholder-4',
    title: '静谧黄昏 · Silent Sunset',
    description: '“日落时分，温暖的海风轻轻吹拂着关于夏天的诺言。”',
    coverUrl: 'https://images.unsplash.com/photo-1472214222541-d510753a8707?q=80&w=600',
    memoryYear: '2022',
    memoryLocation: '黄金海岸',
    memorySeason: 'SUMMER',
    fadeLevel: 0.48,
    privacyLevel: 'PUBLIC',
    isPlaceholder: true
  }
]

// Pick cover image for memories
function getCoverUrl(memory: any) {
  if (memory.isPlaceholder) return memory.coverUrl
  if (memory.sceneDataUrl && (/^https?:\/\//.test(memory.sceneDataUrl) || memory.sceneDataUrl.startsWith('/'))) {
    return memory.sceneDataUrl
  }
  const dyn = dynamicMedia.pickPhoto(memory.id) || dynamicMedia.pickGif(memory.id)
  if (dyn) return dyn.url
  const asset = fallbackSceneCover(memory.id)
  return asset.src
}

const activeEnvelopes = ref<any[]>([])
const activeMemory = ref<any>(null)
const isModalOpen = ref(false)
const animatingClose = ref(false)

// Coordinates for the zoom effect
const startX = ref(0)
const startY = ref(0)
const clickedEnvelopeId = ref<string | null>(null)

// Distribute memories to float on left/right sides
function regenerateEnvelopes() {
  const userMemories = store.memories.map(m => ({
    id: m.id,
    title: m.title,
    description: m.description,
    memoryYear: m.memoryYear,
    memoryLocation: m.memoryLocation,
    memorySeason: m.memorySeason,
    fadeLevel: m.fadeLevel,
    privacyLevel: m.privacyLevel,
    isPlaceholder: false,
    rawMemory: m
  }))

  // Use fallback if there aren't enough user memories
  const pool = userMemories.length > 0 ? userMemories : DEFAULT_MEMORIES
  
  // Decide how many envelopes to show: 4 to 6
  const count = Math.min(pool.length, 6)
  
  // Shuffle pool to randomly pair memories
  const shuffled = [...pool].sort(() => 0.5 - Math.random())
  const selected = shuffled.slice(0, count)
  
  const half = Math.ceil(selected.length / 2)
  
  activeEnvelopes.value = selected.map((m, idx) => {
    const side = idx < half ? 'left' : 'right'
    const rowIdx = side === 'left' ? idx : idx - half
    const rowCount = side === 'left' ? half : selected.length - half
    
    // Distribute top positions evenly to avoid overlap (e.g. between 18% and 80%)
    const baseTop = 18 + (rowIdx / Math.max(1, rowCount - 1)) * 58
    const randomJitter = (Math.random() * 6) - 3
    const top = `${baseTop + randomJitter}%`
    
    // Offsets from the side
    const sideOffset = `${Math.floor(Math.random() * 12) + 12}px` // 12px to 24px
    
    // Tilt angle
    const rotate = `${(Math.random() * 10) - 5}deg` // -5deg to 5deg
    
    // Animation float delay
    const animDelay = `${Math.random() * -5}s`
    
    return {
      id: m.id,
      title: m.title,
      description: m.description,
      memoryYear: m.memoryYear,
      memoryLocation: m.memoryLocation,
      memorySeason: m.memorySeason,
      fadeLevel: m.fadeLevel,
      privacyLevel: m.privacyLevel,
      coverUrl: getCoverUrl(m),
      side,
      floatClass: `envelope-float-${(idx % 4) + 1}`,
      style: {
        position: 'fixed',
        top,
        [side]: sideOffset,
        transform: `rotate(${rotate})`,
        animationDelay: animDelay,
        pointerEvents: 'auto',
        zIndex: 10
      }
    }
  })
}

// Handle envelope click and trigger zooming transition
function openEnvelope(envelope: any, event: MouseEvent) {
  if (isModalOpen.value || animatingClose.value) return

  clickedEnvelopeId.value = envelope.id
  
  // Calculate clicked envelope's center coordinate relative to the screen center
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  startX.value = rect.left + rect.width / 2 - window.innerWidth / 2
  startY.value = rect.top + rect.height / 2 - window.innerHeight / 2

  activeMemory.value = envelope
  
  // Delay modal entrance slightly to allow flap opening animation to play
  setTimeout(() => {
    isModalOpen.value = true
  }, 150)
}

function closeEnvelope() {
  if (!isModalOpen.value) return
  isModalOpen.value = false
  animatingClose.value = true
  
  // Wait for the modal zoom-out transition to complete before clearing states
  setTimeout(() => {
    activeMemory.value = null
    clickedEnvelopeId.value = null
    animatingClose.value = false
  }, 500)
}

// Watch store memories to regenerate when loaded
watch(() => store.memories, () => {
  regenerateEnvelopes()
}, { deep: true })

onMounted(() => {
  regenerateEnvelopes()
})
</script>

<template>
  <div class="ambient-envelopes-wrapper">
    <!-- Left Envelopes -->
    <div class="envelopes-side envelopes-side--left">
      <div 
        v-for="env in activeEnvelopes.filter(e => e.side === 'left')"
        :key="env.id"
        class="envelope-container"
        :class="[env.floatClass, { 'is-open': clickedEnvelopeId === env.id }]"
        :style="env.style"
        @click="openEnvelope(env, $event)"
      >
        <div class="envelope">
          <!-- Flap -->
          <div class="envelope-flap"></div>
          
          <!-- Polaroid Photo peeking out -->
          <div class="envelope-polaroid">
            <img :src="env.coverUrl" class="envelope-polaroid__img" :alt="env.title" loading="lazy" />
            <div class="envelope-polaroid__title">{{ env.title }}</div>
          </div>
          
          <!-- Front Pocket (creates overlay) -->
          <div class="envelope-pocket"></div>
          
          <!-- Decorative seal -->
          <div class="envelope-seal"></div>
        </div>
      </div>
    </div>

    <!-- Right Envelopes -->
    <div class="envelopes-side envelopes-side--right">
      <div 
        v-for="env in activeEnvelopes.filter(e => e.side === 'right')"
        :key="env.id"
        class="envelope-container"
        :class="[env.floatClass, { 'is-open': clickedEnvelopeId === env.id }]"
        :style="env.style"
        @click="openEnvelope(env, $event)"
      >
        <div class="envelope">
          <!-- Flap -->
          <div class="envelope-flap"></div>
          
          <!-- Polaroid Photo peeking out -->
          <div class="envelope-polaroid">
            <img :src="env.coverUrl" class="envelope-polaroid__img" :alt="env.title" loading="lazy" />
            <div class="envelope-polaroid__title">{{ env.title }}</div>
          </div>
          
          <!-- Front Pocket -->
          <div class="envelope-pocket"></div>
          
          <!-- Decorative seal -->
          <div class="envelope-seal"></div>
        </div>
      </div>
    </div>

    <!-- Screen Center Details Modal -->
    <transition name="zoom-envelope">
      <div 
        v-if="isModalOpen && activeMemory" 
        class="envelope-modal-overlay" 
        @click.self="closeEnvelope"
      >
        <div 
          class="envelope-modal-card"
          :style="{
            '--start-x': `${startX}px`,
            '--start-y': `${startY}px`
          }"
        >
          <!-- Close Button -->
          <button class="envelope-modal-close" type="button" @click="closeEnvelope" aria-label="关闭详情">✕</button>
          
          <!-- Premium Memory Card Content -->
          <div class="envelope-modal-header" :style="{ backgroundImage: `linear-gradient(180deg, rgba(8, 10, 14, 0.2) 0%, rgba(8, 10, 14, 0.9) 100%), url(${activeMemory.coverUrl})` }">
            <div class="envelope-modal-tag-group">
              <span class="chip chip--gold" v-if="activeMemory.memoryYear">{{ activeMemory.memoryYear }}</span>
              <span class="chip" v-if="activeMemory.memoryLocation">{{ activeMemory.memoryLocation }}</span>
              <span class="chip" v-if="activeMemory.memorySeason">{{ t(`memory.builder.seasons.${activeMemory.memorySeason}`, activeMemory.memorySeason) }}</span>
              <span class="status-pill status-pill--accent">{{ t(`memory.list.privacy.${activeMemory.privacyLevel}`, activeMemory.privacyLevel) }}</span>
            </div>
          </div>
          
          <div class="envelope-modal-body">
            <h2 class="envelope-modal-title text-gradient">{{ activeMemory.title }}</h2>
            <p class="envelope-modal-desc">{{ activeMemory.description }}</p>
            
            <div class="envelope-modal-drift-box">
              <div class="drift-meta">
                <span class="drift-label">{{ t('memory.detail.drift') || '遗忘漂移率' }}</span>
                <span class="drift-value">{{ Math.round((activeMemory.fadeLevel || 0) * 100) }}%</span>
              </div>
              <div class="fade-bar">
                <div class="fade-bar__fill" :style="{ width: `${(activeMemory.fadeLevel || 0) * 100}%` }"></div>
              </div>
            </div>
          </div>
          
          <div class="envelope-modal-footer">
            <RouterLink 
              v-if="!activeMemory.isPlaceholder"
              :to="`/memories/${activeMemory.id}`" 
              class="button button--primary"
              style="width: 100%; text-align: center; justify-content: center;"
            >
              {{ t('memory.detail.enterScene') || '进入深度记忆详情' }}
            </RouterLink>
            <button v-else class="button button--secondary" style="width: 100%;" @click="closeEnvelope">
              {{ t('common.close') || '关闭预览' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.ambient-envelopes-wrapper {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 5;
}

.envelopes-side {
  position: fixed;
  top: 0;
  bottom: 0;
  width: 160px;
  pointer-events: none;
  z-index: 5;
}

.envelopes-side--left {
  left: 0;
}

.envelopes-side--right {
  right: 0;
}

/* Base envelope size and container */
.envelope-container {
  width: 135px;
  height: 90px;
  perspective: 600px;
  cursor: pointer;
  pointer-events: auto;
  transition: all 0.4s cubic-bezier(0.165, 0.84, 0.44, 1);
}

.envelope {
  position: relative;
  width: 100%;
  height: 100%;
  background: rgba(13, 18, 26, 0.8);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(242, 185, 92, 0.2);
  border-radius: 8px;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.35);
  transform-style: preserve-3d;
  transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.15);
}

.envelope-container:hover .envelope {
  transform: translateY(-5px) scale(1.08) rotate(0deg) !important;
  border-color: rgba(242, 185, 92, 0.55);
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.5), 0 0 15px rgba(242, 185, 92, 0.25);
}

/* Flap layout using css clip-path & transformations */
.envelope-flap {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(242, 185, 92, 0.15);
  border-radius: 8px 8px 0 0;
  clip-path: polygon(0% 0%, 50% 55%, 100% 0%);
  transform-origin: top center;
  transition: transform 0.4s ease, background-color 0.4s ease;
  z-index: 4;
  border-top: 1px solid rgba(242, 185, 92, 0.3);
}

.envelope-container:hover .envelope-flap {
  transform: rotateX(25deg);
  background: rgba(242, 185, 92, 0.25);
}

.envelope-container.is-open .envelope-flap {
  transform: rotateX(180deg);
  background: rgba(242, 185, 92, 0.05);
  z-index: 1;
}

/* Front Pocket */
.envelope-pocket {
  position: absolute;
  inset: -1px;
  background: linear-gradient(135deg, rgba(13, 18, 26, 0.8) 0%, rgba(20, 27, 38, 0.9) 100%);
  border-radius: 8px;
  clip-path: polygon(0% 40%, 50% 65%, 100% 40%, 100% 100%, 0% 100%);
  border: 1px solid rgba(242, 185, 92, 0.15);
  z-index: 3;
}

/* Decorative seal */
.envelope-seal {
  position: absolute;
  top: 55%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--gold, #f2b95c), #d99a38);
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.4);
  z-index: 4;
  transition: opacity 0.3s ease;
}

/* Gold monogram stamp "M" inside the wax seal */
.envelope-seal::after {
  content: "M";
  position: absolute;
  inset: 2px;
  border: 1px dashed rgba(255, 255, 255, 0.4);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 8px;
  font-weight: 900;
  color: rgba(255, 255, 255, 0.9);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

.envelope-container.is-open .envelope-seal {
  opacity: 0;
}

/* Inside polaroid photo peeking out */
.envelope-polaroid {
  position: absolute;
  top: 8px;
  left: 10px;
  right: 10px;
  height: 70px;
  background: #ffffff;
  border-radius: 3px;
  padding: 3px 3px 14px;
  box-shadow: 0 4px 10px rgba(0,0,0,0.3);
  transform: translateY(0);
  transition: transform 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.1);
  z-index: 2;
  overflow: hidden;
}

.envelope-polaroid__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 2px;
}

.envelope-polaroid__title {
  position: absolute;
  bottom: 1px;
  left: 0;
  right: 0;
  font-family: "STXingkai", "华文行楷", 'KaiTi', cursive;
  font-size: 8px;
  color: #1e293b;
  text-align: center;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0 3px;
}

.envelope-container:hover .envelope-polaroid {
  transform: translateY(-24px) rotate(-3deg);
}

.envelope-container.is-open .envelope-polaroid {
  transform: translateY(-40px) scale(0.8);
  opacity: 0;
}

/* Floating animation modes */
@keyframes float-1 {
  0% { transform: translateY(0px) rotate(-4deg); }
  50% { transform: translateY(-8px) rotate(-2deg); }
  100% { transform: translateY(0px) rotate(-4deg); }
}
@keyframes float-2 {
  0% { transform: translateY(0px) rotate(4deg); }
  50% { transform: translateY(-10px) rotate(2deg); }
  100% { transform: translateY(0px) rotate(4deg); }
}
@keyframes float-3 {
  0% { transform: translateY(0px) rotate(-2deg); }
  50% { transform: translateY(-9px) rotate(-4deg); }
  100% { transform: translateY(0px) rotate(-2deg); }
}
@keyframes float-4 {
  0% { transform: translateY(0px) rotate(3deg); }
  50% { transform: translateY(-7px) rotate(5deg); }
  100% { transform: translateY(0px) rotate(3deg); }
}

.envelope-float-1 { animation: float-1 7.2s ease-in-out infinite; }
.envelope-float-2 { animation: float-2 8.5s ease-in-out infinite; }
.envelope-float-3 { animation: float-3 9.8s ease-in-out infinite; }
.envelope-float-4 { animation: float-4 6.6s ease-in-out infinite; }

.envelope-container:hover {
  animation-play-state: paused !important;
}

/* Modal Overlay */
.envelope-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(4, 6, 10, 0.72);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  z-index: 99999;
  display: grid;
  place-items: center;
  padding: 24px;
  cursor: zoom-out;
  pointer-events: auto;
}

/* Zoom card animation coming from envelope coords */
.envelope-modal-card {
  position: relative;
  background: rgba(15, 23, 42, 0.76);
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
  border: 1px solid rgba(242, 185, 92, 0.28);
  border-radius: 12px;
  width: 90vw;
  max-width: 460px;
  overflow: hidden;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.75),
              0 0 30px rgba(242, 185, 92, 0.15);
  cursor: default;
  display: flex;
  flex-direction: column;
}

.envelope-modal-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.85);
  border: 1px solid rgba(242, 185, 92, 0.25);
  color: var(--text-soft);
  font-size: 12px;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: all 0.15s ease;
  z-index: 10;
}
.envelope-modal-close:hover {
  background: #ef4444;
  border-color: #ef4444;
  color: #ffffff;
  transform: scale(1.1);
}

.envelope-modal-header {
  height: 180px;
  background-size: cover;
  background-position: center;
  position: relative;
  display: flex;
  align-items: flex-end;
  padding: 16px;
}

.envelope-modal-tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  z-index: 1;
}

.chip--gold {
  background: rgba(242, 185, 92, 0.15);
  border-color: rgba(242, 185, 92, 0.35) !important;
  color: var(--gold) !important;
}

.envelope-modal-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.envelope-modal-title {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1.3;
}

.envelope-modal-desc {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.92rem;
  line-height: 1.6;
}

.envelope-modal-drift-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.drift-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.drift-label {
  font-size: 0.72rem;
  text-transform: uppercase;
  color: var(--text-muted);
  letter-spacing: 0.1em;
}

.drift-value {
  font-size: 0.94rem;
  font-weight: 600;
  color: var(--gold);
}

.envelope-modal-footer {
  padding: 16px 24px 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

/* Vue Zoom Envelope Transitions */
.zoom-envelope-enter-active {
  transition: opacity 0.5s ease;
}
.zoom-envelope-leave-active {
  transition: opacity 0.4s ease;
}

.zoom-envelope-enter-active .envelope-modal-card {
  animation: zoom-in-envelope 0.5s cubic-bezier(0.25, 1, 0.5, 1) both;
}
.zoom-envelope-leave-active .envelope-modal-card {
  animation: zoom-out-envelope 0.4s cubic-bezier(0.25, 1, 0.5, 1) both;
}

.zoom-envelope-enter-from,
.zoom-envelope-leave-to {
  opacity: 0;
}

@keyframes zoom-in-envelope {
  0% {
    transform: translate(var(--start-x), var(--start-y)) scale(0.18) rotate(-15deg);
    opacity: 0;
  }
  100% {
    transform: translate(0, 0) scale(1) rotate(0deg);
    opacity: 1;
  }
}

@keyframes zoom-out-envelope {
  0% {
    transform: translate(0, 0) scale(1) rotate(0deg);
    opacity: 1;
  }
  100% {
    transform: translate(var(--start-x), var(--start-y)) scale(0.18) rotate(-15deg);
    opacity: 0;
  }
}

/* Hide on narrow viewports to ensure main list content is clean */
@media (max-width: 1400px) {
  .envelopes-side {
    display: none !important;
  }
}
</style>
