<script setup lang="ts" generic="T extends { id: string }">
/**
 * 管理端通用数据表格。
 *
 * 由具体 view（用户 / 记忆 / 共鸣 / 工单）自定义列模板（命名 slot），但分页、
 * 选中、批量动作、空态 / 加载态都由本组件统一渲染。这样新增管理面板基本就是写
 * 一个 columns 定义 + actions 槽位，最大化复用。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface ColumnDef {
  key: string
  labelKey: string
  width?: string
  align?: 'left' | 'center' | 'right'
}

const props = defineProps<{
  rows: T[]
  total: number
  page: number
  size: number
  loading?: boolean
  columns: ColumnDef[]
  selectable?: boolean
  selectedIds?: string[]
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  'update:selectedIds': [ids: string[]]
  'refresh': []
}>()

const { t } = useI18n()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))))
const allSelected = computed(() => {
  if (!props.rows.length) return false
  const set = new Set(props.selectedIds || [])
  return props.rows.every((r) => set.has(r.id))
})

function toggleAll() {
  const set = new Set(props.selectedIds || [])
  if (allSelected.value) {
    props.rows.forEach((r) => set.delete(r.id))
  } else {
    props.rows.forEach((r) => set.add(r.id))
  }
  emit('update:selectedIds', Array.from(set))
}

function toggleRow(id: string) {
  const set = new Set(props.selectedIds || [])
  if (set.has(id)) set.delete(id)
  else set.add(id)
  emit('update:selectedIds', Array.from(set))
}

function gotoPage(p: number) {
  if (p < 0 || p >= totalPages.value) return
  emit('update:page', p)
}
</script>

<template>
  <div class="admin-table" :class="{ 'admin-table--loading': loading }">
    <div v-if="loading" class="admin-table__loader" role="status">
      <span class="admin-table__spinner" />
    </div>
    <div class="admin-table__wrapper">
      <table class="admin-table__table">
        <thead>
          <tr>
            <th v-if="selectable" class="admin-table__checkbox-cell">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            </th>
            <th
              v-for="col in columns"
              :key="col.key"
              :style="{ width: col.width, minWidth: col.width, textAlign: col.align || 'left' }"
            >
              {{ t(col.labelKey) }}
            </th>
            <th class="admin-table__actions-head">
              <slot name="header-actions" />
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length && !loading" class="admin-table__empty-row">
            <td :colspan="columns.length + (selectable ? 2 : 1)">
              <slot name="empty">
                <div class="admin-table__empty">{{ t('admin.common.empty') }}</div>
              </slot>
            </td>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.id"
            :class="{ 'admin-table__row--selected': (selectedIds || []).includes(row.id) }"
          >
            <td v-if="selectable" class="admin-table__checkbox-cell">
              <input
                type="checkbox"
                :checked="(selectedIds || []).includes(row.id)"
                @change="toggleRow(row.id)"
              />
            </td>
            <td
              v-for="col in columns"
              :key="col.key"
              :style="{ width: col.width, minWidth: col.width, textAlign: col.align || 'left' }"
            >
              <slot :name="`cell-${col.key}`" :row="row" :value="(row as any)[col.key]">
                {{ (row as any)[col.key] }}
              </slot>
            </td>
            <td class="admin-table__actions-cell">
              <slot name="row-actions" :row="row" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="admin-table__footer">
      <span class="admin-table__total">{{ total }}</span>
      <div class="admin-table__pager">
        <button class="admin-table__pager-btn" :disabled="page === 0" @click="gotoPage(page - 1)">‹</button>
        <span class="admin-table__pager-info">{{ page + 1 }} / {{ totalPages }}</span>
        <button class="admin-table__pager-btn" :disabled="page >= totalPages - 1" @click="gotoPage(page + 1)">›</button>
      </div>
      <div class="admin-table__size-picker">
        <select :value="size" @change="(e) => emit('update:size', Number((e.target as HTMLSelectElement).value))">
          <option v-for="s in [10, 20, 50, 100]" :key="s" :value="s">{{ s }} / 页</option>
        </select>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-table {
  position: relative;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.4);
  overflow: hidden;
}

.admin-table__wrapper {
  width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.admin-table__loader {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 3px;
  background: linear-gradient(90deg, transparent, var(--primary), transparent);
  background-size: 50% 100%;
  animation: admin-table-loading 1.2s linear infinite;
}
@keyframes admin-table-loading {
  from { background-position: -50% 0; }
  to   { background-position: 150% 0; }
}

.admin-table__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.86rem;
}
.admin-table__table th,
.admin-table__table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: middle;
  white-space: nowrap;
}
.admin-table__table th {
  background: rgba(255, 255, 255, 0.02);
  font-weight: 600;
  color: var(--text-soft);
  font-size: 0.78rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.admin-table__table tbody tr:hover {
  background: rgba(54, 216, 180, 0.04);
}

.admin-table__row--selected {
  background: rgba(54, 216, 180, 0.10) !important;
}

.admin-table__checkbox-cell {
  width: 36px;
}

.admin-table__actions-head,
.admin-table__actions-cell {
  text-align: right;
  white-space: nowrap;
  width: 1%;
}

.admin-table__empty {
  text-align: center;
  padding: 36px 0;
  color: var(--text-muted);
  font-size: 0.9rem;
}

.admin-table__footer {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 14px;
  border-top: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  font-size: 0.82rem;
  color: var(--text-muted);
}

.admin-table__total {
  margin-right: auto;
  font-family: var(--font-mono, monospace);
}

.admin-table__pager {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.admin-table__pager-btn {
  appearance: none;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 160ms ease;
}
.admin-table__pager-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.06);
}
.admin-table__pager-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.admin-table__pager-info {
  font-family: var(--font-mono, monospace);
  font-size: 0.76rem;
  color: var(--text);
}

.admin-table__size-picker select {
  appearance: none;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  font-size: 0.78rem;
  cursor: pointer;
}
</style>
