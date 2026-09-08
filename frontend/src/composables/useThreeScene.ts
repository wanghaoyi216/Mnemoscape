import { ref, onUnmounted, type Ref } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import type { SceneData } from '../types'

export function useThreeScene(containerRef: Ref<HTMLElement | null>) {
  const renderer = ref<THREE.WebGLRenderer | null>(null)
  const scene = ref<THREE.Scene | null>(null)
  const camera = ref<THREE.PerspectiveCamera | null>(null)
  const controls = ref<OrbitControls | null>(null)
  const contentGroup = ref<THREE.Group | null>(null)
  let animFrameId = 0
  // ResizeObserver 监听父容器尺寸（init 时父容器可能还是 0×0；onMounted 同步阶段没拿到布局）
  let resizeObserver: ResizeObserver | null = null

  function init() {
    if (!containerRef.value) return
    const s = new THREE.Scene()
    s.background = new THREE.Color(0x0f172a)
    scene.value = s

    // 初始尺寸：兜底为 1，避免 0×0 导致 WebGL 上下文异常；ResizeObserver 会很快补一次正确尺寸
    const initW = Math.max(1, containerRef.value.clientWidth)
    const initH = Math.max(1, containerRef.value.clientHeight)

    const c = new THREE.PerspectiveCamera(60, initW / initH, 0.1, 100)
    c.position.set(5, 3.5, 8)
    camera.value = c

    const r = new THREE.WebGLRenderer({ antialias: true })
    r.setSize(initW, initH)
    r.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    r.shadowMap.enabled = true
    r.outputColorSpace = THREE.SRGBColorSpace
    containerRef.value.appendChild(r.domElement)
    renderer.value = r

    const o = new OrbitControls(c, r.domElement)
    o.enableDamping = true
    o.dampingFactor = 0.05
    o.target.set(0, 1.5, 0)
    controls.value = o

    const grid = new THREE.GridHelper(12, 24, 0x334155, 0x1e293b)
    grid.position.y = 0.01
    s.add(grid)

    const group = new THREE.Group()
    group.name = 'scene-content'
    s.add(group)
    contentGroup.value = group

    animate()
    window.addEventListener('resize', onResize)

    // 父容器初次布局可能晚于 onMounted；ResizeObserver 兜底确保拿到真实宽高后立即同步相机/渲染器
    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      resizeObserver = new ResizeObserver(() => onResize())
      resizeObserver.observe(containerRef.value)
    }

    return s
  }

  function onResize() {
    if (!containerRef.value || !renderer.value || !camera.value) return
    const w = Math.max(1, containerRef.value.clientWidth)
    const h = Math.max(1, containerRef.value.clientHeight)
    camera.value.aspect = w / h
    camera.value.updateProjectionMatrix()
    renderer.value.setSize(w, h)
  }

  function animate() {
    animFrameId = requestAnimationFrame(animate)
    controls.value?.update()
    if (renderer.value && scene.value && camera.value) {
      renderer.value.render(scene.value, camera.value)
    }
  }

  function loadScene(data: SceneData) {
    if (!scene.value) return
    if (!contentGroup.value) {
      const group = new THREE.Group()
      group.name = 'scene-content'
      scene.value.add(group)
      contentGroup.value = group
    }

    while (contentGroup.value.children.length > 0) {
      const child = contentGroup.value.children[0]
      contentGroup.value.remove(child)
      // 释放 GPU 资源，避免反复 loadScene 导致 geometry/material 内存泄漏
      child.traverse((obj: THREE.Object3D) => {
        const mesh = obj as THREE.Mesh
        if (mesh.geometry) mesh.geometry.dispose()
        const mat = mesh.material as THREE.Material | THREE.Material[] | undefined
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose())
        else if (mat) mat.dispose()
      })
    }

    scene.value.background = new THREE.Color(data.atmosphere.backgroundColor)
    scene.value.fog = new THREE.Fog(data.atmosphere.fogColor, 10, 42)

    const ambient = new THREE.AmbientLight(0xffffff, Math.max(0.25, data.lighting.intensity * 0.55))
    contentGroup.value.add(ambient)

    const dir = new THREE.DirectionalLight(new THREE.Color(data.lighting.color), data.lighting.intensity)
    dir.position.set(8, 14, 6)
    dir.castShadow = true
    contentGroup.value.add(dir)

    const fill = new THREE.HemisphereLight(0xbdd7ff, 0x273449, 0.55)
    contentGroup.value.add(fill)

    const floorGeo = new THREE.PlaneGeometry(14, 14, 1, 1)
    const floorMat = new THREE.MeshStandardMaterial({
      color: new THREE.Color(data.terrain.color),
      roughness: 0.92,
      metalness: 0.02,
    })
    const floor = new THREE.Mesh(floorGeo, floorMat)
    floor.rotation.x = -Math.PI / 2
    floor.receiveShadow = true
    floor.position.y = 0
    contentGroup.value.add(floor)

    for (const obj of data.objects) {
      const geo = getGeometry(obj.type)
      const mat = new THREE.MeshStandardMaterial({
        color: obj.color || '#94a3b8',
        roughness: 0.45,
        metalness: 0.04,
      })
      const mesh = new THREE.Mesh(geo, mat)
      mesh.position.set(obj.position[0], obj.position[1], obj.position[2])
      mesh.scale.set(obj.scale[0], obj.scale[1], obj.scale[2])
      mesh.castShadow = true
      mesh.receiveShadow = true
      mesh.userData.baseColor = mat.color.clone()
      if (obj.name) {
        mesh.userData.label = obj.name
      }
      contentGroup.value.add(mesh)
    }

    for (const fragment of data.fragments) {
      const fragmentMesh = new THREE.Mesh(
        new THREE.SphereGeometry(0.12, 16, 16),
        new THREE.MeshStandardMaterial({
          color: fragment.isDiscovered ? 0x60a5fa : 0xf59e0b,
          emissive: fragment.isDiscovered ? 0x1d4ed8 : 0x78350f,
          emissiveIntensity: 0.35,
          roughness: 0.25,
        }),
      )
      fragmentMesh.position.set(fragment.position3d.x, fragment.position3d.y, fragment.position3d.z)
      fragmentMesh.userData.fragment = fragment
      contentGroup.value.add(fragmentMesh)
    }
  }

  function applyDrift(fadeLevel: number) {
    if (!scene.value) return
    scene.value.fog = new THREE.Fog(0x334155, 8 + fadeLevel * 8, 26 + fadeLevel * 28)
    contentGroup.value?.children.forEach((child: THREE.Object3D) => {
      if (child instanceof THREE.Mesh && child.material instanceof THREE.MeshStandardMaterial) {
        const baseColor = child.userData.baseColor instanceof THREE.Color
          ? child.userData.baseColor
          : child.material.color.clone()
        child.userData.baseColor = baseColor
        child.material.color.copy(baseColor).lerp(new THREE.Color(0x64748b), fadeLevel * 0.6)
        child.material.opacity = Math.max(0.55, 1 - fadeLevel * 0.35)
        child.material.transparent = true
      }
    })
  }

  function getGeometry(type: string): THREE.BufferGeometry {
    switch (type) {
      case 'house': return new THREE.BoxGeometry(1.3, 1.1, 1.3)
      case 'tree': return new THREE.CylinderGeometry(0.25, 0.45, 2.5, 8)
      case 'bench': return new THREE.BoxGeometry(1.6, 0.22, 0.4)
      case 'sphere': return new THREE.SphereGeometry(0.5, 32, 16)
      case 'cylinder': return new THREE.CylinderGeometry(0.3, 0.3, 1, 16)
      case 'cone': return new THREE.ConeGeometry(0.3, 0.8, 16)
      case 'plane': return new THREE.PlaneGeometry(1, 1)
      case 'lamp': return new THREE.CylinderGeometry(0.12, 0.12, 1.8, 12)
      case 'flower': return new THREE.SphereGeometry(0.18, 16, 12)
      case 'pile': return new THREE.ConeGeometry(0.45, 0.7, 10)
      case 'bare_tree': return new THREE.CylinderGeometry(0.2, 0.32, 2.4, 6)
      case 'umbrella': return new THREE.ConeGeometry(0.45, 0.8, 10)
      case 'autumn_tree': return new THREE.CylinderGeometry(0.24, 0.42, 2.5, 8)
      case 'cherry_tree': return new THREE.CylinderGeometry(0.22, 0.38, 2.4, 8)
      case 'particle': return new THREE.SphereGeometry(0.12, 8, 8)
      default: return new THREE.BoxGeometry(1, 1, 1)
    }
  }

  function getCameraPosition() {
    return camera.value?.position.toArray() ?? [0, 1.8, 0]
  }

  function getLookingAt() {
    if (!controls.value) return { x: 0, y: 0, z: -1 }
    const dir = controls.value.target.clone().sub(camera.value!.position).normalize()
    return { x: dir.x, y: dir.y, z: dir.z }
  }

  function dispose() {
    cancelAnimationFrame(animFrameId)
    renderer.value?.dispose()
    renderer.value?.domElement.remove()
    window.removeEventListener('resize', onResize)
    if (resizeObserver) {
      resizeObserver.disconnect()
      resizeObserver = null
    }
    contentGroup.value = null
  }

  onUnmounted(dispose)

  return { init, loadScene, applyDrift, getCameraPosition, getLookingAt, dispose, scene, camera, renderer }
}
