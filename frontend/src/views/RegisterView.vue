<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import LiquidMemoryBackground from '../components/auth/LiquidMemoryBackground.vue'
import { videos } from '../assets/media-catalog'

const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()

const username = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

const passwordPattern = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z\d]).{8,}$/

const passwordChecks = computed(() => ([
  { key: 'length', label: t('register.rules.length'), ok: password.value.length >= 8 },
  { key: 'upper', label: t('register.rules.upper'), ok: /[A-Z]/.test(password.value) },
  { key: 'lower', label: t('register.rules.lower'), ok: /[a-z]/.test(password.value) },
  { key: 'digit', label: t('register.rules.digit'), ok: /\d/.test(password.value) },
  { key: 'symbol', label: t('register.rules.symbol'), ok: /[^A-Za-z\d]/.test(password.value) },
]))

const passwordValid = computed(() => passwordPattern.test(password.value))
const passwordStrength = computed(() => passwordChecks.value.filter((c) => c.ok).length)
const passwordsMatch = computed(() =>
  confirmPassword.value.length === 0 || password.value === confirmPassword.value,
)

// 注册页与登录页共用视频素材策略 — Chronos Flow 作时间长河氛围
const envVideo = (import.meta.env.VITE_LOGIN_VIDEO_URL as string | undefined)
const videoUrl = computed(() => envVideo === undefined ? videos.chronosFlow.src : envVideo)

const canSubmit = computed(() => (
  username.value.trim().length >= 3
  && /^\S+@\S+\.\S+$/.test(email.value.trim())
  && passwordValid.value
  && password.value === confirmPassword.value
  && !loading.value
))

async function handleSubmit() {
  error.value = ''
  if (password.value !== confirmPassword.value) {
    error.value = t('register.error.mismatch')
    return
  }
  if (!passwordValid.value) {
    error.value = t('register.error.weak')
    return
  }
  loading.value = true
  try {
    await auth.register(username.value, email.value, password.value)
    router.push('/login')
  } catch (e: any) {
    error.value = e.response?.data?.message || t('register.error.fallback')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-stage">
    <LiquidMemoryBackground />

    <video
      v-if="videoUrl"
      class="login-stage__video"
      autoplay muted loop playsinline preload="auto"
    >
      <source :src="videoUrl" type="video/mp4" />
    </video>

    <div class="login-stage__ink" aria-hidden="true">
      <div class="ink-drop ink-drop--gold"></div>
      <div class="ink-drop ink-drop--teal"></div>
      <div class="ink-drop ink-drop--violet"></div>
    </div>

    <div class="login-stage__particles" aria-hidden="true">
      <span v-for="i in 48" :key="i" class="particle" :style="{
        '--x': `${Math.random() * 100}%`,
        '--y': `${Math.random() * 100}%`,
        '--d': `${8 + Math.random() * 12}s`,
        '--delay': `-${Math.random() * 12}s`,
        '--scale': `${0.4 + Math.random() * 0.8}`,
      }"></span>
    </div>

    <div class="login-stage__mask" aria-hidden="true"></div>

    <div class="login-stage__content">
      <div class="login-grid">
        <section class="login-hero login-glass">
          <div class="stack stack--lg">
            <p class="eyebrow reveal">{{ t('register.eyebrow') }}</p>
            <h1 class="display-title text-gradient reveal reveal-delay-1" v-html="t('register.title')"></h1>
            <p class="lead reveal reveal-delay-2">{{ t('register.lead') }}</p>

            <ul class="register-hero__highlights reveal reveal-delay-3">
              <li v-for="key in ['private','secure','history','export']" :key="key">
                <span class="register-hero__check" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none">
                    <path d="M5 13l4 4 10-10" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
                <span>{{ t(`register.highlights.${key}`) }}</span>
              </li>
            </ul>
          </div>
        </section>

        <form class="auth-card login-glass login-glass--gold stack ai-glow-border ai-glow-border--intense" @submit.prevent="handleSubmit" aria-labelledby="register-title">
          <div class="stack">
            <p class="eyebrow">{{ t('register.card.eyebrow') }}</p>
            <h2 id="register-title" class="section-title">{{ t('register.card.title') }}</h2>
            <p class="subtitle">{{ t('register.card.subtitle') }}</p>
          </div>

          <transition name="alert">
            <div v-if="error" class="status-pill status-pill--danger" role="alert">{{ error }}</div>
          </transition>

          <div class="stack">
            <label class="field">
              <span class="field__label">{{ t('register.username') }}</span>
              <input v-model="username" class="input" :placeholder="t('register.usernamePlaceholder')" required autocomplete="username" spellcheck="false" />
            </label>

            <label class="field">
              <span class="field__label">{{ t('register.email') }}</span>
              <input v-model="email" type="email" class="input" :placeholder="t('register.emailPlaceholder')" required autocomplete="email" spellcheck="false" />
            </label>

            <label class="field">
              <span class="field__label">{{ t('register.password') }}</span>
              <input
                v-model="password"
                type="password"
                class="input"
                :placeholder="t('register.passwordPlaceholder')"
                required
                autocomplete="new-password"
              />
              <div class="password-meter" :data-strength="passwordStrength">
                <span v-for="i in 5" :key="i" :class="['password-meter__bar', i <= passwordStrength ? 'is-active' : '']"></span>
              </div>
              <ul class="password-rules">
                <li
                  v-for="check in passwordChecks"
                  :key="check.key"
                  :class="['password-rule', check.ok ? 'password-rule--ok' : '']"
                >
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="none" aria-hidden="true">
                    <path v-if="check.ok" d="M5 13l4 4 10-10" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
                    <circle v-else cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
                  </svg>
                  {{ check.label }}
                </li>
              </ul>
            </label>

            <label class="field">
              <span class="field__label">{{ t('register.confirmPassword') }}</span>
              <input
                v-model="confirmPassword"
                type="password"
                class="input"
                :placeholder="t('register.confirmPasswordPlaceholder')"
                required
                autocomplete="new-password"
                :class="{ 'input--invalid': !passwordsMatch }"
              />
              <span v-if="!passwordsMatch" class="field__help" style="color: var(--danger);">{{ t('register.error.mismatch') }}</span>
            </label>
          </div>

          <div class="stack">
            <button type="submit" class="button button--primary auth-submit" :disabled="!canSubmit">
              <span v-if="loading" class="auth-spinner" aria-hidden="true"></span>
              <span>{{ loading ? t('register.submitting') : t('register.submit') }}</span>
            </button>
            <p class="help-text auth-footnote">
              {{ t('register.haveAccount') }}
              <router-link to="/login" class="auth-link">{{ t('register.goLogin') }}</router-link>
            </p>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 复用 LoginView 同款舞台 + 玻璃拟态。两个页面共享视觉语言。
   如果以后要复用到更多页面，再抽 AuthBackground 组件。 */
.login-stage {
  position: relative;
  /* 与 LoginView 保持同一份"舞台"：去掉 -32px/-80px 的负 margin 让 flex 居中真的生效。 */
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
  opacity: 0.7;
}

.login-stage__ink {
  position: absolute;
  inset: 0;
  z-index: -2;
  background: radial-gradient(ellipse at top right, rgba(54, 216, 180, 0.06), transparent 50%),
              radial-gradient(ellipse at bottom left, rgba(242, 185, 92, 0.05), transparent 50%);
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
  right: -10vmin;
  background: radial-gradient(circle at 30% 30%, rgba(242, 185, 92, 0.55), transparent 65%);
  animation-delay: 0s;
}

.ink-drop--teal {
  top: 25vmin;
  left: -15vmin;
  background: radial-gradient(circle at 40% 40%, rgba(54, 216, 180, 0.5), transparent 65%);
  animation-delay: -8s;
}

.ink-drop--violet {
  bottom: -20vmin;
  right: 25vmin;
  background: radial-gradient(circle at 50% 50%, rgba(132, 110, 220, 0.42), transparent 65%);
  animation-delay: -16s;
}

@keyframes ink-float {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  33%      { transform: translate3d(-8vmin, -6vmin, 0) scale(1.08); }
  66%      { transform: translate3d(6vmin, 4vmin, 0) scale(0.92); }
}

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

.login-stage__mask {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    linear-gradient(180deg, rgba(8, 10, 14, 0.62) 0%, rgba(8, 10, 14, 0.38) 50%, rgba(8, 10, 14, 0.74) 100%),
    radial-gradient(ellipse at center, transparent 0%, rgba(8, 10, 14, 0.42) 100%);
  pointer-events: none;
}

.login-stage__content {
  position: relative;
  padding: 64px 24px 96px;
  max-width: var(--page-width-wide);
  margin: 0 auto;
  width: 100%;
}

.login-grid {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 36px;
  align-items: stretch;
  margin-top: 8px;
}

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

.login-hero {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.register-hero__highlights {
  display: grid;
  gap: 12px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.register-hero__highlights li {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 0.96rem;
  color: var(--text-soft);
}

.register-hero__check {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(54, 216, 180, 0.16);
  color: var(--primary);
}

.password-meter {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 4px;
  margin-top: 6px;
}

.password-meter__bar {
  height: 4px;
  border-radius: 2px;
  background: rgba(120, 132, 145, 0.18);
  transition: background 220ms ease;
}

.password-meter[data-strength="1"] .password-meter__bar.is-active { background: var(--danger); }
.password-meter[data-strength="2"] .password-meter__bar.is-active { background: #f59e0b; }
.password-meter[data-strength="3"] .password-meter__bar.is-active { background: var(--warning); }
.password-meter[data-strength="4"] .password-meter__bar.is-active { background: var(--primary); }
.password-meter[data-strength="5"] .password-meter__bar.is-active { background: linear-gradient(90deg, var(--primary), #b6f077); }

.password-rules {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.password-rule {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 0.78rem;
  color: var(--text-muted);
  transition: color 200ms ease;
}

.password-rule--ok {
  color: var(--primary);
}

.input--invalid {
  border-color: rgba(248, 113, 113, 0.42);
  box-shadow: 0 0 0 3px rgba(248, 113, 113, 0.10);
}

.auth-card {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 22px;
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

@media (max-width: 1100px) {
  .login-grid {
    grid-template-columns: 1fr;
  }
  .login-glass {
    padding: 28px;
  }
}

@media (max-width: 640px) {
  .login-stage__content {
    padding: 40px 16px 80px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ink-drop, .particle, .login-glass--gold {
    animation: none !important;
  }
  .login-stage__video {
    display: none;
  }
}
</style>
