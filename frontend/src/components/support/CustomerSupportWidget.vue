<script setup lang="ts">
/**
 * 全局浮动客服窗口。
 *
 * 浮动按钮固定在右下角；点击展开弹窗：
 *   - tickets 列表 tab：用户已创建的所有工单；点击进入对话
 *   - newTicket tab：表单提交新工单（subject + description + priority）
 *   - 对话视图：显示某条工单的全部消息，下方输入框支持文字 / emoji / 图片上传
 *
 * 实时性：通过 /ws/support?userId=...&role=USER WebSocket 接收 message_new /
 *   ticket_status 推送；失败时回退到 polling。
 *
 * 需要登录才显示（未登录用户不会看到入口）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import client from '../../api/client'
import {
  closeTicket,
  createTicket,
  listMessages,
  listMyTickets,
  markRead,
  sendMessage,
  type SupportMessage,
  type SupportTicket,
} from '../../api/support'
import { useAuthStore } from '../../stores/auth'

type View = 'list' | 'create' | 'detail'

const { t } = useI18n()
const auth = useAuthStore()

const open = ref(false)
const view = ref<View>('list')

const tickets = ref<SupportTicket[]>([])
const ticketsLoading = ref(false)
const ticketsError = ref<string | null>(null)

const activeTicket = ref<SupportTicket | null>(null)
const messages = ref<SupportMessage[]>([])
const messagesLoading = ref(false)
const messagesEl = ref<HTMLElement | null>(null)

const inputText = ref('')
const subjectInput = ref('')
const descInput = ref('')
const priorityInput = ref<'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'>('NORMAL')
const submitting = ref(false)

const showEmojiPicker = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

let ws: WebSocket | null = null
let pollTimer: number | null = null

// 一组常用 emoji（避免引第三方 emoji 库的 bundle 体积）
const EMOJI_LIST = [
  '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂',
  '🙂', '🙃', '😉', '😊', '😇', '🥰', '😍', '🤩',
  '😘', '😗', '😚', '😙', '🥲', '😋', '😛', '😜',
  '🤪', '😝', '🤑', '🤗', '🤭', '🤫', '🤔', '😐',
  '😑', '😶', '🙄', '😏', '😒', '🙁', '☹️', '😞',
  '😔', '😟', '😕', '🙁', '😣', '😖', '😫', '😩',
  '🥺', '😢', '😭', '😤', '😠', '😡', '🤬', '🤯',
  '😳', '🥵', '🥶', '😱', '😨', '😰', '😥', '😓',
  '🤝', '👍', '👎', '👏', '🙌', '👋', '🤚', '✋',
  '👌', '✌️', '🤞', '🤟', '🤘', '👈', '👉', '👆',
  '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍',
  '💔', '💕', '💖', '💗', '💘', '💝', '💞', '💟',
  '🌟', '⭐', '✨', '🎉', '🎊', '🎁', '🎂', '🎈',
  '🌹', '🌸', '🌺', '🌻', '🌼', '🌷', '🍀', '🌈',
]

const visible = computed(() => auth.isLoggedIn)

function openWidget() {
  open.value = true
  if (view.value === 'list') {
    void loadTickets()
  }
}

function closeWidget() {
  open.value = false
  showEmojiPicker.value = false
}

function backToList() {
  view.value = 'list'
  activeTicket.value = null
  messages.value = []
  showEmojiPicker.value = false
  void loadTickets()
}

async function loadTickets() {
  ticketsLoading.value = true
  ticketsError.value = null
  try {
    const { data } = await listMyTickets({ page: 0, size: 50 })
    if (data.code === 200) {
      tickets.value = data.data.items || []
    } else {
      ticketsError.value = data.message || 'Error'
    }
  } catch (e: unknown) {
    ticketsError.value = (e as Error).message
  } finally {
    ticketsLoading.value = false
  }
}

async function openDetail(t: SupportTicket) {
  view.value = 'detail'
  activeTicket.value = t
  messages.value = []
  messagesLoading.value = true
  try {
    const { data } = await listMessages(t.id)
    if (data.code === 200) {
      messages.value = data.data || []
    }
    await scrollToBottom()
    // 标记该工单中所有 admin 消息为已读
    void markRead(t.id)
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

async function onSendText() {
  if (!activeTicket.value || !inputText.value.trim()) return
  const text = inputText.value
  inputText.value = ''
  try {
    const { data } = await sendMessage(activeTicket.value.id, {
      content: text,
      messageType: 'TEXT',
    })
    if (data.code === 200 || data.code === 201) {
      messages.value.push(data.data)
      await scrollToBottom()
    }
  } catch {
    inputText.value = text
    alert(t('support.errors.sendFailed'))
  }
}

function onPickEmoji(emoji: string) {
  inputText.value += emoji
}

function triggerFilePicker() {
  fileInput.value?.click()
}

async function onFilePicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !activeTicket.value) return
  if (file.size > 5 * 1024 * 1024) {
    alert(t('support.messages.imageTooLarge'))
    input.value = ''
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const upResp = await client.post('/assets/upload?purpose=support', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    if (upResp.data.code === 200) {
      const url = upResp.data.data.url as string
      const { data } = await sendMessage(activeTicket.value.id, {
        content: url,
        messageType: 'IMAGE',
        fileName: file.name,
        fileSize: file.size,
      })
      if (data.code === 200 || data.code === 201) {
        messages.value.push(data.data)
        await scrollToBottom()
      }
    } else {
      alert(t('support.errors.uploadFailed', { msg: upResp.data.message }))
    }
  } catch (e: unknown) {
    alert(t('support.errors.uploadFailed', { msg: (e as Error).message || '...' }))
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function onCreateTicket() {
  if (!subjectInput.value.trim()) return
  submitting.value = true
  try {
    const { data } = await createTicket({
      subject: subjectInput.value,
      description: descInput.value,
      priority: priorityInput.value,
      clientType: 'WEB_VUE',
    })
    if (data.code === 201 || data.code === 200) {
      subjectInput.value = ''
      descInput.value = ''
      priorityInput.value = 'NORMAL'
      await openDetail(data.data)
    }
  } catch {
    alert(t('support.errors.createFailed'))
  } finally {
    submitting.value = false
  }
}

async function onCloseTicket() {
  if (!activeTicket.value) return
  if (!window.confirm(t('support.closeConfirm'))) return
  try {
    const { data } = await closeTicket(activeTicket.value.id)
    if (data.code === 200) {
      activeTicket.value = data.data
    }
  } catch {
    // noop
  }
}

function connectWebSocket() {
  if (!auth.user) return
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const host = window.location.host
    const url = `${protocol}://${host}/ws/support?userId=${encodeURIComponent(auth.user.id)}&role=USER`
    ws = new WebSocket(url)
    ws.addEventListener('message', (ev) => {
      try {
        const msg = JSON.parse(ev.data)
        if (msg.type === 'message_new') {
          if (activeTicket.value && msg.payload?.ticketId === activeTicket.value.id) {
            messages.value.push(msg.payload)
            void scrollToBottom()
            void markRead(activeTicket.value.id)
          } else {
            // ticket list 角标更新
            void loadTickets()
          }
        } else if (msg.type === 'ticket_status') {
          if (activeTicket.value && msg.payload?.id === activeTicket.value.id) {
            activeTicket.value = { ...activeTicket.value, status: msg.payload.status }
          }
          void loadTickets()
        }
      } catch {
        // ignore
      }
    })
    ws.addEventListener('close', () => {
      setTimeout(() => {
        if (auth.isLoggedIn && (!ws || ws.readyState === WebSocket.CLOSED)) connectWebSocket()
      }, 5000)
    })
  } catch {
    // optional
  }
}

onMounted(() => {
  if (auth.isLoggedIn) {
    connectWebSocket()
    // 偶尔刷新列表（兜底）
    pollTimer = window.setInterval(() => {
      if (open.value && view.value === 'list') void loadTickets()
    }, 30_000)
  }
})

watch(() => auth.isLoggedIn, (logged) => {
  if (logged) {
    if (!ws) connectWebSocket()
  } else {
    try { ws?.close() } catch { /* noop */ }
    ws = null
    closeWidget()
  }
})

onBeforeUnmount(() => {
  try { ws?.close() } catch { /* noop */ }
  ws = null
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

const unreadCount = computed(() => {
  // 有 OPEN / IN_PROGRESS 工单且最后一条消息是 admin 视为提示
  return tickets.value.filter((t) => t.status !== 'CLOSED').length
})
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="cs-widget">
      <!-- 浮动按钮 -->
      <button
        v-if="!open"
        class="cs-fab"
        type="button"
        :aria-label="t('support.fab')"
        @click="openWidget"
      >
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
          <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"
            stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span v-if="unreadCount > 0" class="cs-fab__badge">{{ unreadCount }}</span>
      </button>

      <!-- 弹窗 -->
      <div v-if="open" class="cs-panel" role="dialog" :aria-label="t('support.title')">
        <header class="cs-panel__head">
          <div class="cs-panel__title">
            <strong>{{ t('support.title') }}</strong>
            <small>{{ t('support.subtitle') }}</small>
          </div>
          <button type="button" class="cs-panel__close" :aria-label="t('common.cancel')" @click="closeWidget">×</button>
        </header>

        <!-- 列表视图 -->
        <div v-if="view === 'list'" class="cs-panel__body">
          <div class="cs-panel__tabs">
            <button class="cs-tab cs-tab--active" type="button">{{ t('support.myTickets') }}</button>
            <button class="cs-tab" type="button" @click="view = 'create'">{{ t('support.newTicket') }}</button>
          </div>
          <div v-if="ticketsLoading" class="cs-panel__loading">{{ t('admin.common.loading') }}</div>
          <div v-else-if="!tickets.length" class="cs-panel__empty">
            {{ t('support.emptyTickets') }}
          </div>
          <ul v-else class="cs-tickets">
            <li
              v-for="ti in tickets"
              :key="ti.id"
              class="cs-ticket-item"
              @click="openDetail(ti)"
            >
              <div class="cs-ticket-item__head">
                <strong>{{ ti.subject }}</strong>
                <span class="status-pill" :class="`status-pill--${ti.status.toLowerCase()}`">
                  {{ t('support.ticketStatus.' + ti.status) }}
                </span>
              </div>
              <small class="cs-ticket-item__meta">
                <span>{{ t('support.priority.' + ti.priority) }}</span>
                <span>·</span>
                <span>{{ ti.lastMessageAt?.slice(0, 16).replace('T', ' ') || '' }}</span>
              </small>
            </li>
          </ul>
        </div>

        <!-- 创建工单 -->
        <div v-else-if="view === 'create'" class="cs-panel__body">
          <button class="cs-back" type="button" @click="view = 'list'">‹ {{ t('support.back') }}</button>
          <h4 class="cs-create__title">{{ t('support.createTitle') }}</h4>

          <label class="cs-field">
            <span>{{ t('support.subjectLabel') }}</span>
            <input
              type="text"
              :placeholder="t('support.subjectPlaceholder')"
              v-model="subjectInput"
              maxlength="200"
            />
          </label>
          <label class="cs-field">
            <span>{{ t('support.descriptionLabel') }}</span>
            <textarea
              :placeholder="t('support.descriptionPlaceholder')"
              v-model="descInput"
              rows="4"
            />
          </label>
          <label class="cs-field">
            <span>{{ t('support.priorityLabel') }}</span>
            <select v-model="priorityInput">
              <option value="LOW">{{ t('support.priority.LOW') }}</option>
              <option value="NORMAL">{{ t('support.priority.NORMAL') }}</option>
              <option value="HIGH">{{ t('support.priority.HIGH') }}</option>
              <option value="URGENT">{{ t('support.priority.URGENT') }}</option>
            </select>
          </label>

          <button
            class="button button--primary cs-submit"
            type="button"
            :disabled="!subjectInput.trim() || submitting"
            @click="onCreateTicket"
          >
            {{ submitting ? t('support.submitting') : t('support.submit') }}
          </button>
        </div>

        <!-- 对话视图 -->
        <div v-else class="cs-panel__body cs-detail">
          <div class="cs-detail__head">
            <button class="cs-back" type="button" @click="backToList">‹ {{ t('support.back') }}</button>
            <span v-if="activeTicket" class="status-pill" :class="`status-pill--${activeTicket.status.toLowerCase()}`">
              {{ t('support.ticketStatus.' + activeTicket.status) }}
            </span>
          </div>
          <h4 v-if="activeTicket" class="cs-detail__title">{{ activeTicket.subject }}</h4>

          <div ref="messagesEl" class="cs-detail__messages">
            <div v-if="messagesLoading" class="cs-panel__loading">{{ t('admin.common.loading') }}</div>
            <div v-else-if="!messages.length" class="cs-panel__empty">
              {{ t('support.messages.noMessages') }}
            </div>
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="msg-bubble"
              :class="`msg-bubble--${msg.senderRole.toLowerCase()}`"
            >
              <div v-if="msg.messageType === 'IMAGE'">
                <img :src="msg.content" :alt="msg.fileName || 'image'" class="msg-bubble__img" />
              </div>
              <div v-else>{{ msg.content }}</div>
              <span class="msg-bubble__time">{{ msg.createdAt?.slice(11, 16) }}</span>
            </div>
          </div>

          <div v-if="activeTicket && activeTicket.status === 'CLOSED'" class="cs-closed-hint">
            {{ t('support.closed') }}
          </div>

          <div v-else-if="activeTicket" class="cs-detail__input">
            <div v-if="showEmojiPicker" class="cs-emoji-grid">
              <button
                v-for="emoji in EMOJI_LIST"
                :key="emoji"
                type="button"
                class="cs-emoji"
                @click="onPickEmoji(emoji)"
              >{{ emoji }}</button>
            </div>
            <textarea
              v-model="inputText"
              :placeholder="t('support.messages.placeholder')"
              rows="2"
              @keydown.enter.exact.prevent="onSendText"
            />
            <div class="cs-actions">
              <button
                type="button"
                class="cs-icon-btn"
                :title="t('support.messages.emoji')"
                @click="showEmojiPicker = !showEmojiPicker"
              >😀</button>
              <button
                type="button"
                class="cs-icon-btn"
                :title="t('support.messages.image')"
                :disabled="uploading"
                @click="triggerFilePicker"
              >
                {{ uploading ? '⏳' : '🖼' }}
              </button>
              <input ref="fileInput" type="file" accept="image/*" hidden @change="onFilePicked" />
              <button
                type="button"
                class="button button--ghost cs-close-btn"
                @click="onCloseTicket"
              >{{ t('support.close') }}</button>
              <button
                class="button button--primary cs-send"
                type="button"
                :disabled="!inputText.trim()"
                @click="onSendText"
              >
                {{ t('support.messages.send') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.cs-widget {
  position: fixed;
  bottom: 24px;
  left: 24px;
  z-index: 8500;
}

.cs-fab {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  color: #052017;
  cursor: pointer;
  box-shadow: 0 12px 32px rgba(54, 216, 180, 0.42);
  transition: transform 200ms ease;
  display: grid;
  place-items: center;
}
.cs-fab:hover { transform: scale(1.05); }

.cs-fab__badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #ef4444;
  color: white;
  font-size: 0.7rem;
  font-weight: 700;
  display: grid;
  place-items: center;
  border: 2px solid var(--bg-primary, #0e1116);
}

.cs-panel {
  width: min(420px, calc(100vw - 32px));
  max-height: min(640px, calc(100vh - 64px));
  background: rgba(14, 17, 22, 0.98);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: 0 32px 80px rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(20px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--text);
}

.cs-panel__head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.08), rgba(0,0,0,0));
}
.cs-panel__title { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.cs-panel__title strong { font-family: var(--font-display); font-size: 1rem; }
.cs-panel__title small { color: var(--text-muted); font-size: 0.74rem; }

.cs-panel__close {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 1.4rem;
  cursor: pointer;
  line-height: 1;
}
.cs-panel__close:hover { color: var(--text); }

.cs-panel__body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cs-panel__tabs {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 6px;
  margin-bottom: 4px;
}
.cs-tab {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.84rem;
  font-weight: 500;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.cs-tab--active { color: var(--primary); background: rgba(54, 216, 180, 0.1); }

.cs-panel__loading,
.cs-panel__empty {
  text-align: center;
  color: var(--text-muted);
  padding: 18px 0;
  font-size: 0.86rem;
}

.cs-tickets {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cs-ticket-item {
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease;
}
.cs-ticket-item:hover {
  background: rgba(54, 216, 180, 0.06);
  border-color: rgba(54, 216, 180, 0.3);
}
.cs-ticket-item__head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}
.cs-ticket-item__head strong { font-size: 0.86rem; }
.cs-ticket-item__meta { color: var(--text-muted); font-size: 0.7rem; display: flex; gap: 4px; }

.status-pill {
  display: inline-block;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  font-size: 0.66rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.status-pill--open { background: rgba(245, 158, 11, 0.15); color: #fcd34d; }
.status-pill--in_progress { background: rgba(96, 165, 250, 0.15); color: #93c5fd; }
.status-pill--resolved { background: rgba(54, 216, 180, 0.15); color: var(--primary); }
.status-pill--closed { background: rgba(255,255,255,0.06); color: var(--text-muted); }

.cs-back {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.78rem;
  cursor: pointer;
  padding: 4px 0;
  align-self: flex-start;
}
.cs-back:hover { color: var(--text); }

.cs-create__title {
  margin: 8px 0 0;
  font-family: var(--font-display);
  font-size: 1rem;
}

.cs-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.8rem;
}
.cs-field span { color: var(--text-muted); }
.cs-field input,
.cs-field textarea,
.cs-field select {
  padding: 8px 10px;
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text);
  font-family: inherit;
  font-size: 0.86rem;
}
.cs-submit { margin-top: 4px; align-self: flex-end; }

.cs-detail { padding-bottom: 0; }
.cs-detail__head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.cs-detail__title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 0.96rem;
  word-break: break-word;
}
.cs-detail__messages {
  flex: 1;
  min-height: 220px;
  max-height: 360px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 0;
}
.msg-bubble {
  max-width: 86%;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  font-size: 0.84rem;
  line-height: 1.4;
  word-break: break-word;
  position: relative;
}
.msg-bubble--user {
  align-self: flex-end;
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.2), rgba(54, 216, 180, 0.1));
  border: 1px solid rgba(54, 216, 180, 0.32);
}
.msg-bubble--admin {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
}
.msg-bubble__time {
  display: block;
  font-size: 0.66rem;
  color: var(--text-muted);
  margin-top: 4px;
  font-family: var(--font-mono, monospace);
}
.msg-bubble__img {
  max-width: 240px;
  max-height: 200px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}

.cs-closed-hint {
  padding: 12px;
  border-top: 1px solid var(--border);
  text-align: center;
  color: var(--text-muted);
  font-size: 0.84rem;
}

.cs-detail__input {
  border-top: 1px solid var(--border);
  padding: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cs-detail__input textarea {
  width: 100%;
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text);
  padding: 8px 10px;
  font-family: inherit;
  font-size: 0.86rem;
  resize: vertical;
  min-height: 50px;
}

.cs-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}
.cs-actions .cs-send { margin-left: auto; }
.cs-actions .cs-close-btn { font-size: 0.74rem; padding: 6px 10px; }

.cs-icon-btn {
  appearance: none;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 1rem;
}
.cs-icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cs-icon-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.08); }

.cs-emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.02);
  max-height: 180px;
  overflow-y: auto;
}
.cs-emoji {
  appearance: none;
  border: none;
  background: transparent;
  font-size: 1.1rem;
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-sm);
  transition: background 140ms ease;
}
.cs-emoji:hover { background: rgba(255, 255, 255, 0.08); }

@media (max-width: 600px) {
  .cs-widget {
    bottom: 12px;
    left: 12px;
  }
  .cs-fab { width: 48px; height: 48px; }
}
</style>
