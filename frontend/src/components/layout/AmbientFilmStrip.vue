<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useDynamicMedia } from '../../composables/useDynamicMedia'

// 动态媒体 composable
const dynamicMedia = useDynamicMedia()
const route = useRoute()

// 预设的高清、极简、复古胶片风格背景图（MinIO 为空时用作精美兜底）
const DEFAULT_PHOTOS = [
  {
    name: '星野记忆 · Starry Night',
    url: 'https://images.unsplash.com/photo-1506318137071-a8e063b4bec0?q=80&w=400',
    caption: '“仰望同一片星空，找寻失去的时间。”'
  },
  {
    name: '旧日时光 · Polaroid Cam',
    url: 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?q=80&w=400',
    caption: '“按下快门的那一秒，我们便成为了永恒。”'
  },
  {
    name: '远行足迹 · Nostalgic Train',
    url: 'https://images.unsplash.com/photo-1475924156734-496f6cac6ec1?q=80&w=400',
    caption: '“旅途的终点，是记忆中最温暖的港湾。”'
  },
  {
    name: '静谧黄昏 · Silent Sunset',
    url: 'https://images.unsplash.com/photo-1472214222541-d510753a8707?q=80&w=400',
    caption: '“日落时分，海风吹拂着关于你的诺言。”'
  }
]

// 用户隐藏的拍立得照片 URL 列表
const hiddenUrls = ref<string[]>([])

function hidePhoto(url: string) {
  hiddenUrls.value.push(url)
  // 主动隐藏时，立刻触发一次重洗以补齐/缩减数量
  regeneratePhotos()
}

// 侧边栏当前展示的照片数组 (维护响应式状态以方便定时替换和 Transition)
const activePhotos = ref<Array<{
  id: string
  name: string
  url: string
  caption: string
  side: 'left' | 'right'
  style: any
}>>([])

// 随机挑选图片并计算随机位置的逻辑
function regeneratePhotos() {
  const minioAssets = [...dynamicMedia.state.photos, ...dynamicMedia.state.gifs].map(item => ({
    name: item.name.replace(/\.[^/.]+$/, ''),
    url: item.url,
    caption: `“岁月流转，我们在 ${item.name.slice(0, 8)} 驻足。”`
  }))

  // 合并 MinIO 资产与预设兜底
  const pool = [...minioAssets]
  if (pool.length < 15) {
    DEFAULT_PHOTOS.forEach(p => {
      if (!pool.some(x => x.url === p.url)) {
        pool.push(p)
      }
    })
  }

  // 过滤已隐藏的
  const availablePool = pool.filter(p => !hiddenUrls.value.includes(p.url))
  if (availablePool.length === 0) {
    activePhotos.value = []
    return
  }

  // 随机决定本次展示数量：6 到 10 张
  const count = Math.min(availablePool.length, Math.floor(Math.random() * 5) + 6)

  // 随机挑选 count 个不重复的元素
  const shuffled = [...availablePool].sort(() => 0.5 - Math.random())
  const selected = shuffled.slice(0, count)

  // 均匀分配给左右两侧：前一半放左边，后一半放右边
  const half = Math.ceil(selected.length / 2)
  
  activePhotos.value = selected.map((p, idx) => {
    const side = idx < half ? 'left' : 'right'
    const rowIdx = side === 'left' ? idx : idx - half
    const rowCount = side === 'left' ? half : selected.length - half
    
    // 计算均匀的基础 top 比例 (15% 到 75% 之间均匀错开，避免卡片重叠)，加上随机抖动
    const baseTop = 15 + (rowIdx / Math.max(1, rowCount - 1)) * 62
    const randomJitter = (Math.random() * 8) - 4
    const top = `${baseTop + randomJitter}%`
    
    // 左右偏移随机抖动
    const sideOffset = `${Math.floor(Math.random() * 16) + 12}px` // 12px 到 28px
    
    // 随机倾斜角度
    const rotate = `${(Math.random() * 14) - 7}deg` // -7deg 到 7deg
    
    // 随机浮动延迟
    const animDelay = `${Math.random() * -5}s`
    
    return {
      id: p.url, // 用 url 作为唯一 ID，方便 transition 识别
      name: p.name,
      url: p.url,
      caption: p.caption,
      side,
      style: {
        position: 'absolute',
        top,
        [side]: sideOffset,
        transform: `rotate(${rotate})`,
        animationDelay: animDelay,
        pointerEvents: 'auto'
      }
    }
  })
}

// 稳定计算哈希，绑定动画类名
function hashString(str: string): number {
  let hash = 0
  if (!str) return hash
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  return hash
}

// 把图分发给左右两边的 Computed
const leftPhotos = computed(() => activePhotos.value.filter(p => p.side === 'left'))
const rightPhotos = computed(() => activePhotos.value.filter(p => p.side === 'right'))

// 交互控制
const activeLightboxPhoto = ref<{ name: string; url: string; caption: string } | null>(null)

function openLightbox(photo: { name: string; url: string; caption: string }) {
  activeLightboxPhoto.value = photo
}

function closeLightbox() {
  activeLightboxPhoto.value = null
}

// 隐藏逻辑
const isHidden = computed(() => {
  return ['MemoryAtlas', 'SceneViewer'].includes(String(route.name))
})

// 监视 MinIO 媒体库变化
watch(() => [dynamicMedia.state.photos, dynamicMedia.state.gifs], () => {
  regeneratePhotos()
}, { deep: true })

// 定时微调：每 10 秒随机替换其中的“一张”图片，形成“原位渐变替换”的淡入淡出效果！
let rotationTimer: any = null
function startRotation() {
  rotationTimer = setInterval(() => {
    if (activePhotos.value.length === 0) return
    
    const minioAssets = [...dynamicMedia.state.photos, ...dynamicMedia.state.gifs].map(item => ({
      name: item.name.replace(/\.[^/.]+$/, ''),
      url: item.url,
      caption: `“岁月流转，我们在 ${item.name.slice(0, 8)} 驻足。”`
    }))
    const pool = [...minioAssets]
    DEFAULT_PHOTOS.forEach(p => {
      if (!pool.some(x => x.url === p.url)) pool.push(p)
    })
    
    const currentlyShowingUrls = activePhotos.value.map(p => p.url)
    const candidates = pool.filter(p => !currentlyShowingUrls.includes(p.url) && !hiddenUrls.value.includes(p.url))
    
    if (candidates.length === 0) return
    
    // 随机挑选一张新图
    const newPic = candidates[Math.floor(Math.random() * candidates.length)]
    // 随机决定替换当前展示中的哪一张
    const replaceIdx = Math.floor(Math.random() * activePhotos.value.length)
    const targetToReplace = activePhotos.value[replaceIdx]
    
    // 替换展示图，触发 Transition
    activePhotos.value[replaceIdx] = {
      ...targetToReplace,
      id: newPic.url,
      name: newPic.name,
      url: newPic.url,
      caption: newPic.caption
    }
  }, 10000)
}

import { onMounted, onUnmounted, watch } from 'vue'

onMounted(() => {
  regeneratePhotos()
  startRotation()
})

onUnmounted(() => {
  if (rotationTimer) clearInterval(rotationTimer)
})
</script>

<template>
  <div class="ambient-gallery" :class="{ 'ambient-gallery--hidden': isHidden, 'ambient-gallery--lightbox-active': !!activeLightboxPhoto }">
    <!-- 左侧浮动拍立得胶片 -->
    <div class="ambient-gallery__side ambient-gallery__side--left">
      <div v-for="p in leftPhotos" :key="p.id" class="polaroid-wrapper">
        <transition name="polaroid-fade" mode="out-in">
          <div 
            :key="p.id"
            class="polaroid-card"
            :class="`polaroid-card--float-${(Math.abs(hashString(p.url)) % 4) + 1}`"
            :style="p.style"
            @click="openLightbox(p)"
          >
            <button class="polaroid-card__close" type="button" @click.stop="hidePhoto(p.url)" title="移除此照片">✕</button>
            <div class="polaroid-card__image-container">
              <img :src="p.url" class="polaroid-card__img" :alt="p.name" loading="lazy" />
            </div>
            <div class="polaroid-card__caption">{{ p.name }}</div>
          </div>
        </transition>
      </div>
    </div>

    <!-- 右侧浮动拍立得胶片 -->
    <div class="ambient-gallery__side ambient-gallery__side--right">
      <div v-for="p in rightPhotos" :key="p.id" class="polaroid-wrapper">
        <transition name="polaroid-fade" mode="out-in">
          <div 
            :key="p.id"
            class="polaroid-card"
            :class="`polaroid-card--float-${(Math.abs(hashString(p.url)) % 4) + 1}`"
            :style="p.style"
            @click="openLightbox(p)"
          >
            <button class="polaroid-card__close" type="button" @click.stop="hidePhoto(p.url)" title="移除此照片">✕</button>
            <div class="polaroid-card__image-container">
              <img :src="p.url" class="polaroid-card__img" :alt="p.name" loading="lazy" />
            </div>
            <div class="polaroid-card__caption">{{ p.name }}</div>
          </div>
        </transition>
      </div>
    </div>

    <!-- 拍立得 Lightbox 放大弹窗 (沉浸式毛玻璃) -->
    <transition name="lightbox">
      <div v-if="activeLightboxPhoto" class="lightbox-overlay" @click.self="closeLightbox">
        <div class="lightbox-content">
          <button class="lightbox-close" type="button" @click.stop="closeLightbox" aria-label="关闭预览">✕</button>
          <img :src="activeLightboxPhoto.url" class="lightbox-img" :alt="activeLightboxPhoto.name" />
          <div class="lightbox-meta">
            <h3 class="lightbox-title">{{ activeLightboxPhoto.name }}</h3>
            <p class="lightbox-quote">{{ activeLightboxPhoto.caption }}</p>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
/* 默认仅在 1440px 以上的大宽屏显示侧边记忆胶片，防止小分辨率下压住主体框架 */
.ambient-gallery {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 2;
  transition: opacity 500ms cubic-bezier(0.165, 0.84, 0.44, 1);
}

.ambient-gallery--hidden {
  opacity: 0;
  pointer-events: none !important;
}

.ambient-gallery--lightbox-active {
  z-index: 99999 !important;
  pointer-events: auto !important;
}

@media (max-width: 1439px) {
  .ambient-gallery {
    display: none !important;
  }
}

.ambient-gallery__side {
  position: fixed;
  top: 0;
  bottom: 0;
  width: 240px;
  pointer-events: none;
  z-index: 2;
}

.ambient-gallery__side--left {
  left: 0;
}

.ambient-gallery__side--right {
  right: 0;
}

.polaroid-wrapper {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

/* ---- 拍立得卡片结构设计 (拟真拟物风格) ---- */
.polaroid-card {
  position: relative;
  width: 148px;
  background: var(--surface);
  backdrop-filter: blur(16px) saturate(180%);
  -webkit-backdrop-filter: blur(16px) saturate(180%);
  border-radius: 8px;
  padding: 10px 10px 18px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
  cursor: pointer;
  transform-origin: center center;
  transition: all 400ms cubic-bezier(0.175, 0.885, 0.32, 1.275);
  border: 1px solid rgba(242, 185, 92, 0.18);
}

.polaroid-card__image-container {
  width: 100%;
  height: 120px;
  overflow: hidden;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.polaroid-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.6s ease;
}

/* handwriting caption */
.polaroid-card__caption {
  margin-top: 12px;
  font-family: "STXingkai", "华文行楷", 'KaiTi', cursive;
  font-size: 13px;
  font-weight: 600;
  color: var(--gold);
  text-shadow: 0 0 6px rgba(242, 185, 92, 0.3);
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.polaroid-card__close {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.85);
  border: 1px solid rgba(242, 185, 92, 0.25);
  color: var(--text-soft);
  font-size: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: all 0.2s ease;
  z-index: 5;
}

.polaroid-card:hover .polaroid-card__close {
  opacity: 1;
}

.polaroid-card__close:hover {
  background: #ef4444;
  border-color: #ef4444;
  color: #ffffff;
  transform: scale(1.15);
}

/* 悬停三维抬升 + 摆正 + 拟真发光 */
.polaroid-card:hover {
  transform: scale(1.18) rotate(0deg) translateY(-8px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.6),
              0 0 22px rgba(242, 185, 92, 0.32);
  border-color: rgba(242, 185, 92, 0.55);
  z-index: 10;
}
.polaroid-card:hover .polaroid-card__img {
  transform: scale(1.1);
}

/* ---- 四种不同的错落倾斜与慢速浮动动画 ---- */
@keyframes float-1 {
  0% { transform: rotate(-5deg) translateY(0); }
  50% { transform: rotate(-4deg) translateY(-8px); }
  100% { transform: rotate(-5deg) translateY(0); }
}
@keyframes float-2 {
  0% { transform: rotate(4deg) translateY(0); }
  50% { transform: rotate(5deg) translateY(-10px); }
  100% { transform: rotate(4deg) translateY(0); }
}
@keyframes float-3 {
  0% { transform: rotate(-3deg) translateY(0); }
  50% { transform: rotate(-2deg) translateY(-9px); }
  100% { transform: rotate(-3deg) translateY(0); }
}
@keyframes float-4 {
  0% { transform: rotate(5deg) translateY(0); }
  50% { transform: rotate(4deg) translateY(-7px); }
  100% { transform: rotate(5deg) translateY(0); }
}

.polaroid-card--float-1 { animation: float-1 7s ease-in-out infinite; }
.polaroid-card--float-2 { animation: float-2 8s ease-in-out infinite; }
.polaroid-card--float-3 { animation: float-3 9s ease-in-out infinite; }
.polaroid-card--float-4 { animation: float-4 7.5s ease-in-out infinite; }

/* 悬停时暂时停用浮动关键帧，由 hover 的 transition 代替 */
.polaroid-card:hover {
  animation-play-state: paused !important;
}

/* ---- Lightbox 放大预览弹窗 ---- */
.lightbox-overlay {
  position: fixed;
  inset: 0;
  background: rgba(4, 6, 10, 0.7);
  backdrop-filter: blur(25px);
  z-index: 99999;
  display: grid;
  place-items: center;
  padding: 30px;
  cursor: zoom-out;
  pointer-events: auto;
}

.lightbox-content {
  position: relative;
  background: var(--surface);
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
  border-radius: 12px;
  padding: 16px 16px 24px;
  max-width: 520px;
  width: 90vw;
  box-shadow: 0 25px 60px rgba(0, 0, 0, 0.85);
  cursor: default;
  display: flex;
  flex-direction: column;
  gap: 14px;
  animation: lightbox-scale 350ms cubic-bezier(0.34, 1.56, 0.64, 1) both;
  border: 1px solid rgba(242, 185, 92, 0.28);
}

@keyframes lightbox-scale {
  from { transform: scale(0.85); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.lightbox-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.85);
  border: 1px solid rgba(242, 185, 92, 0.25);
  color: var(--text-soft);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  transition: all 0.15s ease;
  z-index: 10;
}
.lightbox-close:hover {
  background: #ef4444;
  border-color: #ef4444;
  color: #ffffff;
  transform: scale(1.15);
}

.lightbox-img {
  width: 100%;
  max-height: 480px;
  object-fit: contain;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.lightbox-meta {
  padding: 0 6px;
  text-align: center;
}
.lightbox-title {
  margin: 0;
  font-size: 16px;
  color: var(--text);
  font-weight: 700;
  font-family: var(--font-display);
}
.lightbox-quote {
  margin: 8px 0 0;
  font-family: "STXingkai", "华文行楷", 'KaiTi', cursive;
  font-size: 14px;
  color: var(--gold);
  text-shadow: 0 0 6px rgba(242, 185, 92, 0.25);
}

/* Lightbox Transitions */
.lightbox-enter-active,
.lightbox-leave-active {
  transition: opacity 300ms ease;
}
.lightbox-enter-from,
.lightbox-leave-to {
  opacity: 0;
}

/* polaroid-fade transition */
.polaroid-fade-enter-active,
.polaroid-fade-leave-active {
  transition: all 600ms cubic-bezier(0.165, 0.84, 0.44, 1);
}
.polaroid-fade-enter-from {
  opacity: 0;
  transform: scale(0.8) rotate(-5deg);
}
.polaroid-fade-leave-to {
  opacity: 0;
  transform: scale(0.8) rotate(5deg);
}
</style>
