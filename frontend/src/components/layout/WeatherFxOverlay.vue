<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useWeatherFx, WEATHER_EFFECTS, type WeatherEffect } from '../../composables/useWeatherFx'

const canvasRef = ref<HTMLCanvasElement | null>(null)
const { init, setEffect, activeEffect } = useWeatherFx(canvasRef)
const showPicker = ref(false)
const autoRotate = ref(true)
let rotationTimer: number | null = null

// 16 种特效 + 1 个 none 占位（来自 useWeatherFx）
const effects = WEATHER_EFFECTS

// 自动轮换序列（取 8 种风格差异较大的，5 分钟切换一次）
const rotationSequence: WeatherEffect[] = [
  'fireflies',
  'cherry_blossoms',
  'rain',
  'snow',
  'stardust',
  'petals_rose',
  'aurora',
  'bubbles',
  'meteor_shower',
  'bokeh',
  'fog',
  'embers',
  'falling_leaves',
  'crystal_snow',
]

let currentRotationIndex = 0

onMounted(() => {
  init()
  startAutoRotation()
})

onUnmounted(() => {
  stopAutoRotation()
})

function startAutoRotation() {
  if (!autoRotate.value) return
  // 初始随机选择一个效果
  currentRotationIndex = Math.floor(Math.random() * rotationSequence.length)
  setEffect(rotationSequence[currentRotationIndex])
  // 每 4 分钟切换一次
  rotationTimer = window.setInterval(() => {
    if (autoRotate.value) {
      currentRotationIndex = (currentRotationIndex + 1) % rotationSequence.length
      setEffect(rotationSequence[currentRotationIndex])
    }
  }, 4 * 60 * 1000)
}

function stopAutoRotation() {
  if (rotationTimer !== null) {
    window.clearInterval(rotationTimer)
    rotationTimer = null
  }
}

function pick(effect: WeatherEffect) {
  setEffect(effect)
  showPicker.value = false
  // 用户手动选择时，暂停自动轮换
  autoRotate.value = false
  stopAutoRotation()
}

function toggleAutoRotate() {
  autoRotate.value = !autoRotate.value
  if (autoRotate.value) {
    startAutoRotation()
  } else {
    stopAutoRotation()
  }
  showPicker.value = false
}
</script>

<template>
  <canvas ref="canvasRef" class="weather-canvas" aria-hidden="true"></canvas>
  <div class="weather-toggle">
    <button
      class="weather-toggle__btn"
      :class="{ 'weather-toggle__btn--auto': autoRotate }"
      :title="autoRotate ? '自动轮换中（4分钟）' : activeEffect === 'none' ? '开启天气特效' : '切换天气特效'"
      @click="showPicker = !showPicker"
    >
      {{ effects.find(e => e.key === activeEffect)?.icon || '⊘' }}
    </button>
    <transition name="picker">
      <div v-if="showPicker" class="weather-picker" role="dialog" aria-label="天气特效选择器">
        <div class="weather-picker__header">
          <span class="weather-picker__title">环境特效 · {{ effects.length }} 种</span>
        </div>
        <button
          class="weather-picker__item weather-picker__item--auto"
          :class="{ 'weather-picker__item--active': autoRotate }"
          @click="toggleAutoRotate"
        >
          <span class="weather-picker__icon">🔄</span>
          <span class="weather-picker__label">{{ autoRotate ? '停止自动' : '自动轮换' }}</span>
        </button>
        <div class="weather-picker__divider"></div>
        <div class="weather-picker__grid">
          <button
            v-for="fx in effects"
            :key="fx.key"
            class="weather-picker__item"
            :class="{ 'weather-picker__item--active': activeEffect === fx.key && !autoRotate }"
            :title="fx.label"
            @click="pick(fx.key)"
          >
            <span class="weather-picker__icon">{{ fx.icon }}</span>
            <span class="weather-picker__label">{{ fx.label }}</span>
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.weather-canvas {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  pointer-events: none;
  z-index: 9990;
}

.weather-toggle {
  position: fixed;
  bottom: 100px;
  left: 20px;
  z-index: 9991;
}

.weather-toggle__btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(10, 14, 22, 0.75);
  backdrop-filter: blur(12px);
  color: var(--text);
  font-size: 1.1rem;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: all 0.2s ease;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.weather-toggle__btn:hover {
  border-color: var(--primary);
  transform: scale(1.1);
}

.weather-toggle__btn--auto {
  border-color: var(--gold);
  animation: pulse 2.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3); }
  50% { box-shadow: 0 4px 24px var(--gold); }
}

.weather-picker {
  position: absolute;
  bottom: 52px;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border-radius: 14px;
  background: rgba(10, 14, 22, 0.94);
  backdrop-filter: blur(18px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
  width: 248px;
  max-height: 70vh;
  overflow-y: auto;
}

.weather-picker__header {
  padding: 2px 6px 6px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  margin-bottom: 4px;
}

.weather-picker__title {
  font-size: 0.72rem;
  color: var(--text-muted);
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.weather-picker__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
}

.weather-picker__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  font-size: 0.78rem;
  cursor: pointer;
  transition: background 0.15s ease;
  min-width: 0;
}

.weather-picker__item:hover {
  background: rgba(255, 255, 255, 0.06);
}

.weather-picker__item--active {
  background: rgba(54, 216, 180, 0.18);
  color: var(--primary);
  box-shadow: 0 0 0 1px rgba(54, 216, 180, 0.32);
}

.weather-picker__item--auto {
  font-weight: 600;
  width: 100%;
  grid-column: 1 / -1;
}

.weather-picker__divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.1);
  margin: 4px 0;
}

.weather-picker__icon {
  font-size: 0.95rem;
  width: 20px;
  text-align: center;
  flex-shrink: 0;
}

/* 长中文/英文都允许换行截断 */
.weather-picker__label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
  flex: 1;
}

.picker-enter-active,
.picker-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}
.picker-enter-from,
.picker-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 768px) {
  .weather-picker {
    width: 220px;
  }
  .weather-toggle {
    bottom: 80px;
    left: 12px;
  }
}
</style>
