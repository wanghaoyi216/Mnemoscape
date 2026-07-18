<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useDynamicMedia } from '../../composables/useDynamicMedia'
import { memoryCovers } from '../../assets/media-catalog'

// 动态媒体 composable
const dynamicMedia = useDynamicMedia()
const route = useRoute()

// 指定文件夹中已生成的 13–32 号记忆封面。远端资源池不可用时也不再访问外链。
const DEFAULT_PHOTOS = memoryCovers.map((asset) => ({
  name: asset.origin,
  url: asset.src,
  caption: `“${asset.role} · 编号 ${asset.number}”`,
}))

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
  style: Record<string, string>
}>>([])

const PHOTO_SLOTS = [
  { side: 'left', top: '18%', offset: '20px', rotate: '-3deg', delay: '-1.2s' },
  { side: 'left', top: '47%', offset: '32px', rotate: '2deg', delay: '-3.8s' },
  { side: 'left', top: '76%', offset: '18px', rotate: '-2deg', delay: '-2.4s' },
  { side: 'right', top: '18%', offset: '22px', rotate: '3deg', delay: '-2.9s' },
  { side: 'right', top: '47%', offset: '16px', rotate: '-2deg', delay: '-1.7s' },
  { side: 'right', top: '76%', offset: '30px', rotate: '2deg', delay: '-4.1s' },
] as const

// 随机挑选图片并计算随机位置的逻辑
function regeneratePhotos() {
  const minioAssets = [...dynamicMedia.state.photos, ...dynamicMedia.state.gifs].map(item => ({
    name: item.name.replace(/\.[^/.]+$/, ''),
    url: item.url,
    caption: `“岁月流转，我们在 ${item.name.slice(0, 8)} 驻足。”`
  }))

  // 合并 MinIO 资产与本地编号素材，过滤用户已隐藏的条目。
  const pool = [...minioAssets, ...DEFAULT_PHOTOS.filter((fallback) =>
    !minioAssets.some((asset) => asset.url === fallback.url),
  )]
  const availablePool = pool.filter((photo) => !hiddenUrls.value.includes(photo.url))
  if (availablePool.length === 0) {
    activePhotos.value = []
    return
  }

  const selected = [...availablePool]
    .sort(() => 0.5 - Math.random())
    .slice(0, Math.min(PHOTO_SLOTS.length, availablePool.length))

  // 槽位固定，轮换时只替换图片内容；避免每次刷新重新计算位置造成跳动和重叠。
  activePhotos.value = selected.map((photo, index) => {
    const slot = PHOTO_SLOTS[index]
    return {
      id: photo.url,
      name: photo.name,
      url: photo.url,
      caption: photo.caption,
      side: slot.side,
      style: {
        position: 'absolute',
        top: slot.top,
        [slot.side]: slot.offset,
        transform: `rotate(${slot.rotate})`,
        animationDelay: slot.delay,
        pointerEvents: 'auto',
      },
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

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && activeLightboxPhoto.value) closeLightbox()
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
let rotationTimer: number | null = null
function startRotation() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  rotationTimer = window.setInterval(() => {
    if (document.hidden || activePhotos.value.length === 0) return

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

onMounted(() => {
  regeneratePhotos()
  startRotation()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  if (rotationTimer !== null) window.clearInterval(rotationTimer)
  window.removeEventListener('keydown', onKeydown)
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
              <img :src="p.url" class="polaroid-card__img" :alt="p.name" loading="lazy" decoding="async" />
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
              <img :src="p.url" class="polaroid-card__img" :alt="p.name" loading="lazy" decoding="async" />
            </div>
            <div class="polaroid-card__caption">{{ p.name }}</div>
          </div>
        </transition>
      </div>
    </div>

    <!-- 拍立得 Lightbox 放大弹窗 (沉浸式毛玻璃) -->
    <transition name="lightbox">
      <div v-if="activeLightboxPhoto" class="lightbox-overlay" role="presentation" @click.self="closeLightbox">
        <div class="lightbox-content" role="dialog" aria-modal="true" :aria-label="activeLightboxPhoto.name">
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

@media (max-width: 1759px) {
  .ambient-gallery {
    display: none !important;
  }
}

.ambient-gallery__side {
  position: fixed;
  top: 0;
  bottom: 0;
  width: 190px;
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
  width: 128px;
  background: var(--surface);
  backdrop-filter: blur(16px) saturate(180%);
  -webkit-backdrop-filter: blur(16px) saturate(180%);
  border-radius: 8px;
  padding: 10px 10px 18px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
  cursor: pointer;
  transform-origin: center center;
  transition: transform 260ms ease, border-color 200ms ease, box-shadow 260ms ease, opacity 200ms ease;
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
  transition: opacity 420ms ease, transform 420ms cubic-bezier(0.165, 0.84, 0.44, 1);
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
