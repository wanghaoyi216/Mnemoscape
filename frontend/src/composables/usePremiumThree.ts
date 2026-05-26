import { ref, onUnmounted, type Ref } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Howl } from 'howler'
import type { SceneData } from '../types'

export function usePremiumThree(containerRef: Ref<HTMLElement | null>) {
  const renderer = ref<THREE.WebGLRenderer | null>(null)
  const scene = ref<THREE.Scene | null>(null)
  const camera = ref<THREE.PerspectiveCamera | null>(null)
  const controls = ref<OrbitControls | null>(null)

  // Particle Core System
  let particleGeometry: THREE.BufferGeometry | null = null
  let particleMaterial: THREE.PointsMaterial | null = null
  let particles: THREE.Points | null = null
  let particleCount = 2000
  let particlePositions: Float32Array | null = null
  let particleVelocities: number[] = []
  let particleType = 'stars'

  // Ripple System for Rain
  const ripples: { mesh: THREE.Mesh; scale: number; maxScale: number; opacity: number }[] = []

  // Positional Audio Map
  const activeSounds: { [key: string]: Howl } = {}
  let ambientSound: Howl | null = null

  // Ghosts map (userId -> Points representing nebulous stardust)
  const ghosts: Map<string, THREE.Points> = new Map()

  // Frame anim ID
  let animFrameId = 0

  function init() {
    if (!containerRef.value) return
    const s = new THREE.Scene()
    s.background = new THREE.Color(0x070714)
    scene.value = s

    const c = new THREE.PerspectiveCamera(65, containerRef.value.clientWidth / containerRef.value.clientHeight, 0.1, 100)
    c.position.set(6, 4, 10)
    camera.value = c

    const r = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    r.setSize(containerRef.value.clientWidth, containerRef.value.clientHeight)
    r.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    r.shadowMap.enabled = true
    r.shadowMap.type = THREE.PCFSoftShadowMap
    r.toneMapping = THREE.ACESFilmicToneMapping
    r.toneMappingExposure = 1.0

    // Remove any old canvas first
    containerRef.value.innerHTML = ''
    containerRef.value.appendChild(r.domElement)
    renderer.value = r

    const o = new OrbitControls(c, r.domElement)
    o.enableDamping = true
    o.dampingFactor = 0.05
    o.maxPolarAngle = Math.PI / 2 - 0.02 // Don't go below ground
    o.minDistance = 2
    o.maxDistance = 25
    o.target.set(0, 1.5, 0)
    controls.value = o

    // Floor grid with a futuristic cyan-purple touch
    const grid = new THREE.GridHelper(20, 40, 0x6c63ff, 0x1e1a3e)
    if (grid.material instanceof THREE.Material) {
      grid.material.opacity = 0.25
      grid.material.transparent = true
    }
    s.add(grid)

    animate()
    window.addEventListener('resize', onResize)

    return s
  }

  function onResize() {
    if (!containerRef.value || !renderer.value || !camera.value) return
    const w = containerRef.value.clientWidth
    const h = containerRef.value.clientHeight
    camera.value.aspect = w / h
    camera.value.updateProjectionMatrix()
    renderer.value.setSize(w, h)
  }

  function animate() {
    animFrameId = requestAnimationFrame(animate)
    controls.value?.update()

    // Update Particles based on type
    updateParticles()

    // Update Ripples
    updateRipples()

    // Update positional sound coordinates in space
    updateAudioPositions()

    if (renderer.value && scene.value && camera.value) {
      renderer.value.render(scene.value, camera.value)
    }
  }

  // Load complete gallery scene
  function loadScene(data: SceneData, sceneKey = 'summer') {
    if (!scene.value) return

    // Clear previous elements except standard Grid
    scene.value.children = scene.value.children.filter(
      (c: THREE.Object3D) => c instanceof THREE.GridHelper
    )

    // Soft surrounding ambient light
    const ambient = new THREE.AmbientLight(0x0e0e24, 0.8)
    scene.value.add(ambient)

    // Neon Aurora Directional Light
    const dirLight = new THREE.DirectionalLight(0x6c63ff, 1.5)
    dirLight.position.set(10, 12, 8)
    dirLight.castShadow = true
    dirLight.shadow.mapSize.width = 1024
    dirLight.shadow.mapSize.height = 1024
    dirLight.shadow.bias = -0.001
    scene.value.add(dirLight)

    // Warm Accent Spotlight
    const spot = new THREE.SpotLight(0xe040fb, 3, 15, Math.PI / 4, 0.5, 1)
    spot.position.set(-4, 8, -2)
    scene.value.add(spot)

    // Glowing Floor
    const floorGeo = new THREE.PlaneGeometry(24, 24)
    const floorMat = new THREE.MeshStandardMaterial({
      color: 0x0a0a1a,
      roughness: 0.7,
      metalness: 0.1,
      bumpScale: 0.05
    })
    const floor = new THREE.Mesh(floorGeo, floorMat)
    floor.rotation.x = -Math.PI / 2
    floor.receiveShadow = true
    scene.value.add(floor)

    // Add main components from scene data
    if (data.objects) {
      for (const obj of data.objects) {
        const mesh = createObjectMesh(obj)
        if (mesh) scene.value.add(mesh)
      }
    }

    // Build Premium Particle System
    buildParticleSystem(sceneKey)

    // Ambient Fog Configuration
    const fogColor =
      sceneKey === 'winter' ? 0x181a28 :
      sceneKey === 'night' ? 0x05050e :
      sceneKey === 'rain' ? 0x0d0f1a : 0x0a0a1e
    scene.value.fog = new THREE.FogExp2(fogColor, 0.04)

    // Setup audio streams
    setupAudio(sceneKey)
  }

  // Create refined meshes instead of basic shapes
  function createObjectMesh(obj: any): THREE.Mesh | null {
    let geo: THREE.BufferGeometry
    const type = obj.type.toLowerCase()

    if (type === 'tree' || type === 'cherry_tree' || type === 'autumn_tree') {
      // Sophisticated composite structure
      geo = new THREE.CylinderGeometry(0.15, 0.3, 3, 8)
      const trunkMat = new THREE.MeshStandardMaterial({ color: 0x3e2723, roughness: 0.9 })
      const trunk = new THREE.Mesh(geo, trunkMat)
      trunk.position.set(obj.position[0], obj.position[1] + 1.5, obj.position[2])
      trunk.castShadow = true

      // Stardust leaf canopy
      const leafGeo = new THREE.DodecahedronGeometry(1.6, 1)
      const leafColor = type === 'cherry_tree' ? 0xffb7c5 : type === 'autumn_tree' ? 0xff4500 : 0x2e7d32
      const leafMat = new THREE.MeshStandardMaterial({
        color: leafColor,
        roughness: 0.6,
        flatShading: true
      })
      const leaves = new THREE.Mesh(leafGeo, leafMat)
      leaves.position.y = 2.0
      leaves.castShadow = true
      trunk.add(leaves)
      return trunk
    }

    // Default primitive builders
    switch (type) {
      case 'sphere':
        geo = new THREE.SphereGeometry(0.6, 32, 32)
        break
      case 'bench':
        geo = new THREE.BoxGeometry(2.0, 0.15, 0.6)
        break
      case 'lamp':
        geo = new THREE.CylinderGeometry(0.04, 0.04, 2.5, 8)
        const pole = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color: 0x2c3e50 }))
        pole.position.set(obj.position[0], obj.position[1] + 1.25, obj.position[2])
        const lightGeo = new THREE.SphereGeometry(0.3, 16, 16)
        const lightMat = new THREE.MeshBasicMaterial({ color: 0xffea00 })
        const bulb = new THREE.Mesh(lightGeo, lightMat)
        bulb.position.y = 1.35
        pole.add(bulb)
        return pole
      case 'house':
        geo = new THREE.BoxGeometry(3.5, 2.5, 3.5)
        break
      default:
        geo = new THREE.BoxGeometry(1, 1, 1)
    }

    const mat = new THREE.MeshStandardMaterial({
      color: obj.color || 0x6c63ff,
      roughness: 0.4,
      metalness: 0.2
    })
    const mesh = new THREE.Mesh(geo, mat)
    mesh.position.set(obj.position[0], obj.position[1] + (type === 'bench' ? 0.3 : 0.5), obj.position[2])
    mesh.scale.set(obj.scale[0], obj.scale[1], obj.scale[2])
    mesh.castShadow = true
    mesh.receiveShadow = true
    return mesh
  }

  // Particle Storm System Builder
  function buildParticleSystem(sceneKey: string) {
    if (!scene.value) return

    // Clean old system
    if (particles && scene.value) {
      scene.value.remove(particles)
      particleGeometry?.dispose()
      particleMaterial?.dispose()
    }

    particleType = sceneKey
    particleGeometry = new THREE.BufferGeometry()
    const pos = new Float32Array(particleCount * 3)
    particleVelocities = []

    for (let i = 0; i < particleCount; i++) {
      pos[i * 3] = (Math.random() - 0.5) * 24
      pos[i * 3 + 1] = Math.random() * 12
      pos[i * 3 + 2] = (Math.random() - 0.5) * 24

      // X velocity, Y fall speed, Z sway speed
      particleVelocities.push(
        (Math.random() - 0.5) * 0.02, // dx
        -0.02 - Math.random() * 0.03,  // dy
        (Math.random() - 0.5) * 0.02  // dz
      )
    }

    particleGeometry.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    particlePositions = pos

    // Theme color setup for particles
    let color = 0xffffff
    let size = 0.05

    if (sceneKey === 'summer') {
      color = 0xd4ff3f // Golden-green Fireflies
      size = 0.08
    } else if (sceneKey === 'winter') {
      color = 0xffffff // White snow
      size = 0.07
    } else if (sceneKey === 'rain') {
      color = 0x7da2ff // Translucent blue drops
      size = 0.05
    } else if (sceneKey === 'spring') {
      color = 0xffb7c5 // Pink cherry blossoms
      size = 0.09
    } else if (sceneKey === 'autumn') {
      color = 0xff5722 // Orange-brown leaves
      size = 0.1
    } else {
      color = 0xe0e0ff // Mystical stardust
      size = 0.04
    }

    particleMaterial = new THREE.PointsMaterial({
      color: color,
      size: size,
      transparent: true,
      opacity: 0.8,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })

    particles = new THREE.Points(particleGeometry, particleMaterial)
    scene.value.add(particles)
  }

  // Update loop for particles
  function updateParticles() {
    if (!particles || !particlePositions || !scene.value) return

    const posAttr = particleGeometry!.getAttribute('position') as THREE.BufferAttribute
    const positions = posAttr.array as Float32Array

    for (let i = 0; i < particleCount; i++) {
      const idx = i * 3

      if (particleType === 'summer') {
        // Fireflies floating randomly using sine/cosine waves
        const time = Date.now() * 0.001
        positions[idx] += Math.sin(time + i) * 0.005
        positions[idx + 1] += Math.cos(time + i) * 0.003
        positions[idx + 2] += Math.sin(time * 0.5 + i) * 0.005

        // Boundary safety
        if (positions[idx + 1] < 0.1 || positions[idx + 1] > 8) {
          positions[idx + 1] = Math.random() * 8
        }
      } else if (particleType === 'winter' || particleType === 'spring' || particleType === 'autumn') {
        // Falling snowflakes/leaves with wind drift
        positions[idx] += particleVelocities[i * 3] + Math.sin(Date.now() * 0.0005 + i) * 0.002
        positions[idx + 1] += particleVelocities[i * 3 + 1]
        positions[idx + 2] += particleVelocities[i * 3 + 2]

        // Reset if hitting floor
        if (positions[idx + 1] < 0) {
          positions[idx] = (Math.random() - 0.5) * 24
          positions[idx + 1] = 12
          positions[idx + 2] = (Math.random() - 0.5) * 24
        }
      } else if (particleType === 'rain') {
        // Superfast rainy drops falling straight down
        positions[idx + 1] += particleVelocities[i * 3 + 1] * 2.0

        if (positions[idx + 1] < 0) {
          // Generate a floor splash ripple at the impact point!
          createRipple(positions[idx], positions[idx + 2])

          positions[idx] = (Math.random() - 0.5) * 24
          positions[idx + 1] = 12
          positions[idx + 2] = (Math.random() - 0.5) * 24
        }
      } else {
        // Twinkling stars blinking gently
        const factor = Math.sin(Date.now() * 0.002 + i) * 0.001
        positions[idx] += factor
        positions[idx + 2] += factor
      }
    }

    posAttr.needsUpdate = true
  }

  // Ripple generators for雨天
  function createRipple(x: number, z: number) {
    if (!scene.value || ripples.length > 40) return
    const rippleGeo = new THREE.RingGeometry(0.01, 0.15, 16)
    const rippleMat = new THREE.MeshBasicMaterial({
      color: 0x7da2ff,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.6
    })
    const mesh = new THREE.Mesh(rippleGeo, rippleMat)
    mesh.rotation.x = -Math.PI / 2
    mesh.position.set(x, 0.02, z)
    scene.value.add(mesh)
    ripples.push({ mesh, scale: 1, maxScale: 3 + Math.random() * 2, opacity: 0.6 })
  }

  function updateRipples() {
    for (let i = ripples.length - 1; i >= 0; i--) {
      const r = ripples[i]
      r.scale += 0.1
      r.mesh.scale.set(r.scale, r.scale, 1)
      r.opacity -= 0.02
      if (r.mesh.material instanceof THREE.MeshBasicMaterial) {
        r.mesh.material.opacity = Math.max(0, r.opacity)
      }

      if (r.opacity <= 0) {
        if (scene.value) scene.value.remove(r.mesh)
        r.mesh.geometry.dispose()
        if (r.mesh.material instanceof THREE.Material) r.mesh.material.dispose()
        ripples.splice(i, 1)
      }
    }
  }

  // Howler Audio Player Setup
  function setupAudio(sceneKey: string) {
    // Clear old audio
    if (ambientSound) {
      ambientSound.stop()
      ambientSound.unload()
    }
    Object.values(activeSounds).forEach((s) => {
      s.stop()
      s.unload()
    })

    const audioFiles: { [key: string]: string } = {
      summer: 'https://assets.mixkit.co/active_storage/sfx/2433/2433-84.wav', // Cicadas
      winter: 'https://assets.mixkit.co/active_storage/sfx/1188/1188-84.wav', // Cold Wind
      rain: 'https://assets.mixkit.co/active_storage/sfx/2448/2448-84.wav',   // Soft rain
      spring: 'https://assets.mixkit.co/active_storage/sfx/1653/1653-84.wav', // Peaceful forest
      autumn: 'https://assets.mixkit.co/active_storage/sfx/1183/1183-84.wav'  // Rustling leaves
    }

    const file = audioFiles[sceneKey] || audioFiles['spring']
    ambientSound = new Howl({
      src: [file],
      loop: true,
      volume: 0.45,
      html5: true
    })
    ambientSound.play()

    // Mock active spot source
    activeSounds['spot_source'] = new Howl({
      src: ['https://assets.mixkit.co/active_storage/sfx/2568/2568-84.wav'], // Echo bell
      loop: true,
      volume: 0.6
    })
    activeSounds['spot_source'].play()
  }

  function updateAudioPositions() {
    if (!camera.value || !activeSounds['spot_source']) return
    const pos = camera.value.position
    // Spot sound is sitting at tree coordinate [0, 1.5, -4.0]
    const sourcePos = new THREE.Vector3(0, 1.5, -4.0)
    const dist = pos.distanceTo(sourcePos)

    // Custom log decay for positional sound
    const maxDist = 15
    const volume = Math.max(0, 1 - dist / maxDist) * 0.8
    activeSounds['spot_source'].volume(volume)
  }

  // Sync virtual ghosts
  function syncGhost(userId: string, position: number[]) {
    if (!scene.value) return

    let ghostPoints = ghosts.get(userId)
    if (!ghostPoints) {
      // Create a gorgeous nebulous stardust ball for the ghost
      const count = 120
      const geo = new THREE.BufferGeometry()
      const pos = new Float32Array(count * 3)

      for (let i = 0; i < count; i++) {
        // Stardust shell
        const theta = Math.random() * Math.PI * 2
        const phi = Math.acos(Math.random() * 2 - 1)
        const r = 0.15 + Math.random() * 0.25
        pos[i * 3] = r * Math.sin(phi) * Math.cos(theta)
        pos[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta)
        pos[i * 3 + 2] = r * Math.cos(phi)
      }

      geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
      const mat = new THREE.PointsMaterial({
        color: 0x00e5ff,
        size: 0.06,
        transparent: true,
        opacity: 0.85,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      })

      ghostPoints = new THREE.Points(geo, mat)
      scene.value.add(ghostPoints)
      ghosts.set(userId, ghostPoints)
    }

    // Smooth lerp ghost position
    ghostPoints.position.set(position[0], position[1], position[2])
  }

  function removeGhost(userId: string) {
    const ghost = ghosts.get(userId)
    if (ghost && scene.value) {
      scene.value.remove(ghost)
      ghost.geometry.dispose()
      if (ghost.material instanceof THREE.Material) ghost.material.dispose()
      ghosts.delete(userId)
    }
  }

  // Draw local Membrane note
  function placeNoteMesh(x: number, z: number) {
    if (!scene.value) return
    const container = new THREE.Group()
    container.position.set(x, 0.05, z)

    // Inner glowing ring
    const ringGeo = new THREE.RingGeometry(0.01, 0.25, 32)
    const ringMat = new THREE.MeshBasicMaterial({
      color: 0xe040fb,
      transparent: true,
      opacity: 0.8,
      side: THREE.DoubleSide
    })
    const ring = new THREE.Mesh(ringGeo, ringMat)
    ring.rotation.x = -Math.PI / 2
    container.add(ring)

    // Vertical ethereal prism
    const coneGeo = new THREE.ConeGeometry(0.12, 0.4, 4)
    const coneMat = new THREE.MeshStandardMaterial({
      color: 0xff80ab,
      roughness: 0.1,
      metalness: 0.9,
      transparent: true,
      opacity: 0.7
    })
    const cone = new THREE.Mesh(coneGeo, coneMat)
    cone.position.y = 0.3
    container.add(cone)

    scene.value.add(container)
  }

  // Advanced Drift calculations
  function applyDrift(fadeLevel: number) {
    if (!scene.value) return

    // Linear Fog contraction
    scene.value.fog = new THREE.FogExp2(0x050510, 0.04 + fadeLevel * 0.08)

    scene.value.children.forEach((child: THREE.Object3D) => {
      if (child instanceof THREE.Mesh && child.material instanceof THREE.MeshStandardMaterial) {
        // Desaturate and darken colors
        child.material.color.multiplyScalar(1 - fadeLevel * 0.5)
        child.material.roughness = Math.min(1.0, child.material.roughness + fadeLevel * 0.2)
      }
    })

    // Modify particles opacity based on drift
    if (particleMaterial) {
      particleMaterial.opacity = Math.max(0.1, 0.8 * (1 - fadeLevel * 0.7))
    }
  }

  function getCameraPosition() {
    return camera.value?.position.toArray() ?? [0, 1.8, 0]
  }

  function getLookingAt() {
    if (!controls.value || !camera.value) return { x: 0, y: 0, z: -1 }
    const dir = new THREE.Vector3()
    camera.value.getWorldDirection(dir)
    return { x: dir.x, y: dir.y, z: dir.z }
  }

  function dispose() {
    cancelAnimationFrame(animFrameId)
    window.removeEventListener('resize', onResize)

    // Clear ambient & spot sound
    if (ambientSound) {
      ambientSound.stop()
      ambientSound.unload()
    }
    Object.values(activeSounds).forEach((s) => {
      s.stop()
      s.unload()
    })

    // Clean WebGL resources
    renderer.value?.dispose()
    renderer.value?.domElement.remove()

    // Dispose Geometries and Materials
    if (scene.value) {
      scene.value.children.forEach((child) => {
        if (child instanceof THREE.Mesh) {
          child.geometry.dispose()
          if (child.material instanceof Array) {
            child.material.forEach((m) => m.dispose())
          } else {
            child.material.dispose()
          }
        }
      })
    }

    particleGeometry?.dispose()
    particleMaterial?.dispose()

    ghosts.forEach((ghost) => {
      ghost.geometry.dispose()
      if (ghost.material instanceof THREE.Material) ghost.material.dispose()
    })
    ghosts.clear()
  }

  onUnmounted(dispose)

  return {
    init,
    loadScene,
    applyDrift,
    getCameraPosition,
    getLookingAt,
    syncGhost,
    removeGhost,
    placeNoteMesh,
    dispose,
    scene,
    camera,
    renderer,
    controls
  }
}
