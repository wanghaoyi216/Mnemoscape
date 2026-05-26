<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { images } from '../assets/media-catalog'

const versionsBg = images.historicalRubbing.src

const route = useRoute()
const router = useRouter()
const store = useMemoryStore()
const { t } = useI18n()
const id = route.params.id as string
const restoring = ref<number | null>(null)
const loadError = ref('')

const fragments = computed(() => store.currentFragments)
const versions = computed(() => store.currentVersions)

onMounted(async () => {
  loadError.value = ''
  try {
    await store.fetchOne(id)
    await Promise.all([
      store.fetchDrift(id),
      store.fetchFragments(id),
      store.fetchVersions(id),
    ])
  } catch (e: any) {
    // 后端 404 已经走 BizException → ApiResponse.message；其余情况落到 errors.notFound 兜底
    loadError.value = e.response?.data?.message || t('memory.detail.loadError')
  }
})

async function handleLock() {
  const mem = store.current
  if (!mem) return
  await store.toggleLock(id, !mem.isLocked)
  await store.fetchOne(id)
  await store.fetchDrift(id)
}

async function handleRestore(versionNumber: number) {
  restoring.value = versionNumber
  try {
    await store.restoreVersion(id, versionNumber)
    await store.fetchOne(id)
    await store.fetchDrift(id)
    await store.fetchFragments(id)
    await store.fetchVersions(id)
  } finally {
    restoring.value = null
  }
}

function viewScene() {
  router.push(`/scene/${id}`)
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <div v-if="store.loadingDetail" class="section-card empty-state">
      <h3 class="empty-state__title">{{ t('memory.detail.loading.title') }}</h3>
      <p class="empty-state__text">{{ t('memory.detail.loading.text') }}</p>
    </div>

    <div v-else-if="loadError" class="section-card empty-state" role="alert">
      <h3 class="empty-state__title">{{ t('memory.detail.error.title') }}</h3>
      <p class="empty-state__text">{{ loadError }}</p>
      <RouterLink to="/memories" class="button button--secondary">{{ t('memory.detail.back') }}</RouterLink>
    </div>

    <div v-else-if="store.current" class="detail-grid">
      <section 
        class="hero-card hero-card--split"
        :style="store.current.sceneDataUrl && (store.current.sceneDataUrl.startsWith('http') || store.current.sceneDataUrl.startsWith('/')) ? { backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.85) 0%, rgba(8,10,14,0.55) 60%, rgba(8,10,14,0.92) 100%), url(${store.current.sceneDataUrl})`, backgroundSize: 'cover', backgroundPosition: 'center', backgroundRepeat: 'no-repeat' } : {}"
      >
        <div class="stack stack--lg">
          <p class="eyebrow">{{ t('memory.detail.eyebrow') }}</p>
          <h1 class="display-title text-gradient">{{ store.current.title }}</h1>
          <p class="lead">{{ store.current.description }}</p>

          <div class="chip-grid">
            <span v-if="store.current.memoryYear" class="chip">{{ store.current.memoryYear }}</span>
            <span v-if="store.current.memoryLocation" class="chip">{{ store.current.memoryLocation }}</span>
            <span v-if="store.current.memorySeason" class="chip">{{ t(`memory.builder.seasons.${store.current.memorySeason}`, store.current.memorySeason) }}</span>
            <span v-if="store.current.memoryTimeOfDay" class="chip">{{ t(`memory.builder.times.${store.current.memoryTimeOfDay}`, store.current.memoryTimeOfDay) }}</span>
            <span class="chip">{{ t(`memory.list.privacy.${store.current.privacyLevel}`, store.current.privacyLevel) }}</span>
            <span v-if="store.current.isLocked" class="status-pill status-pill--warning">{{ t('memory.detail.locked') }}</span>
          </div>
        </div>

        <div class="stack">
          <div class="metric-grid">
            <div class="metric-card">
              <span class="metric-card__label">{{ t('memory.detail.fadeLevel') }}</span>
              <strong class="metric-card__value">{{ Math.round((store.currentDrift?.fadeLevel || 0) * 100) }}%</strong>
            </div>
            <div class="metric-card">
              <span class="metric-card__label">{{ t('memory.detail.fragments') }}</span>
              <strong class="metric-card__value">{{ fragments.length }}</strong>
            </div>
            <div class="metric-card">
              <span class="metric-card__label">{{ t('memory.detail.versions') }}</span>
              <strong class="metric-card__value">{{ versions.length }}</strong>
            </div>
          </div>

          <div class="stack">
            <button class="button button--primary" type="button" @click="viewScene">{{ t('memory.detail.enterScene') }}</button>
            <button class="button button--secondary" type="button" @click="handleLock">
              {{ store.current.isLocked ? t('memory.detail.unlock') : t('memory.detail.lock') }}
            </button>
          </div>
        </div>
      </section>

      <section class="detail-columns">
        <div class="stack">
          <div v-if="store.currentDrift" class="drift-panel section-card">
            <div class="page-shell__header" style="margin-bottom: 16px;">
              <div>
                <h2 class="section-title">{{ t('memory.detail.driftPanel.title') }}</h2>
                <p class="subtitle">{{ t('memory.detail.driftPanel.subtitle') }}</p>
              </div>
            </div>
            <div class="stack">
              <div class="field">
                <div class="field__label">{{ t('memory.detail.driftPanel.fade') }}</div>
                <div class="fade-bar">
                  <div class="fade-bar__fill" :style="{ width: `${(store.currentDrift.fadeLevel || 0) * 100}%` }"></div>
                </div>
                <div class="help-text">{{ t('memory.detail.driftPanel.fadedPct', { pct: Math.round((store.currentDrift.fadeLevel || 0) * 100) }) }}</div>
              </div>

              <div class="metric-grid">
                <div class="metric-card">
                  <span class="metric-card__label">{{ t('memory.detail.driftPanel.daysSinceCreation') }}</span>
                  <strong class="metric-card__value">{{ store.currentDrift.daysSinceCreation }}</strong>
                </div>
                <div class="metric-card">
                  <span class="metric-card__label">{{ t('memory.detail.driftPanel.saturation') }}</span>
                  <strong class="metric-card__value">{{ Math.round((store.currentDrift.colorSaturation || 0) * 100) }}%</strong>
                </div>
                <div class="metric-card">
                  <span class="metric-card__label">{{ t('memory.detail.driftPanel.fog') }}</span>
                  <strong class="metric-card__value">{{ Math.round((store.currentDrift.fogDensity || 0) * 1000) / 10 }}</strong>
                </div>
              </div>
            </div>
          </div>

          <div class="fragments-panel section-card">
            <div class="page-shell__header" style="margin-bottom: 16px;">
              <div>
                <h2 class="section-title">{{ t('memory.detail.fragmentsPanel.title') }}</h2>
                <p class="subtitle">{{ t('memory.detail.fragmentsPanel.subtitle', { count: fragments.length }) }}</p>
              </div>
            </div>

            <div v-if="fragments.length > 0" class="stack">
              <article
                v-for="fragment in fragments"
                :key="fragment.id"
                class="fragment-card"
                :class="{ 'fragment-card--discovered': fragment.isDiscovered }"
              >
                <div class="fragment-card__head">
                  <span class="status-pill" :class="fragment.isDiscovered ? 'status-pill--success' : 'status-pill--accent'">
                    {{ fragment.fragmentType }}
                  </span>
                  <span class="chip">{{ fragment.isDiscovered ? t('memory.detail.fragmentsPanel.discovered') : t('memory.detail.fragmentsPanel.hidden') }}</span>
                </div>
                <p class="fragment-card__content">{{ fragment.content }}</p>
              </article>
            </div>

            <div v-else class="empty-state">
              <h3 class="empty-state__title">{{ t('memory.detail.fragmentsPanel.emptyTitle') }}</h3>
              <p class="empty-state__text">{{ t('memory.detail.fragmentsPanel.emptyText') }}</p>
            </div>
          </div>
        </div>

        <aside
          class="section-card stack versions-aside"
          :style="{ backgroundImage: `linear-gradient(180deg, rgba(8,10,14,0.92) 0%, rgba(8,10,14,0.78) 50%, rgba(8,10,14,0.95) 100%), url(${versionsBg})` }"
        >
          <div>
            <h2 class="section-title">{{ t('memory.detail.versionsPanel.title') }}</h2>
            <p class="subtitle">{{ t('memory.detail.versionsPanel.subtitle') }}</p>
          </div>

          <div v-if="versions.length > 0" class="stack">
            <article v-for="version in versions" :key="version.id" class="version-card">
              <div class="version-card__head">
                <span class="chip">v{{ version.versionNumber }}</span>
                <span class="status-pill status-pill--accent">{{ version.changeType }}</span>
              </div>
              <p class="help-text">{{ version.changeDescription }}</p>
              <button
                type="button"
                class="button button--secondary"
                :disabled="restoring === version.versionNumber"
                @click="handleRestore(version.versionNumber)"
              >
                {{ restoring === version.versionNumber ? t('memory.detail.versionsPanel.restoring') : t('memory.detail.versionsPanel.restore') }}
              </button>
            </article>
          </div>

          <div v-else class="empty-state" style="padding: 32px 10px;">
            <h3 class="empty-state__title">{{ t('memory.detail.versionsPanel.empty') }}</h3>
          </div>
        </aside>
      </section>
    </div>
  </div>
</template>

<style scoped>
.fragment-card,
.version-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(15, 23, 42, 0.52);
  padding: 18px;
}

.fragment-card--discovered {
  border-color: rgba(52, 211, 153, 0.26);
  background: rgba(16, 185, 129, 0.06);
}

.fragment-card__head,
.version-card__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.fragment-card__content {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.7;
}

.versions-aside {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}
</style>
