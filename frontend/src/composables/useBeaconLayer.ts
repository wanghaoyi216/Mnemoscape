/**
 * 3D Comment Beacon Layer — Sprint 4.2 核心交互。
 *
 * 在 ResonanceSpace 的 Three 场景里叠加一层"留言信标"图层。每条
 * {@link MemoryNote} 渲染为：
 *   - 底部一根 ShaderMaterial 光柱（垂直滚动的能量带 + 顶端柔和散射）
 *   - 顶上一颗按 mood 上色的悬浮发光球体（带轻微上下浮动）
 *   - 配套一个仅用于碰撞检测的不可见 BoundingSphere
 *
 * 用户行为：
 *   1. 点击地面 → {@link pickGroundPoint} 通过 Raycaster 算出世界坐标，
 *      调用方据此在该位置弹起 composer 并提交一条 note；
 *   2. 走到信标 < proximityRadius 范围内按 E 键 → 触发
 *      {@link onNearbyTrigger} 注册的回调，呼出"全息展开"卡片。
 *
 * 与 {@link useThreeScene} 的关系：本 composable 不持有自己的 scene/camera，
 * 只往外部的 scene 里挂 Group；自身仅跑一个轻量 ticker RAF 来更新 shader
 * uniforms（动画时间），渲染依旧由 useThreeScene 的主循环负责。
 */
import { onBeforeUnmount, type Ref } from 'vue'
import * as THREE from 'three'
import type { MemoryNote } from '../types'

export interface BeaconLayerOptions {
  scene: Ref<THREE.Scene | null>
  camera: Ref<THREE.PerspectiveCamera | null>
  containerRef: Ref<HTMLElement | null>
  /** 多近算"靠近"，单位 = 场景单位。默认 2.5 — 大致一步可达。 */
  proximityRadius?: number
}

interface BeaconHandle {
  note: MemoryNote
  group: THREE.Group
  material: THREE.ShaderMaterial
  pos: THREE.Vector3
  born: number
}

const MOOD_COLORS: Record<string, string> = {
  warm: '#f2b95c',
  joyful: '#ffd273',
  melancholic: '#5f7bd8',
  contemplative: '#846edc',
  grateful: '#36d8b4',
}

export function useBeaconLayer(opts: BeaconLayerOptions) {
  const proximityRadius = opts.proximityRadius ?? 2.5

  // 在外部 scene 创建一个分组 — dispose 时一次清理。
  let group: THREE.Group | null = null
  let groundPlane: THREE.Mesh | null = null
  const raycaster = new THREE.Raycaster()
  const beacons: BeaconHandle[] = []
  let rafId: number | null = null
  let nearbyHandler: ((note: MemoryNote) => void) | null = null
  const tStart = performance.now()
  // E 键的去抖 — 避免长按时连续触发
  let lastNearbyFire = 0

  function ensureMounted(): boolean {
    const s = opts.scene.value
    if (!s) return false
    if (!group) {
      group = new THREE.Group()
      group.name = 'beacon-layer'
      s.add(group)
    }
    if (!groundPlane) {
      // 不可见平面 — 仅用于 Raycaster 拾取地面点，比拿场景里的 floor 更稳，
      // 避免 floor 旋转/移动时拾取出错。
      groundPlane = new THREE.Mesh(
        new THREE.PlaneGeometry(400, 400),
        new THREE.MeshBasicMaterial({ visible: false, side: THREE.DoubleSide }),
      )
      groundPlane.rotation.x = -Math.PI / 2
      groundPlane.position.y = 0
      groundPlane.name = 'beacon-ground-raycast-plane'
      s.add(groundPlane)
    }
    return true
  }

  /** 'position3d' 在 MemoryNote 上是字符串（后端 JSON 落库后回吐） */
  function parsePosition(raw: string | undefined | null): THREE.Vector3 {
    if (!raw) return new THREE.Vector3(0, 0, 0)
    try {
      const p = JSON.parse(raw)
      if (Array.isArray(p) && p.length >= 3) {
        return new THREE.Vector3(Number(p[0]) || 0, Number(p[1]) || 0, Number(p[2]) || 0)
      }
      return new THREE.Vector3(Number(p.x) || 0, Number(p.y) || 0, Number(p.z) || 0)
    } catch {
      return new THREE.Vector3(0, 0, 0)
    }
  }

  function makeBeaconMesh(note: MemoryNote, pos: THREE.Vector3): BeaconHandle {
    const color = new THREE.Color(MOOD_COLORS[note.mood] ?? '#36d8b4')

    // 光柱 — 自下而上滚动的能量带 + 顶端散射衰减
    const pillarMat = new THREE.ShaderMaterial({
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      side: THREE.DoubleSide,
      uniforms: {
        uColor: { value: color },
        uTime: { value: 0 },
        uBirth: { value: 0 },
      },
      vertexShader: /* glsl */ `
        varying float vY;
        void main() {
          vY = uv.y;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: /* glsl */ `
        precision highp float;
        uniform vec3 uColor;
        uniform float uTime;
        uniform float uBirth;
        varying float vY;
        void main() {
          // 顶部 fade 出（vY=1 完全透明），中间能量带向上扫
          float fade = smoothstep(1.0, 0.05, vY);
          float band = smoothstep(0.04, 0.0, abs(fract(vY - uTime * 0.45) - 0.5) - 0.04);
          float core = 0.32 + 0.48 * band;
          // 出生动画：上升 0.6s 内不透明度从 0 拉到目标
          float spawn = clamp((uTime - uBirth) / 0.6, 0.0, 1.0);
          float alpha = core * fade * spawn;
          gl_FragColor = vec4(uColor * (1.2 + band * 1.8), alpha);
        }
      `,
    })
    const pillarGeo = new THREE.CylinderGeometry(0.06, 0.06, 2.4, 12, 1, true)
    const pillar = new THREE.Mesh(pillarGeo, pillarMat)
    pillar.position.set(pos.x, 1.2, pos.z)

    // 顶端球体 — emissive 漂浮
    const orbMat = new THREE.MeshStandardMaterial({
      color: color,
      emissive: color,
      emissiveIntensity: 1.3,
      roughness: 0.25,
      metalness: 0.1,
    })
    const orb = new THREE.Mesh(new THREE.SphereGeometry(0.16, 24, 16), orbMat)
    orb.position.set(pos.x, 2.45, pos.z)
    orb.userData.bobBase = 2.45
    orb.userData.bobPhase = Math.random() * Math.PI * 2

    // 信标地脚环 — 用 RingGeometry 在地面画一圈
    const ringMat = new THREE.MeshBasicMaterial({
      color: color,
      transparent: true,
      opacity: 0.42,
      side: THREE.DoubleSide,
    })
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.28, 0.42, 32), ringMat)
    ring.rotation.x = -Math.PI / 2
    ring.position.set(pos.x, 0.012, pos.z)

    const g = new THREE.Group()
    g.userData.noteId = note.id
    g.add(pillar)
    g.add(orb)
    g.add(ring)

    group!.add(g)
    const born = (performance.now() - tStart) / 1000
    pillarMat.uniforms.uBirth.value = born
    return { note, group: g, material: pillarMat, pos: pos.clone(), born }
  }

  function removeBeacon(b: BeaconHandle) {
    group?.remove(b.group)
    b.group.traverse((obj) => {
      if ((obj as any).geometry) (obj as any).geometry.dispose?.()
      if ((obj as any).material) {
        const mat = (obj as any).material
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose?.())
        else mat.dispose?.()
      }
    })
  }

  /** 用 notes 重建信标集合（增量 diff，避免每次全量重建闪烁）。 */
  function syncBeacons(notes: MemoryNote[]) {
    if (!ensureMounted()) return
    const incoming = new Map(notes.map((n) => [n.id, n] as const))
    // 移除已不存在的
    for (let i = beacons.length - 1; i >= 0; i--) {
      if (!incoming.has(beacons[i].note.id)) {
        removeBeacon(beacons[i])
        beacons.splice(i, 1)
      }
    }
    // 新增
    const existing = new Set(beacons.map((b) => b.note.id))
    for (const note of notes) {
      if (existing.has(note.id)) continue
      const pos = parsePosition(note.position3d)
      beacons.push(makeBeaconMesh(note, pos))
    }
  }

  /** 鼠标事件 → 地面上的世界坐标；未命中返回 null。 */
  function pickGroundPoint(ev: MouseEvent | PointerEvent): THREE.Vector3 | null {
    if (!ensureMounted() || !opts.camera.value || !opts.containerRef.value || !groundPlane) return null
    const rect = opts.containerRef.value.getBoundingClientRect()
    const ndc = new THREE.Vector2(
      ((ev.clientX - rect.left) / rect.width) * 2 - 1,
      -((ev.clientY - rect.top) / rect.height) * 2 + 1,
    )
    raycaster.setFromCamera(ndc, opts.camera.value)
    const hits = raycaster.intersectObject(groundPlane, false)
    return hits[0]?.point.clone() ?? null
  }

  /** 注册"E 键 + 靠近"组合触发的回调。再次调用会覆盖上一次。 */
  function onNearbyTrigger(cb: (note: MemoryNote) => void) {
    nearbyHandler = cb
  }

  function findNearestBeacon(): BeaconHandle | null {
    if (!opts.camera.value) return null
    const camPos = opts.camera.value.position
    let best: BeaconHandle | null = null
    let bestDist = Infinity
    for (const b of beacons) {
      const d = camPos.distanceTo(b.pos)
      if (d < bestDist && d < proximityRadius) {
        bestDist = d
        best = b
      }
    }
    return best
  }

  function onKeyDown(ev: KeyboardEvent) {
    if (ev.key !== 'e' && ev.key !== 'E') return
    // 防止用户在 input/textarea 里打字时被拦截
    const tag = (ev.target as HTMLElement | null)?.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA') return
    const now = performance.now()
    if (now - lastNearbyFire < 400) return
    const nearest = findNearestBeacon()
    if (!nearest || !nearbyHandler) return
    lastNearbyFire = now
    nearbyHandler(nearest.note)
  }

  /** 主 ticker — 只更新 uniforms 与 orb 的浮动 y，不重复 render。 */
  function tick() {
    const t = (performance.now() - tStart) / 1000
    for (const b of beacons) {
      b.material.uniforms.uTime.value = t
      const orb = b.group.children.find((c) => (c as THREE.Mesh).geometry instanceof THREE.SphereGeometry) as THREE.Mesh | undefined
      if (orb) {
        const phase = orb.userData.bobPhase as number
        const base = orb.userData.bobBase as number
        orb.position.y = base + Math.sin(t * 1.4 + phase) * 0.07
      }
    }
    rafId = requestAnimationFrame(tick)
  }
  rafId = requestAnimationFrame(tick)
  window.addEventListener('keydown', onKeyDown)

  function dispose() {
    if (rafId !== null) cancelAnimationFrame(rafId)
    rafId = null
    window.removeEventListener('keydown', onKeyDown)
    for (const b of beacons) removeBeacon(b)
    beacons.length = 0
    if (groundPlane && opts.scene.value) {
      opts.scene.value.remove(groundPlane)
      ;(groundPlane.geometry as THREE.BufferGeometry).dispose()
      ;(groundPlane.material as THREE.Material).dispose()
      groundPlane = null
    }
    if (group && opts.scene.value) {
      opts.scene.value.remove(group)
      group = null
    }
    nearbyHandler = null
  }

  onBeforeUnmount(dispose)

  return {
    syncBeacons,
    pickGroundPoint,
    onNearbyTrigger,
    findNearestBeacon,
    /** 当前信标数量 — 给 UI 显示 */
    get beaconCount() { return beacons.length },
    dispose,
  }
}
