<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import AdminDataTable from '../../components/admin/AdminDataTable.vue'
import {
  listMemoriesAdmin,
  patchMemory,
  deleteMemoryAdmin,
  batchDeleteMemories,
  batchUpdatePrivacy,
  batchLockMemories,
  type AdminMemoryRow,
} from '../../api/adminManagement'
import { useToastStore } from '../../stores/toast'

const { t } = useI18n()
const toast = useToastStore()

const rows = ref<AdminMemoryRow[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const search = ref('')
const userIdFilter = ref('')
const privacyFilter = ref<'' | 'PRIVATE' | 'FRIENDS' | 'PUBLIC'>('')
const lockedFilter = ref<'' | 'true' | 'false'>('')
const selectedIds = ref<string[]>([])

const columns = [
  { key: 'title', labelKey: 'admin.memoriesMgmt.columns.title' },
  { key: 'userId', labelKey: 'admin.memoriesMgmt.columns.owner', width: '110px' },
  { key: 'privacyLevel', labelKey: 'admin.memoriesMgmt.columns.privacy', width: '90px' },
  { key: 'isLocked', labelKey: 'admin.memoriesMgmt.columns.locked', width: '70px' },
  { key: 'memoryYear', labelKey: 'admin.memoriesMgmt.columns.year', width: '70px' },
  { key: 'memoryLocation', labelKey: 'admin.memoriesMgmt.columns.location' },
  { key: 'createdAt', labelKey: 'admin.memoriesMgmt.columns.createdAt', width: '110px' },
]

async function load() {
  loading.value = true
  error.value = null
  try {
    const params: Record<string, string | number | boolean> = {
      page: page.value,
      size: size.value,
    }
    if (search.value.trim()) params.search = search.value.trim()
    if (userIdFilter.value.trim()) params.userId = userIdFilter.value.trim()
    if (privacyFilter.value) params.privacyLevel = privacyFilter.value
    if (lockedFilter.value) params.locked = lockedFilter.value === 'true'
    const { data } = await listMemoriesAdmin(params as any)
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
let filterTimer: ReturnType<typeof setTimeout> | undefined
onUnmounted(() => clearTimeout(filterTimer))
watch([page], () => void load())
watch([size, search, userIdFilter, privacyFilter, lockedFilter], () => {
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    if (page.value !== 0) page.value = 0
    else void load()
  }, 300)
})

function toastFail(e: unknown) {
  const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
  toast.push({ key: 'admin.memoriesMgmt.toast.operationFailed', tone: 'error', params: { msg } })
}

async function onPatchPrivacy(row: AdminMemoryRow, level: 'PRIVATE' | 'FRIENDS' | 'PUBLIC') {
  try {
    await patchMemory(row.id, { privacyLevel: level })
    toast.push({ key: 'admin.memoriesMgmt.toast.updated', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e) { toastFail(e) }
}

async function onToggleLock(row: AdminMemoryRow) {
  try {
    await patchMemory(row.id, { locked: !row.isLocked })
    toast.push({ key: 'admin.memoriesMgmt.toast.updated', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e) { toastFail(e) }
}

async function onDelete(row: AdminMemoryRow) {
  if (!window.confirm(t('admin.memoriesMgmt.confirm.deleteOne', { title: row.title }))) return
  try {
    await deleteMemoryAdmin(row.id)
    toast.push({ key: 'admin.memoriesMgmt.toast.deleted', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e) { toastFail(e) }
}

async function onBatchDelete() {
  if (!selectedIds.value.length) return
  if (!window.confirm(t('admin.memoriesMgmt.batch.deleteConfirm', { n: selectedIds.value.length }))) return
  try {
    const { data } = await batchDeleteMemories(selectedIds.value)
    if (data.code === 200) {
      toast.push({ key: 'admin.memoriesMgmt.toast.deleted', tone: 'success', params: { n: data.data.deleted } })
      selectedIds.value = []
      void load()
    }
  } catch (e) { toastFail(e) }
}

async function onBatchPrivacy(level: 'PRIVATE' | 'FRIENDS' | 'PUBLIC') {
  if (!selectedIds.value.length) return
  try {
    const { data } = await batchUpdatePrivacy(selectedIds.value, level)
    if (data.code === 200) {
      toast.push({ key: 'admin.memoriesMgmt.toast.updated', tone: 'success', params: { n: data.data.updated } })
      selectedIds.value = []
      void load()
    }
  } catch (e) { toastFail(e) }
}

async function onBatchLock(locked: boolean) {
  if (!selectedIds.value.length) return
  try {
    const { data } = await batchLockMemories(selectedIds.value, locked)
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
    title="admin.memoriesMgmt.title"
    :state="uiState()"
    :error="error"
    :on-retry="load"
  >
    <div class="mem-mgmt">
      <p class="mem-mgmt__subtitle">{{ t('admin.memoriesMgmt.subtitle') }}</p>

      <div class="mem-mgmt__toolbar">
        <input
          class="mem-mgmt__search"
          type="search"
          :placeholder="t('admin.memoriesMgmt.search')"
          v-model="search"
        />
        <input
          class="mem-mgmt__search mem-mgmt__search--narrow"
          type="text"
          :placeholder="t('admin.memoriesMgmt.filters.userId')"
          v-model="userIdFilter"
        />
        <select v-model="privacyFilter" class="mem-mgmt__select">
          <option value="">{{ t('admin.memoriesMgmt.filters.all') }} ({{ t('admin.memoriesMgmt.filters.privacy') }})</option>
          <option value="PRIVATE">PRIVATE</option>
          <option value="FRIENDS">FRIENDS</option>
          <option value="PUBLIC">PUBLIC</option>
        </select>
        <select v-model="lockedFilter" class="mem-mgmt__select">
          <option value="">{{ t('admin.memoriesMgmt.filters.all') }}</option>
          <option value="true">{{ t('admin.memoriesMgmt.filters.yes') }}</option>
          <option value="false">{{ t('admin.memoriesMgmt.filters.no') }}</option>
        </select>
        <button class="button button--ghost" type="button" @click="load">{{ t('common.refresh') }}</button>
      </div>

      <div v-if="selectedIds.length" class="mem-mgmt__batch-bar">
        <span>{{ t('admin.memoriesMgmt.batch.selected', { n: selectedIds.length }) }}</span>
        <button class="button button--ghost" type="button" @click="onBatchPrivacy('PRIVATE')">PRIVATE</button>
        <button class="button button--ghost" type="button" @click="onBatchPrivacy('FRIENDS')">FRIENDS</button>
        <button class="button button--ghost" type="button" @click="onBatchPrivacy('PUBLIC')">PUBLIC</button>
        <button class="button button--ghost" type="button" @click="onBatchLock(true)">{{ t('admin.memoriesMgmt.batch.lock') }}</button>
        <button class="button button--ghost" type="button" @click="onBatchLock(false)">{{ t('admin.memoriesMgmt.batch.unlock') }}</button>
        <button class="button button--secondary button--danger" type="button" @click="onBatchDelete">
          {{ t('admin.memoriesMgmt.batch.delete') }}
        </button>
        <button class="button button--ghost" type="button" @click="selectedIds = []">
          {{ t('admin.memoriesMgmt.batch.clearSelection') }}
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
        <template #cell-title="{ row }">
          <strong class="mem-title">{{ row.title }}</strong>
          <span class="mem-desc" v-if="row.description">{{ row.description }}</span>
        </template>
        <template #cell-userId="{ value }">
          <code class="mono">{{ (value as string)?.slice(0, 8) }}…</code>
        </template>
        <template #cell-privacyLevel="{ value }">
          <span class="privacy-pill" :class="`privacy-pill--${(value as string).toLowerCase()}`">
            {{ value }}
          </span>
        </template>
        <template #cell-isLocked="{ value }">
          <span class="lock-cell" :title="value ? t('admin.memoriesMgmt.actions.lock') : t('admin.memoriesMgmt.actions.unlock')">
            <svg v-if="value" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/></svg>
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
          </span>
        </template>
        <template #cell-createdAt="{ value }">
          <span class="datestamp">{{ (value as string)?.slice(0, 10) || '—' }}</span>
        </template>
        <template #row-actions="{ row }">
          <button class="button-icon" type="button" :title="row.isLocked ? t('admin.memoriesMgmt.actions.unlock') : t('admin.memoriesMgmt.actions.lock')" @click="onToggleLock(row as AdminMemoryRow)">
            <svg v-if="row.isLocked" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/></svg>
            <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
          </button>
          <button class="button-icon" type="button" :title="t('admin.memoriesMgmt.actions.makePublic')" @click="onPatchPrivacy(row as AdminMemoryRow, 'PUBLIC')" :disabled="row.privacyLevel === 'PUBLIC'">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          </button>
          <button class="button-icon" type="button" :title="t('admin.memoriesMgmt.actions.makePrivate')" @click="onPatchPrivacy(row as AdminMemoryRow, 'PRIVATE')" :disabled="row.privacyLevel === 'PRIVATE'">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
          </button>
          <button class="button-icon button-icon--danger" type="button" :title="t('admin.memoriesMgmt.actions.delete')" @click="onDelete(row as AdminMemoryRow)">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
          </button>
        </template>
      </AdminDataTable>
    </div>
  </AdminPanel>
</template>

<style scoped>
.mem-mgmt { display: flex; flex-direction: column; gap: 14px; }
.mem-mgmt__subtitle { margin: 0; font-size: 0.84rem; color: var(--text-muted); }
.mem-mgmt__toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.mem-mgmt__search {
  flex: 1 1 200px;
  min-width: 180px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,0.04);
  color: var(--text);
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
}
.mem-mgmt__search--narrow { flex: 0 0 180px; min-width: 140px; font-family: var(--font-mono, monospace); font-size: 0.78rem; }
.mem-mgmt__select {
  appearance: none;
  background: rgba(255, 255, 255, 0.04) url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%238b95a1' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E") no-repeat right 12px center;
  background-size: 14px;
  padding: 8px 32px 8px 12px;
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  font-size: 0.84rem;
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease;
}
.mem-mgmt__select:hover {
  border-color: rgba(255, 255, 255, 0.15);
  background-color: rgba(255, 255, 255, 0.06);
}
.mem-mgmt__batch-bar {
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

.mem-title {
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mem-desc {
  display: block;
  margin-top: 2px;
  font-size: 0.74rem;
  color: var(--text-muted);
  font-weight: 400;
  max-width: 460px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono { font-family: var(--font-mono, monospace); font-size: 0.78rem; color: var(--text-muted); }
.datestamp { font-family: var(--font-mono, monospace); font-size: 0.78rem; color: var(--text-muted); }

.lock-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--primary);
  vertical-align: middle;
}
.lock-cell svg { opacity: 0.85; }

.privacy-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.privacy-pill--public { background: rgba(54, 216, 180, 0.15); color: var(--primary); }
.privacy-pill--friends { background: rgba(245, 158, 11, 0.15); color: #fcd34d; }
.privacy-pill--private { background: rgba(255,255,255,0.06); color: var(--text-muted); }

.button-icon {
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-left: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.button-icon:disabled { opacity: 0.3; cursor: not-allowed; }
.button-icon:hover:not(:disabled) { background: rgba(255, 255, 255, 0.08); }
.button-icon--danger:hover:not(:disabled) { background: rgba(239, 68, 68, 0.15); border-color: rgba(239, 68, 68, 0.4); }
.button--danger { background: rgba(239, 68, 68, 0.15); border-color: rgba(239, 68, 68, 0.4); color: #fca5a5; }
.button--danger:hover { background: rgba(239, 68, 68, 0.25); }
</style>
