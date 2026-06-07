import { ref, onUnmounted, watch, type Ref } from 'vue'

/**
 * Mnemoscape 16+ 天气/氛围特效引擎
 * ──────────────────────────────────────────────────────────
 * 现有 8 种:
 *   cherry_blossoms / snow / falling_leaves / fireflies
 *   lightning / aurora / meteor_shower / none
 *
 * 新增 8 种 (v2):
 *   rain / bubbles / stardust / petals_rose
 *   embers / fog / bokeh / crystal_snow
 *
 * 总计 16 种特效 + 1 个 none 占位，picker 中显示 16+ 个选项
 * ──────────────────────────────────────────────────────────
 */
export type WeatherEffect =
  | 'none'
  | 'cherry_blossoms'
  | 'lightning'
  | 'falling_leaves'
  | 'snow'
  | 'fireflies'
  | 'aurora'
  | 'meteor_shower'
  | 'rain'
  | 'bubbles'
  | 'stardust'
  | 'petals_rose'
  | 'embers'
  | 'fog'
  | 'bokeh'
  | 'crystal_snow'

export const WEATHER_EFFECTS: { key: WeatherEffect; label: string; icon: string }[] = [
  { key: 'none', label: '无', icon: '⊘' },
  { key: 'cherry_blossoms', label: '桃花雨', icon: '🌸' },
  { key: 'snow', label: '白雪皑皑', icon: '❄' },
  { key: 'crystal_snow', label: '冰晶纷飞', icon: '✧' },
  { key: 'falling_leaves', label: '风吹落叶', icon: '🍂' },
  { key: 'petals_rose', label: '玫瑰花瓣', icon: '🥀' },
  { key: 'rain', label: '潇潇夜雨', icon: '☂' },
  { key: 'fireflies', label: '萤火虫', icon: '✦' },
  { key: 'embers', label: '余烬火星', icon: '𓍢' },
  { key: 'stardust', label: '星尘低语', icon: '✩' },
  { key: 'bubbles', label: '气泡飘升', icon: '○' },
  { key: 'bokeh', label: '梦幻光斑', icon: '◉' },
  { key: 'fog', label: '晨雾弥漫', icon: '☁' },
  { key: 'lightning', label: '闪电风暴', icon: '⚡' },
  { key: 'aurora', label: '极光', icon: '🌌' },
  { key: 'meteor_shower', label: '流星雨', icon: '☄' },
]

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  size: number
  opacity: number
  rotation: number
  rotationSpeed: number
  life: number
  age: number
  hue?: number
  shape?: number
}

const activeEffect = ref<WeatherEffect>('none')

export function useWeatherFx(canvasRef: Ref<HTMLCanvasElement | null>) {
  let ctx: CanvasRenderingContext2D | null = null
  let animId = 0
  let particles: Particle[] = []
  let w = 0
  let h = 0
  let lightningFlash = 0
  let lightningTimer = 0
  let auroraPhase = 0
  let fogPhase = 0
  let embersPhase = 0

  function resize() {
    if (!canvasRef.value) return
    w = window.innerWidth
    h = window.innerHeight
    canvasRef.value.width = w
    canvasRef.value.height = h
  }

  function init() {
    if (!canvasRef.value) return
    ctx = canvasRef.value.getContext('2d')
    resize()
    window.addEventListener('resize', resize)
    animate()
  }

  function setEffect(effect: WeatherEffect) {
    activeEffect.value = effect
    particles = []
    lightningFlash = 0
    auroraPhase = 0
    fogPhase = 0
    embersPhase = 0
  }

  function spawnParticles() {
    const max = getMaxParticles()
    while (particles.length < max) {
      particles.push(createParticle())
    }
  }

  function getMaxParticles(): number {
    switch (activeEffect.value) {
      case 'cherry_blossoms': return 80
      case 'snow': return 150
      case 'crystal_snow': return 110
      case 'falling_leaves': return 60
      case 'petals_rose': return 50
      case 'rain': return 140
      case 'fireflies': return 50
      case 'stardust': return 90
      case 'bubbles': return 40
      case 'bokeh': return 28
      case 'embers': return 70
      case 'meteor_shower': return 8
      default: return 0
    }
  }

  function createParticle(): Particle {
    const effect = activeEffect.value
    const base = {
      x: 0, y: 0, vx: 0, vy: 0, size: 0, opacity: 0,
      rotation: 0, rotationSpeed: 0, life: 1, age: 0,
    }
    switch (effect) {
      case 'cherry_blossoms':
        return {
          ...base,
          x: Math.random() * w,
          y: -20 - Math.random() * 100,
          vx: 0.3 + Math.random() * 0.8,
          vy: 0.8 + Math.random() * 1.2,
          size: 6 + Math.random() * 8,
          opacity: 0.5 + Math.random() * 0.4,
          rotation: Math.random() * Math.PI * 2,
          rotationSpeed: (Math.random() - 0.5) * 0.04,
          hue: 340 + Math.random() * 20,
        }
      case 'snow':
        return {
          ...base,
          x: Math.random() * w,
          y: -10 - Math.random() * 50,
          vx: (Math.random() - 0.5) * 0.5,
          vy: 0.5 + Math.random() * 1.5,
          size: 2 + Math.random() * 4,
          opacity: 0.4 + Math.random() * 0.5,
        }
      case 'crystal_snow':
        // 棱形冰晶（与 snow 不同：六角星 + 蓝白色调）
        return {
          ...base,
          x: Math.random() * w,
          y: -20 - Math.random() * 80,
          vx: (Math.random() - 0.5) * 0.4,
          vy: 0.3 + Math.random() * 0.9,
          size: 4 + Math.random() * 6,
          opacity: 0.55 + Math.random() * 0.4,
          rotation: Math.random() * Math.PI * 2,
          rotationSpeed: (Math.random() - 0.5) * 0.025,
          shape: Math.floor(Math.random() * 2),
        }
      case 'falling_leaves':
        return {
          ...base,
          x: Math.random() * w,
          y: -30 - Math.random() * 80,
          vx: 0.5 + Math.random() * 1.5,
          vy: 1.0 + Math.random() * 1.5,
          size: 10 + Math.random() * 12,
          opacity: 0.6 + Math.random() * 0.3,
          rotation: Math.random() * Math.PI * 2,
          rotationSpeed: (Math.random() - 0.5) * 0.06,
          hue: 15 + Math.floor(Math.random() * 30),
        }
      case 'petals_rose':
        // 玫瑰花瓣（与 cherry_blossoms 不同：深红/酒红色调 + 较大 + 慢速旋转）
        return {
          ...base,
          x: Math.random() * w,
          y: -40 - Math.random() * 120,
          vx: 0.2 + Math.random() * 0.6,
          vy: 0.5 + Math.random() * 1.0,
          size: 9 + Math.random() * 10,
          opacity: 0.55 + Math.random() * 0.35,
          rotation: Math.random() * Math.PI * 2,
          rotationSpeed: (Math.random() - 0.5) * 0.035,
          hue: 340 + Math.random() * 20,  // 350° ~ 10°（红到深红）
        }
      case 'rain':
        // 重力雨滴（细线 + 斜向）
        return {
          ...base,
          x: Math.random() * w * 1.2 - w * 0.1,
          y: -50 - Math.random() * 100,
          vx: 1.2 + Math.random() * 0.6,  // 斜向右上
          vy: 8 + Math.random() * 6,        // 高速下落
          size: 1 + Math.random() * 1.5,
          opacity: 0.35 + Math.random() * 0.35,
        }
      case 'fireflies':
        return {
          ...base,
          x: Math.random() * w,
          y: h * 0.3 + Math.random() * h * 0.6,
          vx: (Math.random() - 0.5) * 0.8,
          vy: (Math.random() - 0.5) * 0.5,
          size: 3 + Math.random() * 3,
          opacity: Math.random(),
        }
      case 'stardust':
        // 极慢飘动的星尘（小亮点 + 慢速 + 整体轻微向上）
        return {
          ...base,
          x: Math.random() * w,
          y: h + Math.random() * 60,
          vx: (Math.random() - 0.5) * 0.2,
          vy: -0.15 - Math.random() * 0.25,
          size: 1.5 + Math.random() * 2.5,
          opacity: 0.3 + Math.random() * 0.5,
          shape: Math.floor(Math.random() * 2),
        }
      case 'bubbles':
        // 缓慢上升的气泡（带高光）
        return {
          ...base,
          x: Math.random() * w,
          y: h + 20 + Math.random() * 60,
          vx: (Math.random() - 0.5) * 0.3,
          vy: -0.6 - Math.random() * 0.8,
          size: 6 + Math.random() * 14,
          opacity: 0.18 + Math.random() * 0.3,
        }
      case 'bokeh':
        // 大块光斑漂浮（柔光 + 慢速）
        return {
          ...base,
          x: Math.random() * w,
          y: Math.random() * h,
          vx: (Math.random() - 0.5) * 0.25,
          vy: (Math.random() - 0.5) * 0.15,
          size: 28 + Math.random() * 38,
          opacity: 0.08 + Math.random() * 0.18,
          shape: Math.floor(Math.random() * 3),
        }
      case 'embers':
        // 余烬火星向上飘（带尾迹）
        return {
          ...base,
          x: Math.random() * w,
          y: h + 20 + Math.random() * 40,
          vx: (Math.random() - 0.5) * 0.5,
          vy: -1.0 - Math.random() * 1.2,
          size: 1.5 + Math.random() * 2.5,
          opacity: 0.6 + Math.random() * 0.3,
          shape: Math.floor(Math.random() * 2),
        }
      case 'meteor_shower':
        return {
          ...base,
          x: Math.random() * w * 0.8,
          y: -20,
          vx: 4 + Math.random() * 6,
          vy: 3 + Math.random() * 5,
          size: 2 + Math.random() * 2,
          opacity: 0.8 + Math.random() * 0.2,
        }
      default:
        return base
    }
  }

  function updateAndDraw() {
    if (!ctx) return
    ctx.clearRect(0, 0, w, h)

    const effect = activeEffect.value
    if (effect === 'none') return
    if (effect === 'aurora') {
      drawAurora()
      return
    }
    if (effect === 'lightning') {
      drawLightning()
      return
    }
    if (effect === 'fog') {
      // fog: 整体雾层 + 少量漂浮尘埃
      drawFogOverlay()
      spawnParticles()
    } else {
      spawnParticles()
    }

    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i]
      p.age += 1
      p.x += p.vx
      p.y += p.vy
      p.rotation += p.rotationSpeed

      // 物理效果分桶
      if (effect === 'fireflies') {
        p.vx += (Math.random() - 0.5) * 0.1
        p.vy += (Math.random() - 0.5) * 0.08
        p.vx *= 0.98
        p.vy *= 0.98
        p.opacity = 0.3 + Math.sin(Date.now() * 0.003 + i) * 0.5
      } else if (effect === 'cherry_blossoms' || effect === 'falling_leaves' || effect === 'petals_rose') {
        p.vx += Math.sin(Date.now() * 0.001 + i) * 0.02
      } else if (effect === 'meteor_shower') {
        p.life -= 0.015
        p.opacity = p.life
      } else if (effect === 'bubbles') {
        // 气泡轻微左右摆动
        p.vx += Math.sin(Date.now() * 0.002 + i) * 0.015
        p.vx *= 0.98
        // 透明度随高度变化（中间最亮）
        const midY = h * 0.5
        const dist = Math.abs(p.y - midY) / h
        p.opacity = 0.18 + 0.18 * (1 - dist)
      } else if (effect === 'bokeh') {
        // 光斑呼吸效果
        p.opacity = (0.08 + 0.18 * (0.5 + Math.sin(Date.now() * 0.0008 + i) * 0.5)) * (p.size / 50)
      } else if (effect === 'stardust') {
        // 星尘闪烁
        p.opacity = 0.3 + 0.4 * (0.5 + Math.sin(Date.now() * 0.002 + i * 0.3) * 0.5)
      } else if (effect === 'embers') {
        // 余烬拖尾衰减
        p.opacity = Math.max(0, p.opacity - 0.001)
        p.vx += (Math.random() - 0.5) * 0.05
      } else if (effect === 'crystal_snow') {
        // 冰晶缓慢旋转
        p.rotationSpeed *= 0.999
      } else if (effect === 'rain') {
        // 雨：不需要更多物理
      } else if (effect === 'fog') {
        // fog 内的尘埃轻微漂浮
        p.vx += Math.sin(Date.now() * 0.0005 + i) * 0.005
        p.opacity = 0.04 + 0.06 * (0.5 + Math.sin(Date.now() * 0.001 + i) * 0.5)
      }

      // 出界或寿命到
      if (
        p.y > h + 30 || p.x > w + 30 || p.x < -30 || p.life <= 0
        || (effect === 'embers' && p.opacity <= 0.02)
        || (effect === 'bubbles' && p.y < -40)
        || (effect === 'stardust' && p.y < -20)
        || (effect === 'embers' && p.y < -40)
      ) {
        particles.splice(i, 1)
        continue
      }

      drawParticle(p, effect)
    }
  }

  function drawParticle(p: Particle, effect: WeatherEffect) {
    if (!ctx) return
    ctx.save()
    ctx.globalAlpha = Math.max(0, Math.min(1, p.opacity))
    ctx.translate(p.x, p.y)
    ctx.rotate(p.rotation)

    switch (effect) {
      case 'cherry_blossoms':
      case 'petals_rose': {
        // 5 瓣花
        const hue = p.hue ?? (effect === 'petals_rose' ? 350 : 350)
        const sat = effect === 'petals_rose' ? 60 : 80
        const lit = effect === 'petals_rose' ? 38 : 75
        ctx.fillStyle = `hsl(${hue}, ${sat}%, ${lit}%)`
        ctx.beginPath()
        for (let j = 0; j < 5; j++) {
          const angle = (j / 5) * Math.PI * 2
          const r = p.size * 0.5
          ctx.ellipse(
            Math.cos(angle) * r * 0.3,
            Math.sin(angle) * r * 0.3,
            r * 0.4, r * 0.2,
            angle, 0, Math.PI * 2
          )
        }
        ctx.fill()
        break
      }
      case 'snow':
        ctx.fillStyle = '#ffffff'
        ctx.beginPath()
        ctx.arc(0, 0, p.size, 0, Math.PI * 2)
        ctx.fill()
        break
      case 'crystal_snow': {
        // 冰晶：六角星 + 中心白点
        ctx.strokeStyle = 'rgba(220, 235, 255, 0.9)'
        ctx.lineWidth = 1.2
        ctx.beginPath()
        for (let k = 0; k < 6; k++) {
          const a = (k / 6) * Math.PI * 2
          ctx.moveTo(0, 0)
          ctx.lineTo(Math.cos(a) * p.size, Math.sin(a) * p.size)
        }
        ctx.stroke()
        ctx.fillStyle = 'rgba(255, 255, 255, 0.95)'
        ctx.beginPath()
        ctx.arc(0, 0, p.size * 0.15, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'falling_leaves': {
        const hue = p.hue ?? 25
        ctx.fillStyle = `hsl(${hue}, 70%, 45%)`
        ctx.beginPath()
        ctx.ellipse(0, 0, p.size * 0.6, p.size * 0.3, 0, 0, Math.PI * 2)
        ctx.fill()
        ctx.strokeStyle = `hsl(${hue}, 50%, 35%)`
        ctx.lineWidth = 0.5
        ctx.beginPath()
        ctx.moveTo(-p.size * 0.5, 0)
        ctx.lineTo(p.size * 0.5, 0)
        ctx.stroke()
        break
      }
      case 'rain': {
        // 细线雨丝
        ctx.strokeStyle = `rgba(180, 210, 255, ${p.opacity})`
        ctx.lineWidth = p.size
        ctx.lineCap = 'round'
        ctx.beginPath()
        ctx.moveTo(0, 0)
        ctx.lineTo(-p.vx * 1.5, p.vy * 1.5)
        ctx.stroke()
        break
      }
      case 'fireflies': {
        const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, p.size * 2)
        gradient.addColorStop(0, 'rgba(200, 255, 100, 0.9)')
        gradient.addColorStop(0.4, 'rgba(180, 255, 60, 0.4)')
        gradient.addColorStop(1, 'rgba(180, 255, 60, 0)')
        ctx.fillStyle = gradient
        ctx.beginPath()
        ctx.arc(0, 0, p.size * 2, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'stardust': {
        // 4 角星或圆点
        if (p.shape === 0) {
          ctx.fillStyle = `rgba(255, 255, 220, ${p.opacity})`
          ctx.beginPath()
          ctx.arc(0, 0, p.size, 0, Math.PI * 2)
          ctx.fill()
        } else {
          ctx.fillStyle = `rgba(180, 220, 255, ${p.opacity})`
          ctx.beginPath()
          for (let k = 0; k < 4; k++) {
            const a = (k / 4) * Math.PI * 2
            const r = p.size
            ctx.moveTo(0, 0)
            ctx.lineTo(Math.cos(a) * r, Math.sin(a) * r)
            ctx.lineTo(Math.cos(a + Math.PI / 4) * r * 0.3, Math.sin(a + Math.PI / 4) * r * 0.3)
          }
          ctx.closePath()
          ctx.fill()
        }
        break
      }
      case 'bubbles': {
        // 空心圆 + 高光
        ctx.strokeStyle = `rgba(180, 220, 255, ${p.opacity * 1.2})`
        ctx.lineWidth = 1
        ctx.beginPath()
        ctx.arc(0, 0, p.size, 0, Math.PI * 2)
        ctx.stroke()
        // 高光
        ctx.fillStyle = `rgba(255, 255, 255, ${p.opacity * 1.5})`
        ctx.beginPath()
        ctx.arc(-p.size * 0.3, -p.size * 0.3, p.size * 0.2, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'bokeh': {
        // 大柔光圆
        const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, p.size)
        const hue = (p.shape ?? 0) * 60 + 200
        gradient.addColorStop(0, `hsla(${hue}, 80%, 70%, ${p.opacity * 1.5})`)
        gradient.addColorStop(0.5, `hsla(${hue}, 80%, 50%, ${p.opacity * 0.5})`)
        gradient.addColorStop(1, `hsla(${hue}, 80%, 50%, 0)`)
        ctx.fillStyle = gradient
        ctx.beginPath()
        ctx.arc(0, 0, p.size, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'embers': {
        // 火星 + 短尾迹
        const tailLen = p.size * 6
        const g = ctx.createLinearGradient(0, 0, 0, tailLen)
        g.addColorStop(0, `rgba(255, 200, 80, ${p.opacity})`)
        g.addColorStop(0.5, `rgba(255, 100, 40, ${p.opacity * 0.5})`)
        g.addColorStop(1, `rgba(255, 50, 0, 0)`)
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(0, 0, p.size, 0, Math.PI * 2)
        ctx.fill()
        ctx.fillStyle = `rgba(255, 230, 150, ${p.opacity * 1.5})`
        ctx.beginPath()
        ctx.arc(0, 0, p.size * 0.5, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'fog': {
        // 雾中漂浮的尘埃（小圆点）
        ctx.fillStyle = `rgba(220, 230, 240, ${p.opacity})`
        ctx.beginPath()
        ctx.arc(0, 0, p.size, 0, Math.PI * 2)
        ctx.fill()
        break
      }
      case 'meteor_shower': {
        const tailLen = 40 + Math.random() * 30
        const mg = ctx.createLinearGradient(0, 0, -tailLen, -tailLen * 0.6)
        mg.addColorStop(0, `rgba(255, 255, 255, ${p.opacity})`)
        mg.addColorStop(1, 'rgba(255, 255, 255, 0)')
        ctx.strokeStyle = mg
        ctx.lineWidth = p.size
        ctx.lineCap = 'round'
        ctx.beginPath()
        ctx.moveTo(0, 0)
        ctx.lineTo(-tailLen, -tailLen * 0.6)
        ctx.stroke()
        break
      }
    }

    ctx.restore()
  }

  function drawAurora() {
    if (!ctx) return
    auroraPhase += 0.005
    const bands = 3
    for (let b = 0; b < bands; b++) {
      ctx.beginPath()
      const baseY = h * 0.05 + b * 40
      const hue = (120 + b * 60 + auroraPhase * 30) % 360
      ctx.strokeStyle = `hsla(${hue}, 80%, 55%, 0.15)`
      ctx.lineWidth = 30 + b * 10
      ctx.lineCap = 'round'
      ctx.moveTo(0, baseY)
      for (let x = 0; x <= w; x += 20) {
        const y = baseY + Math.sin(x * 0.003 + auroraPhase + b) * 30
          + Math.sin(x * 0.007 + auroraPhase * 1.5) * 15
        ctx.lineTo(x, y)
      }
      ctx.stroke()
    }
  }

  function drawLightning() {
    if (!ctx) return
    lightningTimer++

    if (lightningTimer > 120 + Math.random() * 200) {
      lightningFlash = 1
      lightningTimer = 0
      drawLightningBolt()
    }

    if (lightningFlash > 0) {
      ctx.fillStyle = `rgba(255, 255, 255, ${lightningFlash * 0.15})`
      ctx.fillRect(0, 0, w, h)
      lightningFlash *= 0.85
      if (lightningFlash < 0.01) lightningFlash = 0
    }
  }

  function drawLightningBolt() {
    if (!ctx) return
    const startX = w * 0.2 + Math.random() * w * 0.6
    let x = startX
    let y = 0
    const endY = h * (0.3 + Math.random() * 0.4)

    ctx.strokeStyle = 'rgba(200, 220, 255, 0.9)'
    ctx.lineWidth = 2
    ctx.shadowColor = 'rgba(150, 180, 255, 0.8)'
    ctx.shadowBlur = 15
    ctx.beginPath()
    ctx.moveTo(x, y)

    while (y < endY) {
      x += (Math.random() - 0.5) * 40
      y += 10 + Math.random() * 20
      ctx.lineTo(x, y)
    }
    ctx.stroke()
    ctx.shadowBlur = 0
  }

  /**
   * 雾的整体层：水平条带半透明白雾
   */
  function drawFogOverlay() {
    if (!ctx) return
    fogPhase += 0.002
    const layers = 5
    for (let i = 0; i < layers; i++) {
      const baseY = (h / layers) * i + (h / layers) * 0.3
      ctx.beginPath()
      const grad = ctx.createLinearGradient(0, baseY - 60, 0, baseY + 60)
      grad.addColorStop(0, 'rgba(180, 200, 220, 0)')
      grad.addColorStop(0.5, `rgba(200, 220, 235, ${0.06 + 0.03 * Math.sin(fogPhase + i)})`)
      grad.addColorStop(1, 'rgba(180, 200, 220, 0)')
      ctx.fillStyle = grad
      ctx.moveTo(0, baseY)
      for (let x = 0; x <= w; x += 30) {
        const y = baseY + Math.sin(x * 0.004 + fogPhase + i) * 25
        ctx.lineTo(x, y)
      }
      ctx.lineTo(w, baseY + 80)
      ctx.lineTo(0, baseY + 80)
      ctx.closePath()
      ctx.fill()
    }
  }

  function animate() {
    updateAndDraw()
    animId = requestAnimationFrame(animate)
  }

  function dispose() {
    cancelAnimationFrame(animId)
    window.removeEventListener('resize', resize)
    particles = []
  }

  watch(activeEffect, () => {
    particles = []
    lightningFlash = 0
    lightningTimer = 0
    auroraPhase = 0
    fogPhase = 0
    embersPhase = 0
  })

  onUnmounted(dispose)

  return {
    init,
    setEffect,
    activeEffect,
    dispose,
  }
}

export { activeEffect as globalWeatherEffect }
