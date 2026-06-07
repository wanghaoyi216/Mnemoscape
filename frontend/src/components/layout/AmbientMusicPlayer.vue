<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { Howl } from 'howler'
import { useDynamicMedia } from '../../composables/useDynamicMedia'

// 动态媒体 composable：拉取 MinIO 中的音频资源
const dynamicMedia = useDynamicMedia()

// 默认的高品质、舒缓的公共钢琴/环境纯音乐（作为 MinIO 为空时的兜底，确保开箱即用）
const DEFAULT_TRACKS = [
  {
    name: 'Memory Echoes (时空回响 · 钢琴)',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
    source: 'preset'
  },
  {
    name: 'Dreamy Winds (微风拂面 · 竖琴)',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
    source: 'preset'
  },
  {
    name: 'Nostalgic Rain (雨中恋歌 · 吉他)',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
    source: 'preset'
  }
]

// 组合播放列表：MinIO 音频优先，接着拼接预设舒缓纯音乐
const playlist = computed(() => {
  const minioAudios = dynamicMedia.state.audios.map(a => ({
    name: a.name.replace(/\.[^/.]+$/, ''), // 去除文件后缀名
    url: a.url,
    source: 'minio'
  }))
  return [...minioAudios, ...DEFAULT_TRACKS]
})

// 状态管理
const currentTrackIndex = ref(0)
const isPlaying = ref(false)
const volume = ref(0.4) // 默认 40% 音量，舒适温和
const isExpanded = ref(false) // 默认收缩为一个小唱片，点击展开控制舱

let activeSound: Howl | null = null

const currentTrack = computed(() => {
  if (playlist.value.length === 0) return null
  return playlist.value[currentTrackIndex.value]
})

// 核心播放控制逻辑
function playTrack(index: number) {
  if (playlist.value.length === 0) return

  // 销毁上一个播放实例
  if (activeSound) {
    activeSound.fade(activeSound.volume(), 0, 500) // 500ms 优雅淡出
    const prevSound = activeSound
    setTimeout(() => {
      prevSound.unload()
    }, 600)
    activeSound = null
  }

  currentTrackIndex.value = index
  const track = playlist.value[index]

  // 创建新 Howl 实例
  activeSound = new Howl({
    src: [track.url],
    html5: true, // 使用 HTML5 Audio 支持大文件 / 流式加载
    volume: 0,   // 初始化 0 音量，然后淡入
    loop: false,
    onplay: () => {
      isPlaying.value = true
      activeSound?.fade(0, volume.value, 800) // 800ms 优雅淡入到指定音量
    },
    onend: () => {
      nextTrack()
    },
    onloaderror: () => {
      isPlaying.value = false
      console.warn('[AmbientPlayer] Failed to load track:', track.name)
    },
    onplayerror: () => {
      isPlaying.value = false
      activeSound?.unload()
      activeSound = null
    }
  })

  activeSound.play()
}

function togglePlay() {
  if (!activeSound) {
    playTrack(currentTrackIndex.value)
    return
  }

  if (isPlaying.value) {
    // 暂停：先淡出再暂停
    activeSound.fade(activeSound.volume(), 0, 400)
    setTimeout(() => {
      activeSound?.pause()
      isPlaying.value = false
    }, 400)
  } else {
    // 播放：先恢复播放再淡入
    activeSound.play()
    activeSound.fade(0, volume.value, 600)
    isPlaying.value = true
  }
}

function nextTrack() {
  if (playlist.value.length <= 1) return
  const nextIdx = (currentTrackIndex.value + 1) % playlist.value.length
  playTrack(nextIdx)
}

function prevTrack() {
  if (playlist.value.length <= 1) return
  const prevIdx = (currentTrackIndex.value - 1 + playlist.value.length) % playlist.value.length
  playTrack(prevIdx)
}

// 监听音量条调节
watch(volume, (newVol) => {
  if (activeSound && isPlaying.value) {
    activeSound.volume(newVol)
  }
})

// 组件卸载前销毁播放器
onBeforeUnmount(() => {
  if (activeSound) {
    activeSound.unload()
    activeSound = null
  }
})
</script>

<template>
  <div class="ambient-player" :class="{ 'ambient-player--expanded': isExpanded }">
    <!-- 收起状态下的动态唱盘图标（呼吸流光背景） -->
    <button 
      class="ambient-player__disc-btn"
      :class="{ 'ambient-player__disc-btn--playing': isPlaying }"
      title="情感音乐电台"
      type="button"
      @click="isExpanded = !isExpanded"
    >
      <div class="ambient-player__disc-inner">
        <!-- 黑胶唱盘纹理 -->
        <span class="ambient-player__disc-groove"></span>
        <span class="ambient-player__disc-center"></span>
      </div>
      <!-- 动态跳动的音乐柱 -->
      <div v-if="isPlaying" class="ambient-player__wave" aria-hidden="true">
        <span class="ambient-player__wave-bar"></span>
        <span class="ambient-player__wave-bar"></span>
        <span class="ambient-player__wave-bar"></span>
      </div>
    </button>

    <!-- 控制舱扩展面板 (毛玻璃高拟真质感) -->
    <div class="ambient-player__capsule">
      <div class="ambient-player__head">
        <span class="ambient-player__badge" :class="`ambient-player__badge--${currentTrack?.source}`">
          {{ currentTrack?.source === 'minio' ? 'MinIO 专栏' : '博物馆馆藏' }}
        </span>
        <button class="ambient-player__close-btn" type="button" @click="isExpanded = false" aria-label="收起播放器">✕</button>
      </div>

      <!-- Handwriting styling for the track title -->
      <div class="ambient-player__body">
        <div class="ambient-player__title-container">
          <div class="ambient-player__title" :class="{ 'ambient-player__title--scroll': isPlaying }">
            {{ currentTrack?.name || '静谧之声' }}
          </div>
        </div>
      </div>

      <!-- 控制键舱 -->
      <div class="ambient-player__controls">
        <button class="ctrl-btn" type="button" @click="prevTrack" title="上一首">⏮</button>
        <button class="ctrl-btn ctrl-btn--play" type="button" @click="togglePlay" :title="isPlaying ? '暂停' : '播放'">
          {{ isPlaying ? '⏸' : '▶' }}
        </button>
        <button class="ctrl-btn" type="button" @click="nextTrack" title="下一首">⏭</button>
      </div>

      <!-- 音量条舱 -->
      <div class="ambient-player__footer">
        <span class="vol-icon" aria-hidden="true">🔊</span>
        <input 
          v-model.number="volume" 
          class="vol-slider" 
          type="range" 
          min="0" 
          max="1" 
          step="0.05" 
          title="调节音量"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.ambient-player {
  position: fixed;
  left: 24px;
  bottom: 24px;
  z-index: 999;
  display: flex;
  align-items: flex-end;
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
}

/* ---- 旋转唱盘按钮 ---- */
.ambient-player__disc-btn {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  border: 1px solid rgba(14, 165, 233, 0.35);
  background: radial-gradient(circle, #101524 40%, #060913 100%);
  box-shadow: 0 8px 24px rgba(14, 165, 233, 0.22),
              inset 0 0 10px rgba(14, 165, 233, 0.1);
  padding: 4px;
  cursor: pointer;
  position: relative;
  transition: transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1),
              box-shadow 0.2s ease;
  z-index: 10;
}
.ambient-player__disc-btn:hover {
  transform: scale(1.08) rotate(5deg);
  box-shadow: 0 12px 32px rgba(14, 165, 233, 0.35);
}
.ambient-player__disc-inner {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: repeating-radial-gradient(
    circle,
    #111,
    #111 2px,
    #1b1b1b 3px,
    #111 4px
  );
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ambient-player__disc-groove {
  position: absolute;
  inset: 6px;
  border-radius: 50%;
  border: 1px dashed rgba(255, 255, 255, 0.05);
}
.ambient-player__disc-center {
  width: 10px;
  height: 10px;
  background: var(--gold, #dfb95c);
  border-radius: 50%;
  box-shadow: 0 0 6px rgba(223, 185, 92, 0.8);
}

/* 播放时的唱片自转动画 */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.ambient-player__disc-btn--playing .ambient-player__disc-inner {
  animation: spin 6s linear infinite;
}

/* 动态声波跳动 */
.ambient-player__wave {
  position: absolute;
  top: -8px;
  right: -6px;
  display: flex;
  gap: 2px;
  background: rgba(14, 165, 233, 0.9);
  padding: 4px 6px;
  border-radius: 6px;
  box-shadow: 0 4px 10px rgba(14, 165, 233, 0.3);
}
.ambient-player__wave-bar {
  width: 2px;
  height: 10px;
  background: #fff;
  border-radius: 1px;
  animation: wave-bounce 1s ease-in-out infinite alternate;
}
.ambient-player__wave-bar:nth-child(2) {
  animation-delay: 0.15s;
  height: 13px;
}
.ambient-player__wave-bar:nth-child(3) {
  animation-delay: 0.3s;
  height: 8px;
}
@keyframes wave-bounce {
  from { transform: scaleY(0.4); }
  to { transform: scaleY(1.3); }
}

/* ---- 控制舱扩展面板 ---- */
.ambient-player__capsule {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 280px;
  background: linear-gradient(135deg, rgba(16, 22, 38, 0.94), rgba(8, 12, 22, 0.88));
  border: 1px solid rgba(14, 165, 233, 0.28);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.55),
              0 0 24px rgba(14, 165, 233, 0.08);
  backdrop-filter: blur(20px);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  opacity: 0;
  transform: scale(0.9) translateY(12px);
  transform-origin: bottom left;
  pointer-events: none;
  transition: all 300ms cubic-bezier(0.34, 1.56, 0.64, 1);
  z-index: 5;
}

.ambient-player--expanded .ambient-player__capsule {
  opacity: 1;
  transform: scale(1) translateY(0);
  pointer-events: auto;
}
.ambient-player--expanded .ambient-player__disc-btn {
  transform: scale(0.6) rotate(-15deg);
  opacity: 0;
  pointer-events: none;
}

.ambient-player__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ambient-player__badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  letter-spacing: 0.04em;
}
.ambient-player__badge--minio {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.35);
}
.ambient-player__badge--preset {
  background: rgba(223, 185, 92, 0.15);
  color: var(--gold, #dfb95c);
  border: 1px solid rgba(223, 185, 92, 0.35);
}
.ambient-player__close-btn {
  background: transparent;
  border: none;
  color: #94a3b8;
  cursor: pointer;
  font-size: 13px;
  transition: color 0.15s ease;
}
.ambient-player__close-btn:hover { color: #ff6b6b; }

.ambient-player__body {
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.05);
  padding: 8px 12px;
  border-radius: 8px;
  overflow: hidden;
}
.ambient-player__title-container {
  overflow: hidden;
  position: relative;
  white-space: nowrap;
}
.ambient-player__title {
  font-size: 13px;
  font-weight: 600;
  color: #f1f5f9;
  font-style: italic; /* Cursive / italic styling for human connection */
  letter-spacing: 0.02em;
}

@keyframes marquee {
  0% { transform: translateX(0); }
  50% { transform: translateX(-40%); }
  100% { transform: translateX(0); }
}
.ambient-player__title--scroll {
  display: inline-block;
  animation: marquee 12s linear infinite;
}

.ambient-player__controls {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
}
.ctrl-btn {
  background: transparent;
  border: none;
  color: #cbd5e1;
  font-size: 18px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.ctrl-btn:hover {
  color: var(--gold, #dfb95c);
  transform: scale(1.12);
}
.ctrl-btn--play {
  font-size: 26px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: rgba(14, 165, 233, 0.12);
  border: 1px solid rgba(14, 165, 233, 0.4);
  color: var(--primary, #0ea5e9);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(14, 165, 233, 0.15);
}
.ctrl-btn--play:hover {
  background: var(--primary, #0ea5e9);
  color: #fff;
  border-color: var(--primary, #0ea5e9);
  box-shadow: 0 6px 18px rgba(14, 165, 233, 0.35);
  transform: scale(1.08);
}

.ambient-player__footer {
  display: flex;
  align-items: center;
  gap: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  padding-top: 10px;
}
.vol-icon { font-size: 12px; color: #64748b; }
.vol-slider {
  flex: 1;
  appearance: none;
  height: 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.1);
  outline: none;
  cursor: pointer;
}
.vol-slider::-webkit-slider-runnable-track {
  background: rgba(255, 255, 255, 0.1);
  height: 3px;
}
.vol-slider::-webkit-slider-thumb {
  appearance: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--primary, #0ea5e9);
  box-shadow: 0 0 6px rgba(14, 165, 233, 0.8);
  cursor: pointer;
  margin-top: -3.5px;
}
</style>
