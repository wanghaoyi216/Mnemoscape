<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { useMemoryStore } from '../stores/memory'
import EmotionAttractor, { type EmotionProfile } from '../components/memory/EmotionAttractor.vue'
import { images } from '../assets/media-catalog'
import client from '../api/client'

const auth = useAuthStore()
const memory = useMemoryStore()
const { t, locale } = useI18n()

const heroBg = computed(() => (auth.user as any)?.backgroundImageUrl || images.heartSea.src)
const attractorBg = images.emotionPrism.src

const avatarInput = ref(auth.user?.avatarUrl || '')
const bgInput = ref((auth.user as any)?.backgroundImageUrl || '')
const saveMessage = ref('')

async function updateWallpaper(url: string) {
  bgInput.value = url
  await saveTheme(url, avatarInput.value)
}

async function saveManualTheme() {
  await saveTheme(bgInput.value, avatarInput.value)
}

async function saveTheme(bgUrl: string, avatarUrl: string) {
  try {
    saveMessage.value = ''
    const { data } = await client.put('/auth/profile', {
      avatarUrl: avatarUrl,
      backgroundImageUrl: bgUrl
    })
    
    // Sync local store
    auth.user = {
      ...auth.user,
      avatarUrl: data.data.avatarUrl
    } as any
    ;(auth.user as any).backgroundImageUrl = data.data.backgroundImageUrl
    
    saveMessage.value = locale.value === 'zh-CN' ? '✓ 时空美学主题已保存' : '✓ Space theme saved successfully'
    setTimeout(() => {
      saveMessage.value = ''
    }, 4000)
  } catch (e: any) {
    alert(e.response?.data?.message || 'Failed to save space theme')
  }
}

// 情绪积分 — 后端尚未暴露聚合接口，先用本地 mock 给视觉到位。
// 后续接 ai-service 后改成从 store / 接口拉。
const fakeEmotion = ref<EmotionProfile>({
  joy: 0.62,
  sorrow: 0.31,
  fear: 0.18,
  calm: 0.55,
  nostalgia: 0.71,
})

const memoryCount = computed(() => memory.memories.length)

onMounted(async () => {
  if (!auth.user) {
    auth.fetchProfile()
  }
  if (memory.memories.length === 0) {
    try {
      await memory.fetchList(0, 20)
    } catch {
      /* 静默 — 列表加载失败不阻塞个人页 */
    }
  }
})
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      v-if="auth.user"
      class="hero-card hero-card--split profile-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.78) 0%, rgba(8,10,14,0.42) 60%, rgba(8,10,14,0.85) 100%), url(${heroBg})` }"
    >
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('profile.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ auth.user.username }}</h1>
        <p class="lead">{{ t('profile.lead') }}</p>

        <div class="metric-grid">
          <div class="metric-card">
            <span class="metric-card__label">{{ t('profile.metrics.status') }}</span>
            <strong class="metric-card__value">
              {{ auth.user.verified ? t('profile.metrics.verified') : t('profile.metrics.pending') }}
            </strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('profile.metrics.email') }}</span>
            <strong class="metric-card__value metric-card__value--email" :title="auth.user.email">
              {{ auth.user.email }}
            </strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('profile.metrics.memories') }}</span>
            <strong class="metric-card__value">{{ memoryCount }}</strong>
          </div>
        </div>
      </div>

      <div class="section-card stack profile-hero__card">
        <div class="user-chip" style="width: fit-content;">
          <span class="user-chip__avatar">{{ auth.user.username.charAt(0).toUpperCase() }}</span>
          <span class="user-chip__meta">
            <strong>{{ auth.user.username }}</strong>
            <small>{{ auth.user.avatarUrl ? t('profile.avatar.custom') : t('profile.avatar.default') }}</small>
          </span>
        </div>
        <div class="chip-grid">
          <span class="chip">{{ t('profile.tags.privacy') }}</span>
          <span class="chip">{{ t('profile.tags.history') }}</span>
          <span class="chip">{{ t('profile.tags.resonance') }}</span>
        </div>
        <p class="help-text">{{ t('profile.note') }}</p>
      </div>
    </section>

    <!-- 时空美学壁纸定制面板 -->
    <section class="section-card theme-customizer-card reveal reveal-delay-3" v-if="auth.user" style="margin-top: 24px; padding: 28px;">
      <header class="page-shell__header" style="margin-bottom: 18px;">
        <div>
          <p class="eyebrow">{{ locale === 'zh-CN' ? '时空美学' : 'Space Aesthetics' }}</p>
          <h2 class="section-title">{{ locale === 'zh-CN' ? '时空馆藏背景与头像定制' : 'Curate Space Theme & Avatar' }}</h2>
          <p class="subtitle">{{ locale === 'zh-CN' ? '自由选择馆藏级时空艺术插画作为你的个人空间与聊天会话壁纸。' : 'Select cinematic museum-grade illustrations as your space and chat wallpaper backdrops.' }}</p>
        </div>
      </header>

      <div class="theme-picker-layout">
        <!-- Preset Picker Gallery -->
        <div class="preset-gallery-wrapper">
          <span class="field__label">{{ locale === 'zh-CN' ? '系统馆藏时空艺术画库' : 'System Art Presets' }}</span>
          <div class="preset-gallery-grid">
            <button
              v-for="(imgAsset, key) in images"
              :key="key"
              type="button"
              :class="['preset-art-item', (auth.user as any)?.backgroundImageUrl === imgAsset.src ? 'active' : '']"
              @click="updateWallpaper(imgAsset.src)"
              :title="imgAsset.origin"
            >
              <img :src="imgAsset.thumb || imgAsset.src" :alt="imgAsset.origin" />
              <span class="preset-art-item__label">{{ imgAsset.origin }}</span>
              <span class="preset-art-item__active-badge" v-if="(auth.user as any)?.backgroundImageUrl === imgAsset.src">✓</span>
            </button>
          </div>
        </div>

        <!-- Custom Manual Form -->
        <form class="manual-theme-form stack" @submit.prevent="saveManualTheme">
          <span class="field__label">{{ locale === 'zh-CN' ? '链接手动设置' : 'Manual Link Settings' }}</span>
          <label class="field">
            <span class="field__label">{{ locale === 'zh-CN' ? '头像链接' : 'Avatar URL' }}</span>
            <input v-model="avatarInput" class="input" placeholder="HTTP(S) Link to Avatar" />
          </label>
          <label class="field">
            <span class="field__label">{{ locale === 'zh-CN' ? '时空壁纸链接' : 'Chat Wallpaper URL' }}</span>
            <input v-model="bgInput" class="input" placeholder="HTTP(S) Link to Wallpaper" />
          </label>
          <button type="submit" class="button button--primary" style="margin-top: 8px; min-height: 40px; width: 100%;">
            {{ locale === 'zh-CN' ? '保存自定义设置' : 'Save Custom Settings' }}
          </button>
          <transition name="alert">
            <p v-if="saveMessage" class="status-pill status-pill--success" style="padding: 6px 12px; margin-top: 10px; font-size: 0.8rem; text-align: center; justify-content: center; width: 100%;">
              {{ saveMessage }}
            </p>
          </transition>
        </form>
      </div>
    </section>

    <!-- 情绪混沌吸引子 — 替代雷达图 -->
    <section
      v-if="auth.user"
      class="attractor-section section-card"
      :style="{ backgroundImage: `linear-gradient(180deg, rgba(8,10,14,0.86) 0%, rgba(8,10,14,0.68) 100%), url(${attractorBg})` }"
    >
      <header class="page-shell__header attractor-section__header">
        <div>
          <p class="eyebrow">{{ t('profile.attractor.eyebrow') }}</p>
          <h2 class="section-title">{{ t('profile.attractor.title') }}</h2>
          <p class="subtitle">{{ t('profile.attractor.subtitle') }}</p>
        </div>
      </header>

      <div class="attractor-section__grid">
        <div class="attractor-section__canvas">
          <EmotionAttractor :emotion="fakeEmotion" />
        </div>

        <aside class="attractor-section__legend stack">
          <p class="help-text attractor-section__intro">{{ t('profile.attractor.intro') }}</p>
          <div class="stack stack--sm">
            <div class="attractor-bar" v-for="dim in [
              { key: 'joy',       label: t('profile.attractor.dim.joy'),       color: '#f2b95c' },
              { key: 'nostalgia', label: t('profile.attractor.dim.nostalgia'), color: '#846edc' },
              { key: 'calm',      label: t('profile.attractor.dim.calm'),      color: '#36d8b4' },
              { key: 'sorrow',    label: t('profile.attractor.dim.sorrow'),    color: '#5f9ad8' },
              { key: 'fear',      label: t('profile.attractor.dim.fear'),      color: '#d8525f' },
            ]" :key="dim.key">
              <div class="attractor-bar__head">
                <span class="attractor-bar__label">{{ dim.label }}</span>
                <span class="attractor-bar__value">{{ Math.round(fakeEmotion[dim.key as keyof EmotionProfile] * 100) }}%</span>
              </div>
              <div class="attractor-bar__track">
                <div
                  class="attractor-bar__fill"
                  :style="{ width: `${fakeEmotion[dim.key as keyof EmotionProfile] * 100}%`, background: dim.color, boxShadow: `0 0 10px ${dim.color}88` }"
                ></div>
              </div>
            </div>
          </div>
          <p class="help-text attractor-section__note">{{ t('profile.attractor.mockNote') }}</p>
        </aside>
      </div>
    </section>
  </div>
</template>

<style scoped>
.profile-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.metric-card__value--email {
  font-size: 1.05rem !important;
  font-family: var(--font-mono);
  word-break: break-all;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  margin-top: 2px;
}

.profile-hero__card {
  background: rgba(14, 17, 22, 0.62);
  backdrop-filter: blur(18px) saturate(150%);
}

.attractor-section {
  margin-top: 24px;
  padding: 28px;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.attractor-section__header {
  margin-bottom: 22px;
}

.attractor-section__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 0.9fr);
  gap: 22px;
  align-items: stretch;
}

.attractor-section__canvas {
  min-height: 360px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  overflow: hidden;
  box-shadow: 0 24px 60px -28px rgba(0, 0, 0, 0.7);
}

.attractor-section__legend {
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.62);
  backdrop-filter: blur(14px);
}

.attractor-section__intro,
.attractor-section__note {
  margin: 0;
}

.attractor-bar__head {
  display: flex;
  justify-content: space-between;
  font-size: 0.82rem;
  color: var(--text-soft);
  margin-bottom: 6px;
}

.attractor-bar__value {
  font-variant-numeric: tabular-nums;
  color: var(--text);
}

.attractor-bar__track {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}

.attractor-bar__fill {
  height: 100%;
  border-radius: 999px;
  transition: width 600ms cubic-bezier(0.4, 0, 0.2, 1);
}

/* ============== 时空美学定制器 ============== */
.theme-picker-layout {
  display: grid;
  grid-template-columns: 1.35fr 0.85fr;
  gap: 24px;
}

.preset-gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(105px, 1fr));
  gap: 12px;
  max-height: 280px;
  overflow-y: auto;
  padding: 8px 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(8, 10, 14, 0.4);
}

.preset-gallery-grid::-webkit-scrollbar {
  width: 5px;
}
.preset-gallery-grid::-webkit-scrollbar-track {
  background: rgba(255,255,255,0.01);
}
.preset-gallery-grid::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.1);
  border-radius: 99px;
}

.preset-art-item {
  position: relative;
  background: rgba(14, 17, 22, 0.6);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  padding: 0;
  transition: all 0.24s cubic-bezier(0.165, 0.84, 0.44, 1);
  text-align: center;
}

.preset-art-item:hover {
  transform: translateY(-2px);
  border-color: var(--border-accent);
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.15);
}

.preset-art-item.active {
  border-color: var(--primary);
  box-shadow: 0 0 14px rgba(54, 216, 180, 0.35), inset 0 0 0 1px var(--primary);
}

.preset-art-item img {
  width: 100%;
  height: 60px;
  object-fit: cover;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.preset-art-item__label {
  font-size: 0.68rem;
  color: var(--text-muted);
  padding: 6px 4px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preset-art-item.active .preset-art-item__label {
  color: var(--primary);
  font-weight: 600;
}

.preset-art-item__active-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  background: var(--primary);
  color: #052017;
  font-size: 0.62rem;
  font-weight: 800;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(54, 216, 180, 0.4);
}

.manual-theme-form {
  padding: 20px;
  background: rgba(14, 17, 22, 0.35);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  align-self: start;
}

@media (max-width: 960px) {
  .theme-picker-layout {
    grid-template-columns: 1fr;
  }
  .attractor-section__grid {
    grid-template-columns: 1fr;
  }
  .attractor-section__canvas {
    min-height: 280px;
  }
}
</style>
