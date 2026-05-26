<script setup lang="ts">
/**
 * 情绪混沌吸引子（Lorenz Attractor）—— D3.js 太重了，这里用 canvas + RK4 直
 * 解微分方程，画一条随时间演化的发光轨迹。
 *
 *   dx/dt = σ (y − x)
 *   dy/dt = x (ρ − z) − y
 *   dz/dt = x y − β z
 *
 * 把用户五维情绪积分（喜 / 悲 / 恐 / 平 / 怀旧）映射到 σ / ρ / β：
 *   - σ ↑（喜悦） → 系统响应更快，轨迹更"鲜活"
 *   - ρ ↑（怀旧 + 悲伤） → 吸引子"翼"更展开，轨迹绽放与收敛交替
 *   - β ↑（平和） → 收敛速度更大，轨迹更内敛
 *   - 恐惧 → 颜色相位偏移（视觉冷色化）
 *
 * 与传统雷达图相比：每个用户拿到一张独一无二、缓慢演化的"灵魂混沌画"，
 * 同时仍然是合法的可量化分析对象（轨迹包络面积、Lyapunov 估计等可后接）。
 *
 * 性能：
 *  - 每帧推进 6 步 RK4 → 平滑且不发抖
 *  - 用 globalAlpha 累积绘制（不擦布），靠 destination-out 的"烟雾擦除"
 *    层提供自然衰减拖尾，避免 canvas 越画越糊
 *  - tab 隐藏暂停 RAF，prefers-reduced-motion 渲染一帧静止画后退出
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/** 五维情绪积分，约定取值范围 [0, 1]。 */
export interface EmotionProfile {
  joy: number
  sorrow: number
  fear: number
  calm: number
  nostalgia: number
}

const props = withDefaults(
  defineProps<{
    /** 当前用户的五维情绪积分；不传则给一个均衡的兜底分布。 */
    emotion?: EmotionProfile
    /** 单帧推进的 RK4 步数；越大越快、越小越柔。 */
    stepsPerFrame?: number
  }>(),
  {
    emotion: () => ({ joy: 0.6, sorrow: 0.3, fear: 0.2, calm: 0.55, nostalgia: 0.5 }),
    stepsPerFrame: 6,
  },
)

const canvasRef = ref<HTMLCanvasElement | null>(null)
let ctx: CanvasRenderingContext2D | null = null
let rafId: number | null = null
let dpr = 1
let canvasW = 0
let canvasH = 0

// 当前轨迹位置（在 RK4 域里）
const state = { x: 0.1, y: 0, z: 0 }
const dt = 0.006
// 历史尾点 — 用来画连续段
let prev = { ...state }

const prefersReducedMotion =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * 五维情绪 → (σ, ρ, β, 颜色相位)。
 * 取值经过实验校准：让最常见的情绪组合都落在"洛伦兹蝴蝶"形态附近，
 * 既保留混沌特征又不至于飞出画布。
 */
const params = computed(() => {
  const e = props.emotion
  const sigma = 8 + 6 * clamp01(e.joy)               // 7..14
  const rho = 22 + 12 * clamp01(e.nostalgia + 0.5 * e.sorrow) / 1.5 // 22..36
  const beta = 8 / 3 + 1.6 * clamp01(e.calm)         // ~2.67..4.27
  // 颜色相位：恐惧把整体冷色化，怀旧偏暖
  const huePhase = (0.62 * clamp01(e.fear) - 0.5 * clamp01(e.nostalgia)) // [-0.5, 0.62]
  return { sigma, rho, beta, huePhase }
})

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v))
}

/** RK4 单步 — 比简单欧拉法稳定得多，混沌系统里尤其明显。 */
function rk4Step(s: { x: number; y: number; z: number }, h: number,
                 sigma: number, rho: number, beta: number) {
  const f = (x: number, y: number, z: number) => ({
    dx: sigma * (y - x),
    dy: x * (rho - z) - y,
    dz: x * y - beta * z,
  })
  const k1 = f(s.x, s.y, s.z)
  const k2 = f(s.x + 0.5 * h * k1.dx, s.y + 0.5 * h * k1.dy, s.z + 0.5 * h * k1.dz)
  const k3 = f(s.x + 0.5 * h * k2.dx, s.y + 0.5 * h * k2.dy, s.z + 0.5 * h * k2.dz)
  const k4 = f(s.x + h * k3.dx,        s.y + h * k3.dy,        s.z + h * k3.dz)
  s.x += (h / 6) * (k1.dx + 2 * k2.dx + 2 * k3.dx + k4.dx)
  s.y += (h / 6) * (k1.dy + 2 * k2.dy + 2 * k3.dy + k4.dy)
  s.z += (h / 6) * (k1.dz + 2 * k2.dz + 2 * k3.dz + k4.dz)
}

/** 把 3D 状态点投到 2D 画布 — 简单的轴测，避免上 Three.js。 */
function project(x: number, y: number, z: number) {
  // 把 Lorenz 状态的常见范围 [-25, 25] / [0, 50] 映射到画布中心
  const cx = canvasW / 2
  const cy = canvasH / 2
  const scale = Math.min(canvasW, canvasH) * 0.014
  // 简易等距投影：y 提供斜向贡献
  const px = cx + (x - 0.4 * y) * scale
  const py = cy - (z - 25 - 0.3 * y) * scale
  return { px, py }
}

function frame() {
  if (!ctx) return
  const { sigma, rho, beta, huePhase } = params.value

  // 烟雾擦除：用 destination-out 把整张画板的现有 alpha 慢慢吃掉，
  // 实现"墨水自然褪去"效果。比 fillRect 半透明擦除更通透、不偏色。
  ctx.globalCompositeOperation = 'destination-out'
  ctx.fillStyle = 'rgba(0, 0, 0, 0.045)'
  ctx.fillRect(0, 0, canvasW, canvasH)
  ctx.globalCompositeOperation = 'lighter'

  for (let i = 0; i < props.stepsPerFrame; i++) {
    prev.x = state.x; prev.y = state.y; prev.z = state.z
    rk4Step(state, dt, sigma, rho, beta)

    const a = project(prev.x, prev.y, prev.z)
    const b = project(state.x, state.y, state.z)

    // 速度越快的段越亮 — 用步长向量长度调亮度
    const speed = Math.hypot(b.px - a.px, b.py - a.py)
    const alpha = Math.min(0.85, 0.25 + speed * 0.045)

    // 颜色：基于 z 维度 + 情绪相位的 HSL
    // z ∈ [0, 50] 大致映射 hue 从金 (45) → 薄荷 (165) → 紫 (270)
    const baseHue = 45 + (state.z / 50) * 220
    const hue = (baseHue + huePhase * 60 + 360) % 360
    const sat = 78 + 12 * Math.sin(state.x * 0.05)
    const lit = 60

    ctx.strokeStyle = `hsla(${hue}, ${sat}%, ${lit}%, ${alpha})`
    ctx.lineWidth = 1.2
    ctx.beginPath()
    ctx.moveTo(a.px, a.py)
    ctx.lineTo(b.px, b.py)
    ctx.stroke()
  }

  rafId = requestAnimationFrame(frame)
}

function resize() {
  const canvas = canvasRef.value
  if (!canvas || !ctx) return
  const parent = canvas.parentElement
  if (!parent) return
  const rect = parent.getBoundingClientRect()
  dpr = Math.min(window.devicePixelRatio || 1, 1.75)
  canvas.width = Math.floor(rect.width * dpr)
  canvas.height = Math.floor(rect.height * dpr)
  canvas.style.width = `${rect.width}px`
  canvas.style.height = `${rect.height}px`
  canvasW = canvas.width
  canvasH = canvas.height
  // 重置变换并清空 — 缩放变化必须重画背景层
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.fillStyle = 'rgba(8, 10, 14, 1)'
  ctx.fillRect(0, 0, canvasW, canvasH)
}

function onVisibilityChange() {
  if (document.hidden && rafId !== null) {
    cancelAnimationFrame(rafId)
    rafId = null
  } else if (!document.hidden && rafId === null && !prefersReducedMotion) {
    rafId = requestAnimationFrame(frame)
  }
}

// 情绪向量变化时不要清画 — 让"参数缓变 → 轨迹自然漂移"成为视觉特性。
watch(() => props.emotion, () => {
  /* params 已经响应式，下一帧就会按新参数演化 */
}, { deep: true })

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return
  ctx = canvas.getContext('2d')
  if (!ctx) {
    console.warn('[EmotionAttractor] 2D context unavailable')
    return
  }
  resize()
  window.addEventListener('resize', resize)
  document.addEventListener('visibilitychange', onVisibilityChange)

  if (prefersReducedMotion) {
    // 跑 600 步生成一张静止画作为替代，不进入 RAF
    for (let i = 0; i < 600; i++) frame()
    if (rafId !== null) cancelAnimationFrame(rafId)
    rafId = null
    return
  }
  rafId = requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  rafId = null
  window.removeEventListener('resize', resize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  ctx = null
})
</script>

<template>
  <div class="emotion-attractor">
    <canvas ref="canvasRef" class="emotion-attractor__canvas" aria-hidden="true"></canvas>
    <div class="emotion-attractor__legend">
      <span class="emotion-attractor__dot emotion-attractor__dot--gold"></span> 喜悦
      <span class="emotion-attractor__dot emotion-attractor__dot--mint"></span> 平和
      <span class="emotion-attractor__dot emotion-attractor__dot--violet"></span> 怀旧
    </div>
  </div>
</template>

<style scoped>
.emotion-attractor {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 280px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: #08090d;
}

.emotion-attractor__canvas {
  display: block;
  width: 100%;
  height: 100%;
}

.emotion-attractor__legend {
  position: absolute;
  left: 14px;
  bottom: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 0.72rem;
  color: rgba(255, 255, 255, 0.62);
  letter-spacing: 0.5px;
  pointer-events: none;
}

.emotion-attractor__dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: -4px;
  box-shadow: 0 0 8px currentColor;
}

.emotion-attractor__dot--gold   { color: #f2b95c; background: #f2b95c; }
.emotion-attractor__dot--mint   { color: #36d8b4; background: #36d8b4; }
.emotion-attractor__dot--violet { color: #846edc; background: #846edc; }
</style>
