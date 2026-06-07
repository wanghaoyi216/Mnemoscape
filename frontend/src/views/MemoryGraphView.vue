<script setup lang="ts">
/**
 * Mnemosyne Nebula — 个人记忆星图（Sprint 3 MVP）。
 *
 * 每段记忆 = 一颗带光晕的恒星，位置由 memory.id 哈希到 Fibonacci 球面上，
 * 颜色由 privacyLevel 决定（私密=薄荷绿、好友=紫罗兰、公开=金），fadeLevel
 * 越高越脱色。镜头：OrbitControls + 阻尼自由旋转；点击恒星会用 tween 把
 * 相机平滑推近，并把右侧详情侧栏切换到对应记忆。
 *
 * 这一版还没接 Neo4j（行星 / 卫星 / 关系连线）。等后端的 NLP 实体提取就位
 * 后，再叠加：
 *  - 行星层：人物实体作为公转卫星
 *  - 流体光流：基于关系强度的双向贝塞尔光线
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useMemoryStore } from '../stores/memory'
import { images } from '../assets/media-catalog'
import type { MemoryItem } from '../types'

const router = useRouter()
const store = useMemoryStore()
const { t } = useI18n()

const containerRef = ref<HTMLDivElement | null>(null)
const tooltip = ref<{ x: number; y: number; memory: MemoryItem } | null>(null)
const selected = ref<MemoryItem | null>(null)
const loading = ref(true)

const heroBg = images.memoryCorona.src

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let starMeshes: THREE.Mesh[] = []
const memoryById = new Map<string, MemoryItem>()
let raycaster: THREE.Raycaster | null = null
const pointer = new THREE.Vector2()
let rafId: number | null = null
let resizeObs: ResizeObserver | null = null

// 相机平滑推进的目标 — 由点击恒星驱动
const camTween = {
  active: false,
  fromPos: new THREE.Vector3(),
  toPos: new THREE.Vector3(),
  fromTarget: new THREE.Vector3(),
  toTarget: new THREE.Vector3(),
  startTime: 0,
  duration: 1200,
}

const memoryCount = computed(() => store.memories.length)

/** Fibonacci 球面分布 — 每个 id 哈希到稳定且不聚簇的方向。 */
function fibonacciDirection(idHash: number, total: number): THREE.Vector3 {
  const phi = Math.acos(1 - (2 * (idHash % total + 0.5)) / total)
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))
  const theta = goldenAngle * idHash
  const x = Math.cos(theta) * Math.sin(phi)
  const y = Math.sin(theta) * Math.sin(phi)
  const z = Math.cos(phi)
  return new THREE.Vector3(x, y, z)
}

function hash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0
  return Math.abs(h)
}

/**
 * 情绪 → HEX 颜色（设计书 §3.2.1 璀璨多色星群）：
 * 把记忆 emotionProfile JSON 里的主导情绪映射到星光颜色。
 * 缺失 emotionProfile / 无法解析 → 退回到原本的 privacyLevel 着色。
 */
const EMOTION_COLOR_MAP: Record<string, string> = {
  joy: '#ffd76a',         // 微暖明黄 — 喜悦
  sadness: '#5b8def',     // 深海蓝 — 悲伤
  anger: '#f87171',       // 热血赤 — 愤怒
  fear: '#9ca3af',        // 灰雾 — 恐惧
  surprise: '#34d399',    // 嫩绿 — 惊奇
  nostalgia: '#c084fc',   // 梦幻浅紫 — 怀念
  peace: '#5ee5d9',       // 极光青 — 平和
  melancholy: '#6cc6ff',  // 冷蓝 — 忧郁
}

function dominantEmotionColor(memory: MemoryItem): THREE.Color | null {
  const raw = (memory as { emotionProfile?: string }).emotionProfile
  if (!raw || typeof raw !== 'string') return null
  try {
    const vec = JSON.parse(raw) as Record<string, number>
    if (!vec || typeof vec !== 'object') return null
    let best: { key: string; val: number } | null = null
    for (const [k, v] of Object.entries(vec)) {
      const num = typeof v === 'number' ? v : parseFloat(String(v))
      if (!Number.isFinite(num)) continue
      if (!best || num > best.val) best = { key: k, val: num }
    }
    // 主导情绪权重过低（< 0.2）说明记忆情绪平淡，让 privacyLevel 来定色更合理
    if (!best || best.val < 0.2) return null
    const hex = EMOTION_COLOR_MAP[best.key]
    return hex ? new THREE.Color(hex) : null
  } catch {
    return null
  }
}

/** 主色调：情绪着色优先 → privacyLevel 兜底；fadeLevel 越高越向灰色退色 */
function colorFor(memory: MemoryItem): THREE.Color {
  const base = dominantEmotionColor(memory) || (memory.privacyLevel === 'PUBLIC'
    ? new THREE.Color('#f2b95c')
    : memory.privacyLevel === 'FRIENDS'
      ? new THREE.Color('#846edc')
      : new THREE.Color('#36d8b4'))
  // fadeLevel 高的恒星向冷灰退色 — 视觉上"褪去"
  const fade = Math.min(1, Math.max(0, memory.fadeLevel || 0))
  const grey = new THREE.Color('#3b4452')
  return base.clone().lerp(grey, fade * 0.65)
}

/** 自定义恒星 ShaderMaterial — 用径向衰减做廉价 bloom。 */
function makeStarMaterial(color: THREE.Color) {
  return new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: color },
      uTime: { value: 0 },
    },
    vertexShader: /* glsl */ `
      varying vec3 vNormal;
      varying vec3 vView;
      void main() {
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        vNormal = normalize(normalMatrix * normal);
        vView = normalize(-mv.xyz);
        gl_Position = projectionMatrix * mv;
      }
    `,
    fragmentShader: /* glsl */ `
      precision highp float;
      uniform vec3 uColor;
      uniform float uTime;
      varying vec3 vNormal;
      varying vec3 vView;
      void main() {
        // 边缘 fresnel 让球面外缘发亮 — 廉价 bloom 替身
        float fres = pow(1.0 - max(0.0, dot(vNormal, vView)), 2.2);
        // 心跳脉动
        float pulse = 0.85 + 0.15 * sin(uTime * 1.8);
        vec3 col = uColor * (0.7 + fres * 1.6) * pulse;
        gl_FragColor = vec4(col, 1.0);
      }
    `,
    transparent: false,
  })
}

/** 中心点之外多挂一层稀薄星云，营造"宇宙"感。 */
function addBackdropParticles(s: THREE.Scene) {
  const N = 2400
  const positions = new Float32Array(N * 3)
  const sizes = new Float32Array(N)
  for (let i = 0; i < N; i++) {
    const r = 40 + Math.random() * 60
    const t = Math.random() * Math.PI * 2
    const p = Math.acos(2 * Math.random() - 1)
    positions[i * 3 + 0] = r * Math.sin(p) * Math.cos(t)
    positions[i * 3 + 1] = r * Math.sin(p) * Math.sin(t)
    positions[i * 3 + 2] = r * Math.cos(p)
    sizes[i] = 1 + Math.random() * 3.2
  }
  const geom = new THREE.BufferGeometry()
  geom.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geom.setAttribute('size', new THREE.BufferAttribute(sizes, 1))
  // 用 ShaderMaterial 让每颗星独立闪烁（明暗呼吸，设计书 3.2.1）
  const mat = new THREE.ShaderMaterial({
    uniforms: { uTime: { value: 0 } },
    vertexShader: `
      attribute float size;
      varying float vIdx;
      void main() {
        vIdx = position.x + position.y * 1.7 + position.z * 0.3;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_PointSize = size * (180.0 / -mv.z);
        gl_Position = projectionMatrix * mv;
      }
    `,
    fragmentShader: `
      uniform float uTime;
      varying float vIdx;
      void main() {
        float d = length(gl_PointCoord - vec2(0.5));
        if (d > 0.5) discard;
        float tw = 0.30 + 0.70 * sin(uTime * 2.4 + vIdx * 0.13);
        float a = (1.0 - d * 2.0) * tw;
        gl_FragColor = vec4(1.0, 1.0, 1.0, a);
      }
    `,
    transparent: true,
    depthWrite: false,
  })
  const points = new THREE.Points(geom, mat)
  points.userData.backdrop = true
  s.add(points)
}

/** 廉价径向辉光贴图 — 用做光晕 sprite，让恒星明显更"亮"且有外发光 */
let _haloTex: THREE.CanvasTexture | null = null
function getHaloTexture(): THREE.CanvasTexture {
  if (_haloTex) return _haloTex
  const c = document.createElement('canvas')
  c.width = 256; c.height = 256
  const g = c.getContext('2d')!
  const grad = g.createRadialGradient(128, 128, 0, 128, 128, 128)
  grad.addColorStop(0,   'rgba(255,255,255,1)')
  grad.addColorStop(0.3, 'rgba(255,255,255,0.45)')
  grad.addColorStop(1,   'rgba(255,255,255,0)')
  g.fillStyle = grad
  g.fillRect(0, 0, 256, 256)
  _haloTex = new THREE.CanvasTexture(c)
  return _haloTex
}

function buildScene(memories: MemoryItem[]) {
  if (!scene) return
  // 清掉前一轮的恒星
  for (const m of starMeshes) {
    scene.remove(m)
    ;(m.geometry as THREE.BufferGeometry).dispose()
    ;(m.material as THREE.Material).dispose()
  }
  starMeshes = []
  // 清理光晕 sprite
  scene.children.filter((c: any) => c.userData?.isHalo).forEach((c) => scene!.remove(c))
  memoryById.clear()

  const total = Math.max(memories.length, 1)
  const radius = Math.max(8, Math.sqrt(total) * 3.2)
  const geom = new THREE.SphereGeometry(0.5, 28, 28)
  const haloTex = getHaloTexture()

  memories.forEach((memory, idx) => {
    const h = hash(memory.id || String(idx))
    const dir = fibonacciDirection(h, total).multiplyScalar(radius)
    const color = colorFor(memory)
    const mesh = new THREE.Mesh(geom, makeStarMaterial(color))
    mesh.position.copy(dir)
    // 大小按 fadeLevel 反向缩放（越新越大），但保留下限
    const size = 0.7 + (1 - (memory.fadeLevel || 0)) * 0.65
    mesh.scale.setScalar(size)
    mesh.userData.memoryId = memory.id
    scene!.add(mesh)
    starMeshes.push(mesh)
    memoryById.set(memory.id, memory)

    // 给每颗恒星套一层 sprite halo（廉价 bloom 替身，外发光明显增强）
    const halo = new THREE.Sprite(new THREE.SpriteMaterial({
      map: haloTex,
      color,
      blending: THREE.AdditiveBlending,
      transparent: true,
      depthWrite: false,
      opacity: 0.9,
    }))
    halo.scale.setScalar(size * 3.2)
    halo.position.copy(dir)
    halo.userData.isHalo = true
    halo.userData.memoryId = memory.id
    scene!.add(halo)
  })
}

function onPointerMove(ev: PointerEvent) {
  if (!containerRef.value || !camera || !raycaster) return
  const rect = containerRef.value.getBoundingClientRect()
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects(starMeshes, false)
  if (hits.length > 0) {
    const m = memoryById.get(hits[0].object.userData.memoryId as string)
    if (m) {
      tooltip.value = { x: ev.clientX - rect.left, y: ev.clientY - rect.top, memory: m }
      document.body.style.cursor = 'pointer'
      return
    }
  }
  tooltip.value = null
  document.body.style.cursor = ''
}

function onClick(ev: PointerEvent) {
  if (!containerRef.value || !camera || !controls || !raycaster) return
  const rect = containerRef.value.getBoundingClientRect()
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects(starMeshes, false)
  if (hits.length === 0) return
  const target = hits[0].object as THREE.Mesh
  const memory = memoryById.get(target.userData.memoryId as string)
  if (!memory) return

  // 镜头平滑推进：终点位于恒星朝向相机方向 2.6 单位的位置
  const star = target.position.clone()
  const camToStar = star.clone().sub(camera.position).normalize()
  const newCam = star.clone().sub(camToStar.multiplyScalar(2.6))

  camTween.fromPos.copy(camera.position)
  camTween.toPos.copy(newCam)
  camTween.fromTarget.copy(controls.target)
  camTween.toTarget.copy(star)
  camTween.startTime = performance.now()
  camTween.active = true

  selected.value = memory
}

function easeInOut(t: number) {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2
}

function frame() {
  if (!renderer || !scene || !camera || !controls) return

  // 更新所有恒星的脉动时钟
  const now = performance.now() / 1000
  for (const m of starMeshes) {
    const mat = m.material as THREE.ShaderMaterial
    mat.uniforms.uTime.value = now + (m.userData.memoryId?.charCodeAt(0) ?? 0) * 0.1
  }
  // 更新背景星空时钟（闪烁呼吸）
  scene.children.forEach((c: any) => {
    if (c.userData?.backdrop && c.material?.uniforms?.uTime) {
      c.material.uniforms.uTime.value = now
    }
  })

  // 相机 tween
  if (camTween.active) {
    const t = Math.min(1, (performance.now() - camTween.startTime) / camTween.duration)
    const k = easeInOut(t)
    camera.position.lerpVectors(camTween.fromPos, camTween.toPos, k)
    const tgt = new THREE.Vector3().lerpVectors(camTween.fromTarget, camTween.toTarget, k)
    controls.target.copy(tgt)
    if (t >= 1) camTween.active = false
  }

  controls.update()
  renderer.render(scene, camera)
  rafId = requestAnimationFrame(frame)
}

function resize() {
  if (!containerRef.value || !renderer || !camera) return
  const rect = containerRef.value.getBoundingClientRect()
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
  renderer.setSize(rect.width, rect.height, false)
  camera.aspect = rect.width / rect.height
  camera.updateProjectionMatrix()
}

onMounted(async () => {
  const container = containerRef.value
  if (!container) return

  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  } catch (e) {
    console.warn('[MemoryGraph] WebGL unavailable', e)
    loading.value = false
    return
  }
  renderer.setClearColor(0x05070b, 1)
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x05070b, 0.012)
  camera = new THREE.PerspectiveCamera(55, 1, 0.1, 200)
  camera.position.set(0, 0, 28)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.rotateSpeed = 0.6
  controls.minDistance = 4
  controls.maxDistance = 80

  raycaster = new THREE.Raycaster()
  addBackdropParticles(scene)

  // 拉数据后再造星 — 已加载就跳过
  if (store.memories.length === 0) {
    try { await store.fetchList(0, 100) } catch { /* 静默 */ }
  }
  buildScene(store.memories)
  loading.value = false

  renderer.domElement.addEventListener('pointermove', onPointerMove as EventListener)
  renderer.domElement.addEventListener('click', onClick as EventListener)

  resize()
  resizeObs = new ResizeObserver(resize)
  resizeObs.observe(container)

  rafId = requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  rafId = null
  resizeObs?.disconnect()
  controls?.dispose()
  for (const m of starMeshes) {
    ;(m.geometry as THREE.BufferGeometry).dispose()
    ;(m.material as THREE.Material).dispose()
  }
  starMeshes = []
  scene?.traverse((obj) => {
    if ((obj as any).geometry) (obj as any).geometry.dispose?.()
    if ((obj as any).material) (obj as any).material.dispose?.()
  })
  renderer?.dispose()
  document.body.style.cursor = ''
})

function openDetail(memory: MemoryItem) {
  router.push(`/memories/${memory.id}`)
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      class="hero-card hero-card--split graph-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.82) 0%, rgba(8,10,14,0.42) 55%, rgba(8,10,14,0.92) 100%), url(${heroBg})` }"
    >
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('memory.graph.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ t('memory.graph.title') }}</h1>
        <p class="lead">{{ t('memory.graph.lead') }}</p>
      </div>
      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.graph.metrics.total') }}</span>
          <strong class="metric-card__value">{{ memoryCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.graph.metrics.distribution') }}</span>
          <strong class="metric-card__value">{{ t('memory.graph.metrics.fibonacci') }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.graph.metrics.engine') }}</span>
          <strong class="metric-card__value">{{ t('memory.graph.metrics.engineValue', 'WebGL') }}</strong>
        </div>
      </div>
    </section>

    <section class="section-card graph-stage" style="margin-top: 24px;">
      <div class="graph-stage__hud">
        <div>
          <h2 class="section-title">{{ t('memory.graph.stage.title') }}</h2>
          <p class="subtitle">{{ t('memory.graph.stage.hint') }}</p>
        </div>
        <div class="graph-stage__legend" aria-hidden="true">
          <span><i class="legend-dot legend-dot--mint"></i>{{ t('memory.list.privacy.PRIVATE') }}</span>
          <span><i class="legend-dot legend-dot--violet"></i>{{ t('memory.list.privacy.FRIENDS') }}</span>
          <span><i class="legend-dot legend-dot--gold"></i>{{ t('memory.list.privacy.PUBLIC') }}</span>
        </div>
      </div>

      <div class="graph-stage__viewport-wrap">
        <div ref="containerRef" class="graph-stage__viewport"></div>

        <div v-if="loading" class="graph-stage__loading">
          <p class="eyebrow">{{ t('memory.graph.loading') }}</p>
        </div>

        <!-- 悬浮 tooltip -->
        <div
          v-if="tooltip"
          class="graph-tooltip"
          :style="{ left: `${tooltip.x + 14}px`, top: `${tooltip.y + 14}px` }"
        >
          <strong>{{ tooltip.memory.title }}</strong>
          <small>{{ Math.round((tooltip.memory.fadeLevel || 0) * 100) }}% · {{ tooltip.memory.privacyLevel }}</small>
        </div>

        <!-- 选中详情侧栏 -->
        <aside v-if="selected" class="graph-aside">
          <header class="graph-aside__head">
            <p class="eyebrow">{{ t('memory.graph.selected') }}</p>
            <h3>{{ selected.title }}</h3>
          </header>
          <p class="help-text graph-aside__desc">{{ selected.description }}</p>
          <div class="chip-grid">
            <span v-if="selected.memoryYear" class="chip">{{ selected.memoryYear }}</span>
            <span v-if="selected.memoryLocation" class="chip">{{ selected.memoryLocation }}</span>
            <span class="chip">{{ t(`memory.list.privacy.${selected.privacyLevel}`, selected.privacyLevel) }}</span>
          </div>
          <button class="button button--primary" type="button" @click="openDetail(selected)">
            {{ t('memory.graph.openDetail') }}
          </button>
          <button class="button button--secondary" type="button" @click="selected = null">
            {{ t('common.cancel') }}
          </button>
        </aside>
      </div>
    </section>
  </div>
</template>

<style scoped>
.graph-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.graph-stage {
  padding: 22px;
}

.graph-stage__hud {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.graph-stage__legend {
  display: flex;
  gap: 16px;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.legend-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 6px;
  box-shadow: 0 0 8px currentColor;
}
.legend-dot--mint   { color: #36d8b4; background: #36d8b4; }
.legend-dot--violet { color: #846edc; background: #846edc; }
.legend-dot--gold   { color: #f2b95c; background: #f2b95c; }

.graph-stage__viewport-wrap {
  position: relative;
  width: 100%;
  height: clamp(420px, 64vh, 720px);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: #05070b;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.graph-stage__viewport {
  position: absolute;
  inset: 0;
}

.graph-stage__loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  background: rgba(5, 7, 11, 0.65);
  backdrop-filter: blur(4px);
}

.graph-tooltip {
  position: absolute;
  pointer-events: none;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  background: rgba(8, 10, 14, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(10px);
  display: grid;
  gap: 2px;
  font-size: 0.85rem;
  color: var(--text);
  transform: translateY(-50%);
  z-index: 5;
  max-width: 260px;
  box-shadow: 0 12px 32px -16px rgba(0, 0, 0, 0.6);
}

.graph-tooltip small {
  color: var(--text-muted);
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.graph-aside {
  position: absolute;
  top: 16px;
  right: 16px;
  width: min(320px, 80%);
  padding: 20px;
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.78);
  backdrop-filter: blur(14px) saturate(150%);
  border: 1px solid rgba(255, 255, 255, 0.1);
  display: grid;
  gap: 12px;
  z-index: 6;
  box-shadow: 0 24px 48px -20px rgba(0, 0, 0, 0.7);
}

.graph-aside__head h3 {
  margin: 4px 0 0;
  font-family: var(--font-display);
  font-size: 1.18rem;
  letter-spacing: -0.01em;
}

.graph-aside__desc {
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
