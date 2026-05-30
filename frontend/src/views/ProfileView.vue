<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { useMemoryStore } from '../stores/memory'
import EmotionAttractor, { type EmotionProfile } from '../components/memory/EmotionAttractor.vue'
import UserAvatar3D from '../components/profile/UserAvatar3D.vue'
import UserAvatar3DBuilder from '../components/profile/UserAvatar3DBuilder.vue'
import { images } from '../assets/media-catalog'
import client from '../api/client'
import { getMyAvatarProfile, type AvatarProfile } from '../api/avatar'

const auth = useAuthStore()
const memory = useMemoryStore()
const { t, locale } = useI18n()

// ── 3D 刻画模块 ──────────────────────────────────────────────────────────
const avatarProfile = ref<AvatarProfile | null>(null)
const avatarLoading = ref(false)
const showAvatarBuilder = ref(false)

async function loadAvatarProfile() {
  avatarLoading.value = true
  try {
    const { data } = await getMyAvatarProfile()
    avatarProfile.value = data.data
    // 若已有档案，默认不展开构建器
    showAvatarBuilder.value = !data.data
  } catch {
    // 静默 — 3D 刻画加载失败不阻塞个人页
  } finally {
    avatarLoading.value = false
  }
}

function onAvatarSaved(profile: AvatarProfile) {
  avatarProfile.value = profile
  showAvatarBuilder.value = false
}

function onAvatarDeleted() {
  avatarProfile.value = null
  showAvatarBuilder.value = true
}

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

// 情绪积分 — 动态从用户真实的记忆库中统计计算得出 (Lorenz Attractor 真实轨迹数据源)。
// 统计全部记忆的 emotionProfile 并求均值，若无数据则平滑兜底。
const realEmotion = computed<EmotionProfile>(() => {
  const list = memory.memories
  if (!list || list.length === 0) {
    return {
      joy: 0.62,
      sorrow: 0.31,
      fear: 0.18,
      calm: 0.55,
      nostalgia: 0.71,
    }
  }

  let totalJoy = 0
  let totalSorrow = 0
  let totalFear = 0
  let totalCalm = 0
  let totalNostalgia = 0
  let validCount = 0

  for (const m of list) {
    const raw = (m as any).emotionProfile
    if (!raw || typeof raw !== 'string') continue
    try {
      const vec = JSON.parse(raw) as Record<string, number>
      if (vec && typeof vec === 'object') {
        totalJoy += typeof vec.joy === 'number' ? vec.joy : 0
        const sad = typeof vec.sadness === 'number' ? vec.sadness : 0
        const mel = typeof vec.melancholy === 'number' ? vec.melancholy : 0
        totalSorrow += Math.max(sad, mel)
        totalFear += typeof vec.fear === 'number' ? vec.fear : 0
        totalCalm += typeof vec.peace === 'number' ? vec.peace : (typeof vec.calm === 'number' ? vec.calm : 0)
        totalNostalgia += typeof vec.nostalgia === 'number' ? vec.nostalgia : 0
        validCount++
      }
    } catch {
      // ignore
    }
  }

  if (validCount === 0) {
    return {
      joy: 0.62,
      sorrow: 0.31,
      fear: 0.18,
      calm: 0.55,
      nostalgia: 0.71,
    }
  }

  return {
    joy: Math.min(1, Math.max(0, totalJoy / validCount)),
    sorrow: Math.min(1, Math.max(0, totalSorrow / validCount)),
    fear: Math.min(1, Math.max(0, totalFear / validCount)),
    calm: Math.min(1, Math.max(0, totalCalm / validCount)),
    nostalgia: Math.min(1, Math.max(0, totalNostalgia / validCount)),
  }
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
  // 加载 3D 刻画档案
  loadAvatarProfile()
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
            <input v-model="avatarInput" class="input" :placeholder="t('chat.sidebar.avatarUrlPlaceholder')" />
          </label>
          <label class="field">
            <span class="field__label">{{ locale === 'zh-CN' ? '时空壁纸链接' : 'Chat Wallpaper URL' }}</span>
            <input v-model="bgInput" class="input" :placeholder="t('chat.sidebar.bgUrlPlaceholder')" />
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

    <!-- ══════════════════════════════════════════════════════════════════
         用户 3D 刻画模块
         ══════════════════════════════════════════════════════════════════ -->
    <section v-if="auth.user" class="section-card avatar3d-section" style="margin-top: 24px; padding: 28px;">
      <header class="page-shell__header" style="margin-bottom: 20px;">
        <div>
          <p class="eyebrow">{{ t('profile.avatar3d.eyebrow') }}</p>
          <h2 class="section-title">{{ t('profile.avatar3d.title') }}</h2>
          <p class="subtitle">{{ t('profile.avatar3d.subtitle') }}</p>
        </div>
        <div class="avatar3d-header-actions">
          <button
            v-if="avatarProfile && !showAvatarBuilder"
            type="button"
            class="button button--ghost"
            style="font-size: 0.82rem; padding: 6px 14px;"
            @click="showAvatarBuilder = true"
          >
            ✏️ {{ t('profile.avatar3d.editBtn') }}
          </button>
          <button
            v-if="showAvatarBuilder && avatarProfile"
            type="button"
            class="button button--ghost"
            style="font-size: 0.82rem; padding: 6px 14px;"
            @click="showAvatarBuilder = false"
          >
            {{ t('common.cancel') }}
          </button>
        </div>
      </header>

      <!-- 加载中 -->
      <div v-if="avatarLoading" class="avatar3d-loading">
        <div class="avatar3d-loading__spinner"></div>
        <span>{{ t('common.loading') }}</span>
      </div>

      <!-- 已有档案：展示 3D 形象 -->
      <template v-else-if="avatarProfile && !showAvatarBuilder">
        <div class="avatar3d-display-grid">
          <!-- 3D 渲染区 -->
          <div class="avatar3d-display-canvas">
            <UserAvatar3D :profile="avatarProfile" :height="380" />
          </div>

          <!-- 角色信息面板 -->
          <aside class="avatar3d-info-panel stack">
            <div>
              <p class="eyebrow" style="font-size: 0.7rem;">{{ t('profile.avatar3d.info.titleLabel') }}</p>
              <h3 class="avatar3d-info-title">{{ avatarProfile.avatarTitle }}</h3>
            </div>

            <div>
              <p class="eyebrow" style="font-size: 0.7rem; margin-bottom: 8px;">{{ t('profile.avatar3d.info.storyLabel') }}</p>
              <p class="avatar3d-info-story">{{ avatarProfile.avatarStory }}</p>
            </div>

            <div>
              <p class="eyebrow" style="font-size: 0.7rem; margin-bottom: 8px;">{{ t('profile.avatar3d.info.tagsLabel') }}</p>
              <div class="avatar3d-info-tags">
                <span
                  v-for="tag in (JSON.parse(avatarProfile.personalityTags || '[]') as string[])"
                  :key="tag"
                  class="avatar3d-info-tag"
                >{{ tag }}</span>
              </div>
            </div>

            <div>
              <p class="eyebrow" style="font-size: 0.7rem; margin-bottom: 8px;">{{ t('profile.avatar3d.info.descLabel') }}</p>
              <p class="avatar3d-info-desc">{{ avatarProfile.selfDescription }}</p>
            </div>

            <div class="avatar3d-info-meta">
              <span class="avatar3d-info-public" :class="avatarProfile.isPublic ? 'public--on' : 'public--off'">
                {{ avatarProfile.isPublic ? t('profile.avatar3d.info.public') : t('profile.avatar3d.info.private') }}
              </span>
              <span class="avatar3d-info-date">
                {{ t('profile.avatar3d.info.updatedAt') }}
                {{ new Date(avatarProfile.updatedAt).toLocaleDateString(locale === 'zh-CN' ? 'zh-CN' : 'en-US') }}
              </span>
            </div>
          </aside>
        </div>
      </template>

      <!-- 未创建或编辑模式：展示构建器 -->
      <template v-else-if="!avatarLoading">
        <!-- 引导提示（首次创建） -->
        <div v-if="!avatarProfile && !showAvatarBuilder" class="avatar3d-empty">
          <div class="avatar3d-empty__icon">✨</div>
          <h3 class="avatar3d-empty__title">{{ t('profile.avatar3d.empty.title') }}</h3>
          <p class="avatar3d-empty__text">{{ t('profile.avatar3d.empty.text') }}</p>
          <button
            type="button"
            class="button button--primary"
            @click="showAvatarBuilder = true"
          >
            {{ t('profile.avatar3d.empty.createBtn') }}
          </button>
        </div>

        <!-- 构建器 -->
        <UserAvatar3DBuilder
          v-if="showAvatarBuilder"
          :existing-profile="avatarProfile"
          @saved="onAvatarSaved"
          @deleted="onAvatarDeleted"
        />
      </template>
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
          <EmotionAttractor :emotion="realEmotion" />
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
                <span class="attractor-bar__value">{{ Math.round(realEmotion[dim.key as keyof EmotionProfile] * 100) }}%</span>
              </div>
              <div class="attractor-bar__track">
                <div
                  class="attractor-bar__fill"
                  :style="{ width: `${realEmotion[dim.key as keyof EmotionProfile] * 100}%`, background: dim.color, boxShadow: `0 0 10px ${dim.color}88` }"
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

/* ============== 用户 3D 刻画模块 ============== */
.avatar3d-section {
  border: 1px solid rgba(108, 99, 255, 0.15);
}

.avatar3d-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.avatar3d-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 120px;
  color: var(--text-soft);
  font-size: 0.88rem;
}

.avatar3d-loading__spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(108, 99, 255, 0.3);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.avatar3d-display-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.9fr);
  gap: 24px;
  align-items: start;
}

.avatar3d-display-canvas {
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.avatar3d-info-panel {
  padding: 20px;
  background: rgba(14, 17, 22, 0.55);
  border: 1px solid rgba(108, 99, 255, 0.15);
  border-radius: var(--radius-lg);
  backdrop-filter: blur(12px);
  gap: 18px;
}

.avatar3d-info-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--primary);
  margin: 4px 0 0;
  line-height: 1.4;
}

.avatar3d-info-story {
  font-size: 0.85rem;
  color: var(--text-soft);
  line-height: 1.7;
  margin: 0;
  font-style: italic;
}

.avatar3d-info-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.avatar3d-info-tag {
  font-size: 0.75rem;
  color: var(--primary);
  background: rgba(54, 216, 180, 0.1);
  border: 1px solid rgba(54, 216, 180, 0.2);
  border-radius: 999px;
  padding: 3px 10px;
}

.avatar3d-info-desc {
  font-size: 0.82rem;
  color: var(--text-muted);
  line-height: 1.6;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.avatar3d-info-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.avatar3d-info-public {
  font-size: 0.72rem;
  border-radius: 999px;
  padding: 2px 10px;
  font-weight: 600;
}

.public--on {
  color: var(--primary);
  background: rgba(54, 216, 180, 0.1);
  border: 1px solid rgba(54, 216, 180, 0.2);
}

.public--off {
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.avatar3d-info-date {
  font-size: 0.72rem;
  color: var(--text-muted);
}

/* 空状态 */
.avatar3d-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 200px;
  text-align: center;
  padding: 32px;
}

.avatar3d-empty__icon {
  font-size: 2.5rem;
  line-height: 1;
}

.avatar3d-empty__title {
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--text);
  margin: 0;
}

.avatar3d-empty__text {
  font-size: 0.85rem;
  color: var(--text-soft);
  max-width: 42ch;
  line-height: 1.6;
  margin: 0;
}

@media (max-width: 960px) {
  .avatar3d-display-grid {
    grid-template-columns: 1fr;
  }
}
</style>
