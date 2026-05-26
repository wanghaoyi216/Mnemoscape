<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import LiquidMemoryBackground from '../components/auth/LiquidMemoryBackground.vue'
import { videos } from '../assets/media-catalog'
import client from '../api/client'

const router = useRouter()
const auth = useAuthStore()
const { t, locale } = useI18n()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

interface PlaylistItem {
  src: string
  label: string
  eng: string
}

const defaultPlaylist: PlaylistItem[] = [
  { src: videos.neuralResonance.src, label: '神经共鸣', eng: 'Neural Resonance' },
  { src: videos.chronosFlow.src, label: '时间之流', eng: 'Chronos Flow' },
  { src: videos.ebbingHourglass.src, label: '沙漏倒流', eng: 'Ebbing Hourglass' }
]

/*
 * 登录页视频背景层级：
 *  1) .env.local 的 VITE_LOGIN_VIDEO_URL 优先（可换你自己的素材）
 *  2) 默认拉取 asset-service /static/resources（热更新 + MinIO 动态注入）
 *  3) 兜底使用 public/media/videos/ 内置三段
 *  4) 配置成空字符串则关闭视频层，回退到 WebGL + CSS
 */
const envVideo = (import.meta.env.VITE_LOGIN_VIDEO_URL as string | undefined)
const playlist = ref<PlaylistItem[]>(defaultPlaylist)
const currentVideoIndex = ref(0)
const videoUrl = computed(() => envVideo !== undefined ? envVideo : (playlist.value[currentVideoIndex.value]?.src || ''))

// 选择器默认隐藏；提供一颗"维网入口"按钮唤起
const selectorOpen = ref(false)
// 自动轮播默认开启；用户手动切换则关闭
const autoCycle = ref(true)
let cycleTimer: number | null = null

function startAutoCycle() {
  stopAutoCycle()
  if (!autoCycle.value || envVideo !== undefined || playlist.value.length < 2) return
  cycleTimer = window.setInterval(() => {
    currentVideoIndex.value = (currentVideoIndex.value + 1) % playlist.value.length
  }, 18000)
}
function stopAutoCycle() {
  if (cycleTimer !== null) {
    window.clearInterval(cycleTimer)
    cycleTimer = null
  }
}
function toggleAutoCycle() {
  autoCycle.value = !autoCycle.value
  if (autoCycle.value) startAutoCycle()
  else stopAutoCycle()
}
function pickVideo(idx: number) {
  if (currentVideoIndex.value === idx) return
  currentVideoIndex.value = idx
  // 用户手动切换 → 自动循环按用户偏好继续，但要重置计时
  if (autoCycle.value) startAutoCycle()
}

function handleVideoEnded() {
  if (envVideo === undefined && playlist.value.length > 0) {
    currentVideoIndex.value = (currentVideoIndex.value + 1) % playlist.value.length
  }
}

watch(playlist, () => startAutoCycle(), { deep: true })

onMounted(async () => {
  try {
    const resp = await client.get('/assets/static/resources')
    const files = resp.data?.data || []
    const foundVideos = files.filter((f: any) => f.type === 'video')
    if (foundVideos.length > 0) {
      playlist.value = foundVideos.map((fv: any) => {
        const filename = fv.name.replace(/\.mp4$/i, '')
        let label = filename
        let eng = filename
        const match = filename.match(/(.+?)[（(](.+?)[）)]/)
        if (match) {
          label = match[1].trim()
          eng = match[2].trim()
        }
        return {
          src: fv.path,
          label: label,
          eng: eng
        }
      })
    }
  } catch (e) {
    console.warn('Failed to load dynamic videos, keeping defaults.', e)
  }
  startAutoCycle()
})

onBeforeUnmount(() => stopAutoCycle())
 
const canSubmit = computed(() =>
  username.value.trim().length > 0
  && password.value.trim().length > 0
  && !loading.value,
)
 
async function handleSubmit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/memories')
  } catch (e: any) {
    error.value = e.response?.data?.message || t('login.error.fallback')
  } finally {
    loading.value = false
  }
}
</script>
 
<template>
  <div class="login-stage">
    <!--
      背景层级（z-index 从底到面）：
        1) WebGL "记忆流体"着色器 - 鼠标互动 + 神经突触粒子（首选）
        2) 可选视频背景 - 用户在 .env.local 配置 VITE_LOGIN_VIDEO_URL 时叠加
        3) CSS 墨水 / 星光粒子 / 暗色蒙版 - 永远存在的兜底层
      WebGL 在 GPU 缺失/用户偏好"减少动画"时自动退化，CSS 层永远保证基线视觉。
    -->
    <LiquidMemoryBackground />
 
    <!-- 可选视频背景（叠加在着色器之上、CSS 层之下） -->
    <transition name="video-fade" mode="out-in">
      <video
        v-if="videoUrl"
        :key="videoUrl"
        class="login-stage__video"
        autoplay
        muted
        :loop="!!envVideo"
        playsinline
        preload="auto"
        @ended="handleVideoEnded"
      >
        <source :src="videoUrl" type="video/mp4" />
      </video>
    </transition>
 
    <!-- CSS 墨水扩散层（永远存在） -->
    <div class="login-stage__ink" aria-hidden="true">
      <div class="ink-drop ink-drop--gold"></div>
      <div class="ink-drop ink-drop--teal"></div>
      <div class="ink-drop ink-drop--violet"></div>
    </div>
 
    <!-- 星光粒子层 -->
    <div class="login-stage__particles" aria-hidden="true">
      <span v-for="i in 48" :key="i" class="particle" :style="{
        '--x': `${Math.random() * 100}%`,
        '--y': `${Math.random() * 100}%`,
        '--d': `${8 + Math.random() * 12}s`,
        '--delay': `-${Math.random() * 12}s`,
        '--scale': `${0.4 + Math.random() * 0.8}`,
      }"></span>
    </div>
 
    <!-- 暗色蒙版 + 噪点 -->
    <div class="login-stage__mask" aria-hidden="true"></div>
 
    <!-- 记忆维网切换（多视频背景控制器） — 默认折叠为"维网入口"按钮 -->
    <div
      v-if="envVideo === undefined"
      class="login-stage__theme-selector reveal reveal-delay-4"
      :class="{ 'login-stage__theme-selector--open': selectorOpen }"
    >
      <button
        type="button"
        class="theme-selector-trigger"
        :aria-expanded="selectorOpen"
        :aria-label="locale === 'zh-CN' ? '展开记忆维网' : 'Toggle memory dimensions'"
        @click="selectorOpen = !selectorOpen"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none">
          <path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z" stroke="currentColor" stroke-width="1.8"/>
          <path d="M12 7v5l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
        </svg>
        <span class="theme-selector-trigger__label">
          {{ locale === 'zh-CN' ? '记忆时空维网' : 'Memory Dimensions' }}
        </span>
        <span class="theme-selector-trigger__current">
          {{ locale === 'zh-CN' ? playlist[currentVideoIndex]?.label : playlist[currentVideoIndex]?.eng }}
        </span>
        <svg class="theme-selector-trigger__chev" viewBox="0 0 24 24" width="12" height="12" fill="none">
          <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
      <transition name="theme-panel">
        <div v-if="selectorOpen" class="theme-selector-panel">
          <div class="theme-selector-buttons">
            <button
              v-for="(vid, idx) in playlist"
              :key="idx"
              type="button"
              :class="['theme-sel-btn', currentVideoIndex === idx ? 'active' : '']"
              @click="pickVideo(idx)"
            >
              <span class="theme-sel-btn__dot"></span>
              <span class="theme-sel-btn__name">{{ locale === 'zh-CN' ? vid.label : vid.eng }}</span>
            </button>
          </div>
          <label class="theme-selector-cycle">
            <input type="checkbox" :checked="autoCycle" @change="toggleAutoCycle" />
            <span>{{ locale === 'zh-CN' ? '自动轮播' : 'Auto rotate' }}</span>
          </label>
        </div>
      </transition>
    </div>
 
    <!-- 实际内容（玻璃拟态卡片） -->
    <div class="login-stage__content">
      <div class="login-grid">
        <section class="login-hero login-glass">
          <div class="stack stack--lg">
            <p class="eyebrow reveal">{{ t('login.eyebrow') }}</p>
            <h1 class="display-title text-gradient reveal reveal-delay-1" v-html="t('login.title')"></h1>
            <p class="lead reveal reveal-delay-2">{{ t('login.lead') }}</p>

            <div class="login-hero__feature-grid reveal reveal-delay-3">
              <div class="login-hero__feature">
                <span class="login-hero__icon">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
                    <path d="M12 2L4 6v6c0 5 4 9 8 10 4-1 8-5 8-10V6l-8-4z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
                  </svg>
                </span>
                <div>
                  <strong>{{ t('login.features.jwt.title') }}</strong>
                  <small>{{ t('login.features.jwt.desc') }}</small>
                </div>
              </div>
              <div class="login-hero__feature">
                <span class="login-hero__icon">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
                    <path d="M3 12h18M12 3v18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
                  </svg>
                </span>
                <div>
                  <strong>{{ t('login.features.scene.title') }}</strong>
                  <small>{{ t('login.features.scene.desc') }}</small>
                </div>
              </div>
              <div class="login-hero__feature">
                <span class="login-hero__icon">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
                    <path d="M4 18l4-4 3 3 9-9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
                <div>
                  <strong>{{ t('login.features.drift.title') }}</strong>
                  <small>{{ t('login.features.drift.desc') }}</small>
                </div>
              </div>
              <div class="login-hero__feature">
                <span class="login-hero__icon">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
                    <circle cx="9" cy="12" r="5" stroke="currentColor" stroke-width="1.6" />
                    <circle cx="15" cy="12" r="5" stroke="currentColor" stroke-width="1.6" />
                  </svg>
                </span>
                <div>
                  <strong>{{ t('login.features.resonance.title') }}</strong>
                  <small>{{ t('login.features.resonance.desc') }}</small>
                </div>
              </div>
            </div>
          </div>

          <div class="login-hero__quote reveal reveal-delay-4">
            <span aria-hidden="true">"</span>
            <p>{{ t('login.quote') }}</p>
          </div>
        </section>

        <form class="auth-card login-glass login-glass--gold stack ai-glow-border ai-glow-border--intense" @submit.prevent="handleSubmit" aria-labelledby="login-title">
          <div class="stack">
            <p class="eyebrow">{{ t('login.card.eyebrow') }}</p>
            <h2 id="login-title" class="section-title">{{ t('login.card.title') }}</h2>
            <p class="subtitle">{{ t('login.card.subtitle') }}</p>
          </div>

          <transition name="alert">
            <div v-if="error" class="status-pill status-pill--danger auth-error" role="alert">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
                <path d="M12 8v4m0 4h.01" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
              {{ error }}
            </div>
          </transition>

          <label class="field">
            <span class="field__label">{{ t('login.username') }}</span>
            <input
              v-model="username"
              class="input"
              :placeholder="t('login.usernamePlaceholder')"
              required
              autocomplete="username"
              spellcheck="false"
            />
          </label>

          <label class="field">
            <span class="field__label">{{ t('login.password') }}</span>
            <input
              v-model="password"
              type="password"
              class="input"
              :placeholder="t('login.passwordPlaceholder')"
              required
              autocomplete="current-password"
            />
          </label>

          <div class="stack">
            <button type="submit" class="button button--primary auth-submit ai-glow-border ai-glow-border--soft" :disabled="!canSubmit">
              <span v-if="loading" class="auth-spinner" aria-hidden="true"></span>
              <span>{{ loading ? t('login.submitting') : t('login.submit') }}</span>
              <svg v-if="!loading" viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>

            <p class="help-text auth-footnote">
              {{ t('login.noAccount') }}
              <router-link to="/register" class="auth-link">{{ t('login.goRegister') }}</router-link>
            </p>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============== 全屏舞台 ============== */
.login-stage {
  position: relative;
  /* 之前用 `min-height: calc(100vh - 88px)` + `margin: -32px ... -80px` 是想撑满
   * 视口并打破 .page-shell 的 padding。但 LoginView 没经过 .page-shell 包裹（直接挂
   * 在 <main class="app-shell__main"> 下），那个 -32px 让表单看起来"偏上不居中"。
   * 改成 0 边距 + 100vh 减去实际 header 高度，保证内容在视口正中。 */
  min-height: 100vh;
  margin: 0 calc(50% - 50vw);
  overflow: hidden;
  isolation: isolate;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-stage__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: -3;
  /* 提升清晰度：opacity 调高 + 轻度对比/饱和度，减弱 CSS 蒙版上压时的浑浊感 */
  opacity: 0.92;
  filter: contrast(1.06) saturate(1.12) brightness(1.02);
}

/* ============== 记忆时空维网切换器 — 默认折叠 ============== */
.login-stage__theme-selector {
  position: absolute;
  bottom: 24px;
  right: 24px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
  min-width: 220px;
  max-width: 320px;
}

.theme-selector-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  background: rgba(14, 17, 22, 0.55);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
  border: 1px solid rgba(54, 216, 180, 0.22);
  border-radius: 999px;
  color: var(--text-soft);
  font-size: 0.78rem;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: all 0.22s ease;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
}
.theme-selector-trigger:hover {
  border-color: rgba(54, 216, 180, 0.55);
  background: rgba(14, 17, 22, 0.72);
  color: var(--text);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.55), 0 0 22px rgba(54, 216, 180, 0.18);
}
.login-stage__theme-selector--open .theme-selector-trigger {
  border-color: rgba(54, 216, 180, 0.6);
  color: var(--primary);
}
.theme-selector-trigger__label {
  flex: 0 0 auto;
  text-transform: uppercase;
  font-weight: 500;
}
.theme-selector-trigger__current {
  flex: 1 1 auto;
  text-align: right;
  font-size: 0.74rem;
  color: var(--text-muted);
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.theme-selector-trigger__chev {
  flex: 0 0 auto;
  opacity: 0.6;
  transition: transform 0.25s ease;
}
.login-stage__theme-selector--open .theme-selector-trigger__chev {
  transform: rotate(180deg);
}

.theme-selector-panel {
  background: rgba(10, 14, 22, 0.65);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.5);
}

.theme-selector-buttons {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.theme-sel-btn {
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: var(--radius-xs);
  width: 100%;
  text-align: left;
  cursor: pointer;
  transition: all 0.2s ease;
  color: var(--text-soft);
  font-size: 0.8rem;
  font-weight: 500;
}

.theme-sel-btn:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text);
}

.theme-sel-btn.active {
  background: rgba(54, 216, 180, 0.08);
  color: var(--primary);
  box-shadow: inset 0 0 0 1px rgba(54, 216, 180, 0.2);
}

.theme-sel-btn__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  transition: all 0.2s ease;
}

.theme-sel-btn.active .theme-sel-btn__dot {
  background: var(--primary);
  box-shadow: 0 0 8px var(--primary-glow);
}

.theme-selector-cycle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  font-size: 0.72rem;
  color: var(--text-muted);
  cursor: pointer;
  user-select: none;
}
.theme-selector-cycle input { accent-color: var(--primary); }

.theme-panel-enter-active, .theme-panel-leave-active {
  transition: opacity 220ms ease, transform 240ms cubic-bezier(0.16, 1, 0.3, 1);
}
.theme-panel-enter-from, .theme-panel-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.97);
}

@media (max-width: 768px) {
  .login-stage__theme-selector {
    position: relative;
    bottom: auto;
    right: auto;
    margin: 24px auto 0;
    align-self: center;
    width: 100%;
    max-width: 320px;
  }
}

/* 墨水扩散：三个慢动作的径向渐变 blob */
.login-stage__ink {
  position: absolute;
  inset: 0;
  z-index: -2;
  background: radial-gradient(ellipse at top left, rgba(54, 216, 180, 0.06), transparent 50%),
              radial-gradient(ellipse at bottom right, rgba(242, 185, 92, 0.05), transparent 50%);
}

.ink-drop {
  position: absolute;
  width: 60vmin;
  height: 60vmin;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.55;
  mix-blend-mode: screen;
  animation: ink-float 24s ease-in-out infinite;
  will-change: transform;
}

.ink-drop--gold {
  top: -15vmin;
  left: -10vmin;
  background: radial-gradient(circle at 30% 30%, rgba(242, 185, 92, 0.55), transparent 65%);
  animation-delay: 0s;
}

.ink-drop--teal {
  top: 30vmin;
  right: -15vmin;
  background: radial-gradient(circle at 40% 40%, rgba(54, 216, 180, 0.5), transparent 65%);
  animation-delay: -8s;
}

.ink-drop--violet {
  bottom: -20vmin;
  left: 30vmin;
  background: radial-gradient(circle at 50% 50%, rgba(132, 110, 220, 0.4), transparent 65%);
  animation-delay: -16s;
}

@keyframes ink-float {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  33%      { transform: translate3d(8vmin, -6vmin, 0) scale(1.08); }
  66%      { transform: translate3d(-6vmin, 4vmin, 0) scale(0.92); }
}

/* 星光粒子 */
.login-stage__particles {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
}

.particle {
  position: absolute;
  left: var(--x);
  top: var(--y);
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0) 70%);
  transform: scale(var(--scale));
  animation: particle-drift var(--d) ease-in-out infinite;
  animation-delay: var(--delay);
  opacity: 0;
  filter: drop-shadow(0 0 4px rgba(255, 255, 255, 0.55));
}

@keyframes particle-drift {
  0%   { opacity: 0; transform: translateY(0) scale(var(--scale)); }
  20%  { opacity: 0.9; }
  80%  { opacity: 0.5; }
  100% { opacity: 0; transform: translateY(-60px) scale(calc(var(--scale) * 0.6)); }
}

/* 暗色蒙版 — 保证文字始终清晰；视频清晰度调高后蒙版相应降低，避免压糊画面 */
.login-stage__mask {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    linear-gradient(180deg, rgba(8, 10, 14, 0.45) 0%, rgba(8, 10, 14, 0.22) 50%, rgba(8, 10, 14, 0.58) 100%),
    radial-gradient(ellipse at center, transparent 0%, rgba(8, 10, 14, 0.28) 100%);
  pointer-events: none;
}

/* 内容区 */
.login-stage__content {
  position: relative;
  padding: 40px 24px;
  max-width: var(--page-width-wide);
  margin: 0 auto;
  width: 100%;
}

.login-grid {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 36px;
  align-items: center;
}

/* ============== 玻璃拟态 ============== */
.login-glass {
  position: relative;
  background: rgba(14, 17, 22, 0.42);
  backdrop-filter: blur(24px) saturate(160%);
  -webkit-backdrop-filter: blur(24px) saturate(160%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-lg);
  padding: 40px;
  box-shadow:
    0 24px 60px -20px rgba(0, 0, 0, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

/* 金色边缘流光 — 用 box-shadow 脉动取代复杂的 conic-gradient 旋转，省 GPU 也更稳 */
.login-glass--gold {
  border-color: rgba(242, 185, 92, 0.32);
  animation: gold-pulse 5.5s ease-in-out infinite;
}

@keyframes gold-pulse {
  0%, 100% {
    border-color: rgba(242, 185, 92, 0.32);
    box-shadow:
      0 24px 60px -20px rgba(0, 0, 0, 0.55),
      0 0 30px -8px rgba(242, 185, 92, 0.22),
      0 0 60px -22px rgba(54, 216, 180, 0.18),
      inset 0 1px 0 rgba(255, 255, 255, 0.05);
  }
  50% {
    border-color: rgba(242, 185, 92, 0.55);
    box-shadow:
      0 24px 60px -20px rgba(0, 0, 0, 0.55),
      0 0 44px -8px rgba(242, 185, 92, 0.42),
      0 0 80px -22px rgba(54, 216, 180, 0.32),
      inset 0 1px 0 rgba(255, 255, 255, 0.12);
  }
}

/* ============== Hero 内部排版 ============== */
.login-hero {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 36px;
}

.login-hero__feature-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 8px;
}

.login-hero__feature {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.35);
  transition: border-color 200ms ease, background-color 200ms ease, transform 200ms ease;
}

.login-hero__feature:hover {
  border-color: var(--border-accent);
  background: rgba(54, 216, 180, 0.06);
  transform: translateY(-2px);
}

.login-hero__icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  background: rgba(54, 216, 180, 0.10);
  color: var(--primary);
  border: 1px solid var(--border-accent);
}

.login-hero__feature strong {
  display: block;
  font-size: 0.92rem;
  margin-bottom: 2px;
  color: var(--text);
}

.login-hero__feature small {
  display: block;
  color: var(--text-muted);
  font-size: 0.78rem;
  line-height: 1.5;
}

.login-hero__quote {
  position: relative;
  padding: 22px 24px 22px 56px;
  border-radius: var(--radius-md);
  background: rgba(8, 10, 14, 0.42);
  border-left: 2px solid var(--gold, #f2b95c);
}

.login-hero__quote span {
  position: absolute;
  top: 6px;
  left: 18px;
  font-family: var(--font-display);
  font-size: 4rem;
  color: var(--gold, #f2b95c);
  opacity: 0.42;
  line-height: 1;
}

.login-hero__quote p {
  margin: 0;
  font-style: italic;
  font-family: var(--font-display);
  color: var(--text-soft);
  line-height: 1.6;
  font-size: 1.02rem;
}

/* ============== 表单 ============== */
.auth-card {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.auth-error {
  align-self: flex-start;
}

.auth-submit {
  width: 100%;
  height: 52px;
  font-size: 0.98rem;
}

.auth-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(5, 32, 23, 0.32);
  border-top-color: #052017;
  border-radius: 50%;
  animation: auth-spin 0.7s linear infinite;
}

@keyframes auth-spin {
  to { transform: rotate(360deg); }
}

.auth-footnote {
  text-align: center;
  margin: 0;
}

.auth-link {
  font-weight: 600;
  color: var(--gold, #f2b95c);
  transition: color 180ms ease;
}

.auth-link:hover {
  color: #ffd58a;
}

.alert-enter-active,
.alert-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.alert-enter-from,
.alert-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.video-fade-enter-active,
.video-fade-leave-active {
  transition: opacity 800ms ease;
}

.video-fade-enter-from,
.video-fade-leave-to {
  opacity: 0;
}

@media (max-width: 1100px) {
  .login-grid {
    grid-template-columns: 1fr;
  }
  .login-glass {
    padding: 28px;
  }
}

@media (max-width: 640px) {
  .login-hero__feature-grid {
    grid-template-columns: 1fr;
  }
  .login-stage__content {
    padding: 24px 16px;
  }
}

/* 降低动效给"减少动画"用户 */
@media (prefers-reduced-motion: reduce) {
  .ink-drop, .particle, .login-glass--gold {
    animation: none !important;
  }
  .login-stage__video {
    display: none;
  }
}
</style>
