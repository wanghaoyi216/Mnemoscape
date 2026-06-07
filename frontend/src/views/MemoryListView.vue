<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { fallbackSceneCover, images, videos } from '../assets/media-catalog'
import { useDynamicMedia } from '../composables/useDynamicMedia'
import AmbientEnvelopes from '../components/layout/AmbientEnvelopes.vue'

const dynamicMedia = useDynamicMedia()

// Hero 区背景 — 记忆星冕图营造"记忆库"宏观氛围
const heroBg = images.memoryCorona.src
const heroVideo = videos.auroraMemoryRiver.src
const rippleVideo = videos.memoryRipple.src

// 单条记忆缺少 sceneDataUrl 时的封面：
// 优先使用 MinIO / 本地资源池里的图（70+ 多模态素材），
// 池为空时再退回到内置静态 media-catalog
function coverFor(memoryId: string, sceneDataUrl?: string) {
  if (sceneDataUrl && (/^https?:\/\//.test(sceneDataUrl) || sceneDataUrl.startsWith('/'))) return sceneDataUrl
  const dyn = dynamicMedia.pickPhoto(memoryId) || dynamicMedia.pickGif(memoryId)
  if (dyn) return dyn.url
  const asset = fallbackSceneCover(memoryId)
  return asset.thumb ?? asset.src
}

const store = useMemoryStore()
const { t, locale } = useI18n()
const privacyFilter = ref('')
const query = ref('')
const guideOpen = ref(true)

const totalMemories = computed(() => store.memories.length)
const lockedMemories = computed(() => store.memories.filter((memory) => memory.isLocked).length)
const publicMemories = computed(() => store.memories.filter((memory) => memory.privacyLevel === 'PUBLIC').length)
const averageFade = computed(() => {
  if (!store.memories.length) return '0%'
  const avg = store.memories.reduce((sum, memory) => sum + (memory.fadeLevel || 0), 0) / store.memories.length
  return `${Math.round(avg * 100)}%`
})
const filteredMemories = computed(() => {
  const term = query.value.trim().toLowerCase()
  if (!term) return store.memories
  return store.memories.filter((memory) => [
    memory.title,
    memory.description,
    memory.memoryLocation,
    memory.memorySeason,
    memory.memoryTimeOfDay,
  ].filter(Boolean).some((value) => String(value).toLowerCase().includes(term)))
})

onMounted(() => {
  store.fetchList()
})

function applyPrivacyFilter() {
  store.fetchList(0, 12, privacyFilter.value || undefined)
}

function formatDate(value?: string) {
  if (!value) return t('common.empty')
  // 跟随当前 locale 渲染日期，避免中文页面里夹一堆英文月份缩写
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric', year: 'numeric' }).format(new Date(value))
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      class="hero-card hero-card--split memory-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.78) 0%, rgba(8,10,14,0.4) 55%, rgba(8,10,14,0.85) 100%), url(${heroBg})` }"
    >
      <video class="memory-hero__video" :src="heroVideo" autoplay muted loop playsinline aria-hidden="true"></video>
      <div class="memory-hero__grain" aria-hidden="true"></div>
      <div class="stack stack--lg">
        <p class="eyebrow reveal">{{ t('memory.list.title') }}</p>
        <h1 class="display-title text-gradient reveal reveal-delay-1">
          {{ t('memory.list.title') }}
        </h1>
        <p class="lead reveal reveal-delay-2">{{ t('memory.list.subtitle') }}</p>
      </div>

      <div class="metric-grid reveal reveal-delay-3">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('profile.stats.memories') }}</span>
          <strong class="metric-card__value">{{ totalMemories }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.detail.lock') }}</span>
          <strong class="metric-card__value">{{ lockedMemories }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.list.fadeLevel') }}</span>
          <strong class="metric-card__value">{{ averageFade }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.list.privacy.PUBLIC') }}</span>
          <strong class="metric-card__value">{{ publicMemories }}</strong>
        </div>
      </div>
    </section>

    <!-- 时空手账与导览手册 (Museum Guide) -->
    <section class="section-card museum-guide" style="margin-top: 24px;">
      <div class="museum-guide__header" @click="guideOpen = !guideOpen" role="button" tabindex="0" @keydown.enter="guideOpen = !guideOpen">
        <div class="stack" style="gap: 4px;">
          <h2 class="museum-guide__title">{{ t('memory.list.guide.title') }}</h2>
          <p class="subtitle" style="margin: 0; font-size: 0.86rem;">{{ t('memory.list.guide.subtitle') }}</p>
        </div>
        <button type="button" class="button button--ghost guide-toggle-btn" :aria-label="guideOpen ? t('memory.list.guide.toggleCollapse') : t('memory.list.guide.toggleExpand')">
          <span>{{ guideOpen ? t('memory.list.guide.toggleCollapse') : t('memory.list.guide.toggleExpand') }}</span>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" class="guide-toggle-icon" :class="{ 'guide-toggle-icon--collapsed': !guideOpen }">
            <path d="M18 15l-6-6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>

      <transition name="guide-panel">
        <div v-show="guideOpen" class="museum-guide__content" style="margin-top: 20px; border-top: 1px solid var(--border); padding-top: 20px;">
          <div class="guide-grid">
            <div class="guide-col">
              <h3 class="guide-col__title">{{ t('memory.list.guide.reconstruction.title') }}</h3>
              <p class="guide-col__desc">{{ t('memory.list.guide.reconstruction.desc') }}</p>
            </div>
            <div class="guide-col">
              <h3 class="guide-col__title">{{ t('memory.list.guide.drift.title') }}</h3>
              <p class="guide-col__desc">{{ t('memory.list.guide.drift.desc') }}</p>
            </div>
            <div class="guide-col">
              <h3 class="guide-col__title">{{ t('memory.list.guide.fragments.title') }}</h3>
              <p class="guide-col__desc">{{ t('memory.list.guide.fragments.desc') }}</p>
            </div>
            <div class="guide-col">
              <h3 class="guide-col__title">{{ t('memory.list.guide.resonance.title') }}</h3>
              <p class="guide-col__desc">{{ t('memory.list.guide.resonance.desc') }}</p>
            </div>
          </div>
        </div>
      </transition>
    </section>

    <section class="stack" style="margin-top: 32px;">
      <div class="page-shell__header" style="margin-bottom: 0;">
        <div>
          <h2 class="section-title">{{ t('memory.list.title') }}</h2>
          <p class="subtitle">{{ t('memory.list.subtitle') }}</p>
        </div>

        <RouterLink to="/memories/new" class="button button--primary">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
          </svg>
          <span>{{ t('memory.list.createButton') }}</span>
        </RouterLink>
      </div>

      <div class="section-card collection-toolbar">
        <video class="collection-toolbar__motion" :src="rippleVideo" autoplay muted loop playsinline aria-hidden="true"></video>
        <label class="field">
          <span class="field__label">{{ t('common.search') }}</span>
          <div class="search-input">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
              <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8" />
              <path d="M21 21l-4.3-4.3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <input v-model="query" class="input" :placeholder="t('common.search')" />
          </div>
        </label>

        <label class="field">
          <span class="field__label">{{ t('memory.builder.fields.privacy') }}</span>
          <select v-model="privacyFilter" class="select" @change="applyPrivacyFilter">
            <option value="">{{ t('common.more') }}</option>
            <option value="PRIVATE">{{ t('memory.list.privacy.PRIVATE') }}</option>
            <option value="FRIENDS">{{ t('memory.list.privacy.FRIENDS') }}</option>
            <option value="PUBLIC">{{ t('memory.list.privacy.PUBLIC') }}</option>
          </select>
        </label>
      </div>

      <div v-if="store.loadingList" class="section-card loading-state">
        <span class="loading-state__bar"></span>
        <span class="loading-state__bar"></span>
        <span class="loading-state__bar"></span>
      </div>

      <div v-else-if="store.error" class="section-card empty-state" role="alert">
        <h3 class="empty-state__title">{{ t('memory.list.error.title') }}</h3>
        <p class="empty-state__text">{{ t('memory.list.error.summary') }}</p>
        <p v-if="store.errorStatus && store.errorStatus >= 500" class="help-text">
          {{ t('memory.list.error.backendHint') }}
        </p>
        <p v-if="store.errorRequestId" class="help-text">
          {{ t('memory.list.error.requestId', { id: store.errorRequestId }) }}
        </p>
        <details v-if="store.errorStatus && store.errorStatus < 500" class="memory-error-details">
          <summary>{{ t('memory.list.error.detailsToggle') }}</summary>
          <p class="help-text">{{ store.error }}</p>
        </details>
        <button type="button" class="button button--secondary" @click="applyPrivacyFilter">{{ t('common.retry') }}</button>
      </div>

      <transition-group
        v-else-if="filteredMemories.length > 0"
        tag="div"
        name="list-stagger"
        class="card-grid"
      >
        <RouterLink
          v-for="(memory, idx) in filteredMemories"
          :key="memory.id"
          :to="`/memories/${memory.id}`"
          class="memory-card-link"
          :style="{ transitionDelay: `${Math.min(idx * 40, 320)}ms` }"
        >
          <article class="memory-card">
            <!-- 封面：若后端给了真实缩略 URL 就用，否则按 id 稳定哈希到一张占位画 -->
            <div
              class="memory-card__cover"
              :style="{ backgroundImage: `linear-gradient(180deg, rgba(8,10,14,0.05) 0%, rgba(8,10,14,0.75) 100%), url(${coverFor(memory.id, memory.sceneDataUrl)})` }"
              aria-hidden="true"
            ></div>
            <div class="memory-card__top">
              <div class="memory-card__accent" :class="{ 'memory-card__accent--locked': memory.isLocked }" aria-hidden="true">
                <svg v-if="memory.isLocked" viewBox="0 0 24 24" width="13" height="13" fill="none">
                  <rect x="5" y="11" width="14" height="9" rx="2" stroke="currentColor" stroke-width="1.6" />
                  <path d="M8 11V8a4 4 0 018 0v3" stroke="currentColor" stroke-width="1.6" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="13" height="13" fill="none">
                  <path d="M12 2v6m0 0l-3-3m3 3l3-3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                  <circle cx="12" cy="14" r="6" stroke="currentColor" stroke-width="1.6" />
                </svg>
              </div>
              <div class="memory-card__meta">
                <span class="status-pill" :class="memory.isLocked ? 'status-pill--warning' : 'status-pill--accent'">
                  {{ memory.isLocked ? t('memory.detail.lock') : t('common.yes') }}
                </span>
                <span class="chip">{{ t(`memory.list.privacy.${memory.privacyLevel}`) }}</span>
              </div>
            </div>

            <div class="stack" style="gap: 10px;">
              <h3 class="memory-card__title">{{ memory.title }}</h3>
              <p class="memory-card__desc">{{ memory.description }}</p>
            </div>

            <div class="memory-card__drift">
              <div class="memory-card__drift-meta">
                <span class="memory-card__drift-label">{{ t('memory.detail.drift') }}</span>
                <span class="memory-card__drift-value">{{ Math.round((memory.fadeLevel || 0) * 100) }}%</span>
              </div>
              <div class="fade-bar">
                <div class="fade-bar__fill" :style="{ width: `${(memory.fadeLevel || 0) * 100}%` }"></div>
              </div>
            </div>

            <div class="memory-card__footer">
              <span v-if="memory.memoryYear" class="chip">{{ memory.memoryYear }}</span>
              <span v-if="memory.memoryLocation" class="chip">{{ memory.memoryLocation }}</span>
              <span class="chip">{{ formatDate(memory.memoryDate || memory.createdAt) }}</span>
            </div>
          </article>
        </RouterLink>
      </transition-group>

      <div class="section-card empty-state memory-empty" v-else>
        <!-- 「记忆守护者」插画 — 没有记忆时的视觉锚 -->
        <img class="memory-empty__art" :src="images.memoryGuardian.src" alt="" aria-hidden="true" />
        <h3 class="empty-state__title">{{ t('memory.list.empty') }}</h3>
        <p class="empty-state__text">{{ t('memory.list.subtitle') }}</p>
        <RouterLink to="/memories/new" class="button button--primary">{{ t('memory.list.createButton') }}</RouterLink>
      </div>
    </section>

    <!-- 漂浮的记忆信封 -->
    <AmbientEnvelopes />
  </div>
</template>

<style scoped>
.collection-toolbar {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 320px);
  gap: 20px;
  align-items: end;
}

.collection-toolbar > * {
  position: relative;
  z-index: 1;
}

.collection-toolbar__motion {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.16;
  filter: saturate(1.2) contrast(1.05);
  mix-blend-mode: screen;
  pointer-events: none;
}

.search-input {
  position: relative;
  display: flex;
  align-items: center;
}

.search-input svg {
  position: absolute;
  left: 16px;
  color: var(--text-muted);
  pointer-events: none;
}

.search-input .input {
  padding-left: 44px;
}

.loading-state {
  display: grid;
  gap: 14px;
  padding: 24px;
}

.loading-state__bar {
  height: 24px;
  border-radius: var(--radius-sm);
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(255, 255, 255, 0.10) 50%,
    rgba(255, 255, 255, 0.04) 100%
  );
  background-size: 240% 100%;
  animation: shimmer-bar 1.4s ease-in-out infinite;
}

.loading-state__bar:nth-child(2) { width: 78%; }
.loading-state__bar:nth-child(3) { width: 54%; }

@keyframes shimmer-bar {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}

.memory-card-link {
  display: block;
  min-width: 0;
}

.memory-hero {
  position: relative;
  min-height: 360px;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
  isolation: isolate;
}

.memory-hero > :not(.memory-hero__video):not(.memory-hero__grain) {
  position: relative;
  z-index: 1;
}

.memory-hero__video {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.34;
  filter: saturate(1.18) contrast(1.08) brightness(0.78);
  pointer-events: none;
}

.memory-hero__grain {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(180deg, rgba(255, 255, 255, 0.026) 1px, transparent 1px),
    radial-gradient(circle at 22% 18%, rgba(54, 216, 180, 0.18), transparent 34%),
    radial-gradient(circle at 84% 72%, rgba(242, 185, 92, 0.12), transparent 38%);
  background-size: 44px 44px, 44px 44px, auto, auto;
  mix-blend-mode: screen;
  opacity: 0.5;
}

.memory-card__cover {
  height: 140px;
  margin: -26px -26px 0;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  border-top-left-radius: var(--radius-md);
  border-top-right-radius: var(--radius-md);
}

.memory-card {
  height: 100%;
  padding: 26px;
  display: grid;
  gap: 22px;
  align-content: start;
  transition:
    transform var(--duration-base) var(--ease-out-quart),
    border-color var(--duration-base) var(--ease-out-quart),
    box-shadow var(--duration-base) var(--ease-out-quart);
  position: relative;
  overflow: hidden;
}

.memory-card::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.10), transparent 60%);
  opacity: 0;
  transition: opacity var(--duration-base) var(--ease-out-quart);
  pointer-events: none;
}

.memory-card:hover {
  transform: translateY(-6px);
  border-color: var(--border-accent);
  box-shadow: var(--shadow-lg), 0 0 0 1px var(--border-accent);
}

.memory-card:hover::after {
  opacity: 1;
}

.memory-card__top {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.memory-card__accent {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  color: #052017;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  box-shadow: 0 0 0 4px rgba(54, 216, 180, 0.12);
}

.memory-card__accent--locked {
  background: linear-gradient(135deg, var(--gold), #ef6f7a);
  box-shadow: 0 0 0 4px rgba(242, 185, 92, 0.14);
}

.memory-card__meta,
.memory-card__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.memory-card__title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.28rem;
  font-weight: 600;
  letter-spacing: -0.015em;
  line-height: 1.3;
}

.memory-card__desc {
  margin: 0;
  color: var(--text-muted);
  line-height: 1.65;
  min-height: 4.5em;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 0.92rem;
}

.memory-card__drift {
  display: grid;
  gap: 8px;
  position: relative;
  z-index: 1;
}

.memory-card__drift-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.memory-card__drift-label {
  font-size: 0.72rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.memory-card__drift-value {
  font-family: var(--font-display);
  font-size: 0.96rem;
  color: var(--text);
}

.memory-card__footer {
  justify-content: flex-start;
}

.memory-empty {
  text-align: center;
  padding: 36px 24px 32px;
}

.memory-error-details {
  max-width: 56rem;
  margin: 0 auto;
  text-align: left;
}

.memory-error-details summary {
  cursor: pointer;
  color: var(--text-soft);
  font-size: 0.85rem;
  font-weight: 600;
}

.memory-error-details p {
  margin: 12px 0 0;
}

.memory-empty__art {
  width: 180px;
  height: 180px;
  object-fit: cover;
  border-radius: 50%;
  margin: 0 auto 18px;
  filter: drop-shadow(0 12px 30px rgba(242, 185, 92, 0.18));
}

.empty-state__icon {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  margin: 0 auto 18px;
  border-radius: var(--radius-md);
  background: rgba(54, 216, 180, 0.08);
  color: var(--primary);
  border: 1px solid var(--border-accent);
}

@media (max-width: 768px) {
  .collection-toolbar {
    grid-template-columns: 1fr;
  }
}

/* ============== 时空手账与导览手册 ============== */
.museum-guide {
  transition: border-color var(--duration-base) var(--ease-out-quart),
              box-shadow var(--duration-base) var(--ease-out-quart);
}

.museum-guide:hover {
  border-color: rgba(54, 216, 180, 0.24);
  box-shadow: var(--shadow-md), 0 0 20px rgba(54, 216, 180, 0.05);
}

.museum-guide__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  user-select: none;
  gap: 16px;
}

.museum-guide__title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text);
  letter-spacing: -0.01em;
}

.guide-toggle-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px !important;
  padding: 0 10px !important;
  font-size: 0.8rem !important;
  border-radius: var(--radius-full);
}

.guide-toggle-icon {
  transition: transform var(--duration-base) var(--ease-out-quart);
}

.guide-toggle-icon--collapsed {
  transform: rotate(180deg);
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 20px;
}

.guide-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.35);
  border: 1px solid var(--border);
  transition: border-color 0.22s ease, background 0.22s ease;
}

.guide-col:hover {
  border-color: var(--border-strong);
  background: rgba(14, 17, 22, 0.55);
}

.guide-col__title {
  margin: 0;
  font-family: var(--font-sans);
  font-size: 0.94rem;
  font-weight: 700;
  color: var(--text);
}

.guide-col__desc {
  margin: 0;
  font-size: 0.82rem;
  line-height: 1.6;
  color: var(--text-soft);
}

/* Panel transition */
.guide-panel-enter-active,
.guide-panel-leave-active {
  transition: all 300ms cubic-bezier(0.16, 1, 0.3, 1);
  max-height: 500px;
  overflow: hidden;
}

.guide-panel-enter-from,
.guide-panel-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0 !important;
  margin-top: 0 !important;
  transform: translateY(-8px);
}
</style>
