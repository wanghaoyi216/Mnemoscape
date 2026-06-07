<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import * as THREE from 'three'

const { t } = useI18n()
const router = useRouter()
const memoryStore = useMemoryStore()

const containerRef = ref<HTMLElement | null>(null)
const playing = ref(false)
const currentIndex = ref(0)
const transitionProgress = ref(0)
const playbackSpeed = ref(1.0)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let currentTexture: THREE.Texture | null = null
let nextTexture: THREE.Texture | null = null
let transitionMaterial: THREE.ShaderMaterial | null = null
let plane: THREE.Mesh | null = null
let animId = 0
let playbackTimer: number | null = null

const memories = computed(() => {
  return [...memoryStore.memories].sort((a, b) => {
    const aTime = new Date(a.createdAt).getTime()
    const bTime = new Date(b.createdAt).getTime()
    return aTime - bTime
  })
})

const currentMemory = computed(() => memories.value[currentIndex.value])
const hasNext = computed(() => currentIndex.value < memories.value.length - 1)
const hasPrev = computed(() => currentIndex.value > 0)

const transitionShader = {
  vertexShader: `
    varying vec2 vUv;
    void main() {
      vUv = uv;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    }
  `,
  fragmentShader: `
    uniform sampler2D tCurrent;
    uniform sampler2D tNext;
    uniform float progress;
    uniform int transitionType;
    varying vec2 vUv;

    // Fade transition
    vec4 fade(vec4 current, vec4 next, float p) {
      return mix(current, next, p);
    }

    // Dissolve transition
    vec4 dissolve(vec4 current, vec4 next, float p) {
      float noise = fract(sin(dot(vUv, vec2(12.9898, 78.233))) * 43758.5453);
      float threshold = p;
      return noise < threshold ? next : current;
    }

    // Wipe transition (left to right)
    vec4 wipe(vec4 current, vec4 next, float p) {
      return vUv.x < p ? next : current;
    }

    // Zoom transition
    vec4 zoom(vec4 current, vec4 next, float p) {
      vec2 center = vec2(0.5, 0.5);
      float scale = 1.0 + p * 0.5;
      vec2 uv = (vUv - center) * scale + center;
      vec4 c = texture2D(tCurrent, uv);
      vec4 n = texture2D(tNext, vUv);
      return mix(c, n, p);
    }

    void main() {
      vec4 current = texture2D(tCurrent, vUv);
      vec4 next = texture2D(tNext, vUv);

      vec4 color;
      if (transitionType == 0) {
        color = fade(current, next, progress);
      } else if (transitionType == 1) {
        color = dissolve(current, next, progress);
      } else if (transitionType == 2) {
        color = wipe(current, next, progress);
      } else {
        color = zoom(current, next, progress);
      }

      gl_FragColor = color;
    }
  `,
}

onMounted(async () => {
  if (!memoryStore.memories.length) {
    await memoryStore.fetchList(0, 50)
  }
  initScene()
  if (memories.value.length > 0) {
    loadMemoryTexture(0)
  }
})

onUnmounted(() => {
  stopPlayback()
  cancelAnimationFrame(animId)
  renderer?.dispose()
  currentTexture?.dispose()
  nextTexture?.dispose()
})

function initScene() {
  if (!containerRef.value) return

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0a0a0a)

  const w = containerRef.value.clientWidth
  const h = containerRef.value.clientHeight

  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  containerRef.value.appendChild(renderer.domElement)

  const geometry = new THREE.PlaneGeometry(2, 2)
  transitionMaterial = new THREE.ShaderMaterial({
    uniforms: {
      tCurrent: { value: null },
      tNext: { value: null },
      progress: { value: 0 },
      transitionType: { value: 0 },
    },
    vertexShader: transitionShader.vertexShader,
    fragmentShader: transitionShader.fragmentShader,
  })

  plane = new THREE.Mesh(geometry, transitionMaterial)
  scene.add(plane)

  animate()

  window.addEventListener('resize', onResize)
}

function onResize() {
  if (!containerRef.value || !renderer || !camera) return
  const w = containerRef.value.clientWidth
  const h = containerRef.value.clientHeight
  renderer.setSize(w, h)
}

function animate() {
  animId = requestAnimationFrame(animate)
  if (renderer && scene && camera) {
    renderer.render(scene, camera)
  }
}

function loadMemoryTexture(index: number) {
  const memory = memories.value[index]
  if (!memory) return

  const loader = new THREE.TextureLoader()
  const fallbackUrl = memory.coverImageUrl || '/placeholder-memory.jpg'

  loader.load(
    fallbackUrl,
    (texture) => {
      if (currentTexture) currentTexture.dispose()
      currentTexture = texture
      if (transitionMaterial) {
        transitionMaterial.uniforms.tCurrent.value = texture
        transitionMaterial.uniforms.progress.value = 0
      }
    },
    undefined,
    (err) => {
      console.warn('Failed to load memory texture:', err)
    }
  )
}

function startPlayback() {
  playing.value = true
  playNext()
}

function stopPlayback() {
  playing.value = false
  if (playbackTimer) {
    clearTimeout(playbackTimer)
    playbackTimer = null
  }
}

function playNext() {
  if (!playing.value || !hasNext.value) {
    stopPlayback()
    return
  }

  const nextIdx = currentIndex.value + 1
  const nextMem = memories.value[nextIdx]
  if (!nextMem) return

  const loader = new THREE.TextureLoader()
  const fallbackUrl = nextMem.coverImageUrl || '/placeholder-memory.jpg'

  loader.load(fallbackUrl, (texture) => {
    nextTexture = texture
    if (transitionMaterial) {
      transitionMaterial.uniforms.tNext.value = texture
      transitionMaterial.uniforms.transitionType.value = Math.floor(Math.random() * 4)
    }

    performTransition(() => {
      currentIndex.value = nextIdx
      loadMemoryTexture(nextIdx)

      const duration = 5000 / playbackSpeed.value
      playbackTimer = window.setTimeout(() => {
        if (playing.value) playNext()
      }, duration)
    })
  })
}

function performTransition(onComplete: () => void) {
  const duration = 1500
  const startTime = Date.now()

  function updateTransition() {
    const elapsed = Date.now() - startTime
    const progress = Math.min(elapsed / duration, 1)

    transitionProgress.value = progress
    if (transitionMaterial) {
      transitionMaterial.uniforms.progress.value = progress
    }

    if (progress < 1) {
      requestAnimationFrame(updateTransition)
    } else {
      onComplete()
    }
  }

  updateTransition()
}

function goToMemory(index: number) {
  stopPlayback()
  currentIndex.value = index
  loadMemoryTexture(index)
}

function prev() {
  if (hasPrev.value) {
    goToMemory(currentIndex.value - 1)
  }
}

function next() {
  if (hasNext.value) {
    goToMemory(currentIndex.value + 1)
  }
}

function viewDetail() {
  if (currentMemory.value) {
    router.push(`/memories/${currentMemory.value.id}`)
  }
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section class="hero-card" style="margin-bottom: 24px;">
      <div class="stack stack--lg">
        <p class="eyebrow">TIMELINE PLAYBACK · 时光回溯</p>
        <h1 class="display-title text-gradient">记忆时光机</h1>
        <p class="lead">按时间顺序自动播放你的记忆，shader 过渡效果让时光流转如梦似幻。</p>
      </div>
    </section>

    <section class="section-card" style="position: relative; padding: 0; overflow: hidden;">
      <div ref="containerRef" class="player-canvas"></div>

      <div class="player-overlay">
        <div v-if="currentMemory" class="player-info">
          <h3 class="player-info__title">{{ currentMemory.title }}</h3>
          <p class="player-info__meta">
            <span v-if="currentMemory.memoryLocation">{{ currentMemory.memoryLocation }}</span>
            <span v-if="currentMemory.memoryYear">{{ currentMemory.memoryYear }}</span>
          </p>
        </div>

        <div class="player-controls">
          <button class="player-btn" :disabled="!hasPrev" @click="prev">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/>
            </svg>
          </button>

          <button class="player-btn player-btn--primary" @click="playing ? stopPlayback() : startPlayback()">
            <svg v-if="!playing" viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
              <path d="M8 5v14l11-7z"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
              <path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z"/>
            </svg>
          </button>

          <button class="player-btn" :disabled="!hasNext" @click="next">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>
            </svg>
          </button>

          <div class="player-speed">
            <label>{{ playbackSpeed.toFixed(1) }}x</label>
            <input
              v-model.number="playbackSpeed"
              type="range"
              min="0.5"
              max="2"
              step="0.1"
              class="player-slider"
            />
          </div>

          <button class="player-btn" @click="viewDetail">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="16" x2="12" y2="12"/>
              <line x1="12" y1="8" x2="12.01" y2="8"/>
            </svg>
          </button>
        </div>

        <div class="player-progress">
          <span class="player-progress__label">{{ currentIndex + 1 }} / {{ memories.length }}</span>
          <div class="player-progress__bar">
            <div
              class="player-progress__fill"
              :style="{ width: `${((currentIndex + 1) / memories.length) * 100}%` }"
            ></div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.player-canvas {
  width: 100%;
  min-height: min(75vh, 800px);
  background: #0a0a0a;
}

.player-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 24px;
  pointer-events: none;
  background: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0.6) 0%,
    transparent 30%,
    transparent 70%,
    rgba(0, 0, 0, 0.7) 100%
  );
}

.player-info {
  pointer-events: none;
}

.player-info__title {
  margin: 0 0 8px;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.8);
}

.player-info__meta {
  margin: 0;
  font-size: 0.9rem;
  color: var(--text-soft);
  display: flex;
  gap: 12px;
}

.player-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  pointer-events: auto;
}

.player-btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(10, 14, 22, 0.8);
  backdrop-filter: blur(12px);
  color: var(--text);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: all 0.2s ease;
}

.player-btn:hover:not(:disabled) {
  border-color: var(--primary);
  transform: scale(1.1);
}

.player-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.player-btn--primary {
  width: 64px;
  height: 64px;
  background: rgba(108, 99, 255, 0.9);
  border-color: var(--primary);
}

.player-speed {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(10, 14, 22, 0.8);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.player-speed label {
  font-size: 0.85rem;
  color: var(--text-soft);
  min-width: 32px;
}

.player-slider {
  width: 80px;
}

.player-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  pointer-events: none;
}

.player-progress__label {
  font-size: 0.85rem;
  color: var(--text-soft);
  min-width: 60px;
}

.player-progress__bar {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  overflow: hidden;
}

.player-progress__fill {
  height: 100%;
  background: var(--primary);
  transition: width 0.3s ease;
}
</style>
