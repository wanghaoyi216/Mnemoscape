<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useResonanceStore } from '../stores/resonance'
import { useMemoryStore } from '../stores/memory'
import { images } from '../assets/media-catalog'
import type { ResonanceMatch } from '../types'

const { t } = useI18n()
const heroBg = images.resonanceBridge.src

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
      <article v-for="match in resonanceStore.searchResults" :key="match.memoryId" class="result-card">
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
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.search-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: end;
}

.result-card {
  display: grid;
  gap: 20px;
  padding: 22px;
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
