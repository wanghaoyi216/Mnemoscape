import { ref, onUnmounted, type Ref } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import type { AvatarTraits, EmotionTone } from '../api/avatar'

/**
 * 用户 3D 角色渲染 composable。
 *
 * 基于 AvatarTraits 数据，用 Three.js 渲染一个代表用户灵魂的 3D 角色形象：
 * - 核心球体（coreShape 决定形状）
 * - 粒子光晕（particleType 决定粒子类型和行为）
 * - 动态光效（auraColor + auraIntensity）
 * - 呼吸动画（pulseFrequency）
 * - 拖尾效果（trailEffect）
 */
export function useAvatarThree(containerRef: Ref<HTMLElement | null>) {
  const renderer = ref<THREE.WebGLRenderer | null>(null)
  const scene = ref<THREE.Scene | null>(null)
  const camera = ref<THREE.PerspectiveCamera | null>(null)
  const controls = ref<OrbitControls | null>(null)

  let animFrameId = 0
  let resizeObserver: ResizeObserver | null = null

  // 核心对象
  let coreMesh: THREE.Mesh | null = null
  let coreGlow: THREE.Mesh | null = null
  let particleSystem: THREE.Points | null = null
  let particleGeometry: THREE.BufferGeometry | null = null
  let particleMaterial: THREE.PointsMaterial | null = null
  let trailMeshes: THREE.Mesh[] = []
  let innerRingMesh: THREE.Mesh | null = null
  let outerRingMesh: THREE.Mesh | null = null

  // 动画状态
  let currentTraits: AvatarTraits | null = null
  let particlePositions: Float32Array | null = null
  let particleVelocities: number[] = []
  let particleCount = 0
  let startTime = Date.now()

  function init() {
    if (!containerRef.value) return

    const s = new THREE.Scene()
    s.background = new THREE.Color(0x050714)
    scene.value = s

    const w = Math.max(1, containerRef.value.clientWidth)
    const h = Math.max(1, containerRef.value.clientHeight)

    const c = new THREE.PerspectiveCamera(55, w / h, 0.1, 100)
    c.position.set(0, 0, 7)
    camera.value = c

    const r = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    r.setSize(w, h)
    r.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    r.toneMapping = THREE.ACESFilmicToneMapping
    r.toneMappingExposure = 1.2
    containerRef.value.innerHTML = ''
    containerRef.value.appendChild(r.domElement)
    renderer.value = r

    const o = new OrbitControls(c, r.domElement)
    o.enableDamping = true
    o.dampingFactor = 0.06
    o.enableZoom = true
    o.minDistance = 3
    o.maxDistance = 14
    o.autoRotate = true
    o.autoRotateSpeed = 0.5
    controls.value = o

    // 环境光
    const ambient = new THREE.AmbientLight(0x0a0a1a, 0.6)
    s.add(ambient)

    // 星空背景粒子
    addBackgroundStars(s)

    animate()
    window.addEventListener('resize', onResize)

    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      resizeObserver = new ResizeObserver(() => onResize())
      resizeObserver.observe(containerRef.value)
    }
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
    const elapsed = (Date.now() - startTime) / 1000

    controls.value?.update()
    updateCoreAnimation(elapsed)
    updateParticles(elapsed)
    updateTrails(elapsed)
    updateRings(elapsed)

    if (renderer.value && scene.value && camera.value) {
      renderer.value.render(scene.value, camera.value)
    }
  }

  /**
   * 加载角色特征并渲染 3D 形象。
   */
  function loadAvatar(traits: AvatarTraits, _emotionTone?: EmotionTone) {
    if (!scene.value) return
    currentTraits = traits
    startTime = Date.now()

    // 清除旧对象
    clearAvatarObjects()

    const coreColor = new THREE.Color(traits.coreColor)
    const auraColor = new THREE.Color(traits.auraColor)
    const secondaryColor = new THREE.Color(traits.secondaryColor || '#f59e0b')

    // 1. 核心形状
    buildCore(traits, coreColor, auraColor)

    // 2. 粒子光晕
    buildParticles(traits, auraColor, secondaryColor)

    // 3. 光环
    buildRings(traits, auraColor)

    // 4. 动态光源
    buildLights(traits, coreColor, auraColor)

    // 5. 拖尾效果
    if (traits.trailEffect) {
      buildTrails(traits, auraColor)
    }

    // 调整自转速度
    if (controls.value) {
      controls.value.autoRotateSpeed = traits.rotationSpeed * 1.5
    }
  }

  function buildCore(traits: AvatarTraits, coreColor: THREE.Color, auraColor: THREE.Color) {
    if (!scene.value) return

    let coreGeo: THREE.BufferGeometry

    switch (traits.coreShape) {
      case 'crystal':
        coreGeo = new THREE.OctahedronGeometry(0.9, 2)
        break
      case 'nebula':
        coreGeo = new THREE.IcosahedronGeometry(0.85, 3)
        break
      case 'prism':
        coreGeo = new THREE.CylinderGeometry(0.6, 0.8, 1.4, 6, 1)
        break
      case 'torus':
        coreGeo = new THREE.TorusGeometry(0.7, 0.28, 16, 64)
        break
      default: // sphere
        coreGeo = new THREE.SphereGeometry(0.85, 64, 64)
    }

    const coreMat = new THREE.MeshStandardMaterial({
      color: coreColor,
      emissive: coreColor,
      emissiveIntensity: 0.45,
      roughness: 0.15,
      metalness: 0.7,
      transparent: true,
      opacity: 0.92,
    })

    coreMesh = new THREE.Mesh(coreGeo, coreMat)
    scene.value.add(coreMesh)

    // 外层发光球（大一圈，半透明）
    const glowGeo = new THREE.SphereGeometry(traits.glowRadius * 0.45, 32, 32)
    const glowMat = new THREE.MeshBasicMaterial({
      color: auraColor,
      transparent: true,
      opacity: traits.auraIntensity * 0.12,
      side: THREE.BackSide,
    })
    coreGlow = new THREE.Mesh(glowGeo, glowMat)
    scene.value.add(coreGlow)
  }

  function buildParticles(traits: AvatarTraits, auraColor: THREE.Color, _secondaryColor: THREE.Color) {
    if (!scene.value) return

    particleCount = Math.min(3000, Math.max(300, traits.particleCount))
    particleGeometry = new THREE.BufferGeometry()
    const positions = new Float32Array(particleCount * 3)
    particleVelocities = []

    const radius = traits.glowRadius

    for (let i = 0; i < particleCount; i++) {
      // 球壳分布
      const theta = Math.random() * Math.PI * 2
      const phi = Math.acos(2 * Math.random() - 1)
      const r = radius * (0.6 + Math.random() * 0.8)

      positions[i * 3] = r * Math.sin(phi) * Math.cos(theta)
      positions[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta)
      positions[i * 3 + 2] = r * Math.cos(phi)

      // 速度（用于动画）
      particleVelocities.push(
        (Math.random() - 0.5) * 0.008,
        (Math.random() - 0.5) * 0.008,
        (Math.random() - 0.5) * 0.008,
      )
    }

    particleGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    particlePositions = positions

    // 粒子颜色和大小
    let color = auraColor.clone()
    let size = 0.04

    switch (traits.particleType) {
      case 'fireflies':
        color = new THREE.Color('#d4ff3f')
        size = 0.07
        break
      case 'snowflakes':
        color = new THREE.Color('#e0f0ff')
        size = 0.06
        break
      case 'petals':
        color = new THREE.Color('#ffb7c5')
        size = 0.08
        break
      case 'embers':
        color = new THREE.Color('#ff6b35')
        size = 0.06
        break
      case 'crystals':
        color = auraColor.clone()
        size = 0.05
        break
      default: // stars
        color = auraColor.clone()
        size = 0.04
    }

    particleMaterial = new THREE.PointsMaterial({
      color,
      size,
      transparent: true,
      opacity: 0.85,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    })

    particleSystem = new THREE.Points(particleGeometry, particleMaterial)
    scene.value.add(particleSystem)
  }

  function buildRings(traits: AvatarTraits, auraColor: THREE.Color) {
    if (!scene.value) return

    const r = traits.glowRadius * 0.55

    // 内环
    const innerGeo = new THREE.TorusGeometry(r, 0.015, 8, 128)
    const innerMat = new THREE.MeshBasicMaterial({
      color: auraColor,
      transparent: true,
      opacity: traits.auraIntensity * 0.7,
    })
    innerRingMesh = new THREE.Mesh(innerGeo, innerMat)
    innerRingMesh.rotation.x = Math.PI / 4
    scene.value.add(innerRingMesh)

    // 外环
    const outerGeo = new THREE.TorusGeometry(r * 1.4, 0.008, 8, 128)
    const outerMat = new THREE.MeshBasicMaterial({
      color: new THREE.Color(traits.secondaryColor || '#f59e0b'),
      transparent: true,
      opacity: traits.auraIntensity * 0.4,
    })
    outerRingMesh = new THREE.Mesh(outerGeo, outerMat)
    outerRingMesh.rotation.x = -Math.PI / 6
    outerRingMesh.rotation.y = Math.PI / 3
    scene.value.add(outerRingMesh)
  }

  function buildLights(traits: AvatarTraits, coreColor: THREE.Color, auraColor: THREE.Color) {
    if (!scene.value) return

    // 核心点光源
    const coreLight = new THREE.PointLight(coreColor, traits.auraIntensity * 3, 8)
    coreLight.position.set(0, 0, 0)
    scene.value.add(coreLight)

    // 光晕点光源（偏移位置）
    const auraLight = new THREE.PointLight(auraColor, traits.auraIntensity * 1.5, 12)
    auraLight.position.set(2, 1, 1)
    scene.value.add(auraLight)

    // 辅助光源
    const fillLight = new THREE.PointLight(
      new THREE.Color(traits.secondaryColor || '#f59e0b'),
      0.8,
      10,
    )
    fillLight.position.set(-2, -1, 2)
    scene.value.add(fillLight)
  }

  function buildTrails(traits: AvatarTraits, auraColor: THREE.Color) {
    if (!scene.value) return

    const trailCount = 6
    for (let i = 0; i < trailCount; i++) {
      const angle = (i / trailCount) * Math.PI * 2
      const r = traits.glowRadius * 0.7
      const trailGeo = new THREE.SphereGeometry(0.06, 8, 8)
      const trailMat = new THREE.MeshBasicMaterial({
        color: auraColor,
        transparent: true,
        opacity: 0.6 - i * 0.08,
      })
      const trail = new THREE.Mesh(trailGeo, trailMat)
      trail.position.set(
        Math.cos(angle) * r,
        Math.sin(angle * 0.5) * r * 0.3,
        Math.sin(angle) * r,
      )
      trail.userData.angle = angle
      trail.userData.index = i
      scene.value.add(trail)
      trailMeshes.push(trail)
    }
  }

  function addBackgroundStars(s: THREE.Scene) {
    const starCount = 800
    const starGeo = new THREE.BufferGeometry()
    const starPos = new Float32Array(starCount * 3)
    for (let i = 0; i < starCount; i++) {
      starPos[i * 3] = (Math.random() - 0.5) * 60
      starPos[i * 3 + 1] = (Math.random() - 0.5) * 60
      starPos[i * 3 + 2] = (Math.random() - 0.5) * 60
    }
    starGeo.setAttribute('position', new THREE.BufferAttribute(starPos, 3))
    const starMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.03,
      transparent: true,
      opacity: 0.5,
    })
    s.add(new THREE.Points(starGeo, starMat))
  }

  // ── 动画更新 ──────────────────────────────────────────────────────────

  function updateCoreAnimation(elapsed: number) {
    if (!coreMesh || !coreGlow || !currentTraits) return

    const freq = currentTraits.pulseFrequency || 1.2
    const pulse = Math.sin(elapsed * freq * Math.PI * 2) * 0.08 + 1.0

    coreMesh.scale.setScalar(pulse)
    coreGlow.scale.setScalar(pulse * 1.05)

    // 核心自转
    coreMesh.rotation.y += 0.005 * (currentTraits.rotationSpeed || 0.4)
    coreMesh.rotation.x += 0.002 * (currentTraits.rotationSpeed || 0.4)

    // 光晕透明度呼吸
    if (coreGlow.material instanceof THREE.MeshBasicMaterial) {
      coreGlow.material.opacity =
        currentTraits.auraIntensity * 0.12 * (0.8 + Math.sin(elapsed * freq * Math.PI * 2) * 0.2)
    }
  }

  function updateParticles(elapsed: number) {
    if (!particleSystem || !particlePositions || !currentTraits) return

    const posAttr = particleGeometry!.getAttribute('position') as THREE.BufferAttribute
    const positions = posAttr.array as Float32Array
    const type = currentTraits.particleType

    for (let i = 0; i < particleCount; i++) {
      const idx = i * 3

      if (type === 'fireflies') {
        // 萤火虫：随机漂浮
        positions[idx] += Math.sin(elapsed * 0.8 + i * 0.3) * 0.006
        positions[idx + 1] += Math.cos(elapsed * 0.6 + i * 0.4) * 0.005
        positions[idx + 2] += Math.sin(elapsed * 0.7 + i * 0.5) * 0.006
      } else if (type === 'snowflakes') {
        // 雪花：缓慢下落
        positions[idx + 1] -= 0.008
        positions[idx] += Math.sin(elapsed + i) * 0.002
        if (positions[idx + 1] < -currentTraits.glowRadius * 1.2) {
          positions[idx + 1] = currentTraits.glowRadius * 1.2
        }
      } else if (type === 'petals') {
        // 花瓣：螺旋飘落
        const angle = elapsed * 0.3 + i * 0.1
        positions[idx] += Math.cos(angle) * 0.004
        positions[idx + 1] -= 0.005
        positions[idx + 2] += Math.sin(angle) * 0.004
        if (positions[idx + 1] < -currentTraits.glowRadius * 1.2) {
          positions[idx + 1] = currentTraits.glowRadius * 1.2
        }
      } else if (type === 'embers') {
        // 火星：向上飘散
        positions[idx + 1] += 0.01
        positions[idx] += (Math.random() - 0.5) * 0.005
        if (positions[idx + 1] > currentTraits.glowRadius * 1.5) {
          positions[idx + 1] = -currentTraits.glowRadius * 0.5
        }
      } else if (type === 'crystals') {
        // 水晶：缓慢旋转
        const r = Math.sqrt(
          positions[idx] ** 2 + positions[idx + 1] ** 2 + positions[idx + 2] ** 2,
        )
        const theta = Math.atan2(positions[idx + 2], positions[idx]) + 0.003
        positions[idx] = r * Math.cos(theta)
        positions[idx + 2] = r * Math.sin(theta)
      } else {
        // stars：轻微闪烁漂移
        positions[idx] += particleVelocities[i * 3] * 0.3
        positions[idx + 1] += particleVelocities[i * 3 + 1] * 0.3
        positions[idx + 2] += particleVelocities[i * 3 + 2] * 0.3

        // 边界回弹
        const r = Math.sqrt(
          positions[idx] ** 2 + positions[idx + 1] ** 2 + positions[idx + 2] ** 2,
        )
        const maxR = currentTraits.glowRadius * 1.5
        if (r > maxR) {
          positions[idx] *= maxR / r
          positions[idx + 1] *= maxR / r
          positions[idx + 2] *= maxR / r
          particleVelocities[i * 3] *= -0.8
          particleVelocities[i * 3 + 1] *= -0.8
          particleVelocities[i * 3 + 2] *= -0.8
        }
      }
    }

    posAttr.needsUpdate = true

    // 粒子整体缓慢旋转
    if (particleSystem) {
      particleSystem.rotation.y += 0.001
    }
  }

  function updateTrails(elapsed: number) {
    if (!currentTraits) return
    const r = currentTraits.glowRadius * 0.7
    trailMeshes.forEach((trail, i) => {
      const angle = trail.userData.angle + elapsed * currentTraits!.rotationSpeed * 0.5
      trail.position.set(
        Math.cos(angle) * r,
        Math.sin(angle * 0.7 + i) * r * 0.35,
        Math.sin(angle) * r,
      )
      if (trail.material instanceof THREE.MeshBasicMaterial) {
        trail.material.opacity = (0.6 - i * 0.08) * (0.7 + Math.sin(elapsed * 2 + i) * 0.3)
      }
    })
  }

  function updateRings(elapsed: number) {
    if (innerRingMesh) {
      innerRingMesh.rotation.z += 0.008
      innerRingMesh.rotation.x = Math.PI / 4 + Math.sin(elapsed * 0.5) * 0.1
    }
    if (outerRingMesh) {
      outerRingMesh.rotation.y += 0.005
      outerRingMesh.rotation.z -= 0.003
    }
  }

  function clearAvatarObjects() {
    if (!scene.value) return

    const toRemove = [coreMesh, coreGlow, particleSystem, innerRingMesh, outerRingMesh, ...trailMeshes]
    toRemove.forEach((obj) => {
      if (obj) {
        scene.value!.remove(obj)
        if (obj instanceof THREE.Mesh || obj instanceof THREE.Points) {
          obj.geometry?.dispose()
          if (Array.isArray(obj.material)) {
            obj.material.forEach((m) => m.dispose())
          } else {
            obj.material?.dispose()
          }
        }
      }
    })

    coreMesh = null
    coreGlow = null
    particleSystem = null
    particleGeometry = null
    particleMaterial = null
    innerRingMesh = null
    outerRingMesh = null
    trailMeshes = []
    particlePositions = null
    particleVelocities = []
    particleCount = 0

    // 清除动态光源（保留环境光和背景星空）
    const lightsToRemove = scene.value.children.filter((c) => c instanceof THREE.PointLight)
    lightsToRemove.forEach((l) => scene.value!.remove(l))
  }

  function dispose() {
    cancelAnimationFrame(animFrameId)
    window.removeEventListener('resize', onResize)
    if (resizeObserver) {
      resizeObserver.disconnect()
      resizeObserver = null
    }
    clearAvatarObjects()
    renderer.value?.dispose()
    renderer.value?.domElement.remove()
  }

  onUnmounted(dispose)

  return { init, loadAvatar, dispose, scene, camera, renderer }
}
