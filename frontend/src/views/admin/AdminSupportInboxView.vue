<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import AdminDataTable from '../../components/admin/AdminDataTable.vue'
import {
  listSupportTickets,
  getSupportStats,
  patchSupportTicket,
  deleteSupportTicket,
  listTicketMessages,
  sendTicketMessage,
  type SupportTicket,
  type SupportMessage,
} from '../../api/adminManagement'
import { useToastStore } from '../../stores/toast'
import { useAuthStore } from '../../stores/auth'

const { t } = useI18n()
const toast = useToastStore()
const auth = useAuthStore()

const rows = ref<SupportTicket[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const stats = ref<Record<string, number>>({})

const search = ref('')
const statusFilter = ref('')
const priorityFilter = ref('')

// detail panel
const activeTicket = ref<SupportTicket | null>(null)
const messages = ref<SupportMessage[]>([])
const messagesLoading = ref(false)
const replyText = ref('')
const messagesEl = ref<HTMLElement | null>(null)

let ws: WebSocket | null = null

const columns = [
  { key: 'subject', labelKey: 'admin.supportInbox.columns.subject' },
  { key: 'userId', labelKey: 'admin.supportInbox.columns.user', width: '110px' },
  { key: 'priority', labelKey: 'admin.supportInbox.columns.priority', width: '90px' },
  { key: 'status', labelKey: 'admin.supportInbox.columns.status', width: '110px' },
  { key: 'lastMessageAt', labelKey: 'admin.supportInbox.columns.lastMessageAt', width: '160px' },
]

async function load() {
  loading.value = true
  error.value = null
  try {
    const params: Record<string, string | number> = { page: page.value, size: size.value }
    if (search.value.trim()) params.search = search.value.trim()
    if (statusFilter.value) params.status = statusFilter.value
    if (priorityFilter.value) params.priority = priorityFilter.value
    const { data } = await listSupportTickets(params as any)
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

async function loadStats() {
  try {
    const { data } = await getSupportStats()
    if (data.code === 200) stats.value = data.data
  } catch {
    // optional
  }
}

onMounted(() => {
  void load()
  void loadStats()
  connectWebSocket()
})

watch([page, size, search, statusFilter, priorityFilter], () => {
  if (page.value !== 0 && (search.value || statusFilter.value || priorityFilter.value)) {
    page.value = 0
    return
  }
  void load()
})

function connectWebSocket() {
  if (!auth.user) return
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const host = window.location.host
    const url = `${protocol}://${host}/ws/support?userId=${encodeURIComponent(auth.user.id)}&role=ADMIN`
    ws = new WebSocket(url)
    ws.addEventListener('message', (ev) => {
      try {
        const msg = JSON.parse(ev.data)
        if (msg.type === 'ticket_created' || msg.type === 'ticket_status') {
          // refresh list and stats
          void load()
          void loadStats()
        } else if (msg.type === 'message_new' && activeTicket.value) {
          if (msg.payload?.ticketId === activeTicket.value.id) {
            messages.value.push(msg.payload)
            void scrollToBottom()
          }
          void load()
          void loadStats()
        }
      } catch {
        // ignore
      }
    })
    ws.addEventListener('close', () => {
      // reconnect lazily
      setTimeout(() => {
        if (ws?.readyState === WebSocket.CLOSED) connectWebSocket()
      }, 5000)
    })
  } catch {
    // optional
  }
}

onBeforeUnmount(() => {
  try { ws?.close() } catch { /* noop */ }
  ws = null
})

async function openDetail(row: SupportTicket) {
  activeTicket.value = row
  messages.value = []
  messagesLoading.value = true
  try {
    const { data } = await listTicketMessages(row.id)
    if (data.code === 200) {
      messages.value = data.data || []
    }
    await scrollToBottom()
  } finally {
    messagesLoading.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  }
}

async function onReply() {
  if (!activeTicket.value || !replyText.value.trim()) return
  try {
    const { data } = await sendTicketMessage(activeTicket.value.id, {
      content: replyText.value,
      messageType: 'TEXT',
    })
    if (data.code === 201 || data.code === 200) {
      messages.value.push(data.data)
      replyText.value = ''
      await scrollToBottom()
      void load()
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.memoriesMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

async function onChangeStatus(status: string) {
  if (!activeTicket.value) return
  try {
    const { data } = await patchSupportTicket(activeTicket.value.id, { status })
    if (data.code === 200) {
      activeTicket.value = data.data
      void load()
      void loadStats()
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.memoriesMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

async function onDelete(row: SupportTicket) {
  if (!window.confirm('Delete ticket "' + row.subject + '" ?')) return
  try {
    await deleteSupportTicket(row.id)
    toast.push({ key: 'admin.memoriesMgmt.toast.deleted', tone: 'success', params: { n: 1 } })
    if (activeTicket.value?.id === row.id) activeTicket.value = null
    void load()
    void loadStats()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Error'
    toast.push({ key: 'admin.memoriesMgmt.toast.operationFailed', tone: 'error', params: { msg } })
  }
}

const uiState = computed(() => {
  if (loading.value && !rows.value.length) return 'loading' as const
  if (error.value) return 'error' as const
  return 'ready' as const
})
</script>

<template>
  <AdminPanel
    title="admin.supportInbox.title"
    :state="uiState"
    :error="error"
    :on-retry="load"
  >
    <div class="support-inbox">
      <p class="support-inbox__subtitle">{{ t('admin.supportInbox.subtitle') }}</p>

      <div class="support-inbox__stats">
        <div class="stat-card stat-card--open">
          <span class="stat-card__label">{{ t('admin.supportInbox.stats.open') }}</span>
          <strong class="stat-card__value">{{ stats.open ?? 0 }}</strong>
        </div>
        <div class="stat-card stat-card--progress">
          <span class="stat-card__label">{{ t('admin.supportInbox.stats.inProgress') }}</span>
          <strong class="stat-card__value">{{ stats.inProgress ?? 0 }}</strong>
        </div>
        <div class="stat-card stat-card--resolved">
          <span class="stat-card__label">{{ t('admin.supportInbox.stats.resolved') }}</span>
          <strong class="stat-card__value">{{ stats.resolved ?? 0 }}</strong>
        </div>
        <div class="stat-card stat-card--closed">
          <span class="stat-card__label">{{ t('admin.supportInbox.stats.closed') }}</span>
          <strong class="stat-card__value">{{ stats.closed ?? 0 }}</strong>
        </div>
      </div>

      <div class="support-inbox__layout">
        <div class="support-inbox__list">
          <div class="support-inbox__toolbar">
            <input
              class="support-inbox__search"
              type="search"
              :placeholder="t('admin.supportInbox.filters.search')"
              v-model="search"
            />
            <select v-model="statusFilter" class="support-inbox__select">
              <option value="">{{ t('admin.supportInbox.filters.all') }}</option>
              <option value="OPEN">{{ t('admin.supportInbox.status.OPEN') }}</option>
              <option value="IN_PROGRESS">{{ t('admin.supportInbox.status.IN_PROGRESS') }}</option>
              <option value="RESOLVED">{{ t('admin.supportInbox.status.RESOLVED') }}</option>
              <option value="CLOSED">{{ t('admin.supportInbox.status.CLOSED') }}</option>
            </select>
            <select v-model="priorityFilter" class="support-inbox__select">
              <option value="">{{ t('admin.supportInbox.filters.all') }}</option>
              <option value="LOW">{{ t('admin.supportInbox.priority.LOW') }}</option>
              <option value="NORMAL">{{ t('admin.supportInbox.priority.NORMAL') }}</option>
              <option value="HIGH">{{ t('admin.supportInbox.priority.HIGH') }}</option>
              <option value="URGENT">{{ t('admin.supportInbox.priority.URGENT') }}</option>
            </select>
            <button class="button button--ghost" type="button" @click="load">{{ t('common.refresh') }}</button>
          </div>

          <AdminDataTable
            :rows="rows"
            :total="total"
            :page="page"
            :size="size"
            :loading="loading"
            :columns="columns"
            @update:page="(p) => (page = p)"
            @update:size="(s) => (size = s)"
          >
            <template #cell-subject="{ row }">
              <button class="link-btn" type="button" @click="openDetail(row as SupportTicket)">
                <strong>{{ row.subject }}</strong>
              </button>
            </template>
            <template #cell-userId="{ value }">
              <code class="mono">{{ (value as string)?.slice(0, 8) }}…</code>
            </template>
            <template #cell-priority="{ value }">
              <span class="priority-pill" :class="`priority-pill--${(value as string).toLowerCase()}`">
                {{ t('admin.supportInbox.priority.' + value) }}
              </span>
            </template>
            <template #cell-status="{ value }">
              <span class="status-pill" :class="`status-pill--${(value as string).toLowerCase()}`">
                {{ t('admin.supportInbox.status.' + value) }}
              </span>
            </template>
            <template #cell-lastMessageAt="{ value }">
              <span class="datestamp">{{ (value as string)?.slice(0, 16).replace('T', ' ') || '—' }}</span>
            </template>
            <template #row-actions="{ row }">
              <button class="button-icon" type="button" :title="t('admin.supportInbox.actions.open')" @click="openDetail(row as SupportTicket)">💬</button>
              <button class="button-icon button-icon--danger" type="button" :title="t('admin.supportInbox.actions.delete')" @click="onDelete(row as SupportTicket)">🗑</button>
            </template>
          </AdminDataTable>
        </div>

        <aside v-if="activeTicket" class="support-inbox__detail">
          <header class="detail-head">
            <h4>{{ activeTicket.subject }}</h4>
            <p class="detail-head__meta">
              <code class="mono">#{{ activeTicket.id.slice(0, 8) }}</code>
              <span class="status-pill" :class="`status-pill--${activeTicket.status.toLowerCase()}`">
                {{ t('admin.supportInbox.status.' + activeTicket.status) }}
              </span>
              <span class="priority-pill" :class="`priority-pill--${activeTicket.priority.toLowerCase()}`">
                {{ t('admin.supportInbox.priority.' + activeTicket.priority) }}
              </span>
            </p>
            <p class="detail-head__sub">
              {{ t('admin.supportInbox.detail.from') }}:
              <code class="mono">{{ activeTicket.userId.slice(0, 12) }}…</code>
            </p>
          </header>

          <div ref="messagesEl" class="detail-msgs">
            <div v-if="messagesLoading" class="detail-msgs__loading">{{ t('admin.common.loading') }}</div>
            <div v-else-if="!messages.length" class="detail-msgs__empty">
              {{ t('admin.supportInbox.detail.noMessages') }}
            </div>
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="msg-bubble"
              :class="`msg-bubble--${msg.senderRole.toLowerCase()}`"
            >
              <div class="msg-bubble__head">
                <span>{{ msg.senderRole === 'ADMIN' ? t('support.messages.support') : t('support.messages.you') }}</span>
                <span class="msg-bubble__time">{{ msg.createdAt?.slice(11, 16) }}</span>
              </div>
              <div v-if="msg.messageType === 'IMAGE'" class="msg-bubble__body">
                <img :src="msg.content" :alt="msg.fileName || 'image'" class="msg-bubble__img" />
              </div>
              <div v-else class="msg-bubble__body">{{ msg.content }}</div>
            </div>
          </div>

          <div class="detail-actions">
            <button class="button button--ghost" type="button" @click="onChangeStatus('IN_PROGRESS')" :disabled="activeTicket.status === 'IN_PROGRESS'">
              {{ t('admin.supportInbox.status.IN_PROGRESS') }}
            </button>
            <button class="button button--ghost" type="button" @click="onChangeStatus('RESOLVED')" :disabled="activeTicket.status === 'RESOLVED'">
              {{ t('admin.supportInbox.actions.resolve') }}
            </button>
            <button class="button button--ghost" type="button" @click="onChangeStatus('CLOSED')" :disabled="activeTicket.status === 'CLOSED'">
              {{ t('admin.supportInbox.actions.close') }}
            </button>
          </div>

          <div class="detail-reply">
            <textarea
              v-model="replyText"
              :placeholder="t('admin.supportInbox.detail.replyPlaceholder')"
              rows="3"
              :disabled="activeTicket.status === 'CLOSED'"
            />
            <button
              class="button button--primary"
              type="button"
              :disabled="!replyText.trim() || activeTicket.status === 'CLOSED'"
              @click="onReply"
            >
              {{ t('admin.supportInbox.detail.send') }}
            </button>
          </div>
        </aside>
      </div>
    </div>
  </AdminPanel>
</template>

<style scoped>
.support-inbox { display: flex; flex-direction: column; gap: 14px; }
.support-inbox__subtitle { margin: 0; font-size: 0.84rem; color: var(--text-muted); }

.support-inbox__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}
.stat-card {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-card__label { font-size: 0.74rem; color: var(--text-muted); letter-spacing: 0.06em; text-transform: uppercase; }
.stat-card__value { font-family: var(--font-display); font-size: 1.6rem; color: var(--text); }
.stat-card--open { border-left: 3px solid #fbbf24; }
.stat-card--progress { border-left: 3px solid #60a5fa; }
.stat-card--resolved { border-left: 3px solid var(--primary); }
.stat-card--closed { border-left: 3px solid rgba(255, 255, 255, 0.3); }

.support-inbox__layout {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(320px, 1fr);
  gap: 16px;
}
@media (max-width: 1100px) {
  .support-inbox__layout { grid-template-columns: 1fr; }
}

.support-inbox__list { min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.support-inbox__toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.support-inbox__search {
  flex: 1 1 200px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,0.04);
  color: var(--text);
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
}
.support-inbox__select {
  appearance: none;
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 0.84rem;
  cursor: pointer;
}

.link-btn {
  appearance: none;
  border: none;
  background: none;
  color: var(--primary);
  cursor: pointer;
  text-align: left;
  padding: 0;
  font: inherit;
}
.link-btn:hover { text-decoration: underline; }

.priority-pill, .status-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.priority-pill--low { background: rgba(255,255,255,0.06); color: var(--text-muted); }
.priority-pill--normal { background: rgba(96, 165, 250, 0.15); color: #93c5fd; }
.priority-pill--high { background: rgba(245, 158, 11, 0.15); color: #fcd34d; }
.priority-pill--urgent { background: rgba(239, 68, 68, 0.18); color: #fca5a5; }

.status-pill--open { background: rgba(245, 158, 11, 0.15); color: #fcd34d; }
.status-pill--in_progress { background: rgba(96, 165, 250, 0.15); color: #93c5fd; }
.status-pill--resolved { background: rgba(54, 216, 180, 0.15); color: var(--primary); }
.status-pill--closed { background: rgba(255,255,255,0.06); color: var(--text-muted); }

.mono { font-family: var(--font-mono, monospace); font-size: 0.78rem; color: var(--text-muted); }
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

/* ----- detail panel ----- */
.support-inbox__detail {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.4);
  display: flex;
  flex-direction: column;
  min-height: 480px;
  max-height: 720px;
  overflow: hidden;
}
.detail-head {
  padding: 16px 18px;
  border-bottom: 1px solid var(--border);
}
.detail-head h4 { margin: 0 0 8px; font-family: var(--font-display); font-size: 1rem; }
.detail-head__meta { margin: 0 0 4px; display: inline-flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.detail-head__sub { margin: 0; font-size: 0.74rem; color: var(--text-muted); }

.detail-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.detail-msgs__loading,
.detail-msgs__empty {
  text-align: center;
  color: var(--text-muted);
  padding: 24px 0;
  font-size: 0.86rem;
}

.msg-bubble {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: 0.86rem;
  line-height: 1.45;
  word-break: break-word;
}
.msg-bubble--user {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
}
.msg-bubble--admin {
  align-self: flex-end;
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.20), rgba(54, 216, 180, 0.10));
  border: 1px solid rgba(54, 216, 180, 0.32);
}
.msg-bubble__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 0.7rem;
  color: var(--text-muted);
  margin-bottom: 4px;
}
.msg-bubble__time { font-family: var(--font-mono, monospace); }
.msg-bubble__body { white-space: pre-wrap; }
.msg-bubble__img {
  max-width: 240px;
  max-height: 200px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}

.detail-actions {
  display: flex;
  gap: 6px;
  padding: 8px 16px;
  border-top: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  flex-wrap: wrap;
}

.detail-reply {
  display: flex;
  gap: 8px;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--border);
}
.detail-reply textarea {
  flex: 1;
  resize: vertical;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  font-size: 0.86rem;
  font-family: inherit;
}
</style>
