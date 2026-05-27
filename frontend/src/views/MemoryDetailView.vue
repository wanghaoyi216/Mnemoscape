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
const { t, tm } = useI18n()
const id = route.params.id as string
const restoring = ref<number | null>(null)
const loadError = ref('')

const fragments = computed(() => store.currentFragments)
const versions = computed(() => store.currentVersions)

/**
 * 把英文历史 fragment 内容映射为中文。
 *
 * <p>历史规则版（v1 MockReconstructService）把 24 条英文文案 freeze 进 DB 的
 * memory_fragments.content；新建记忆已经走 LLM 中文输出。这个函数让旧数据也能
 * 显示为中文 — 用 vue-i18n 的 {@code tm()} 拿 i18n 字典里的 legacyContent 整段，
 * 再用完整原文精确匹配；命中返回中文，未命中保留原文。
 *
 * <p>tm() 不会按 "." 分割 key，避免 fragment.content 里的句点 / 空格被
 * messageResolver 误解析。
 */
const legacyContentMap = computed<Record<string, string>>(() => {
  const dict = tm('memory.detail.fragmentsPanel.legacyContent') as Record<string, string> | undefined
  return (dict && typeof dict === 'object') ? dict : {}
})
function fragmentContent(content: string | null | undefined): string {
  if (!content) return ''
  return legacyContentMap.value[content] || content
}

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

const regenerating = ref(false)
const regenError = ref('')
async function handleRegenerate() {
  if (regenerating.value) return
  regenerating.value = true
  regenError.value = ''
  try {
    await store.regenerateScene(id)
    await store.fetchFragments(id)
    await store.fetchVersions(id)
  } catch (e: any) {
    regenError.value = e.response?.data?.message || t('memory.detail.regenError')
  } finally {
    regenerating.value = false
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
              <button
                type="button"
                class="button button--secondary"
                :disabled="regenerating"
                :title="t('memory.detail.fragmentsPanel.regenHint')"
                @click="handleRegenerate"
              >
                <span v-if="regenerating" class="auth-spinner" aria-hidden="true"></span>
                <span>{{ regenerating
                  ? t('memory.detail.fragmentsPanel.regenerating')
                  : t('memory.detail.fragmentsPanel.regenerate') }}</span>
              </button>
            </div>
            <transition name="alert">
              <p v-if="regenError" class="status-pill status-pill--danger" role="alert" style="margin-bottom: 12px;">
                {{ regenError }}
              </p>
            </transition>

            <div v-if="fragments.length > 0" class="stack">
              <article
                v-for="fragment in fragments"
                :key="fragment.id"
                class="fragment-card"
                :class="{ 'fragment-card--discovered': fragment.isDiscovered }"
              >
                <div class="fragment-card__head">
                  <span class="status-pill" :class="fragment.isDiscovered ? 'status-pill--success' : 'status-pill--accent'">
                    {{ t(`memory.detail.fragmentsPanel.fragmentTypes.${fragment.fragmentType}`, fragment.fragmentType) }}
                  </span>
                  <span class="chip">{{ fragment.isDiscovered ? t('memory.detail.fragmentsPanel.discovered') : t('memory.detail.fragmentsPanel.hidden') }}</span>
                </div>
                <p class="fragment-card__content">{{ fragmentContent(fragment.content) }}</p>
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
                <span class="status-pill status-pill--accent">{{ t(`memory.detail.versionsPanel.versionTypes.${version.changeType}`, version.changeType) }}</span>
              </div>
              <p class="help-text">{{ t(`memory.detail.versionsPanel.versionMessages.${version.changeDescription}`, version.changeDescription) }}</p>
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

.auth-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(54, 216, 180, 0.25);
  border-top-color: var(--primary, #36d8b4);
  border-radius: 50%;
  animation: auth-spin 0.7s linear infinite;
  margin-right: 6px;
  vertical-align: -2px;
  display: inline-block;
}
@keyframes auth-spin { to { transform: rotate(360deg); } }

.alert-enter-active,
.alert-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}
.alert-enter-from,
.alert-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
