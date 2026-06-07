<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import AdminDataTable from '../../components/admin/AdminDataTable.vue'
import {
  listUsers,
  changeUserRole,
  changeUserVerified,
  deleteUser,
  batchDeleteUsers,
  type AdminUserRow,
} from '../../api/adminManagement'
import { useToastStore } from '../../stores/toast'

const { t } = useI18n()
const toast = useToastStore()

const rows = ref<AdminUserRow[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const search = ref('')
const roleFilter = ref<'' | 'USER' | 'ADMIN'>('')
const verifiedFilter = ref<'' | 'true' | 'false'>('')
const selectedIds = ref<string[]>([])

const columns = [
  { key: 'username', labelKey: 'admin.usersMgmt.columns.username', width: '160px' },
  { key: 'email', labelKey: 'admin.usersMgmt.columns.email' },
  { key: 'role', labelKey: 'admin.usersMgmt.columns.role', width: '90px' },
  { key: 'verified', labelKey: 'admin.usersMgmt.columns.verified', width: '90px' },
  { key: 'createdAt', labelKey: 'admin.usersMgmt.columns.createdAt', width: '160px' },
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
    if (roleFilter.value) params.role = roleFilter.value
    if (verifiedFilter.value) params.verified = verifiedFilter.value === 'true'
    const { data } = await listUsers(params as any)
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
watch([page, size, search, roleFilter, verifiedFilter], () => {
  if (page.value !== 0 && (search.value || roleFilter.value || verifiedFilter.value)) {
    page.value = 0
    return
  }
  void load()
})

async function onChangeRole(row: AdminUserRow) {
  const target = row.role === 'ADMIN' ? 'USER' : 'ADMIN'
  const confirmKey = target === 'ADMIN' ? 'admin.usersMgmt.confirm.promote' : 'admin.usersMgmt.confirm.demote'
  if (!window.confirm(t(confirmKey, { name: row.username }))) return
  try {
    await changeUserRole(row.id, target)
    toast.push({ key: 'admin.usersMgmt.toast.roleChanged', tone: 'success' })
    void load()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.usersMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

async function onToggleVerified(row: AdminUserRow) {
  const target = !row.verified
  const confirmKey = target ? 'admin.usersMgmt.confirm.verify' : 'admin.usersMgmt.confirm.unverify'
  if (!window.confirm(t(confirmKey, { name: row.username }))) return
  try {
    await changeUserVerified(row.id, target)
    toast.push({ key: 'admin.usersMgmt.toast.verifiedChanged', tone: 'success' })
    void load()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.usersMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

async function onDelete(row: AdminUserRow) {
  if (!window.confirm(t('admin.usersMgmt.confirm.deleteOne', { name: row.username }))) return
  try {
    await deleteUser(row.id)
    toast.push({ key: 'admin.usersMgmt.toast.deleted', tone: 'success', params: { n: 1 } })
    void load()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.usersMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

async function onBatchDelete() {
  if (!selectedIds.value.length) return
  if (!window.confirm(t('admin.usersMgmt.batch.deleteConfirm', { n: selectedIds.value.length }))) return
  try {
    const { data } = await batchDeleteUsers(selectedIds.value)
    if (data.code === 200) {
      toast.push({
        key: 'admin.usersMgmt.toast.deleted',
        tone: 'success',
        params: { n: data.data.deleted },
      })
      if (data.data.failed?.length) {
        toast.push({
          key: 'admin.usersMgmt.toast.deleteFailed',
          tone: 'warning',
          params: { n: data.data.failed.length },
        })
      }
      selectedIds.value = []
      void load()
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.usersMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

function uiState() {
  if (loading.value && !rows.value.length) return 'loading'
  if (error.value) return 'error'
  return 'ready'
}
</script>

<template>
  <AdminPanel
    title="admin.usersMgmt.title"
    :state="uiState()"
    :error="error"
    :on-retry="load"
  >
    <div class="users-mgmt">
      <p class="users-mgmt__subtitle">{{ t('admin.usersMgmt.subtitle') }}</p>

      <div class="users-mgmt__toolbar">
        <input
          class="users-mgmt__search"
          type="search"
          :placeholder="t('admin.usersMgmt.search')"
          v-model="search"
        />
        <select v-model="roleFilter" class="users-mgmt__select">
          <option value="">{{ t('admin.usersMgmt.filters.all') }}</option>
          <option value="USER">USER</option>
          <option value="ADMIN">ADMIN</option>
        </select>
        <select v-model="verifiedFilter" class="users-mgmt__select">
          <option value="">{{ t('admin.usersMgmt.filters.all') }}</option>
          <option value="true">{{ t('admin.usersMgmt.filters.verified') }}</option>
          <option value="false">{{ t('admin.usersMgmt.filters.unverified') }}</option>
        </select>
        <button class="button button--ghost" type="button" @click="load">
          {{ t('common.refresh') }}
        </button>
      </div>

      <div v-if="selectedIds.length" class="users-mgmt__batch-bar">
        <span>{{ t('admin.usersMgmt.batch.selected', { n: selectedIds.length }) }}</span>
        <button class="button button--secondary button--danger" type="button" @click="onBatchDelete">
          {{ t('admin.usersMgmt.batch.delete') }}
        </button>
        <button class="button button--ghost" type="button" @click="selectedIds = []">
          {{ t('admin.usersMgmt.batch.clearSelection') }}
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
        <template #cell-username="{ row }">
          <strong>{{ row.username }}</strong>
        </template>
        <template #cell-role="{ row }">
          <span class="role-pill" :class="`role-pill--${row.role.toLowerCase()}`">
            {{ row.role }}
          </span>
        </template>
        <template #cell-verified="{ row }">
          <span class="status-pill" :class="row.verified ? 'status-pill--ok' : 'status-pill--warn'">
            {{ row.verified ? '✓' : '·' }}
          </span>
        </template>
        <template #cell-createdAt="{ value }">
          <span class="datestamp">{{ (value as string)?.slice(0, 10) || '—' }}</span>
        </template>
        <template #row-actions="{ row }">
          <button class="button-icon" type="button" :title="row.role === 'ADMIN' ? t('admin.usersMgmt.actions.demote') : t('admin.usersMgmt.actions.promote')" @click="onChangeRole(row as AdminUserRow)">
            {{ row.role === 'ADMIN' ? '↓' : '↑' }}
          </button>
          <button class="button-icon" type="button" :title="row.verified ? t('admin.usersMgmt.actions.unverify') : t('admin.usersMgmt.actions.verify')" @click="onToggleVerified(row as AdminUserRow)">
            {{ row.verified ? '✗' : '✓' }}
          </button>
          <button class="button-icon button-icon--danger" type="button" :title="t('admin.usersMgmt.actions.delete')" @click="onDelete(row as AdminUserRow)">
            🗑
          </button>
        </template>
      </AdminDataTable>
    </div>
  </AdminPanel>
</template>

<style scoped>
.users-mgmt {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.users-mgmt__subtitle {
  margin: 0;
  font-size: 0.84rem;
  color: var(--text-muted);
}
.users-mgmt__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.users-mgmt__search {
  flex: 1 1 220px;
  min-width: 200px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,0.04);
  color: var(--text);
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
}
.users-mgmt__select {
  appearance: none;
  background: rgba(255, 255, 255, 0.04) url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%238b95a1' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E") no-repeat right 12px center;
  background-size: 14px;
  padding: 8px 32px 8px 12px;
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease;
}
.users-mgmt__select:hover {
  border-color: rgba(255, 255, 255, 0.15);
  background-color: rgba(255, 255, 255, 0.06);
}
.users-mgmt__batch-bar {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  background: rgba(54, 216, 180, 0.08);
  border: 1px solid rgba(54, 216, 180, 0.32);
  border-radius: var(--radius-sm);
  font-size: 0.84rem;
  color: var(--text);
}

.role-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.role-pill--user {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
}
.role-pill--admin {
  background: linear-gradient(135deg, var(--gold), var(--primary));
  color: #052017;
}

.status-pill {
  display: inline-block;
  width: 22px;
  text-align: center;
  border-radius: var(--radius-full);
  font-size: 0.84rem;
  font-weight: 700;
}
.status-pill--ok { color: var(--primary); }
.status-pill--warn { color: var(--text-muted); }

.datestamp {
  font-family: var(--font-mono, monospace);
  font-size: 0.78rem;
  color: var(--text-muted);
}

.button-icon {
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-left: 4px;
  transition: background 140ms ease;
}
.button-icon:hover {
  background: rgba(255, 255, 255, 0.08);
}
.button-icon--danger:hover {
  background: rgba(239, 68, 68, 0.15);
  border-color: rgba(239, 68, 68, 0.4);
}

.button--danger {
  background: rgba(239, 68, 68, 0.15);
  border-color: rgba(239, 68, 68, 0.4);
  color: #fca5a5;
}
.button--danger:hover {
  background: rgba(239, 68, 68, 0.25);
}
</style>
