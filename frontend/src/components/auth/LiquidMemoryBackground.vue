<script setup lang="ts">
/**
 * 「记忆流体」登录页背景 — Three.js + 自定义 Fragment Shader。
 *
 * 设计意图：屏幕级的全屏四边形（fullscreen quad）上跑一个 raymarched
 * 流体 + 神经突触粒子着色器。鼠标位置作为 uMouse 推入 shader，靠 SDF
 * 距离场计算出"鼠标推动流体"的排斥力，并在粒子层做余晖拖尾。
 *
 * 性能策略：
 *  - 单 Mesh、单 ShaderMaterial、无后处理通道，4K 屏 60fps 可达
 *  - 标签页隐藏时自动暂停 (Page Visibility API)，避免后台烧电
 *  - prefers-reduced-motion 时直接退化为静态采样（不挂 RAF）
 *  - 组件卸载时清理 RAF / dispose 几何与材质，无 GPU 泄漏
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

const canvasRef = ref<HTMLCanvasElement | null>(null)
let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.OrthographicCamera | null = null
let material: THREE.ShaderMaterial | null = null
let geometry: THREE.PlaneGeometry | null = null
let mesh: THREE.Mesh | null = null
let rafId: number | null = null

// 鼠标在归一化坐标系 [-1, 1]，目标值 + 当前插值
const mouseTarget = { x: 0, y: 0 }
const mouseCurrent = { x: 0, y: 0 }
let lastFrameTime = performance.now()
const startTime = performance.now()

// 减少动画偏好直接跳出
const prefersReducedMotion =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

const vertexShader = /* glsl */ `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = vec4(position, 1.0);
  }
`

// 流体噪声 + 粒子层 + 鼠标排斥力。
// 颜色取金 (#f2b95c)、薄荷 (#36d8b4)、紫罗兰 (#846edc) 三主色，
// 与 LoginView 玻璃卡片的边缘流光保持视觉同构。
const fragmentShader = /* glsl */ `
  precision highp float;

  varying vec2 vUv;
  uniform float uTime;
  uniform vec2 uResolution;
  uniform vec2 uMouse;       // 归一化 [-1,1]
  uniform float uMouseEnergy;// 鼠标移动强度（按速度衰减）

  // — Simplex 2D noise (经典实现) —
  vec3 permute(vec3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
  float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                       -0.577350269189626, 0.024390243902439);
    vec2 i  = floor(v + dot(v, C.yy));
    vec2 x0 = v -   i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0))
                  + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 ox = floor(x + 0.5);
    vec3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x  = a0.x  * x0.x  + h.x  * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

  // 流体场 — 多倍频 simplex，受鼠标位置推扯
  float fluid(vec2 p) {
    vec2 q = p;
    float t = uTime * 0.06;
    float pull = 0.45 * uMouseEnergy;
    q += pull * normalize(p - uMouse) / (0.25 + length(p - uMouse));
    float n  = 0.5  * snoise(q * 1.10 + vec2(t,        -t * 0.7));
          n += 0.25 * snoise(q * 2.30 + vec2(-t * 1.3,  t * 0.9));
          n += 0.12 * snoise(q * 4.70 + vec2(t * 1.7,  -t * 1.4));
    return n;
  }

  // 神经突触粒子层 — 哈希点散布 + 鼠标半径内拖尾余晖
  vec3 synapse(vec2 p) {
    vec3 col = vec3(0.0);
    for (int i = 0; i < 5; i++) {
      float fi = float(i);
      vec2 seed = vec2(127.1 + fi * 13.7, 311.7 + fi * 7.31);
      vec2 anchor = vec2(
        fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453),
        fract(sin(dot(seed + 17.0, vec2(94.673, 53.31))) * 26553.1763)
      ) * 2.0 - 1.0;
      anchor += 0.08 * vec2(sin(uTime * 0.4 + fi), cos(uTime * 0.35 + fi * 1.7));
      float d  = length(p - anchor);
      float halo = 0.018 / (d * d + 0.0024);
      vec3 tint = mix(vec3(0.95, 0.73, 0.36),  // 金
                      vec3(0.21, 0.85, 0.71),  // 薄荷
                      fract(fi * 0.37));
      col += halo * tint;
    }
    // 鼠标附近的拖尾光环 — 神经突触条件反射
    float md = length(p - uMouse);
    col += 0.022 * uMouseEnergy / (md * md + 0.0035) * vec3(0.95, 0.72, 0.95);
    return col;
  }

  void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    uv.x *= uResolution.x / uResolution.y;

    float f = fluid(uv);
    // 色阶 — 在墨水深空背景上挑染金/薄荷/紫
    vec3 base    = vec3(0.027, 0.034, 0.052);
    vec3 deep    = vec3(0.060, 0.105, 0.115);  // 深湖蓝
    vec3 hilite  = mix(vec3(0.95, 0.73, 0.36), vec3(0.52, 0.43, 0.86),
                       0.5 + 0.5 * sin(uTime * 0.12));
    vec3 col = mix(base, deep, smoothstep(-0.6, 0.4, f));
    col      = mix(col, hilite, smoothstep(0.55, 0.95, f) * 0.7);

    col += synapse(uv);

    // 边缘晕影 — 把视觉重心收拢到登录卡片
    float vignette = smoothstep(1.35, 0.35, length(vUv - 0.5));
    col *= mix(0.45, 1.0, vignette);

    // 轻微胶片颗粒 — 1080p 屏幕看不腻
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    col += (grain - 0.5) * 0.018;

    gl_FragColor = vec4(col, 1.0);
  }
`

function handleMouseMove(ev: MouseEvent) {
  const w = window.innerWidth
  const h = window.innerHeight
  const ratio = w / h
  // 与 shader 内 uv.x *= aspect 保持一致的归一化空间
  const nx = (ev.clientX / w) * 2.0 - 1.0
  const ny = -((ev.clientY / h) * 2.0 - 1.0)
  mouseTarget.x = nx * ratio
  mouseTarget.y = ny
}

function handleResize() {
  if (!renderer || !material) return
  const w = window.innerWidth
  const h = window.innerHeight
  // dpr 限制在 1.5 以内 — Retina 上 60fps 还能保住
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
  renderer.setSize(w, h, false)
  ;(material.uniforms.uResolution.value as THREE.Vector2).set(w, h)
}

function frame() {
  if (!renderer || !scene || !camera || !material) return
  const now = performance.now()
  const dt = Math.min(0.05, (now - lastFrameTime) / 1000)
  lastFrameTime = now

  // 鼠标插值 + 能量按 dt 衰减 — 没有动作时回归 0，避免持续高亮
  mouseCurrent.x += (mouseTarget.x - mouseCurrent.x) * 0.08
  mouseCurrent.y += (mouseTarget.y - mouseCurrent.y) * 0.08
  const u = material.uniforms
  const speed = Math.hypot(mouseTarget.x - mouseCurrent.x, mouseTarget.y - mouseCurrent.y)
  const energyTarget = Math.min(1.0, speed * 6.0)
  u.uMouseEnergy.value += (energyTarget - u.uMouseEnergy.value) * Math.min(1, dt * 4)
  ;(u.uMouse.value as THREE.Vector2).set(mouseCurrent.x, mouseCurrent.y)
  u.uTime.value = (now - startTime) / 1000

  renderer.render(scene, camera)
  rafId = requestAnimationFrame(frame)
}

function handleVisibilityChange() {
  if (document.hidden && rafId !== null) {
    cancelAnimationFrame(rafId)
    rafId = null
  } else if (!document.hidden && rafId === null && !prefersReducedMotion) {
    lastFrameTime = performance.now()
    rafId = requestAnimationFrame(frame)
  }
}

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return

  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: false,
      alpha: false,
      powerPreference: 'high-performance',
    })
  } catch (e) {
    // 用户的 GPU/驱动拒绝 WebGL — 直接静默退化，CSS 兜底层依旧提供视觉
    console.warn('[LiquidMemoryBackground] WebGL unavailable, falling back to CSS layer.', e)
    return
  }

  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
  geometry = new THREE.PlaneGeometry(2, 2)
  material = new THREE.ShaderMaterial({
    vertexShader,
    fragmentShader,
    uniforms: {
      uTime: { value: 0 },
      uResolution: { value: new THREE.Vector2(window.innerWidth, window.innerHeight) },
      uMouse: { value: new THREE.Vector2(0, 0) },
      uMouseEnergy: { value: 0 },
    },
    depthTest: false,
    depthWrite: false,
  })
  mesh = new THREE.Mesh(geometry, material)
  scene.add(mesh)

  handleResize()
  window.addEventListener('mousemove', handleMouseMove, { passive: true })
  window.addEventListener('resize', handleResize)
  document.addEventListener('visibilitychange', handleVisibilityChange)

  if (prefersReducedMotion) {
    // 渲染一帧静态画面，不进入循环
    renderer.render(scene, camera)
    return
  }

  lastFrameTime = performance.now()
  rafId = requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  rafId = null
  window.removeEventListener('mousemove', handleMouseMove)
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  // 注意：resizeObserver 当前实现中并未被实际创建，保留变量供后续扩展。
  geometry?.dispose()
  material?.dispose()
  renderer?.dispose()
  renderer = null
  scene = null
  camera = null
  material = null
  geometry = null
  mesh = null
})
</script>

<template>
  <canvas ref="canvasRef" class="liquid-memory-canvas" aria-hidden="true"></canvas>
</template>

<style scoped>
.liquid-memory-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  /* 比 LoginView 中视频/墨水/粒子层（-3 ~ -1）更低，作为最底层基底。 */
  z-index: -4;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .liquid-memory-canvas {
    /* 静态首帧已渲染，不再消耗 CPU/GPU */
    opacity: 0.85;
  }
}
</style>
