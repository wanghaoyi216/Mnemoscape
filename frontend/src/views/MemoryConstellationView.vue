<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useMemoryStore } from '../stores/memory'
import { conceptIllustrations, loginBackgrounds } from '../assets/media-catalog'

const router = useRouter()
const memoryStore = useMemoryStore()
const constellationArt = conceptIllustrations[1]
const constellationBg = loginBackgrounds[8]
const containerRef = ref<HTMLElement | null>(null)
const hoveredMemory = ref<any | null>(null)
const hoveredEmotion = ref<string | null>(null)
const tooltipPos = ref({ x: 0, y: 0 })

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let animId = 0
let raycaster: THREE.Raycaster | null = null
let mouse = new THREE.Vector2()
/**
 * starMeshes 现在装的是「不可见的大命中球」，
 * 视觉的 star/glow/core 仍各自渲染但不再参与 raycast 命中。
 * 这样：
 *   - 命中区比可见的星体大 5-7 倍，光标在星附近就能命中
 *   - 视觉细腻（0.2 球 + 0.4 光晕）和命中宽松（大球）解耦
 *   - 即使 starMeshes 整体在旋转/呼吸缩放也不会让命中区忽大忽小
 */
let starMeshes: THREE.Mesh[] = []
let visualStars: THREE.Mesh[] = []   // 真正渲染的小星球（呼吸缩放）
let visualGlows: THREE.Mesh[] = []   // 真正的光晕
let visualCores: THREE.Mesh[] = []   // 真正的核心
let pentagramGroups: THREE.Group[] = []
let particleTrails: THREE.Points[] = []
let resizeObserver: ResizeObserver | null = null
let autoRotateRestoreTimer: number | null = null

const emotionColors: Record<string, number> = {
  joy: 0xffd700,
  sadness: 0x4a90d9,
  nostalgia: 0xff8c42,
  excitement: 0xff4081,
  calm: 0x36d8b4,
  melancholy: 0x7b68ee,
  gratitude: 0xffb7c5,
  anxiety: 0xe040fb,
}

function getStarColor(memory: any): number {
  if (memory.emotionVector && typeof memory.emotionVector === 'object') {
    let dominant = 'calm'
    let max = 0
    for (const [k, v] of Object.entries(memory.emotionVector)) {
      if (typeof v === 'number' && v > max) { max = v; dominant = k }
    }
    return emotionColors[dominant] || 0x36d8b4
  }
  const seasonColors: Record<string, number> = {
    spring: 0xffb7c5,
    summer: 0xffd700,
    autumn: 0xff5722,
    winter: 0x4a90d9,
  }
  return seasonColors[memory.memorySeason] || 0xe0e0ff
}

/**
 * 五芒星阵布局算法：
 * 1. 将记忆按相似度聚类（简化版：按情感/季节分组）
 * 2. 每个聚类形成一个五芒星
 * 3. 五芒星的 5 个顶点放置最相似的记忆
 * 4. 中心放置聚类代表记忆
 */
function buildPentagramConstellation() {
  if (!scene) return
  const memories = memoryStore.memories

  // 按主导情感分组
  const clusters = groupByEmotion(memories)

  let clusterIndex = 0
  for (const [emotion, mems] of Object.entries(clusters)) {
    if (mems.length === 0) continue

    const group = new THREE.Group()
    const baseRadius = 8
    const angle = (clusterIndex / Object.keys(clusters).length) * Math.PI * 2
    const centerX = Math.cos(angle) * baseRadius * 1.5
    const centerZ = Math.sin(angle) * baseRadius * 1.5

    group.position.set(centerX, 0, centerZ)

    // 创建五芒星
    createPentagram(group, mems, emotion)

    scene.add(group)
    pentagramGroups.push(group)
    clusterIndex++
  }
}

function groupByEmotion(memories: any[]): Record<string, any[]> {
  const groups: Record<string, any[]> = {}

  for (const mem of memories) {
    let dominant = 'calm'
    if (mem.emotionVector && typeof mem.emotionVector === 'object') {
      let max = 0
      for (const [k, v] of Object.entries(mem.emotionVector)) {
        if (typeof v === 'number' && v > max) { max = v; dominant = k }
      }
    } else if (mem.memorySeason) {
      dominant = mem.memorySeason
    }

    if (!groups[dominant]) groups[dominant] = []
    groups[dominant].push(mem)
  }

  return groups
}

function createPentagram(group: THREE.Group, memories: any[], emotion: string) {
  const color = emotionColors[emotion] || 0x36d8b4
  const radius = 3

  // 五芒星的 5 个顶点坐标（正五角星）
  const points: THREE.Vector3[] = []
  for (let i = 0; i < 5; i++) {
    const angle = (i * 2 * Math.PI / 5) - Math.PI / 2
    points.push(new THREE.Vector3(
      Math.cos(angle) * radius,
      Math.sin(i * 0.5) * 0.5,
      Math.sin(angle) * radius
    ))
  }

  // 绘制五芒星连线（1->3->0->2->4->1）
  const starOrder = [0, 2, 4, 1, 3, 0]
  const linePoints: THREE.Vector3[] = []
  for (const idx of starOrder) {
    linePoints.push(points[idx])
  }

  const lineGeo = new THREE.BufferGeometry().setFromPoints(linePoints)
  const lineMat = new THREE.LineBasicMaterial({
    color,
    transparent: true,
    opacity: 0.4,
    linewidth: 2,
  })
  const line = new THREE.Line(lineGeo, lineMat)
  group.add(line)

  // 在顶点放置记忆星球
  const memsToPlace = memories.slice(0, 5)
  for (let i = 0; i < memsToPlace.length && i < points.length; i++) {
    const mem = memsToPlace[i]
    const pos = points[i]

    // 1) 视觉：精细小星球（半径 0.2，不参与 raycast 命中）
    const starGeo = new THREE.SphereGeometry(0.2, 16, 16)
    const starMat = new THREE.MeshBasicMaterial({
      color: getStarColor(mem),
      transparent: true,
      opacity: 0.9,
    })
    const star = new THREE.Mesh(starGeo, starMat)
    star.position.copy(pos)
    star.userData = { memory: mem, pentagram: emotion, kind: 'star' }
    star.renderOrder = 2
    group.add(star)
    visualStars.push(star)

    // 2) 视觉：发光光晕（半径 0.4，不参与 raycast 命中）
    const glowGeo = new THREE.SphereGeometry(0.4, 16, 16)
    const glowMat = new THREE.MeshBasicMaterial({
      color: getStarColor(mem),
      transparent: true,
      opacity: 0.2,
      depthWrite: false,
    })
    const glow = new THREE.Mesh(glowGeo, glowMat)
    glow.position.copy(pos)
    group.add(glow)
    visualGlows.push(glow)

    // 3) 命中：不可见的大命中球（半径 1.0 — 视觉的 5 倍大，hover 友好）
    //    完全透明，只为 raycast 服务
    const hitGeo = new THREE.SphereGeometry(1.0, 12, 12)
    const hitMat = new THREE.MeshBasicMaterial({
      color: 0xffffff,
      transparent: true,
      opacity: 0,         // 完全不可见
      depthWrite: false,
      depthTest: false,
    })
    const hit = new THREE.Mesh(hitGeo, hitMat)
    hit.position.copy(pos)
    hit.userData = { memory: mem, pentagram: emotion, kind: 'hit' }
    hit.visible = true   // 保持可见才能被 raycast
    group.add(hit)
    starMeshes.push(hit)
  }

  // 中心核心（视觉上的 wireframe 八面体）
  if (memories.length > 0) {
    const coreGeo = new THREE.OctahedronGeometry(0.3, 0)
    const coreMat = new THREE.MeshBasicMaterial({
      color,
      transparent: true,
      opacity: 0.8,
      wireframe: true,
    })
    const core = new THREE.Mesh(coreGeo, coreMat)
    core.position.set(0, 0, 0)
    core.userData = { memory: memories[0], pentagram: emotion, isCore: true, kind: 'core' }
    group.add(core)
    visualCores.push(core)

    // 中心核心也加一个命中球（更大，半径 1.2 — 让中央也容易点中）
    const hitGeo = new THREE.SphereGeometry(1.2, 12, 12)
    const hitMat = new THREE.MeshBasicMaterial({
      color: 0xffffff,
      transparent: true,
      opacity: 0,
      depthWrite: false,
      depthTest: false,
    })
    const coreHit = new THREE.Mesh(hitGeo, hitMat)
    coreHit.position.set(0, 0, 0)
    coreHit.userData = { memory: memories[0], pentagram: emotion, isCore: true, kind: 'hit' }
    group.add(coreHit)
    starMeshes.push(coreHit)
  }

  // 粒子轨迹（沿五芒星边缘流动）
  createParticleTrail(group, linePoints, color)
}

function createParticleTrail(group: THREE.Group, path: THREE.Vector3[], color: number) {
  const particleCount = 50
  const positions = new Float32Array(particleCount * 3)

  for (let i = 0; i < particleCount; i++) {
    const t = i / particleCount
    const segmentIndex = Math.floor(t * (path.length - 1))
    const segmentT = (t * (path.length - 1)) - segmentIndex

    if (segmentIndex < path.length - 1) {
      const p1 = path[segmentIndex]
      const p2 = path[segmentIndex + 1]
      positions[i * 3] = p1.x + (p2.x - p1.x) * segmentT
      positions[i * 3 + 1] = p1.y + (p2.y - p1.y) * segmentT
      positions[i * 3 + 2] = p1.z + (p2.z - p1.z) * segmentT
    }
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))

  const mat = new THREE.PointsMaterial({
    color,
    size: 0.08,
    transparent: true,
    opacity: 0.6,
    blending: THREE.AdditiveBlending,
  })

  const particles = new THREE.Points(geo, mat)
  particles.userData = { pathLength: path.length, offset: Math.random() }
  group.add(particles)
  particleTrails.push(particles)
}

onMounted(async () => {
  if (!memoryStore.memories.length) {
    await memoryStore.fetchList(0, 50)
  }
  initScene()
  buildPentagramConstellation()
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('click', onClick)
})

onUnmounted(() => {
  cancelAnimationFrame(animId)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('click', onClick)
  if (resizeObserver) resizeObserver.disconnect()
  if (autoRotateRestoreTimer !== null) {
    window.clearTimeout(autoRotateRestoreTimer)
  }
  renderer?.dispose()
})

function initScene() {
  if (!containerRef.value) return
  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x020614)

  const w = Math.max(1, containerRef.value.clientWidth)
  const h = Math.max(1, containerRef.value.clientHeight)

  camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 200)
  camera.position.set(0, 12, 25)

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
  controls.maxDistance = 60

  raycaster = new THREE.Raycaster()

  const ambient = new THREE.AmbientLight(0x1a1a3e, 0.6)
  scene.add(ambient)

  const pointLight = new THREE.PointLight(0x6c63ff, 1.5, 50)
  pointLight.position.set(0, 10, 0)
  scene.add(pointLight)

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

function animate() {
  animId = requestAnimationFrame(animate)
  controls?.update()

  const t = Date.now() * 0.001

  // 五芒星阵旋转
  pentagramGroups.forEach((group, i) => {
    group.rotation.y = t * 0.2 + i * 0.5
  })

  // 视觉星球呼吸（不影响命中）
  const hoveredId = hoveredMemory.value?.id
  visualStars.forEach((mesh, i) => {
    const baseScale = 1 + Math.sin(t * 1.5 + i * 0.7) * 0.15
    const hoverBoost = mesh.userData.memory?.id === hoveredId ? 1.6 : 1.0
    mesh.scale.setScalar(baseScale * hoverBoost)
  })
  // 视觉光晕呼吸（hover 时更亮）
  visualGlows.forEach((mesh, i) => {
    const mat = mesh.material as THREE.MeshBasicMaterial
    const baseOpacity = 0.2 + Math.sin(t * 1.5 + i * 0.7) * 0.06
    const hoverBoost = mesh.userData.memory?.id === hoveredId ? 2.4 : 1.0
    mat.opacity = baseOpacity * hoverBoost
  })
  // 视觉核心呼吸
  visualCores.forEach((mesh, i) => {
    const baseScale = 1 + Math.sin(t * 1.2 + i * 0.9) * 0.1
    const hoverBoost = mesh.userData.memory?.id === hoveredId ? 1.4 : 1.0
    mesh.scale.setScalar(baseScale * hoverBoost)
  })

  // 粒子轨迹流动
  particleTrails.forEach((trail) => {
    const positions = trail.geometry.getAttribute('position')

    for (let i = 0; i < positions.count; i++) {
      const baseY = positions.getY(i)
      positions.setY(i, baseY + Math.sin(t * 2 + i * 0.2) * 0.05)
    }
    positions.needsUpdate = true
  })

  if (renderer && scene && camera) {
    renderer.render(scene, camera)
  }
}

function onMouseMove(ev: MouseEvent) {
  if (!containerRef.value || !raycaster || !camera || !scene) return
  const rect = containerRef.value.getBoundingClientRect()
  mouse.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  mouse.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1

  // 关键修复 1：raycast 之前先更新场景的世界矩阵，
  // 让 group.rotation.y 的实时变化能反映在 raycaster 上
  scene.updateMatrixWorld(true)
  raycaster.setFromCamera(mouse, camera)
  // 关键修复 2：starMeshes 现在是不可见的大命中球，半径 1.0+
  const intersects = raycaster.intersectObjects(starMeshes, false)

  if (intersects.length > 0) {
    const hit = intersects[0].object
    const mem = hit.userData.memory
    hoveredMemory.value = mem
    // 同时记录当前 pentagram 情感，给 tooltip 用
    hoveredEmotion.value = hit.userData.pentagram || null
    tooltipPos.value = { x: ev.clientX - rect.left, y: ev.clientY - rect.top }
    if (containerRef.value.style.cursor !== 'pointer') {
      containerRef.value.style.cursor = 'pointer'
    }
    // 关键修复 3：悬停时暂停 OrbitControls 自转（用户更易点中）
    if (controls && controls.autoRotate) {
      controls.autoRotate = false
      // 5 秒无操作后恢复自转
      scheduleAutoRotateRestore()
    }
  } else {
    hoveredMemory.value = null
    hoveredEmotion.value = null
    if (containerRef.value.style.cursor !== 'default') {
      containerRef.value.style.cursor = 'default'
    }
  }
}

function scheduleAutoRotateRestore() {
  if (autoRotateRestoreTimer !== null) {
    window.clearTimeout(autoRotateRestoreTimer)
  }
  autoRotateRestoreTimer = window.setTimeout(() => {
    if (controls && !hoveredMemory.value) {
      controls.autoRotate = true
    }
  }, 5000)
}

function onMouseLeave() {
  hoveredMemory.value = null
  if (containerRef.value) {
    containerRef.value.style.cursor = 'default'
  }
  if (controls) {
    controls.autoRotate = true
  }
}

function onClick() {
  if (hoveredMemory.value) {
    router.push(`/memories/${hoveredMemory.value.id}`)
  }
}

/** 颜色 hex 字符串（用于模板 :style） */
function getStarColorHex(mem: any): string {
  const c = getStarColor(mem)
  return `#${c.toString(16).padStart(6, '0')}`
}

/** 长文本截断（英文按词/中文按字符都行） */
function truncate(text: string, max: number): string {
  if (!text) return ''
  if (text.length <= max) return text
  return text.slice(0, max) + '…'
}

/** 情感键 → 中英文标签 */
const emotionLabelMap: Record<string, { zh: string; en: string }> = {
  joy: { zh: '喜悦', en: 'Joy' },
  happiness: { zh: '喜悦', en: 'Happiness' },
  sadness: { zh: '忧思', en: 'Sadness' },
  anger: { zh: '愤怒', en: 'Anger' },
  fear: { zh: '恐惧', en: 'Fear' },
  surprise: { zh: '惊讶', en: 'Surprise' },
  disgust: { zh: '厌恶', en: 'Disgust' },
  anticipation: { zh: '期许', en: 'Anticipation' },
  trust: { zh: '信任', en: 'Trust' },
  calm: { zh: '宁静', en: 'Calm' },
  nostalgia: { zh: '怀旧', en: 'Nostalgia' },
  longing: { zh: '思念', en: 'Longing' },
  love: { zh: '眷恋', en: 'Love' },
  spring: { zh: '春', en: 'Spring' },
  summer: { zh: '夏', en: 'Summer' },
  autumn: { zh: '秋', en: 'Autumn' },
  fall: { zh: '秋', en: 'Fall' },
  winter: { zh: '冬', en: 'Winter' },
}
function emotionLabel(key: string): string {
  const e = emotionLabelMap[key] || { zh: key, en: key }
  // 当前语言为英文时显示英文，否则显示中文
  return i18nLocale.value === 'en-US' ? e.en : e.zh
}
const i18nLocale = computed(() => useI18n().locale.value)
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section class="hero-card hero-card--split page-hero" style="margin-bottom: 24px;">
      <div class="page-hero__bg" :style="{ backgroundImage: `url(${constellationBg.src})` }" aria-hidden="true"></div>
      <div class="stack stack--lg" style="position:relative;z-index:1;">
        <p class="eyebrow">PENTAGRAM CONSTELLATION · 五芒星阵</p>
        <h1 class="display-title text-gradient">记忆星阵图谱</h1>
        <p class="lead">相似的记忆凝聚成五芒星阵，每个星阵代表一种情感共鸣。星辰沿轨迹流转，诉说着时光的秘密。</p>
      </div>
      <figure class="art-frame" style="position:relative;z-index:1;max-width:340px;margin:0;aspect-ratio:1;">
        <img :src="constellationArt.src" :alt="constellationArt.origin" loading="lazy" decoding="async" />
      </figure>
    </section>

    <section
      class="section-card constellation-stage"
      style="position: relative; padding: 0; overflow: hidden;"
      @mouseleave="onMouseLeave"
    >
      <div ref="containerRef" class="constellation-canvas"></div>

      <transition name="fade">
        <div
          v-if="hoveredMemory"
          class="constellation-tooltip"
          :style="{ left: `${tooltipPos.x + 16}px`, top: `${tooltipPos.y - 10}px` }"
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
        <div class="constellation-legend__item" v-for="(color, emotion) in emotionColors" :key="emotion">
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
  /* 英文/中文长字符串都安全换行（关键，避免在窄容器里溢出） */
  overflow-wrap: anywhere;
  word-break: break-word;
}

.constellation-tooltip__head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.constellation-tooltip__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 0 0 6px currentColor;
}

.constellation-tooltip__title {
  color: var(--text);
  font-size: 0.9rem;
  font-weight: 600;
  line-height: 1.3;
  flex: 1;
  min-width: 0;
  /* 英文/中文长标题都安全换行 */
  overflow-wrap: anywhere;
}

.constellation-tooltip__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 0.72rem;
  color: var(--text-muted);
}

.constellation-tooltip__meta span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.constellation-tooltip__emotion {
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(54, 216, 180, 0.12);
  color: var(--primary);
  font-size: 0.7rem;
}

.constellation-tooltip__desc {
  margin: 4px 0 0;
  font-size: 0.76rem;
  color: var(--text-muted);
  line-height: 1.45;
  max-height: 60px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  /* 英文长 description 也安全换行 */
  overflow-wrap: anywhere;
}

.constellation-tooltip__cta {
  margin-top: 4px;
  font-size: 0.7rem;
  color: var(--primary);
  font-weight: 500;
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
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 0.7rem;
  color: var(--text-muted);
}

.constellation-legend__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 150ms ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
