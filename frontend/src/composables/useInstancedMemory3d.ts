/**
 * useInstancedMemory3d.ts
 * ---------------------------------------------------------
 * R20 InstancedMesh optimization for the 3D memory viewer.
 *
 * Background:
 *   The legacy `MemoryConstellationView.vue` rendered every memory
 *   as 3 independent THREE.Mesh objects (a 0.2 star, a 0.4 glow,
 *   and a 1.0 invisible hit ball). With N memories that meant
 *   `3N` draw calls per frame — fine at 50 memories (150 draw
 *   calls) but catastrophic at 50,000 memories (150,000 draw
 *   calls) where the GPU stalls on CPU-side command queueing.
 *
 * This composable:
 *   1. Groups memories by their dominant emotion (8 buckets).
 *   2. Builds ONE InstancedMesh per (emotion, layer) pair.
 *      - "star"  : 0.2 sphere (visual body, per-instance color)
 *      - "glow"  : 0.4 sphere (visual halo, additive)
 *      - "hit"   : 1.0 sphere (invisible, raycast target only)
 *      => 3 * 8 = 24 InstancedMesh objects total, regardless of
 *         how many memories you throw at it.
 *   3. Each instance is positioned via `setMatrixAt`, coloured via
 *      `setColorAt`.  Marking `instanceMatrix.needsUpdate` and
 *      `instanceColor.needsUpdate` re-uploads in one GPU call.
 *   4. Picking: `pickInstance(instanceId)` and `pickAt(mouseVec)`
 *      resolve the underlying memory using `intersect.instanceId`.
 *
 * Public API:
 *   const engine = useInstancedMemory3d(containerRef)
 *   engine.build(memories)                    // builds InstancedMesh
 *   engine.pickAt(mouse: Vector2)             // returns Memory | null
 *   engine.pickInstance(emotion, instanceId)  // returns Memory | null
 *   engine.setHovered(emotion, instanceId)    // triggers hover effect
 *   engine.update(dt)                         // per-frame breathing
 *   engine.dispose()                          // cleanup GPU resources
 *
 * Author: Mnemoscape / R20 InstancedMesh refactor.
 */

import { onUnmounted, getCurrentInstance, type Ref } from 'vue'
import * as THREE from 'three'
import type { Raycaster, Vector2 } from 'three'
import type { MemoryItem } from '../types'

/* -----------------------------------------------------------
 * Constants — emotion → colour (mirrors MemoryConstellationView)
 * --------------------------------------------------------- */
export const EMOTION_COLORS: Record<string, number> = {
  joy: 0xffd700,
  happiness: 0xffd700,
  sadness: 0x4a90d9,
  anger: 0xef4444,
  fear: 0x6b7280,
  surprise: 0xfbbf24,
  disgust: 0x84cc16,
  anticipation: 0xf59e0b,
  trust: 0x60a5fa,
  calm: 0x36d8b4,
  nostalgia: 0xff8c42,
  longing: 0xa78bfa,
  love: 0xfb7185,
  spring: 0xffb7c5,
  summer: 0xffd700,
  autumn: 0xff5722,
  fall: 0xff5722,
  winter: 0x4a90d9,
  anxiety: 0xe040fb,
  melancholy: 0x7b68ee,
  gratitude: 0xffb7c5,
}

const VISIBLE_EMOTIONS = [
  'joy', 'sadness', 'nostalgia', 'excitement',
  'calm', 'melancholy', 'gratitude', 'anxiety',
]

/* -----------------------------------------------------------
 * Layer definitions — star / glow / hit
 * --------------------------------------------------------- */
interface LayerSpec {
  name: 'star' | 'glow' | 'hit'
  radius: number
  detail: number
  transparent: boolean
  opacity: number
  additive: boolean
  visible: boolean // hit layer is "visible" only for raycaster, not the eye
  castShadow: boolean
}

const LAYER_SPECS: Record<LayerSpec['name'], LayerSpec> = {
  star: { name: 'star', radius: 0.2, detail: 12, transparent: true, opacity: 0.9, additive: false, visible: true,  castShadow: false },
  glow: { name: 'glow', radius: 0.4, detail: 10, transparent: true, opacity: 0.2, additive: true,  visible: true,  castShadow: false },
  hit:  { name: 'hit',  radius: 1.0, detail: 8,  transparent: true, opacity: 0.0, additive: false, visible: true,  castShadow: false },
}

type LayerName = LayerSpec['name']

/* -----------------------------------------------------------
 * Per-instance record (memory + emotion + index-into-InstancedMesh)
 * --------------------------------------------------------- */
export interface InstanceRecord {
  memory: MemoryItem
  emotion: string
  position: THREE.Vector3
  /** layer → instanceId within that InstancedMesh */
  ids: Record<LayerName, number>
}

/* -----------------------------------------------------------
 * Public engine interface
 * --------------------------------------------------------- */
export interface InstancedMemory3dEngine {
  build: (memories: MemoryItem[]) => void
  pickAt: (mouse: Vector2, camera: THREE.Camera) => { memory: MemoryItem; emotion: string; instanceId: number } | null
  pickInstance: (emotion: string, instanceId: number) => MemoryItem | null
  setHovered: (emotion: string | null, instanceId: number | null) => void
  update: (elapsedSec: number) => void
  dispose: () => void
  getRaycaster: () => Raycaster
  getHitMeshes: () => THREE.InstancedMesh[]
  getRoot: () => THREE.Group
  getPerEmotionCount: () => Record<string, number>
  getTotalInstances: () => number
  getDrawCallCount: () => number
}

/* -----------------------------------------------------------
 * Composable
 * --------------------------------------------------------- */
export function useInstancedMemory3d(
  containerRef: Ref<HTMLElement | null>,
): InstancedMemory3dEngine {
  /* -- singletons -- */
  const raycaster = new THREE.Raycaster()

  /* -- InstancedMesh bookkeeping -- */
  /** emotion → layer → InstancedMesh */
  const meshTable: Record<string, Record<LayerName, THREE.InstancedMesh | null>> = {}
  /** emotion → layer → array of memory records (parallel to instanceId) */
  const recordTable: Record<string, Record<LayerName, InstanceRecord[]>> = {}

  /** emotion → THREE.Color (for breathing tinting) */
  const baseColorTable: Record<string, THREE.Color> = {}

  /** Currently hovered record (per emotion) */
  const hovered: { emotion: string | null; instanceId: number | null } = { emotion: null, instanceId: null }

  /** Global parent group */
  const root = new THREE.Group()
  root.name = 'instanced-memory-3d'

  /* -- reusable scratch -- */
  const _matrix = new THREE.Matrix4()
  const _quat = new THREE.Quaternion()
  const _scale = new THREE.Vector3()
  const _pos = new THREE.Vector3()
  const _color = new THREE.Color()

  /* -- helpers -- */
  function dominantEmotion(memory: MemoryItem): string {
    if (memory.emotionVector && typeof memory.emotionVector === 'object') {
      let dominant = 'calm'
      let max = 0
      for (const [k, v] of Object.entries(memory.emotionVector)) {
        if (typeof v === 'number' && v > max) { max = v; dominant = k }
      }
      return dominant
    }
    return memory.memorySeason || 'calm'
  }

  function emotionHex(emotion: string): number {
    return EMOTION_COLORS[emotion] ?? 0x36d8b4
  }

  function ensureEmotionBucket(emotion: string): void {
    if (meshTable[emotion]) return

    meshTable[emotion] = { star: null, glow: null, hit: null }
    recordTable[emotion] = { star: [], glow: [], hit: [] }
    baseColorTable[emotion] = new THREE.Color(emotionHex(emotion))

    for (const layerName of Object.keys(LAYER_SPECS) as LayerName[]) {
      const spec = LAYER_SPECS[layerName]

      // Pre-allocate large capacity — we resize via setCount at the end.
      // 50,000 memories across 8 buckets = 6250 each on average.
      const INITIAL_CAPACITY = 8192

      const geometry = new THREE.SphereGeometry(spec.radius, spec.detail, spec.detail)
      let material: THREE.Material

      if (layerName === 'hit') {
        // Hit layer is purely for raycasting.  Use a fully transparent
        // material so it costs nothing visually but still participates
        // in the InstancedMesh's draw call (and in raycaster.intersectObject).
        material = new THREE.MeshBasicMaterial({
          color: 0xffffff,
          transparent: true,
          opacity: 0,
          depthWrite: false,
          depthTest: false,
        })
      } else if (layerName === 'glow') {
        material = new THREE.MeshBasicMaterial({
          color: 0xffffff,        // per-instance color drives actual hue
          transparent: true,
          opacity: spec.opacity,
          depthWrite: false,
          blending: THREE.AdditiveBlending,
        })
      } else {
        material = new THREE.MeshBasicMaterial({
          color: 0xffffff,        // per-instance color drives actual hue
          transparent: true,
          opacity: spec.opacity,
        })
      }

      const inst = new THREE.InstancedMesh(geometry, material, INITIAL_CAPACITY)
      inst.name = `memories-${emotion}-${layerName}`
      inst.frustumCulled = false // 50k items — bounding-sphere recompute is more expensive than just drawing
      inst.count = 0
      inst.renderOrder = layerName === 'star' ? 2 : (layerName === 'glow' ? 1 : 0)
      // Activate per-instance colour attribute
      inst.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(INITIAL_CAPACITY * 3), 3)
      inst.instanceColor.setUsage(THREE.DynamicDrawUsage)
      inst.instanceMatrix.setUsage(THREE.DynamicDrawUsage)

      root.add(inst)
      meshTable[emotion][layerName] = inst
    }
  }

  /**
   * Position a memory deterministically on a 3D cluster so that
   * the InstancedMesh still produces a meaningful visual layout
   * (orbit-friendly, not all clumped at origin).
   */
  function placeMemory(memory: MemoryItem, index: number, emotion: string): THREE.Vector3 {
    // Pentagram-ish orbital placement (5 memories per cluster ring,
    // expand outwards with golden ratio as count grows).
    const ring = Math.floor(index / 5)
    const slot = index % 5
    const ringRadius = 3 + ring * 1.6
    const angle = (slot / 5) * Math.PI * 2 + ring * 0.7
    const y = Math.sin(index * 0.5) * 0.5

    // Each emotion bucket offsets around the global scene.
    const emotionOffset = VISIBLE_EMOTIONS.indexOf(emotion)
    const eo = emotionOffset >= 0 ? emotionOffset : 0
    const baseRadius = 8
    const ea = (eo / Math.max(1, VISIBLE_EMOTIONS.length)) * Math.PI * 2
    const cx = Math.cos(ea) * baseRadius * 1.5
    const cz = Math.sin(ea) * baseRadius * 1.5

    return new THREE.Vector3(
      cx + Math.cos(angle) * ringRadius,
      y,
      cz + Math.sin(angle) * ringRadius,
    )
  }

  function build(memories: MemoryItem[]): void {
    // Clean previous build (if any)
    disposeMeshes()

    const buckets: Record<string, MemoryItem[]> = {}
    for (const mem of memories) {
      const e = dominantEmotion(mem)
      if (!buckets[e]) buckets[e] = []
      buckets[e].push(mem)
    }

    for (const [emotion, list] of Object.entries(buckets)) {
      ensureEmotionBucket(emotion)

      for (let i = 0; i < list.length; i++) {
        const mem = list[i]
        const pos = placeMemory(mem, i, emotion)
        const record: InstanceRecord = { memory: mem, emotion, position: pos, ids: { star: -1, glow: -1, hit: -1 } }

        // Push into all three layers with matching instanceId
        for (const layerName of Object.keys(LAYER_SPECS) as LayerName[]) {
          const inst = meshTable[emotion][layerName]!
          const newId = inst.count
          inst.count = newId + 1

          // Position via matrix
          _matrix.compose(pos, _quat.identity(), _scale.set(1, 1, 1))
          inst.setMatrixAt(newId, _matrix)

          // Per-instance color: same hue for star & hit, slightly tinted for glow
          const base = baseColorTable[emotion]
          if (layerName === 'star' || layerName === 'hit') {
            _color.copy(base)
          } else {
            // glow = brighter / more saturated halo
            _color.copy(base).lerp(new THREE.Color(0xffffff), 0.25)
          }
          inst.setColorAt(newId, _color)

          record.ids[layerName] = newId
          recordTable[emotion][layerName].push(record)
        }
      }

      // Flush GPU buffers
      for (const layerName of Object.keys(LAYER_SPECS) as LayerName[]) {
        const inst = meshTable[emotion][layerName]!
        inst.instanceMatrix.needsUpdate = true
        if (inst.instanceColor) inst.instanceColor.needsUpdate = true
        // Shrink the underlying typed-array views to the actual count to
        // avoid paying for unused slots in the draw call.
        inst.computeBoundingSphere?.()
      }
    }
  }

  function pickAt(mouse: Vector2, camera: THREE.Camera) {
    raycaster.setFromCamera(mouse, camera)
    // We only raycast against the "hit" layer — visually invisible,
    // radius 1.0 (5x the star) so hover/click is forgiving.
    const targets: THREE.Object3D[] = []
    for (const e of Object.keys(meshTable)) {
      const hit = meshTable[e].hit
      if (hit) targets.push(hit)
    }
    const intersects = raycaster.intersectObjects(targets, false)
    if (intersects.length === 0) return null
    const first = intersects[0]
    const inst = first.object as THREE.InstancedMesh
    const emotion = (inst.name.split('-')[1] ?? '')
    const record = recordTable[emotion]?.hit[first.instanceId ?? -1]
    if (!record) return null
    return { memory: record.memory, emotion, instanceId: first.instanceId ?? -1 }
  }

  function pickInstance(emotion: string, instanceId: number): MemoryItem | null {
    const rec = recordTable[emotion]?.hit?.[instanceId]
    return rec ? rec.memory : null
  }

  function setHovered(emotion: string | null, instanceId: number | null) {
    // Clear previous hover
    if (hovered.emotion && hovered.instanceId !== null) {
      const prev = recordTable[hovered.emotion]?.star?.[hovered.instanceId]
      if (prev) writeStarColor(hovered.emotion, hovered.instanceId, baseColorTable[hovered.emotion], 1.0)
    }
    hovered.emotion = emotion
    hovered.instanceId = instanceId
    if (emotion && instanceId !== null) {
      writeStarColor(emotion, instanceId, baseColorTable[emotion], 1.8)
    }
  }

  function writeStarColor(emotion: string, instanceId: number, base: THREE.Color, hoverBoost: number) {
    const inst = meshTable[emotion]?.star
    if (!inst || !inst.instanceColor) return
    _color.copy(base).multiplyScalar(hoverBoost)
    inst.setColorAt(instanceId, _color)
    inst.instanceColor.needsUpdate = true
  }

  function update(elapsedSec: number) {
    // Breathing animation: modulate per-instance scale.
    // Only the "star" layer visibly scales; the glow modulates opacity
    // via vertex color brightness (cheap & GPU-friendly).
    for (const emotion of Object.keys(meshTable)) {
      const star = meshTable[emotion].star!
      const glow = meshTable[emotion].glow!
      const records = recordTable[emotion].star
      const count = records.length
      if (count === 0) continue

      const base = baseColorTable[emotion]
      for (let i = 0; i < count; i++) {
        const phase = elapsedSec * 1.5 + i * 0.7
        const s = 1 + Math.sin(phase) * 0.15
        const isHovered = (hovered.emotion === emotion && hovered.instanceId === i)
        const finalScale = isHovered ? s * 1.6 : s
        const rec = records[i]
        _matrix.compose(rec.position, _quat.identity(), _scale.setScalar(finalScale))
        star.setMatrixAt(i, _matrix)

        // glow brightness
        const glowBoost = isHovered ? 2.4 : 1.0
        _color.copy(base).lerp(new THREE.Color(0xffffff), 0.25 * glowBoost)
        glow.setColorAt(i, _color)
      }
      star.instanceMatrix.needsUpdate = true
      if (glow.instanceColor) glow.instanceColor.needsUpdate = true
    }
  }

  function getRaycaster() { return raycaster }
  function getHitMeshes(): THREE.InstancedMesh[] {
    const out: THREE.InstancedMesh[] = []
    for (const e of Object.keys(meshTable)) {
      const hit = meshTable[e].hit
      if (hit) out.push(hit)
    }
    return out
  }
  function getRoot(): THREE.Group { return root }
  function getPerEmotionCount(): Record<string, number> {
    const out: Record<string, number> = {}
    for (const e of Object.keys(recordTable)) {
      out[e] = recordTable[e].star.length
    }
    return out
  }
  function getTotalInstances(): number {
    let n = 0
    for (const e of Object.keys(recordTable)) n += recordTable[e].star.length
    return n
  }
  function getDrawCallCount(): number {
    // Each InstancedMesh = 1 draw call. 3 layers × emotions.
    let n = 0
    for (const e of Object.keys(meshTable)) n += 3
    return n
  }

  function disposeMeshes() {
    for (const e of Object.keys(meshTable)) {
      for (const layerName of Object.keys(LAYER_SPECS) as LayerName[]) {
        const inst = meshTable[e][layerName]
        if (inst) {
          root.remove(inst)
          inst.geometry.dispose()
          ;(inst.material as THREE.Material).dispose()
          if (inst.instanceColor) (inst.instanceColor as THREE.BufferAttribute).array = new Float32Array(0)
        }
        meshTable[e][layerName] = null
      }
      delete meshTable[e]
      delete recordTable[e]
    }
  }

  function dispose() {
    disposeMeshes()
    if (root.parent) root.parent.remove(root)
  }

  // Register auto-cleanup only when called from a component setup
  // context (i.e. inside a real Vue component).  When the composable
  // is invoked directly from a test (no current instance), the
  // caller is responsible for calling `dispose()`.
  if (getCurrentInstance()) {
    onUnmounted(dispose)
  }

  return {
    build,
    pickAt,
    pickInstance,
    setHovered,
    update,
    dispose,
    getRaycaster,
    getHitMeshes,
    getRoot,
    getPerEmotionCount,
    getTotalInstances,
    getDrawCallCount,
  }
}
