<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useResonanceStore } from '../stores/resonance'
import { useMemoryStore } from '../stores/memory'
import { images, videos } from '../assets/media-catalog'
import type { ResonanceMatch } from '../types'

const { t } = useI18n()
const heroBg = images.resonanceBridge.src
const heroVideo = videos.driftPerception.src
const signalVideo = videos.neuralResonance.src

const router = useRouter()
const resonanceStore = useResonanceStore()
const memoryStore = useMemoryStore()
const selectedMemoryId = ref('')
const loading = ref(false)
const creating = ref<string | null>(null)

const hasResults = computed(() => resonanceStore.searchResults.length > 0)

onMounted(async () => {
  if (!memoryStore.memories.length) {
    await memoryStore.fetchList()
  }
})

async function search() {
  if (!selectedMemoryId.value) return
  loading.value = true
  try {
    await resonanceStore.search(selectedMemoryId.value)
  } finally {
    loading.value = false
  }
}

async function createSpace(match: ResonanceMatch) {
  creating.value = match.memoryId
  try {
    const space = await resonanceStore.createSpace(selectedMemoryId.value, match.memoryId)
    router.push(`/resonance/${space.id}`)
  } finally {
    creating.value = null
  }
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      class="hero-card hero-card--split resonance-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.78) 0%, rgba(8,10,14,0.4) 55%, rgba(8,10,14,0.85) 100%), url(${heroBg})` }"
    >
      <video class="resonance-hero__video" :src="heroVideo" autoplay muted loop playsinline aria-hidden="true"></video>
      <div class="resonance-hero__mesh" aria-hidden="true"></div>
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('resonance.hub.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ t('resonance.hub.title') }}</h1>
        <p class="lead">{{ t('resonance.hub.lead') }}</p>
      </div>

      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.hub.metrics.threshold') }}</span>
          <strong class="metric-card__value">0.75</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.hub.metrics.ranking') }}</span>
          <strong class="metric-card__value">{{ t('resonance.hub.metrics.rankingValue') }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.hub.metrics.output') }}</span>
          <strong class="metric-card__value">{{ t('resonance.hub.metrics.outputValue') }}</strong>
        </div>
      </div>
    </section>

    <section class="section-card stack" style="margin-top: 24px;">
      <video class="resonance-search__signal" :src="signalVideo" autoplay muted loop playsinline aria-hidden="true"></video>
      <div class="page-shell__header" style="margin-bottom: 0;">
        <div>
          <h2 class="section-title">{{ t('resonance.hub.search.title') }}</h2>
          <p class="subtitle">{{ t('resonance.hub.search.subtitle') }}</p>
        </div>
      </div>

      <div class="search-panel">
        <label class="field">
          <span class="field__label">{{ t('resonance.hub.search.baseMemory') }}</span>
          <select v-model="selectedMemoryId" class="select">
            <option value="" disabled>{{ t('resonance.hub.search.placeholder') }}</option>
            <option v-for="memory in memoryStore.memories" :key="memory.id" :value="memory.id">
              {{ memory.title }}
            </option>
          </select>
        </label>

        <button class="button button--primary" type="button" @click="search" :disabled="!selectedMemoryId || loading">
          {{ loading ? t('resonance.hub.search.searching') : t('resonance.hub.search.submit') }}
        </button>
      </div>
    </section>

    <section v-if="hasResults" class="card-grid" style="margin-top: 24px;">
      <article v-for="match in resonanceStore.searchResults" :key="match.memoryId" class="result-card resonance-result">
        <video class="resonance-result__video" :src="signalVideo" autoplay muted loop playsinline aria-hidden="true"></video>
        <div class="stack">
          <div class="result-card__head">
            <h3 class="result-card__title">{{ match.title }}</h3>
            <span class="chip">{{ match.ownerUsername }}</span>
          </div>
          <p class="help-text" style="margin: 0;">{{ t('resonance.hub.result.note') }}</p>
        </div>

        <div class="metric-grid">
          <div class="metric-card">
            <span class="metric-card__label">{{ t('resonance.hub.result.overall') }}</span>
            <strong class="metric-card__value">{{ (match.similarityScore * 100).toFixed(0) }}%</strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('resonance.hub.result.emotion') }}</span>
            <strong class="metric-card__value">{{ (match.emotionSimilarity * 100).toFixed(0) }}%</strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('resonance.hub.result.scene') }}</span>
            <strong class="metric-card__value">{{ (match.sceneSimilarity * 100).toFixed(0) }}%</strong>
          </div>
        </div>

        <button
          class="button button--primary"
          type="button"
          @click="createSpace(match)"
          :disabled="creating === match.memoryId"
        >
          {{ creating === match.memoryId ? t('resonance.hub.result.creating') : t('resonance.hub.result.openSpace') }}
        </button>
      </article>
    </section>

    <section v-else class="section-card empty-state" style="margin-top: 24px;">
      <h3 class="empty-state__title">{{ t('resonance.hub.empty.title') }}</h3>
      <p class="empty-state__text">{{ t('resonance.hub.empty.text') }}</p>
    </section>
  </div>
</template>

<style scoped>
.resonance-hero {
  position: relative;
  min-height: 360px;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
  isolation: isolate;
}

.resonance-hero > :not(.resonance-hero__video):not(.resonance-hero__mesh) {
  position: relative;
  z-index: 1;
}

.resonance-hero__video {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.32;
  filter: saturate(1.25) contrast(1.08) brightness(0.7);
  pointer-events: none;
}

.resonance-hero__mesh {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  opacity: 0.44;
  background:
    linear-gradient(90deg, transparent 0 49%, rgba(54, 216, 180, 0.28) 50%, transparent 51% 100%),
    linear-gradient(0deg, transparent 0 49%, rgba(242, 185, 92, 0.16) 50%, transparent 51% 100%),
    radial-gradient(circle at 72% 28%, rgba(54, 216, 180, 0.22), transparent 36%);
  background-size: 68px 68px, 68px 68px, auto;
  mix-blend-mode: screen;
  animation: resonance-mesh 18s linear infinite;
}

@keyframes resonance-mesh {
  from { background-position: 0 0, 0 0, center; }
  to { background-position: 68px 68px, -68px 68px, center; }
}

.search-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: end;
}

.section-card.stack {
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.section-card.stack > :not(.resonance-search__signal) {
  position: relative;
  z-index: 1;
}

.resonance-search__signal {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.1;
  filter: saturate(1.3) contrast(1.06);
  mix-blend-mode: screen;
  pointer-events: none;
}

.result-card {
  display: grid;
  gap: 20px;
  padding: 22px;
}

.resonance-result {
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.resonance-result > :not(.resonance-result__video) {
  position: relative;
  z-index: 1;
}

.resonance-result::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: linear-gradient(120deg, rgba(54, 216, 180, 0.12), transparent 42%, rgba(242, 185, 92, 0.08));
  opacity: 0;
  transition: opacity var(--duration-base) var(--ease-out-quart);
}

.resonance-result:hover::after {
  opacity: 1;
}

.resonance-result__video {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.1;
  filter: saturate(1.4) contrast(1.08);
  pointer-events: none;
}

.result-card__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: start;
}

.result-card__title {
  margin: 0;
  font-size: 1.1rem;
  letter-spacing: 0;
}

@media (max-width: 768px) {
  .search-panel {
    grid-template-columns: 1fr;
  }
}
</style>
