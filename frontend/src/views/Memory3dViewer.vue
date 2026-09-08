<script setup lang="ts">
/**
 * Memory3dViewer.vue
 * ---------------------------------------------------------
 * R20 InstancedMesh-based 3D memory viewer.
 *
 * Replaces the legacy per-memory Mesh approach (each memory as 3
 * independent THREE.Mesh objects → draw call explosion) with a
 * single InstancedMesh per (emotion, layer) tuple.
 *
 * Public surface for perf testing:
 *   - window.__memory3dEngine   : the InstancedMemory3dEngine
 *   - window.__memory3dScene    : the THREE.Scene
 *   - window.__memory3dRenderer : the THREE.WebGLRenderer
 *   - window.__memory3dCamera   : the THREE.PerspectiveCamera
 *   - window.__memory3dPickAt   : (mx, my) => MemoryItem | null
 *   - window.__memory3dMetrics  : { drawCalls, totalInstances, fps }
 *
 * These are exposed only in dev/test mode so that
 * `memory_3d_viewer_perf.spec.ts` can drive the page.
 */
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useMemoryStore } from '../stores/memory'
import {
  useInstancedMemory3d,
  EMOTION_COLORS,
  type InstancedMemory3dEngine,
} from '../composables/useInstancedMemory3d'
import type { MemoryItem } from '../types'

const router = useRouter()
const memoryStore = useMemoryStore()
const containerRef = ref<HTMLElement | null>(null)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let animId = 0
const mouse = new THREE.Vector2()
let resizeObserver: ResizeObserver | null = null

const engine: InstancedMemory3dEngine = useInstancedMemory3d(containerRef)

const hoveredMemory = ref<MemoryItem | null>(null)
const hoveredEmotion = ref<string | null>(null)
const tooltipPos = ref({ x: 0, y: 0 })

/* -----------------------------------------------------------
 * Per-frame metrics (consumed by the perf test)
 * --------------------------------------------------------- */
const fps = ref(0)
const totalInstances = ref(0)
const drawCallCount = ref(0)
let _lastFpsTime = performance.now()
let _frameCounter = 0

const metrics = computed(() => ({
  fps: fps.value,
  totalInstances: totalInstances.value,
  drawCalls: drawCallCount.value,
  perEmotion: _perEmotionCount.value,
}))

const _perEmotionCount = ref<Record<string, number>>({})

/* -----------------------------------------------------------
 * Mount / unmount
 * --------------------------------------------------------- */
onMounted(async () => {
  if (!memoryStore.memories.length) {
    await memoryStore.fetchList(0, 50)
  }
  initScene()
  // Materialize the instanced meshes
  engine.build(memoryStore.memories)
  refreshMetrics()
  bindInteraction()
  if (import.meta.env.DEV || (import.meta.env as any).VITE_EXPOSE_TEST_HOOKS === 'true') {
    exposeTestHooks()
  }
})

onUnmounted(() => {
  cancelAnimationFrame(animId)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('click', onClick)
  if (resizeObserver) resizeObserver.disconnect()
  renderer?.dispose()
  engine.dispose()
})

function refreshMetrics() {
  totalInstances.value = engine.getTotalInstances()
  drawCallCount.value = engine.getDrawCallCount()
  _perEmotionCount.value = engine.getPerEmotionCount()
}

/* -----------------------------------------------------------
 * Three.js scene init
 * --------------------------------------------------------- */
function initScene() {
  if (!containerRef.value) return
  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x020614)

  const w = Math.max(1, containerRef.value.clientWidth)
  const h = Math.max(1, containerRef.value.clientHeight)

  camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 400)
  camera.position.set(0, 18, 38)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  containerRef.value.innerHTML = ''
  containerRef.value.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.03
  controls.autoRotate = true
  controls.autoRotateSpeed = 0.5
  controls.minDistance = 8
  controls.maxDistance = 120

  const ambient = new THREE.AmbientLight(0x1a1a3e, 0.6)
  scene.add(ambient)
  const pointLight = new THREE.PointLight(0x6c63ff, 1.5, 80)
  pointLight.position.set(0, 12, 0)
  scene.add(pointLight)

  // Mount the composable's root group into the scene
  scene.add(engine.getRoot())

  if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      if (!containerRef.value || !renderer || !camera) return
      const nw = containerRef.value.clientWidth
      const nh = containerRef.value.clientHeight
      camera.aspect = nw / nh
      camera.updateProjectionMatrix()
      renderer.setSize(nw, nh)
    })
    resizeObserver.observe(containerRef.value)
  }

  animate()
}

/**
 * (helper kept for symmetry — the engine's root is now fetched
 * via `engine.getRoot()` and added to the scene in initScene.)
 */

/* -----------------------------------------------------------
 * Animation loop
 * --------------------------------------------------------- */
function animate() {
  animId = requestAnimationFrame(animate)
  controls?.update()
  const t = performance.now() * 0.001
  engine.update(t)
  if (renderer && scene && camera) {
    renderer.render(scene, camera)
  }
  _frameCounter++
  const now = performance.now()
  if (now - _lastFpsTime > 500) {
    fps.value = Math.round((_frameCounter * 1000) / (now - _lastFpsTime))
    _frameCounter = 0
    _lastFpsTime = now
  }
}

/* -----------------------------------------------------------
 * Interaction (hover, click → router)
 * --------------------------------------------------------- */
function bindInteraction() {
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('click', onClick)
}

function onMouseMove(ev: MouseEvent) {
  if (!containerRef.value || !camera || !scene) return
  const rect = containerRef.value.getBoundingClientRect()
  mouse.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  mouse.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
  scene.updateMatrixWorld(true)
  const hit = engine.pickAt(mouse, camera)
  if (hit) {
    hoveredMemory.value = hit.memory
    hoveredEmotion.value = hit.emotion
    tooltipPos.value = { x: ev.clientX - rect.left, y: ev.clientY - rect.top }
    engine.setHovered(hit.emotion, hit.instanceId)
    if (containerRef.value.style.cursor !== 'pointer') {
      containerRef.value.style.cursor = 'pointer'
    }
    if (controls?.autoRotate) controls.autoRotate = false
  } else {
    if (hoveredMemory.value) engine.setHovered(null, null)
    hoveredMemory.value = null
    hoveredEmotion.value = null
    if (containerRef.value.style.cursor !== 'default') {
      containerRef.value.style.cursor = 'default'
    }
  }
}

function onClick() {
  if (hoveredMemory.value) {
    router.push(`/memories/${hoveredMemory.value.id}`)
  }
}

/* -----------------------------------------------------------
 * Dev / perf-test hooks
 *
 * These globals are read by `memory_3d_viewer_perf.spec.ts` and
 * the in-browser perf monitor overlay.  They are inert in prod
 * builds (gated by import.meta.env.DEV).
 * --------------------------------------------------------- */
function exposeTestHooks() {
  const w = window as any
  w.__memory3dEngine = engine
  w.__memory3dScene = scene
  w.__memory3dRenderer = renderer
  w.__memory3dCamera = camera
  w.__memory3dMetrics = metrics.value
  w.__memory3dPickAt = (mx: number, my: number): MemoryItem | null => {
    if (!camera) return null
    mouse.set(mx, my)
    return engine.pickAt(mouse, camera)?.memory ?? null
  }
  w.__memory3dLoadSynthetic = (count: number) => loadSyntheticMemories(count)
}

/**
 * Generate N synthetic memories for the perf test — does not touch
 * the real backend.  Each memory has a random emotion/season so we
 * exercise the full bucket distribution.
 */
function loadSyntheticMemories(count: number): void {
  const emotions = Object.keys(EMOTION_COLORS)
  const synth: MemoryItem[] = []
  for (let i = 0; i < count; i++) {
    const e = emotions[i % emotions.length]
    synth.push({
      id: `synth-${i}`,
      title: `Synthetic ${i}`,
      description: `Auto-generated memory #${i} for perf benchmarking.`,
      memorySeason: e,
      emotionVector: { [e]: 1 } as any,
    } as any)
  }
  engine.build(synth)
  refreshMetrics()
  ;(window as any).__memory3dMetrics = metrics.value
}

/* -----------------------------------------------------------
 * Tooltip helpers
 * --------------------------------------------------------- */
function getStarColorHex(mem: MemoryItem | null): string {
  if (!mem) return '#36d8b4'
  const e = (mem as any).memorySeason
    ?? (mem.emotionVector ? Object.entries(mem.emotionVector).sort((a, b) => (b[1] as number) - (a[1] as number))[0]?.[0] : null)
    ?? 'calm'
  const c = EMOTION_COLORS[e as string] ?? 0x36d8b4
  return `#${c.toString(16).padStart(6, '0')}`
}

function truncate(text: string, max: number): string {
  if (!text) return ''
  if (text.length <= max) return text
  return text.slice(0, max) + '…'
}

const emotionLabelMap: Record<string, { zh: string; en: string }> = {
  joy: { zh: '喜悦', en: 'Joy' },
  happiness: { zh: '喜悦', en: 'Happiness' },
  sadness: { zh: '忧思', en: 'Sadness' },
  calm: { zh: '宁静', en: 'Calm' },
  nostalgia: { zh: '怀旧', en: 'Nostalgia' },
  longing: { zh: '思念', en: 'Longing' },
  love: { zh: '眷恋', en: 'Love' },
  spring: { zh: '春', en: 'Spring' },
  summer: { zh: '夏', en: 'Summer' },
  autumn: { zh: '秋', en: 'Autumn' },
  fall: { zh: '秋', en: 'Fall' },
  winter: { zh: '冬', en: 'Winter' },
  anxiety: { zh: '焦虑', en: 'Anxiety' },
  melancholy: { zh: '忧郁', en: 'Melancholy' },
  gratitude: { zh: '感恩', en: 'Gratitude' },
  excitement: { zh: '兴奋', en: 'Excitement' },
}
function emotionLabel(key: string | null): string {
  if (!key) return ''
  return emotionLabelMap[key]?.zh ?? key
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section class="hero-card" style="margin-bottom: 24px;">
      <div class="stack stack--lg">
        <p class="eyebrow">INSTANCED 3D MEMORY · R20 高性能记忆星图</p>
        <h1 class="display-title text-gradient">记忆星图（InstancedMesh）</h1>
        <p class="lead">
          50,000 颗记忆粒子 = 24 个 draw call（每个情感 × 三层）。原版每颗记忆 3 个 Mesh，
          50k 时高达 150,000 次 draw call，浏览器直接卡到 12 FPS。
        </p>
      </div>
    </section>

    <section
      class="section-card constellation-stage"
      style="position: relative; padding: 0; overflow: hidden;"
      @mouseleave="() => { hoveredMemory = null; if (containerRef) containerRef.style!.cursor = 'default' }"
    >
      <div ref="containerRef" class="constellation-canvas"></div>

      <!-- HUD: live perf metrics -->
      <div class="perf-hud" data-testid="perf-hud">
        <div class="perf-hud__row">
          <span class="perf-hud__label">FPS</span>
          <strong class="perf-hud__value" data-testid="perf-fps">{{ fps }}</strong>
        </div>
        <div class="perf-hud__row">
          <span class="perf-hud__label">Draw Calls</span>
          <strong class="perf-hud__value" data-testid="perf-draw-calls">{{ drawCallCount }}</strong>
        </div>
        <div class="perf-hud__row">
          <span class="perf-hud__label">Memories</span>
          <strong class="perf-hud__value" data-testid="perf-instances">{{ totalInstances }}</strong>
        </div>
      </div>

      <transition name="fade">
        <div
          v-if="hoveredMemory"
          class="constellation-tooltip"
          :style="{ left: `${tooltipPos.x + 16}px`, top: `${tooltipPos.y - 10}px` }"
          data-testid="memory-tooltip"
        >
          <div class="constellation-tooltip__head">
            <span
              class="constellation-tooltip__dot"
              :style="{ background: getStarColorHex(hoveredMemory) }"
            ></span>
            <strong class="constellation-tooltip__title">{{ hoveredMemory.title }}</strong>
          </div>
          <div class="constellation-tooltip__meta">
            <span v-if="hoveredMemory.memoryLocation">📍 {{ hoveredMemory.memoryLocation }}</span>
            <span v-if="hoveredMemory.memoryYear">🗓 {{ hoveredMemory.memoryYear }}</span>
            <span v-if="hoveredEmotion" class="constellation-tooltip__emotion">
              {{ emotionLabel(hoveredEmotion) }}
            </span>
          </div>
          <p v-if="hoveredMemory.description" class="constellation-tooltip__desc">
            {{ truncate(hoveredMemory.description, 96) }}
          </p>
          <span class="constellation-tooltip__cta">点击查看详情 →</span>
        </div>
      </transition>

      <div class="constellation-legend">
        <div class="constellation-legend__item" v-for="(color, emotion) in EMOTION_COLORS" :key="emotion">
          <span class="constellation-legend__dot" :style="{ background: `#${color.toString(16).padStart(6, '0')}` }"></span>
          <span>{{ emotion }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.constellation-canvas {
  width: 100%;
  min-height: min(80vh, 800px);
  border-radius: var(--radius-lg);
}

.perf-hud {
  position: absolute;
  top: 16px;
  left: 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(10, 14, 22, 0.78);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  pointer-events: none;
  z-index: 10;
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: 0.78rem;
  min-width: 140px;
}
.perf-hud__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.perf-hud__label {
  color: var(--text-muted, #94a3b8);
}
.perf-hud__value {
  color: var(--primary, #36d8b4);
  font-weight: 600;
}

.constellation-tooltip {
  position: absolute;
  pointer-events: none;
  z-index: 20;
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(10, 14, 22, 0.92);
  backdrop-filter: blur(14px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.82rem;
  color: var(--text);
  max-width: 240px;
  min-width: 120px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  overflow-wrap: anywhere;
  word-break: break-word;
}
.constellation-tooltip__head { display: flex; align-items: center; gap: 6px; }
.constellation-tooltip__dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
  box-shadow: 0 0 6px currentColor;
}
.constellation-tooltip__title { font-size: 0.9rem; font-weight: 600; line-height: 1.3; }
.constellation-tooltip__meta {
  display: flex; flex-wrap: wrap; gap: 8px; font-size: 0.72rem; color: var(--text-muted);
}
.constellation-tooltip__emotion {
  padding: 1px 6px; border-radius: 4px;
  background: rgba(54, 216, 180, 0.12); color: var(--primary); font-size: 0.7rem;
}
.constellation-tooltip__desc {
  margin: 4px 0 0; font-size: 0.76rem; color: var(--text-muted); line-height: 1.45;
  max-height: 60px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow-wrap: anywhere;
}
.constellation-tooltip__cta {
  margin-top: 4px; font-size: 0.7rem; color: var(--primary); font-weight: 500;
}

.constellation-legend {
  position: absolute;
  bottom: 16px;
  right: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(10, 14, 22, 0.8);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.06);
  pointer-events: none;
  z-index: 10;
}
.constellation-legend__item {
  display: flex; align-items: center; gap: 4px;
  font-size: 0.7rem; color: var(--text-muted);
}
.constellation-legend__dot { width: 8px; height: 8px; border-radius: 50%; }

.fade-enter-active, .fade-leave-active { transition: opacity 150ms ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
