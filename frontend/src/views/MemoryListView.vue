<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { conceptIllustrations, fallbackSceneCover, images } from '../assets/media-catalog'
import { useDynamicMedia } from '../composables/useDynamicMedia'
import AmbientEnvelopes from '../components/layout/AmbientEnvelopes.vue'
import MuseumPortalGrid from '../components/home/MuseumPortalGrid.vue'

const dynamicMedia = useDynamicMedia()

// Hero 区背景 — 记忆星冕图营造"记忆库"宏观氛围
const heroBackdrop = conceptIllustrations[0]
const heroArtwork = conceptIllustrations[1]

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
const guideOpen = ref(false)

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
    >
      <div
        class="memory-hero__backdrop"
        :style="{ backgroundImage: `url(${heroBackdrop.src})` }"
        aria-hidden="true"
      ></div>
      <div class="memory-hero__copy">
        <div class="memory-hero__edition reveal">
          <span class="memory-hero__edition-dot" aria-hidden="true"></span>
          <span>{{ t('museum.portal.eyebrow') }}</span>
          <span aria-hidden="true">·</span>
          <span>Mnemoscape 08</span>
        </div>
        <div class="stack stack--lg">
          <h1 class="display-title reveal reveal-delay-1">{{ t('memory.list.title') }}</h1>
          <p class="lead reveal reveal-delay-2">{{ t('memory.list.subtitle') }}</p>
        </div>
        <div class="memory-hero__actions reveal reveal-delay-3">
          <RouterLink to="/memories/new" class="button button--primary">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" aria-hidden="true">
              <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
            <span>{{ t('memory.list.createButton') }}</span>
          </RouterLink>
          <RouterLink to="/memories/atlas" class="button button--secondary">
            <span>{{ t('nav.atlas') }}</span>
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" aria-hidden="true">
              <path d="M5 12h14m-5-5 5 5-5 5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </RouterLink>
        </div>
      </div>

      <figure class="memory-hero__visual reveal reveal-delay-2">
        <img
          :src="heroArtwork.src"
          :srcset="`${heroArtwork.thumb} 420w, ${heroArtwork.src} 1254w`"
          sizes="(max-width: 980px) 92vw, 42vw"
          :alt="t('museum.portal.title')"
          fetchpriority="high"
          decoding="async"
        />
        <figcaption class="memory-hero__caption">
          <span>{{ t('museum.portal.artNumber', { number: heroArtwork.number }) }}</span>
          <strong>{{ t('museum.portal.title') }}</strong>
        </figcaption>
        <div class="memory-hero__metrics">
          <div class="memory-hero__metric">
            <span>{{ t('profile.stats.memories') }}</span>
            <strong>{{ totalMemories }}</strong>
          </div>
          <div class="memory-hero__metric">
            <span>{{ t('memory.detail.lock') }}</span>
            <strong>{{ lockedMemories }}</strong>
          </div>
          <div class="memory-hero__metric">
            <span>{{ t('memory.list.fadeLevel') }}</span>
            <strong>{{ averageFade }}</strong>
          </div>
          <div class="memory-hero__metric">
            <span>{{ t('memory.list.privacy.PUBLIC') }}</span>
            <strong>{{ publicMemories }}</strong>
          </div>
        </div>
      </figure>
    </section>

    <MuseumPortalGrid />

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

      <div v-if="store.loadingList" class="card-grid" aria-busy="true" aria-label="加载中">
        <article v-for="n in 6" :key="`sk-${n}`" class="memory-card memory-card--skeleton">
          <div class="skeleton memory-card__cover"></div>
          <div class="memory-card__top">
            <div class="skeleton" style="width: 26px; height: 26px; border-radius: 999px;"></div>
            <div class="skeleton skeleton-line" style="width: 96px; margin: 0;"></div>
          </div>
          <div class="stack" style="gap: 10px;">
            <div class="skeleton skeleton-line skeleton-line--lg" style="height: 1.3em;"></div>
            <div class="skeleton skeleton-line skeleton-line--md"></div>
            <div class="skeleton skeleton-line skeleton-line--sm"></div>
          </div>
          <div class="skeleton" style="height: 8px; border-radius: 999px; margin-top: 8px;"></div>
          <div class="memory-card__footer">
            <div class="skeleton skeleton-line" style="width: 54px; margin: 0;"></div>
            <div class="skeleton skeleton-line" style="width: 72px; margin: 0;"></div>
          </div>
        </article>
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
  border-color: rgba(231, 224, 255, 0.12);
  background:
    radial-gradient(circle at 8% 0%, color-mix(in srgb, var(--primary) 9%, transparent), transparent 34%),
    rgba(16, 14, 31, 0.76);
}

.collection-toolbar > * {
  position: relative;
  z-index: 1;
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
  min-height: 540px;
  grid-template-columns: minmax(0, 0.86fr) minmax(420px, 0.74fr);
  align-items: center;
  gap: clamp(32px, 5vw, 76px);
  border: 1px solid rgba(231, 224, 255, 0.14);
  background:
    radial-gradient(circle at 12% 15%, color-mix(in srgb, var(--primary) 18%, transparent), transparent 34%),
    radial-gradient(circle at 86% 88%, color-mix(in srgb, var(--gold) 10%, transparent), transparent 38%),
    linear-gradient(145deg, rgba(25, 18, 49, 0.96), rgba(8, 7, 18, 0.92));
  isolation: isolate;
  box-shadow: 0 36px 90px rgba(2, 1, 9, 0.48), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.memory-hero > :not(.memory-hero__backdrop) {
  position: relative;
  z-index: 1;
}

.memory-hero__backdrop {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background-position: left center;
  background-size: 62% auto;
  background-repeat: no-repeat;
  opacity: 0.12;
  filter: saturate(0.85) contrast(1.08);
  -webkit-mask-image: linear-gradient(90deg, #000, transparent 68%);
  mask-image: linear-gradient(90deg, #000, transparent 68%);
  pointer-events: none;
}

.memory-hero__copy {
  display: grid;
  align-content: center;
  gap: 34px;
  max-width: 620px;
}

.memory-hero__copy .display-title {
  max-width: 8ch;
  font-size: clamp(3.3rem, 6vw, 6.3rem);
  line-height: 0.94;
  letter-spacing: -0.055em;
  color: var(--text);
}

.memory-hero__copy .lead {
  max-width: 42ch;
  color: var(--text-soft);
  font-size: 1.04rem;
}

.memory-hero__edition {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: 10px;
  color: var(--text-muted);
  font-size: 0.68rem;
  font-weight: 750;
  letter-spacing: 0.17em;
  text-transform: uppercase;
}

.memory-hero__edition-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 14px var(--primary-glow);
}

.memory-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.memory-hero__visual {
  position: relative;
  min-width: 0;
  margin: 0;
  aspect-ratio: 0.92;
  overflow: hidden;
  border: 1px solid rgba(241, 232, 255, 0.2);
  border-radius: clamp(22px, 3vw, 34px);
  background: #0c0920;
  box-shadow: 0 28px 70px rgba(3, 1, 12, 0.55), 0 0 0 8px rgba(216, 180, 254, 0.035), 0 0 50px color-mix(in srgb, var(--primary) 12%, transparent);
}

.memory-hero__visual::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(5, 3, 14, 0.02) 42%, rgba(5, 3, 14, 0.9) 100%);
  pointer-events: none;
}

.memory-hero__visual > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 900ms var(--ease-out-expo);
}

.memory-hero__visual:hover > img {
  transform: scale(1.025);
}

.memory-hero__caption {
  position: absolute;
  top: 22px;
  left: 22px;
  z-index: 2;
  display: grid;
  gap: 3px;
  max-width: calc(100% - 44px);
  padding: 11px 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 13px;
  background: rgba(8, 6, 20, 0.62);
  backdrop-filter: blur(14px);
}

.memory-hero__caption span {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 0.62rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.memory-hero__caption strong {
  color: var(--text);
  font-size: 0.84rem;
}

.memory-hero__metrics {
  position: absolute;
  right: 16px;
  bottom: 16px;
  left: 16px;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  background: rgba(8, 6, 20, 0.72);
  backdrop-filter: blur(18px) saturate(135%);
  overflow: hidden;
}

.memory-hero__metric {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 13px 10px;
  text-align: center;
}

.memory-hero__metric + .memory-hero__metric {
  border-left: 1px solid rgba(255, 255, 255, 0.1);
}

.memory-hero__metric span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 0.59rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-overflow: ellipsis;
  text-transform: uppercase;
  white-space: nowrap;
}

.memory-hero__metric strong {
  color: var(--text);
  font-family: var(--font-display);
  font-size: clamp(1rem, 2vw, 1.35rem);
  font-variant-numeric: tabular-nums;
}

.memory-card__cover {
  height: 208px;
  margin: -26px -26px 0;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  border-top-left-radius: calc(var(--radius-lg) - 1px);
  border-top-right-radius: calc(var(--radius-lg) - 1px);
}

.memory-card {
  height: 100%;
  padding: 26px;
  display: grid;
  gap: 20px;
  align-content: start;
  transition:
    transform var(--duration-base) var(--ease-out-quart),
    border-color var(--duration-base) var(--ease-out-quart),
    box-shadow var(--duration-base) var(--ease-out-quart);
  position: relative;
  overflow: hidden;
  border-color: rgba(231, 224, 255, 0.1);
  background: linear-gradient(180deg, rgba(27, 20, 50, 0.88), rgba(14, 11, 28, 0.86));
}

.memory-card::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, color-mix(in srgb, var(--primary) 14%, transparent), transparent 60%);
  opacity: 0;
  transition: opacity var(--duration-base) var(--ease-out-quart);
  pointer-events: none;
}

.memory-card:hover {
  transform: translateY(-5px);
  border-color: var(--border-accent);
  box-shadow: var(--shadow-lg), 0 0 0 1px var(--border-accent), 0 16px 40px color-mix(in srgb, var(--primary) 12%, transparent);
}

.memory-card:hover::after {
  opacity: 1;
}

/* 骨架卡：复用真实卡片布局，禁用 hover/交互，纯展示加载形态 */
.memory-card--skeleton {
  pointer-events: none;
  cursor: default;
}
.memory-card--skeleton:hover {
  transform: none;
  border-color: var(--border);
  box-shadow: var(--shadow-md);
}
.memory-card--skeleton .memory-card__cover {
  height: 208px;
  margin: -26px -26px 0;
  border-radius: var(--radius-md) var(--radius-md) 0 0;
}

.memory-card__top {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  position: relative;
  z-index: 2;
  margin-top: -42px;
}

.memory-card__accent {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  color: #171022;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  box-shadow: 0 0 0 4px rgba(8, 6, 20, 0.72), 0 10px 24px rgba(3, 1, 12, 0.34);
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
  font-size: 1.22rem;
  font-weight: 700;
  letter-spacing: -0.015em;
  line-height: 1.3;
}

.memory-card__desc {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.65;
  min-height: 4.5em;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 0.88rem;
  opacity: 0.82;
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
  background: color-mix(in srgb, var(--primary) 9%, transparent);
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
  border-color: rgba(231, 224, 255, 0.1);
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--primary) 7%, transparent), transparent 48%),
    rgba(15, 13, 29, 0.72);
  transition: border-color var(--duration-base) var(--ease-out-quart),
              box-shadow var(--duration-base) var(--ease-out-quart);
}

.museum-guide:hover {
  border-color: color-mix(in srgb, var(--primary) 35%, transparent);
  box-shadow: var(--shadow-md), 0 0 20px color-mix(in srgb, var(--primary) 8%, transparent);
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
  padding: 18px;
  border-radius: var(--radius-md);
  background: rgba(24, 20, 43, 0.48);
  border: 1px solid var(--border);
  transition: border-color 0.22s ease, background 0.22s ease;
}

.guide-col:hover {
  border-color: var(--border-strong);
  background: color-mix(in srgb, var(--primary) 8%, rgba(14, 17, 22, 0.55));
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

@media (max-width: 1040px) {
  .memory-hero {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .memory-hero__copy {
    max-width: 760px;
  }

  .memory-hero__copy .display-title {
    max-width: 12ch;
  }

  .memory-hero__visual {
    width: 100%;
    aspect-ratio: 16 / 10;
  }
}

@media (max-width: 640px) {
  .memory-hero {
    gap: 30px;
    border-radius: 22px;
  }

  .memory-hero__copy {
    gap: 24px;
  }

  .memory-hero__copy .display-title {
    font-size: clamp(2.55rem, 15vw, 4rem);
  }

  .memory-hero__visual {
    aspect-ratio: 4 / 5;
    border-radius: 20px;
  }

  .memory-hero__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .memory-hero__metric:nth-child(3),
  .memory-hero__metric:nth-child(4) {
    border-top: 1px solid rgba(255, 255, 255, 0.1);
  }

  .memory-hero__metric:nth-child(3) {
    border-left: 0;
  }

  .memory-hero__actions .button {
    flex: 1 1 100%;
  }

  .museum-guide__header {
    align-items: flex-start;
  }

.guide-toggle-btn > span {
    display: none;
  }
}

/* Editorial collection pass: fewer effects, stronger image and type hierarchy. */
.collection-toolbar {
  border-color: var(--border);
  background: var(--surface);
}

.memory-hero {
  min-height: 500px;
  border-color: var(--border-strong);
  background: linear-gradient(135deg, var(--surface-elevated), var(--surface));
  box-shadow: var(--shadow-lg);
}

.memory-hero__backdrop {
  opacity: 0.18;
  filter: saturate(0.68) contrast(1.02);
}

.memory-hero__copy {
  gap: 28px;
}

.memory-hero__copy .display-title {
  font-weight: 600;
  letter-spacing: -0.04em;
}

.memory-hero__edition-dot {
  background: var(--gold);
  box-shadow: none;
}

.memory-hero__visual {
  border-color: var(--border-strong);
  border-radius: 16px;
  background: var(--bg-0);
  box-shadow: var(--shadow-lg);
}

.memory-hero__caption {
  top: 18px;
  left: 18px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(14, 11, 13, 0.86);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.memory-hero__metrics {
  right: 14px;
  bottom: 14px;
  left: 14px;
  border-color: rgba(244, 232, 220, 0.16);
  border-radius: 10px;
  background: rgba(14, 11, 13, 0.9);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.memory-card {
  border-color: var(--border);
  border-radius: 14px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.memory-card::after {
  background: linear-gradient(135deg, color-mix(in srgb, var(--gold) 9%, transparent), transparent 62%);
}

.memory-card:hover {
  transform: translateY(-3px);
  border-color: var(--border-accent);
  box-shadow: var(--shadow-md);
}

.memory-card__cover {
  border-bottom-color: var(--border);
}

.memory-card__accent,
.memory-card__accent--locked {
  color: #2c211d;
  background: var(--gold);
  box-shadow: 0 0 0 4px var(--surface), 0 5px 14px rgba(0, 0, 0, 0.24);
}

.memory-card__title {
  font-weight: 600;
}

.memory-card__desc {
  opacity: 0.9;
}

.museum-guide {
  border-color: var(--border);
  background: var(--surface);
}

.museum-guide:hover {
  border-color: var(--border-accent);
  box-shadow: var(--shadow-sm);
}

.guide-col {
  background: var(--surface-soft);
}

.guide-col:hover {
  background: var(--surface-elevated);
}
</style>
