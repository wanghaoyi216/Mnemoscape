<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import LiquidMemoryBackground from '../components/auth/LiquidMemoryBackground.vue'
import { loginBackgrounds, videos } from '../assets/media-catalog'

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
 *  2) 未配置时使用 public/media/videos/ 内置三段轻量氛围视频
 *  3) 配置成空字符串则关闭视频层，保留编号背景与 WebGL/CSS
 */
const envVideo = (import.meta.env.VITE_LOGIN_VIDEO_URL as string | undefined)
const playlist = ref<PlaylistItem[]>(defaultPlaylist)
const currentVideoIndex = ref(0)
const currentBackgroundIndex = ref(0)
const currentBackground = computed(() => loginBackgrounds[currentBackgroundIndex.value])
const videoUrl = computed(() => envVideo !== undefined ? envVideo : (playlist.value[currentVideoIndex.value]?.src || ''))

// 背景画廊默认折叠，避免控制器抢占登录表单注意力。
const selectorOpen = ref(false)
// 自动轮播默认开启；手动选择后从当前背景重新计时。
const autoCycle = ref(true)
let cycleTimer: number | null = null

function startAutoCycle() {
  stopAutoCycle()
  if (!autoCycle.value || loginBackgrounds.length < 2) return
  cycleTimer = window.setInterval(() => {
    if (envVideo === undefined && playlist.value.length > 1) {
      currentVideoIndex.value = (currentVideoIndex.value + 1) % playlist.value.length
    }
    currentBackgroundIndex.value = (currentBackgroundIndex.value + 1) % loginBackgrounds.length
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
function pickBackground(index: number) {
  if (currentBackgroundIndex.value === index) return
  currentBackgroundIndex.value = index
  if (autoCycle.value) startAutoCycle()
}

function handleVideoEnded() {
  if (envVideo === undefined && playlist.value.length > 0) {
    currentVideoIndex.value = (currentVideoIndex.value + 1) % playlist.value.length
    currentBackgroundIndex.value = (currentBackgroundIndex.value + 1) % loginBackgrounds.length
  }
}


onMounted(startAutoCycle)

onBeforeUnmount(() => {
  stopAutoCycle()
})

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

    <!-- 1–12 号生成背景按固定节奏交叉淡入；编号与素材方案保持一致。 -->
    <transition name="background-fade" mode="in-out">
      <img
        :key="currentBackground.src"
        class="login-stage__image"
        :src="currentBackground.src"
        :alt="currentBackground.origin"
        fetchpriority="high"
      />
    </transition>

    <!-- 旧视频仅作为轻量动态纹理，不再遮盖新生成背景。 -->
    <transition name="video-fade" mode="out-in">
      <video
        v-if="videoUrl"
        :key="videoUrl"
        class="login-stage__video"
        autoplay
        muted
        :loop="!!envVideo"
        playsinline
        preload="metadata"
        :poster="currentBackground.src"
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

    <!-- 背景画廊：允许手动选择编号素材，也可关闭自动轮播。 -->
    <div
      class="login-stage__theme-selector reveal reveal-delay-4"
      :class="{ 'login-stage__theme-selector--open': selectorOpen }"
    >
      <button
        type="button"
        class="theme-selector-trigger"
        :aria-expanded="selectorOpen"
        aria-controls="login-background-panel"
        :aria-label="locale === 'zh-CN' ? '选择登录背景' : 'Choose login background'"
        @click="selectorOpen = !selectorOpen"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
          <rect x="3" y="4" width="18" height="16" rx="3" stroke="currentColor" stroke-width="1.8"/>
          <path d="m6 16 4-4 3 3 2-2 3 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span class="theme-selector-trigger__label">
          {{ locale === 'zh-CN' ? '背景画廊' : 'Backgrounds' }}
        </span>
        <span class="theme-selector-trigger__current">{{ currentBackground.origin }}</span>
        <svg class="theme-selector-trigger__chev" viewBox="0 0 24 24" width="12" height="12" fill="none" aria-hidden="true">
          <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
      <transition name="theme-panel">
        <div v-if="selectorOpen" id="login-background-panel" class="theme-selector-panel">
          <div class="background-selector-grid" role="list" :aria-label="locale === 'zh-CN' ? '登录背景列表' : 'Login backgrounds'">
            <button
              v-for="(background, index) in loginBackgrounds"
              :key="background.number"
              type="button"
              class="background-swatch"
              :class="{ 'background-swatch--active': currentBackgroundIndex === index }"
              :aria-label="background.origin"
              :aria-pressed="currentBackgroundIndex === index"
              :title="background.origin"
              @click="pickBackground(index)"
            >
              <img :src="background.thumb" alt="" loading="lazy" decoding="async" />
              <span>{{ background.number }}</span>
            </button>
          </div>
          <label class="theme-selector-cycle">
            <input type="checkbox" :checked="autoCycle" @change="toggleAutoCycle" />
            <span>{{ locale === 'zh-CN' ? '自动轮播背景' : 'Auto rotate backgrounds' }}</span>
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
            <button type="submit" class="button button--primary auth-submit" :disabled="!canSubmit">
              <svg v-if="loading" class="auth-loading-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" style="opacity: 0.25"></circle>
                <path fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" style="opacity: 0.85"></path>
              </svg>
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

.login-stage__image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  z-index: -3;
  filter: saturate(1.04) contrast(1.02);
}

.background-fade-enter-active,
.background-fade-leave-active {
  transition: opacity 1.8s ease, transform 8s ease;
}
.background-fade-enter-from,
.background-fade-leave-to {
  opacity: 0;
}
.background-fade-enter-from {
  transform: scale(1.025);
}

.login-stage__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: -2;
  opacity: 0.18;
  mix-blend-mode: screen;
  filter: contrast(1.05) saturate(0.9) brightness(0.78);
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

.background-selector-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.background-swatch {
  position: relative;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  transition: border-color 180ms ease, transform 180ms ease, box-shadow 180ms ease;
}
.background-swatch:hover {
  transform: translateY(-2px);
  border-color: rgba(255, 255, 255, 0.34);
}
.background-swatch--active {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px rgba(54, 216, 180, 0.2);
}
.background-swatch img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.background-swatch span {
  position: absolute;
  right: 4px;
  bottom: 3px;
  min-width: 20px;
  padding: 1px 5px;
  border-radius: 999px;
  background: rgba(5, 6, 8, 0.74);
  color: #fff;
  font-size: 0.65rem;
  line-height: 1.5;
}


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
  z-index: 5;
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
  .login-stage__theme-selector {
    right: 12px;
    bottom: 12px;
    min-width: min(300px, calc(100vw - 24px));
    max-width: calc(100vw - 24px);
  }
  .theme-selector-trigger__current {
    max-width: 112px;
  }
  .login-hero__feature-grid {
    grid-template-columns: 1fr;
  }
  .login-stage__content {
    padding: 24px 16px;
  }
}

/* ============== 登录提交 Loading Spinner ============== */
.auth-loading-svg {
  animation: auth-spin 0.8s linear infinite;
  width: 18px;
  height: 18px;
  color: currentColor;
  display: inline-block;
  vertical-align: middle;
  margin-right: 8px;
  flex-shrink: 0;
}

/* 降低动效给"减少动画"用户 */
@media (prefers-reduced-motion: reduce) {
  .ink-drop, .particle, .login-glass--gold {
    animation: none !important;
  }
  .login-stage__video {
    display: none;
  }
  .login-stage__image {
    transition: none !important;
  }
}
</style>
