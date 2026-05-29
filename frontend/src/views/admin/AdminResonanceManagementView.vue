<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import AdminDataTable from '../../components/admin/AdminDataTable.vue'
import {
  listResonancesAdmin,
  patchResonance,
  deleteResonance,
  batchDeleteResonances,
  batchUpdateResonanceStatus,
  type AdminResonanceRow,
} from '../../api/adminManagement'
import { useToastStore } from '../../stores/toast'

const { t } = useI18n()
const toast = useToastStore()

const rows = ref<AdminResonanceRow[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const statusFilter = ref('')
const selectedIds = ref<string[]>([])

const columns = [
  { key: 'memoryAId', labelKey: 'admin.resonanceMgmt.columns.memoryAId' },
  { key: 'memoryBId', labelKey: 'admin.resonanceMgmt.columns.memoryBId' },
  { key: 'resonanceScore', labelKey: 'admin.resonanceMgmt.columns.score', width: '90px', align: 'right' as const },
  { key: 'status', labelKey: 'admin.resonanceMgmt.columns.status', width: '110px' },
  { key: 'createdAt', labelKey: 'admin.resonanceMgmt.columns.createdAt', width: '160px' },
]

async function load() {
  loading.value = true
  error.value = null
  try {
    const params: Record<string, string | number> = { page: page.value, size: size.value }
    if (statusFilter.value) params.status = statusFilter.value
    const { data } = await listResonancesAdmin(params as any)
    if (data.code === 200) {
      rows.value = data.data.items || []
      total.value = data.data.total || 0
    } else {
      error.value = { code: data.message || 'NETWORK', message: data.message || 'Error' }
    }
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    error.value = {
      code: status === 403 ? 'ADMIN_REQUIRED' : 'NETWORK',
      message: t(`admin.errors.${status === 403 ? 'ADMIN_REQUIRED' : 'NETWORK'}`),
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([page, size, statusFilter], () => {
  if (page.value !== 0 && statusFilter.value) {
    page.value = 0
    return
  }
  void load()
})

function toastFail(e: unknown) {
  const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
  toast.push({ key: 'admin.memoriesMgmt.toast.operationFailed', tone: 'error', params: { msg } })
}

async function onSetStatus(row: AdminResonanceRow, status: string) {
  try {
    await patchResonance(row.id, { status })
    toast.push({ key: 'admin.memoriesMgmt.toast.updated', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e) { toastFail(e) }
}

async function onDelete(row: AdminResonanceRow) {
  if (!window.confirm(t('admin.resonanceMgmt.actions.delete') + ' #' + row.id.slice(0, 8) + '?')) return
  try {
    await deleteResonance(row.id)
    toast.push({ key: 'admin.memoriesMgmt.toast.deleted', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e) { toastFail(e) }
}

async function onBatchDelete() {
  if (!selectedIds.value.length) return
  if (!window.confirm(t('admin.resonanceMgmt.batch.delete') + ' (' + selectedIds.value.length + ')?')) return
  try {
    const { data } = await batchDeleteResonances(selectedIds.value)
    if (data.code === 200) {
      toast.push({ key: 'admin.memoriesMgmt.toast.deleted', tone: 'success', params: { n: data.data.deleted } })
      selectedIds.value = []
      void load()
    }
  } catch (e) { toastFail(e) }
}

async function onBatchStatus(status: string) {
  if (!selectedIds.value.length) return
  try {
    const { data } = await batchUpdateResonanceStatus(selectedIds.value, status)
    if (data.code === 200) {
      toast.push({ key: 'admin.memoriesMgmt.toast.updated', tone: 'success', params: { n: data.data.updated } })
      selectedIds.value = []
      void load()
    }
  } catch (e) { toastFail(e) }
}

function uiState() {
  if (loading.value && !rows.value.length) return 'loading'
  if (error.value) return 'error'
  return 'ready'
}
</script>

<template>
  <AdminPanel
    title="admin.resonanceMgmt.title"
    :state="uiState()"
    :error="error"
    :on-retry="load"
  >
    <div class="res-mgmt">
      <p class="res-mgmt__subtitle">{{ t('admin.resonanceMgmt.subtitle') }}</p>
      <div class="res-mgmt__toolbar">
        <select v-model="statusFilter" class="res-mgmt__select">
          <option value="">{{ t('admin.resonanceMgmt.filters.all') }}</option>
          <option value="pending">{{ t('admin.resonanceMgmt.filters.pending') }}</option>
          <option value="accepted">{{ t('admin.resonanceMgmt.filters.accepted') }}</option>
          <option value="rejected">{{ t('admin.resonanceMgmt.filters.rejected') }}</option>
          <option value="archived">{{ t('admin.resonanceMgmt.filters.archived') }}</option>
        </select>
        <button class="button button--ghost" type="button" @click="load">{{ t('common.refresh') }}</button>
      </div>

      <div v-if="selectedIds.length" class="res-mgmt__batch-bar">
        <span>{{ t('admin.resonanceMgmt.batch.selected', { n: selectedIds.length }) }}</span>
        <button class="button button--ghost" type="button" @click="onBatchStatus('accepted')">accepted</button>
        <button class="button button--ghost" type="button" @click="onBatchStatus('rejected')">rejected</button>
        <button class="button button--ghost" type="button" @click="onBatchStatus('archived')">archived</button>
        <button class="button button--secondary button--danger" type="button" @click="onBatchDelete">
          {{ t('admin.resonanceMgmt.batch.delete') }}
        </button>
        <button class="button button--ghost" type="button" @click="selectedIds = []">
          {{ t('admin.resonanceMgmt.batch.clearSelection') }}
        </button>
      </div>

      <AdminDataTable
        :rows="rows"
        :total="total"
        :page="page"
        :size="size"
        :loading="loading"
        :columns="columns"
        :selectable="true"
        :selected-ids="selectedIds"
        @update:page="(p) => (page = p)"
        @update:size="(s) => (size = s)"
        @update:selected-ids="(ids) => (selectedIds = ids)"
      >
        <template #cell-memoryAId="{ value }">
          <code class="mono">{{ (value as string).slice(0, 8) }}…</code>
        </template>
        <template #cell-memoryBId="{ value }">
          <code class="mono">{{ (value as string).slice(0, 8) }}…</code>
        </template>
        <template #cell-resonanceScore="{ value }">
          <strong class="score">{{ Number(value).toFixed(3) }}</strong>
        </template>
        <template #cell-status="{ value }">
          <span class="status-pill" :class="`status-pill--${value}`">{{ value }}</span>
        </template>
        <template #cell-createdAt="{ value }">
          <span class="datestamp">{{ (value as string)?.slice(0, 16).replace('T', ' ') || '—' }}</span>
        </template>
        <template #row-actions="{ row }">
          <button class="button-icon" type="button" title="accepted" @click="onSetStatus(row as AdminResonanceRow, 'accepted')">✓</button>
          <button class="button-icon" type="button" title="rejected" @click="onSetStatus(row as AdminResonanceRow, 'rejected')">✗</button>
          <button class="button-icon" type="button" title="archived" @click="onSetStatus(row as AdminResonanceRow, 'archived')">📦</button>
          <button class="button-icon button-icon--danger" type="button" :title="t('admin.resonanceMgmt.actions.delete')" @click="onDelete(row as AdminResonanceRow)">🗑</button>
        </template>
      </AdminDataTable>
    </div>
  </AdminPanel>
</template>

<style scoped>
.res-mgmt { display: flex; flex-direction: column; gap: 14px; }
.res-mgmt__subtitle { margin: 0; font-size: 0.84rem; color: var(--text-muted); }
.res-mgmt__toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.res-mgmt__select {
  appearance: none;
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 0.86rem;
  cursor: pointer;
}
.res-mgmt__batch-bar {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  background: rgba(54, 216, 180, 0.08);
  border: 1px solid rgba(54, 216, 180, 0.32);
  border-radius: var(--radius-sm);
  font-size: 0.84rem;
  color: var(--text);
}
.mono { font-family: var(--font-mono, monospace); font-size: 0.78rem; color: var(--text-muted); }
.score { font-family: var(--font-mono, monospace); color: var(--primary); }
.status-pill {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.status-pill--pending { background: rgba(245, 158, 11, 0.15); color: #fcd34d; }
.status-pill--accepted { background: rgba(54, 216, 180, 0.15); color: var(--primary); }
.status-pill--rejected { background: rgba(239, 68, 68, 0.15); color: #fca5a5; }
.status-pill--archived { background: rgba(255,255,255,0.06); color: var(--text-muted); }
.datestamp { font-family: var(--font-mono, monospace); font-size: 0.78rem; color: var(--text-muted); }
.button-icon {
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-left: 4px;
}
.button-icon:hover { background: rgba(255, 255, 255, 0.08); }
.button-icon--danger:hover { background: rgba(239, 68, 68, 0.15); border-color: rgba(239, 68, 68, 0.4); }
.button--danger { background: rgba(239, 68, 68, 0.15); border-color: rgba(239, 68, 68, 0.4); color: #fca5a5; }
</style>
