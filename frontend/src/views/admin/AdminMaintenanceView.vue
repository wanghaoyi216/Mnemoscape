<script setup lang="ts">
/**
 * 管理后台 — 维护工具面板。
 *
 * 聚合三个一次性 / 偶发性的数据治理操作：
 *  1. 向量回填：给历史记忆补 Milvus 索引（首次接入向量检索后必跑）
 *  2. visualData 清洗：批量重建仍是旧英文模板 / 空的 3D 场景数据
 *  3. MinIO 历史孤儿迁移：把无 users/ 前缀的历史上传搬到 legacy-orphan/（先预览后执行）
 *
 * 所有操作均走 ROLE_ADMIN 端点，后端逐条 best-effort，前端只展示统计结果。
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import {
  backfillVectors,
  backfillGeocoords,
  cleanupVisualData,
  rebuildFragments,
  migrateLegacyOrphans,
  type VectorBackfillResult,
  type GeoBackfillResult,
  type VisualDataCleanupResult,
  type FragmentRebuildResult,
  type OrphanMigrationResult,
} from '../../api/adminManagement'
import { useToastStore } from '../../stores/toast'

const { t } = useI18n()
const toast = useToastStore()

// ---- 向量回填 ----
const vectorLimit = ref(500)
const vectorBusy = ref(false)
const vectorResult = ref<VectorBackfillResult | null>(null)

async function runBackfill() {
  vectorBusy.value = true
  try {
    const { data } = await backfillVectors(vectorLimit.value)
    if (data.code === 200) {
      vectorResult.value = data.data
      toast.push({ key: 'admin.maintenance.done', tone: 'success' })
    } else {
      toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
    }
  } catch {
    toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
  } finally {
    vectorBusy.value = false
  }
}

// ---- 坐标回填 ----
const geoLimit = ref(1000)
const geoBusy = ref(false)
const geoResult = ref<GeoBackfillResult | null>(null)

async function runGeoBackfill() {
  geoBusy.value = true
  try {
    const { data } = await backfillGeocoords(geoLimit.value)
    if (data.code === 200) {
      geoResult.value = data.data
      toast.push({ key: 'admin.maintenance.done', tone: 'success' })
    } else {
      toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
    }
  } catch {
    toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
  } finally {
    geoBusy.value = false
  }
}

// ---- visualData 清洗 ----
const visualLimit = ref(500)
const visualBusy = ref(false)
const visualResult = ref<VisualDataCleanupResult | null>(null)

async function runCleanup() {
  visualBusy.value = true
  try {
    const { data } = await cleanupVisualData(visualLimit.value)
    if (data.code === 200) {
      visualResult.value = data.data
      toast.push({ key: 'admin.maintenance.done', tone: 'success' })
    } else {
      toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
    }
  } catch {
    toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
  } finally {
    visualBusy.value = false
  }
}

// ---- Fragments 批量重建 ----
const fragmentsLimit = ref(500)
const fragmentsBusy = ref(false)
const fragmentsResult = ref<FragmentRebuildResult | null>(null)

async function runFragmentsRebuild() {
  fragmentsBusy.value = true
  try {
    const { data } = await rebuildFragments(fragmentsLimit.value)
    if (data.code === 200) {
      fragmentsResult.value = data.data
      toast.push({ key: 'admin.maintenance.done', tone: 'success' })
    } else {
      toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
    }
  } catch {
    toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
  } finally {
    fragmentsBusy.value = false
  }
}

// ---- MinIO 历史孤儿迁移 ----
const orphanBusy = ref(false)
const orphanResult = ref<OrphanMigrationResult | null>(null)

async function runMigration(apply: boolean) {
  orphanBusy.value = true
  try {
    const { data } = await migrateLegacyOrphans(apply)
    if (data.code === 200) {
      orphanResult.value = data.data
      toast.push({ key: 'admin.maintenance.done', tone: 'success' })
    } else {
      toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
    }
  } catch {
    toast.push({ key: 'admin.maintenance.failed', tone: 'error' })
  } finally {
    orphanBusy.value = false
  }
}
</script>

<template>
  <AdminPanel title="admin.maintenance.title" state="ready">
    <div class="maintenance-grid">
      <!-- 向量回填 -->
      <section class="maint-card">
        <h3 class="maint-card__title">🧬 {{ t('admin.maintenance.vector.title') }}</h3>
        <p class="maint-card__desc">{{ t('admin.maintenance.vector.desc') }}</p>
        <div class="maint-card__row">
          <label>{{ t('admin.maintenance.limit') }}</label>
          <input v-model.number="vectorLimit" type="number" min="1" max="2000" class="input maint-input" />
          <button class="button button--primary" :disabled="vectorBusy" @click="runBackfill">
            <span v-if="vectorBusy" class="auth-spinner"></span>
            <span v-else>{{ t('admin.maintenance.vector.run') }}</span>
          </button>
        </div>
        <p v-if="vectorResult" class="maint-card__result">
          {{ t('admin.maintenance.vector.result', { dispatched: vectorResult.dispatched, total: vectorResult.total }) }}
        </p>
      </section>

      <!-- 坐标回填 -->
      <section class="maint-card">
        <h3 class="maint-card__title">🌍 {{ t('admin.maintenance.geo.title') }}</h3>
        <p class="maint-card__desc">{{ t('admin.maintenance.geo.desc') }}</p>
        <div class="maint-card__row">
          <label>{{ t('admin.maintenance.limit') }}</label>
          <input v-model.number="geoLimit" type="number" min="1" max="5000" class="input maint-input" />
          <button class="button button--primary" :disabled="geoBusy" @click="runGeoBackfill">
            <span v-if="geoBusy" class="auth-spinner"></span>
            <span v-else>{{ t('admin.maintenance.geo.run') }}</span>
          </button>
        </div>
        <p v-if="geoResult" class="maint-card__result">
          {{ t('admin.maintenance.geo.result', {
            resolved: geoResult.resolved,
            scanned: geoResult.scanned,
            skipped: geoResult.skipped,
          }) }}
        </p>
      </section>

      <!-- visualData 清洗 -->
      <section class="maint-card">
        <h3 class="maint-card__title">🪄 {{ t('admin.maintenance.visual.title') }}</h3>
        <p class="maint-card__desc">{{ t('admin.maintenance.visual.desc') }}</p>
        <div class="maint-card__row">
          <label>{{ t('admin.maintenance.limit') }}</label>
          <input v-model.number="visualLimit" type="number" min="1" max="1000" class="input maint-input" />
          <button class="button button--primary" :disabled="visualBusy" @click="runCleanup">
            <span v-if="visualBusy" class="auth-spinner"></span>
            <span v-else>{{ t('admin.maintenance.visual.run') }}</span>
          </button>
        </div>
        <p v-if="visualResult" class="maint-card__result">
          {{ t('admin.maintenance.visual.result', { dispatched: visualResult.dispatched, scanned: visualResult.scanned }) }}
        </p>
      </section>

      <!-- Fragments 批量重建 -->
      <section class="maint-card">
        <h3 class="maint-card__title">🧩 {{ t('admin.maintenance.fragments.title') }}</h3>
        <p class="maint-card__desc">{{ t('admin.maintenance.fragments.desc') }}</p>
        <div class="maint-card__row">
          <label>{{ t('admin.maintenance.limit') }}</label>
          <input v-model.number="fragmentsLimit" type="number" min="1" max="1000" class="input maint-input" />
          <button class="button button--primary" :disabled="fragmentsBusy" @click="runFragmentsRebuild">
            <span v-if="fragmentsBusy" class="auth-spinner"></span>
            <span v-else>{{ t('admin.maintenance.fragments.run') }}</span>
          </button>
        </div>
        <p v-if="fragmentsResult" class="maint-card__result">
          {{ t('admin.maintenance.fragments.result', { dispatched: fragmentsResult.dispatched, scanned: fragmentsResult.scanned }) }}
        </p>
      </section>

      <!-- MinIO 历史孤儿迁移 -->
      <section class="maint-card maint-card--wide">
        <h3 class="maint-card__title">🗂️ {{ t('admin.maintenance.orphan.title') }}</h3>
        <p class="maint-card__desc">{{ t('admin.maintenance.orphan.desc') }}</p>
        <div class="maint-card__row">
          <button class="button" :disabled="orphanBusy" @click="runMigration(false)">
            <span v-if="orphanBusy" class="auth-spinner"></span>
            <span v-else>{{ t('admin.maintenance.orphan.preview') }}</span>
          </button>
          <button
            class="button button--primary"
            :disabled="orphanBusy || !orphanResult || orphanResult.candidates === 0"
            @click="runMigration(true)"
          >
            {{ t('admin.maintenance.orphan.apply') }}
          </button>
        </div>
        <div v-if="orphanResult" class="maint-card__result">
          <p>
            {{ t('admin.maintenance.orphan.result', {
              scanned: orphanResult.scanned,
              candidates: orphanResult.candidates,
              migrated: orphanResult.migrated,
            }) }}
            <span v-if="orphanResult.dryRun" class="maint-badge">{{ t('admin.maintenance.orphan.dryRun') }}</span>
          </p>
          <ul v-if="orphanResult.samples && orphanResult.samples.length" class="maint-samples">
            <li v-for="s in orphanResult.samples" :key="s">{{ s }}</li>
          </ul>
          <p v-if="orphanResult.error" class="maint-error">{{ orphanResult.error }}</p>
        </div>
      </section>
    </div>
  </AdminPanel>
</template>

<style scoped>
.maintenance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 18px;
}
.maint-card {
  background: rgba(14, 17, 22, 0.55);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 18px 20px;
}
.maint-card--wide { grid-column: 1 / -1; }
.maint-card__title { margin: 0 0 8px; font-size: 1rem; }
.maint-card__desc { margin: 0 0 14px; color: var(--text-muted); font-size: 0.84rem; line-height: 1.5; }
.maint-card__row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.maint-card__row label { font-size: 0.8rem; color: var(--text-muted); }
.maint-input { width: 100px; }
.maint-card__result { margin: 12px 0 0; font-size: 0.84rem; color: var(--text); }
.maint-badge {
  display: inline-block; margin-left: 8px; padding: 1px 8px;
  border-radius: 999px; font-size: 0.7rem;
  background: rgba(232, 199, 122, 0.18); color: #e8c77a;
  border: 1px solid rgba(232, 199, 122, 0.4);
}
.maint-samples {
  margin: 8px 0 0; padding-left: 18px; max-height: 160px; overflow: auto;
  font-size: 0.74rem; color: var(--text-muted); font-family: monospace;
}
.maint-error { color: var(--danger, #f87171); font-size: 0.8rem; margin-top: 8px; }
</style>
