<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import client from '../api/client'
import { images } from '../assets/media-catalog'

const { t } = useI18n()
const auth = useAuthStore()

// State
const activeTab = ref<'chats' | 'discover' | 'groups' | 'profile'>('chats')
const friends = ref<any[]>([])
const groups = ref<any[]>([])
const activeContact = ref<any>(null) // Selected friend or group
const searchQuery = ref('')
const searchResults = ref<any[]>([])
const newGroupName = ref('')
const selectedMembers = ref<string[]>([])
const messageText = ref('')
const messages = ref<any[]>([])
const uploadLoading = ref(false)

// Profile customizer fields
const newAvatarUrl = ref(auth.user?.avatarUrl || '')
const newBgUrl = ref((auth.user as any)?.backgroundImageUrl || '')
const profileMessage = ref('')
const profileMessageType = ref<'success' | 'danger'>('success')

// AI 助手融合（设计书 §3.2.3）
const AI_USER_ID = 'ai-echo-envoy'
const icebreakerLoading = ref(false)
const icebreakerSuggestion = ref('')

// WebSocket reference
let ws: WebSocket | null = null
const messageStreamEnd = ref<HTMLElement | null>(null)

// Computed
const currentUserId = computed(() => auth.user?.id || 'unknown')
const activeWallpaper = computed(() => {
  if (activeContact.value && activeContact.value.backgroundImageUrl) {
    return activeContact.value.backgroundImageUrl
  }
  return newBgUrl.value || 'radial-gradient(circle at center, rgba(14, 17, 22, 0.6) 0%, rgba(5, 6, 8, 0.95) 100%)'
})

// Lifecycle
onMounted(() => {
  fetchFriends()
  fetchGroups()
  connectWebSocket()
})

onUnmounted(() => {
  if (ws) {
    ws.close()
  }
})

// WebSocket setup
function connectWebSocket() {
  if (!currentUserId.value || currentUserId.value === 'unknown') return

  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  // Route through the local Vite proxy or Nginx reverse proxy dynamically
  const wsUrl = `${protocol}://${window.location.host}/ws/chat?userId=${currentUserId.value}`

  logMessage('Connecting Chat WebSocket to: ' + wsUrl)
  ws = new WebSocket(wsUrl)

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      if (data.type === 'MSG_RECEIVE') {
        // Append to messages if active conversation matches
        const isCurrentPrivate = activeContact.value && !activeContact.value.isGroup &&
          ((data.senderId === activeContact.value.id && data.receiverId === currentUserId.value) ||
           (data.senderId === currentUserId.value && data.receiverId === activeContact.value.id))
        
        const isCurrentGroup = activeContact.value && activeContact.value.isGroup &&
          data.groupId === activeContact.value.id

        if (isCurrentPrivate || isCurrentGroup) {
          messages.value.push(data)
          scrollToBottom()
        }
      } else if (data.type === 'GROUP_CREATED') {
        fetchGroups()
      } else if (data.type === 'SYSTEM') {
        logMessage('System WS: ' + data.message)
      }
    } catch (e) {
      console.error('Error parsing WS message', e)
    }
  }

  ws.onerror = (err) => {
    console.error('Chat WS error', err)
  }

  ws.onclose = () => {
    logMessage('Chat WS disconnected. Reconnecting in 5s...')
    setTimeout(connectWebSocket, 5000)
  }
}

function logMessage(msg: string) {
  console.log('[ChatService] ' + msg)
}

// APIs
async function fetchFriends() {
  try {
    const { data } = await client.get('/friends')
    friends.value = data.data || []
  } catch (e) {
    console.error('Failed to load friends', e)
  }
}

async function fetchGroups() {
  try {
    const { data } = await client.get('/chat/groups')
    groups.value = (data.data || []).map((g: any) => ({ ...g, isGroup: true }))
  } catch (e) {
    console.error('Failed to load groups', e)
  }
}

async function searchUsers() {
  if (!searchQuery.value.trim()) return
  try {
    const { data } = await client.get(`/friends/search?query=${searchQuery.value.trim()}`)
    searchResults.value = data.data || []
  } catch (e) {
    console.error('Search failed', e)
  }
}

async function addFriend(username: string) {
  try {
    await client.post('/friends/requests', { username })
    alert('Friend request sent to: ' + username)
    searchQuery.value = ''
    searchResults.value = []
  } catch (e: any) {
    alert(e.response?.data?.message || 'Failed to send request')
  }
}

async function selectContact(contact: any) {
  activeContact.value = contact
  messages.value = []
  
  // Fetch history
  try {
    const params: any = {}
    if (contact.isGroup) {
      params.groupId = contact.id
    } else {
      params.receiverId = contact.id
    }
    const { data } = await client.get('/chat/messages', { params })
    messages.value = data.data || []
    scrollToBottom()
  } catch (e) {
    console.error('Failed to load messages history', e)
  }
}

async function sendMessage(type: 'TEXT' | 'IMAGE' | 'FILE' = 'TEXT', contentText = '', fileInfo: any = {}) {
  const finalContent = contentText || messageText.value.trim()
  if (!finalContent || !activeContact.value) return

  if (ws && ws.readyState === WebSocket.OPEN) {
    const payload: any = {
      type: 'SEND_MSG',
      content: finalContent,
      messageType: type
    }
    if (activeContact.value.isGroup) {
      payload.groupId = activeContact.value.id
    } else {
      payload.receiverId = activeContact.value.id
    }

    if (type !== 'TEXT') {
      payload.fileName = fileInfo.fileName
      payload.fileSize = fileInfo.fileSize
    }

    ws.send(JSON.stringify(payload))
    messageText.value = ''
  } else {
    alert('Real-time connection is offline. Reconnecting...')
  }
}

// 私聊"求助星空使者破冰"：调后端 /chat/icebreaker，把 AI 建议填进输入框（不自动发送）
async function requestIcebreaker() {
  if (!activeContact.value || activeContact.value.isGroup || icebreakerLoading.value) return
  icebreakerLoading.value = true
  icebreakerSuggestion.value = ''
  try {
    const { data } = await client.post('/chat/icebreaker', { otherId: activeContact.value.id })
    const suggestion = data?.data?.suggestion || ''
    icebreakerSuggestion.value = suggestion
  } catch (e: any) {
    icebreakerSuggestion.value = t('chat.ai.icebreakerError')
  } finally {
    icebreakerLoading.value = false
  }
}

// 把破冰建议填入输入框，用户可编辑后再发送
function useIcebreakerSuggestion() {
  if (icebreakerSuggestion.value) {
    messageText.value = icebreakerSuggestion.value
    icebreakerSuggestion.value = ''
  }
}

function dismissIcebreaker() {
  icebreakerSuggestion.value = ''
}

// 是否 AI（星空使者）发的消息 —— 用于气泡特殊渲染
function isAiMessage(msg: any): boolean {
  return msg?.senderId === AI_USER_ID || msg?.isAi === true
}

// File Upload
async function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || !activeContact.value) return

  uploadLoading.value = true
  const formData = new FormData()
  formData.append('file', file)

  try {
    // Post to asset uploading service
    const { data } = await client.post('/assets/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    const fileUrl = data.data?.url || data.data
    if (!fileUrl) throw new Error('No url returned')

    const isImage = file.type.startsWith('image/')
    sendMessage(isImage ? 'IMAGE' : 'FILE', fileUrl, {
      fileName: file.name,
      fileSize: file.size
    })
  } catch (e) {
    console.error('File upload failed', e)
    // Fallback: Mock mock asset URL for local offline testing
    const mockUrl = URL.createObjectURL(file)
    const isImage = file.type.startsWith('image/')
    sendMessage(isImage ? 'IMAGE' : 'FILE', mockUrl, {
      fileName: file.name,
      fileSize: file.size
    })
  } finally {
    uploadLoading.value = false
    target.value = ''
  }
}

// Group creation
async function handleCreateGroup() {
  if (!newGroupName.value.trim()) return
  try {
    const { data } = await client.post('/chat/groups', {
      name: newGroupName.value.trim(),
      memberIds: selectedMembers.value
    })
    newGroupName.value = ''
    selectedMembers.value = []
    fetchGroups()
    selectContact({ ...data.data, isGroup: true })
  } catch (e) {
    console.error('Failed to create group', e)
  }
}

// Profile customizer submit
async function handleProfileUpdate() {
  profileMessage.value = ''
  try {
    const { data } = await client.put('/auth/profile', {
      avatarUrl: newAvatarUrl.value,
      backgroundImageUrl: newBgUrl.value
    })
    
    // Sync local store
    auth.user = {
      ...auth.user,
      avatarUrl: data.data.avatarUrl
    } as any
    ;(auth.user as any).backgroundImageUrl = data.data.backgroundImageUrl
    
    profileMessageType.value = 'success'
    profileMessage.value = '✓ Profile settings saved successfully'
  } catch (e: any) {
    profileMessageType.value = 'danger'
    profileMessage.value = e.response?.data?.message || 'Failed to update profile settings'
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messageStreamEnd.value) {
      messageStreamEnd.value.scrollIntoView({ behavior: 'smooth' })
    }
  })
}

function formatMsgTime(dateStr: string) {
  const d = new Date(dateStr)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatBytes(bytes: number) {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <div class="chat-outer-shell">
      
      <!-- LEFT SIDEBAR -->
      <aside class="chat-sidebar">
        <header class="chat-sidebar__header">
          <div class="user-chip" style="padding:0; border:none; background:none;">
            <div class="user-chip__avatar" style="width:38px; height:38px;">
              {{ auth.user?.username?.charAt(0).toUpperCase() }}
            </div>
            <div class="user-chip__meta">
              <strong>{{ auth.user?.username }}</strong>
              <small>{{ auth.user?.email }}</small>
            </div>
          </div>
        </header>

        <!-- Navigation Tabs -->
        <div class="chat-tabs">
          <button 
            type="button" 
            :class="['chat-tab-btn', activeTab === 'chats' ? 'active' : '']"
            @click="activeTab = 'chats'"
          >
            {{ t('chat.tabs.chats') }}
          </button>
          <button 
            type="button" 
            :class="['chat-tab-btn', activeTab === 'discover' ? 'active' : '']"
            @click="activeTab = 'discover'"
          >
            {{ t('chat.tabs.discover') }}
          </button>
          <button 
            type="button" 
            :class="['chat-tab-btn', activeTab === 'groups' ? 'active' : '']"
            @click="activeTab = 'groups'"
          >
            {{ t('chat.tabs.groups') }}
          </button>
          <button 
            type="button" 
            :class="['chat-tab-btn', activeTab === 'profile' ? 'active' : '']"
            @click="activeTab = 'profile'"
          >
            {{ t('chat.tabs.theme') }}
          </button>
        </div>
 
        <!-- Sidebar Content Sections -->
        <div class="chat-sidebar__body">
          
          <!-- TAB: CHATS / FRIENDS LIST -->
          <div v-if="activeTab === 'chats'" class="chat-sidebar__list">
            <h3 class="list-section-title">{{ t('chat.sidebar.friendsTitle') }}</h3>
            <div v-if="friends.length === 0" class="sidebar-empty">
              {{ t('chat.sidebar.noFriends') }}
            </div>
            <button
              v-for="friend in friends"
              :key="friend.id"
              type="button"
              :class="['contact-item', activeContact && activeContact.id === friend.id ? 'active' : '']"
              @click="selectContact(friend)"
            >
              <div class="contact-item__avatar">
                <img v-if="friend.avatarUrl" :src="friend.avatarUrl" alt="" />
                <span v-else>{{ friend.username.charAt(0).toUpperCase() }}</span>
              </div>
              <div class="contact-item__meta">
                <strong>{{ friend.username }}</strong>
                <small>{{ friend.email }}</small>
              </div>
            </button>
          </div>
 
          <!-- TAB: DISCOVER / USER SEARCH -->
          <div v-else-if="activeTab === 'discover'" class="chat-sidebar__search stack">
            <div class="search-input" style="width: 100%;">
              <input 
                v-model="searchQuery" 
                class="input" 
                :placeholder="t('chat.sidebar.searchPlaceholder')" 
                @keyup.enter="searchUsers" 
              />
              <button class="button button--primary search-go" type="button" @click="searchUsers">{{ t('chat.sidebar.searchBtn') }}</button>
            </div>
 
            <div class="search-results stack">
              <h3 class="list-section-title">{{ t('chat.sidebar.matchesTitle') }}</h3>
              <div v-if="searchResults.length === 0" class="sidebar-empty">
                {{ t('chat.sidebar.noMatches') }}
              </div>
              <div
                v-for="user in searchResults"
                :key="user.id"
                class="search-user-card"
              >
                <div class="contact-item__avatar">
                  <img v-if="user.avatarUrl" :src="user.avatarUrl" alt="" />
                  <span v-else>{{ user.username.charAt(0).toUpperCase() }}</span>
                </div>
                <div class="contact-item__meta" style="flex:1;">
                  <strong>{{ user.username }}</strong>
                </div>
                <button 
                  type="button" 
                  class="button button--secondary button--xs"
                  @click="addFriend(user.username)"
                >
                  {{ t('chat.sidebar.addFriendBtn') }}
                </button>
              </div>
            </div>
          </div>
 
          <!-- TAB: GROUPS -->
          <div v-else-if="activeTab === 'groups'" class="chat-sidebar__groups stack">
            <div class="group-creator-box section-card stack" style="padding: 16px; border-radius: var(--radius-md);">
              <h4 class="list-section-title" style="margin-top:0;">{{ t('chat.sidebar.createGroupTitle') }}</h4>
              <input v-model="newGroupName" class="input" :placeholder="t('chat.sidebar.groupNamePlaceholder')" />
              
              <!-- Select Members Checklist -->
              <div class="members-checklist stack">
                <span class="field__label">{{ t('chat.sidebar.selectFriendsLabel') }}</span>
                <label v-for="friend in friends" :key="friend.id" class="checklist-item">
                  <input type="checkbox" :value="friend.id" v-model="selectedMembers" />
                  <span>{{ friend.username }}</span>
                </label>
              </div>
              <button 
                type="button" 
                class="button button--primary" 
                style="min-height:36px; width:100%;"
                @click="handleCreateGroup"
              >
                {{ t('chat.sidebar.createGroupBtn') }}
              </button>
            </div>
 
            <div class="chat-sidebar__list">
              <h3 class="list-section-title">{{ t('chat.sidebar.myGroupsTitle') }}</h3>
              <div v-if="groups.length === 0" class="sidebar-empty">
                {{ t('chat.sidebar.noGroups') }}
              </div>
              <button
                v-for="group in groups"
                :key="group.id"
                type="button"
                :class="['contact-item', activeContact && activeContact.id === group.id ? 'active' : '']"
                @click="selectContact(group)"
              >
                <div class="contact-item__avatar group-avatar">
                  <span v-if="!group.avatarUrl">👥</span>
                  <img v-else :src="group.avatarUrl" alt="" />
                </div>
                <div class="contact-item__meta">
                  <strong>{{ group.name }}</strong>
                  <small>{{ group.ownerId === currentUserId ? t('chat.sidebar.groupOwner') : t('chat.sidebar.groupMember') }}</small>
                </div>
              </button>
            </div>
          </div>
 
          <!-- TAB: PROFILE / THEME CUSTOMIZER -->
          <div v-else-if="activeTab === 'profile'" class="chat-sidebar__profile stack">
            <div class="section-card stack" style="padding: 18px; border-radius: var(--radius-md);">
              <h4 class="list-section-title" style="margin-top:0;">{{ t('chat.sidebar.themeTitle') }}</h4>
              <p class="subtitle" style="font-size:0.78rem; line-height:1.4; margin-bottom:12px;">
                {{ t('chat.sidebar.themeSubtitle') }}
              </p>
 
              <transition name="alert">
                <div v-if="profileMessage" :class="['status-pill', `status-pill--${profileMessageType}`]" style="padding: 6px 12px; margin-bottom:10px;">
                  {{ profileMessage }}
                </div>
              </transition>
 
              <span class="field__label" style="font-size:0.75rem; margin-bottom:6px;">{{ t('chat.sidebar.presetsLabel') }}</span>
              <div class="chat-preset-gallery">
                <button
                  v-for="(imgAsset, key) in images"
                  :key="key"
                  type="button"
                  :class="['chat-preset-item', newBgUrl === imgAsset.src ? 'active' : '']"
                  @click="newBgUrl = imgAsset.src"
                  :title="imgAsset.origin"
                >
                  <img :src="imgAsset.thumb || imgAsset.src" :alt="imgAsset.origin" />
                  <span class="chat-preset-item__label">{{ imgAsset.origin }}</span>
                </button>
              </div>
 
              <label class="field" style="margin-top: 10px;">
                <span class="field__label">{{ t('chat.sidebar.avatarUrlLabel') }}</span>
                <input v-model="newAvatarUrl" class="input" :placeholder="t('chat.sidebar.avatarUrlPlaceholder')" />
              </label>
 
              <label class="field">
                <span class="field__label">{{ t('chat.sidebar.bgUrlLabel') }}</span>
                <input v-model="newBgUrl" class="input" :placeholder="t('chat.sidebar.bgUrlPlaceholder')" />
              </label>
 
              <button 
                type="button" 
                class="button button--primary" 
                style="width: 100%; min-height: 40px; margin-top:8px;"
                @click="handleProfileUpdate"
              >
                {{ t('chat.sidebar.saveThemeBtn') }}
              </button>
            </div>
          </div>
 
        </div>
      </aside>
 
      <!-- RIGHT CHAT AREA -->
      <section class="chat-body" :style="{ background: activeWallpaper.startsWith('http') ? `linear-gradient(180deg, rgba(8,10,14,0.65) 0%, rgba(8,10,14,0.9) 100%), url(${activeWallpaper}) center/cover no-repeat` : activeWallpaper }">
        
        <template v-if="activeContact">
          <!-- Chat Header -->
          <header class="chat-body__header">
            <div class="chat-header-info">
              <span class="chat-header-avatar">
                <span v-if="activeContact.isGroup">👥</span>
                <span v-else>{{ activeContact.username.charAt(0).toUpperCase() }}</span>
              </span>
              <div>
                <h2 class="chat-header-title">{{ activeContact.isGroup ? activeContact.name : activeContact.username }}</h2>
                <span class="status-pill status-pill--success" style="padding: 1px 8px; font-size:0.68rem; margin-top:4px;">
                  {{ t('chat.body.connectedStatus') }}
                </span>
              </div>
            </div>
            <!-- 私聊：求助星空使者破冰 / 群聊：@AI 提示 -->
            <button
              v-if="!activeContact.isGroup"
              type="button"
              class="icebreaker-btn"
              :disabled="icebreakerLoading"
              :title="t('chat.ai.icebreakerHint')"
              @click="requestIcebreaker"
            >
              <span v-if="icebreakerLoading" class="auth-spinner"></span>
              <span v-else>✨ {{ t('chat.ai.icebreakerBtn') }}</span>
            </button>
            <span v-else class="ai-mention-tip" :title="t('chat.ai.mentionHint')">
              @AI {{ t('chat.ai.mentionTip') }}
            </span>
          </header>

          <!-- 破冰建议卡片 -->
          <transition name="alert">
            <div v-if="icebreakerSuggestion" class="icebreaker-card">
              <div class="icebreaker-card__head">
                <span class="icebreaker-card__title">✨ {{ t('chat.ai.suggestionTitle') }}</span>
                <button type="button" class="icebreaker-card__close" @click="dismissIcebreaker">×</button>
              </div>
              <p class="icebreaker-card__body">{{ icebreakerSuggestion }}</p>
              <div class="icebreaker-card__actions">
                <button type="button" class="button button--primary" style="padding:4px 12px;font-size:0.78rem;" @click="useIcebreakerSuggestion">
                  {{ t('chat.ai.useSuggestion') }}
                </button>
              </div>
            </div>
          </transition>
 
          <!-- Messages Stream -->
          <div class="chat-messages-stream">
            <div 
              v-for="msg in messages" 
              :key="msg.id"
              :class="['message-bubble-wrapper', msg.senderId === currentUserId ? 'mine' : '', isAiMessage(msg) ? 'ai' : '']"
            >
              <div class="message-avatar" :class="{ 'message-avatar--ai': isAiMessage(msg) }">
                <template v-if="isAiMessage(msg)">✦</template>
                <template v-else>{{ msg.senderId === currentUserId ? auth.user?.username?.charAt(0).toUpperCase() : (activeContact.isGroup ? 'M' : activeContact.username.charAt(0).toUpperCase()) }}</template>
              </div>
              <div class="message-bubble-container">
                <span v-if="isAiMessage(msg)" class="message-ai-name">{{ t('chat.ai.name') }}</span>
                <div class="message-bubble" :class="{ 'message-bubble--ai': isAiMessage(msg) }">
                  <!-- Text -->
                  <span v-if="msg.messageType === 'TEXT'">{{ msg.content }}</span>
                  
                  <!-- Image -->
                  <div v-else-if="msg.messageType === 'IMAGE'" class="message-bubble__image">
                    <img :src="msg.content" alt="shared photo" loading="lazy" />
                  </div>
                  
                  <!-- File -->
                  <div v-else-if="msg.messageType === 'FILE'" class="message-bubble__file">
                    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" class="file-icon">
                      <path d="M15.172 7l-6.586 6.586a2 2 0 1 0 2.828 2.828l6.414-6.414a4 4 0 0 0-5.656-5.656l-6.415 6.414a6 6 0 1 0 8.486 8.486L20.5 13" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <div class="file-info">
                      <a :href="msg.content" target="_blank" class="file-link">{{ msg.fileName || 'Shared Document' }}</a>
                      <span class="file-size">{{ formatBytes(msg.fileSize || 0) }}</span>
                    </div>
                  </div>
                </div>
                <span class="message-time">{{ formatMsgTime(msg.createdAt) }}</span>
              </div>
            </div>
            <div ref="messageStreamEnd"></div>
          </div>
 
          <!-- Chat Input -->
          <footer class="chat-input-bar">
            <div class="chat-input-bar__inner">
              
              <!-- File Attachment Button -->
              <label class="attach-btn" title="Send file or photo">
                <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
                  <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
                </svg>
                <input type="file" style="display:none;" @change="handleFileUpload" :disabled="uploadLoading" />
              </label>
 
              <!-- Text Input -->
              <input 
                v-model="messageText" 
                class="input chat-input-field" 
                :placeholder="t('chat.body.inputTextPlaceholder')" 
                @keyup.enter="sendMessage('TEXT')"
                :disabled="uploadLoading"
              />
 
              <!-- Send Button -->
              <button 
                type="button" 
                class="button button--primary send-btn"
                @click="sendMessage('TEXT')"
                :disabled="!messageText.trim() || uploadLoading"
              >
                <span v-if="uploadLoading" class="auth-spinner"></span>
                <span v-else>{{ t('chat.body.sendBtn') }}</span>
              </button>
 
            </div>
          </footer>
        </template>
 
        <!-- No Conversation Selected Overlay -->
        <div v-else class="chat-no-selection">
          <div class="no-selection-content stack">
            <span class="empty-bubble-icon">💬</span>
            <h2>{{ t('chat.body.noSelectionTitle') }}</h2>
            <p class="subtitle" style="max-width: 42ch; margin: 0 auto;">
              {{ t('chat.body.noSelectionSubtitle') }}
            </p>
          </div>
        </div>

      </section>

    </div>
  </div>
</template>

<style scoped>
.chat-outer-shell {
  display: flex;
  height: calc(100vh - 190px);
  border-radius: var(--radius-lg);
  background: var(--surface);
  border: 1px solid var(--border);
  overflow: hidden;
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
  box-shadow: var(--shadow-lg);
}

/* SIDEBAR */
.chat-sidebar {
  width: 330px;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  background: rgba(10, 13, 18, 0.45);
}

.chat-sidebar__header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
}

.chat-tabs {
  display: flex;
  border-bottom: 1px solid var(--border);
  background: rgba(5, 6, 8, 0.2);
}

.chat-tab-btn {
  flex: 1;
  padding: 12px 6px;
  font-size: 0.8rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-muted);
  border-bottom: 2px solid transparent;
  text-align: center;
  transition: all 0.2s ease;
}

.chat-tab-btn:hover {
  color: var(--text);
  background: rgba(255,255,255,0.02);
}

.chat-tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  background: rgba(54, 216, 180, 0.04);
}

.chat-sidebar__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.list-section-title {
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-faint);
  margin: 12px 0 8px 4px;
}

.sidebar-empty {
  font-size: 0.8rem;
  color: var(--text-faint);
  padding: 24px 8px;
  text-align: center;
  line-height: 1.5;
}

.contact-item, .search-user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  width: 100%;
  text-align: left;
  transition: all 0.2s ease;
  background: transparent;
  margin-bottom: 4px;
}

.contact-item:hover, .search-user-card:hover {
  background: rgba(255, 255, 255, 0.04);
}

.contact-item.active {
  background: rgba(54, 216, 180, 0.08);
  border: 1px solid var(--border-accent);
}

.contact-item__avatar, .message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  display: grid;
  place-items: center;
  color: #052017;
  font-weight: 700;
  font-size: 0.85rem;
  overflow: hidden;
  flex-shrink: 0;
}

.group-avatar {
  background: linear-gradient(135deg, var(--gold), #ef6f7a);
}

.contact-item__avatar img, .message-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.contact-item__meta {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.contact-item__meta strong {
  font-size: 0.9rem;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contact-item__meta small {
  font-size: 0.74rem;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-go {
  min-height: 36px;
  padding: 0 14px;
  border-radius: var(--radius-sm);
}

.checklist-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.86rem;
  color: var(--text-soft);
  cursor: pointer;
  padding: 4px;
}

.checklist-item input {
  cursor: pointer;
}

/* CHAT BODY */
.chat-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  transition: background 0.3s ease;
}

.chat-body__header {
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
  background: rgba(10, 13, 18, 0.55);
  backdrop-filter: blur(10px);
  z-index: 2;
}

.chat-header-info {
  display: flex;
  align-items: center;
  gap: 14px;
}

.chat-header-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  display: grid;
  place-items: center;
  font-size: 1rem;
  color: #052017;
  font-weight: 800;
}

.chat-header-title {
  font-size: 1.05rem;
  font-family: var(--font-display);
  margin: 0;
}

.chat-messages-stream {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  background: rgba(5,6,8,0.15);
}

.message-bubble-wrapper {
  display: flex;
  gap: 12px;
  max-width: 75%;
}

.message-bubble-wrapper.mine {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-bubble-container {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-bubble-wrapper.mine .message-bubble-container {
  align-items: flex-end;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.85);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: 0.94rem;
  line-height: 1.5;
  word-break: break-word;
  box-shadow: var(--shadow-sm);
}

.message-bubble-wrapper.mine .message-bubble {
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.15), rgba(182, 240, 119, 0.15));
  border-color: var(--border-accent);
  color: var(--text);
}

.message-time {
  font-size: 0.72rem;
  color: var(--text-faint);
  font-family: var(--font-mono);
}

.message-bubble__image img {
  max-width: 280px;
  max-height: 200px;
  border-radius: var(--radius-xs);
  object-fit: cover;
  display: block;
}

.message-bubble__file {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 200px;
}

.file-icon {
  color: var(--primary);
  flex-shrink: 0;
}

.file-info {
  display: grid;
  gap: 2px;
}

.file-link {
  font-size: 0.88rem;
  color: var(--text);
  font-weight: 500;
  text-decoration: underline;
}

.file-size {
  font-size: 0.7rem;
  color: var(--text-muted);
}

.chat-input-bar {
  padding: 16px 24px;
  background: rgba(10, 13, 18, 0.7);
  backdrop-filter: blur(10px);
  border-top: 1px solid var(--border);
}

.chat-input-bar__inner {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: var(--page-width-wide);
  margin: 0 auto;
}

.attach-btn {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  display: grid;
  place-items: center;
  color: var(--text-muted);
  cursor: pointer;
  background: rgba(14, 17, 22, 0.5);
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.attach-btn:hover {
  color: var(--primary);
  border-color: var(--border-accent);
  background: rgba(54, 216, 180, 0.04);
}

.chat-input-field {
  flex: 1;
  min-height: 44px;
}

.send-btn {
  min-height: 44px;
  padding: 0 20px;
  flex-shrink: 0;
}

/* NO SELECTION */
.chat-no-selection {
  flex: 1;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 32px;
}

.no-selection-content h2 {
  font-family: var(--font-display);
  font-size: 1.4rem;
  margin: 0 0 10px;
}

.empty-bubble-icon {
  font-size: 3.5rem;
  margin-bottom: 12px;
}

/* ============== 聊天预设壁纸库 ============== */
.chat-preset-gallery {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  max-height: 360px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: rgba(5, 6, 8, 0.4);
}

.chat-preset-gallery::-webkit-scrollbar {
  width: 4px;
}
.chat-preset-gallery::-webkit-scrollbar-track {
  background: rgba(255,255,255,0.01);
}
.chat-preset-gallery::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.08);
  border-radius: 99px;
}

.chat-preset-item {
  position: relative;
  background: rgba(14, 17, 22, 0.5);
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  padding: 0;
  transition: all 0.2s ease;
  text-align: center;
}

.chat-preset-item:hover {
  border-color: var(--border-accent);
  transform: translateY(-1px);
}

.chat-preset-item.active {
  border-color: var(--primary);
  box-shadow: 0 0 8px rgba(54, 216, 180, 0.25), inset 0 0 0 1px var(--primary);
}

.chat-preset-item img {
  width: 100%;
  height: 124px;
  object-fit: cover;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  transition: transform 0.4s ease;
}

.chat-preset-item:hover img {
  transform: scale(1.04);
}

.chat-preset-item__label {
  font-size: 0.78rem;
  color: var(--text-muted);
  padding: 7px 6px 9px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: 0.01em;
  font-weight: 500;
}

.chat-preset-item.active .chat-preset-item__label {
  color: var(--primary);
  font-weight: 600;
}

/* ---------------- AI 助手融合（@AI / 破冰）---------------- */
.chat-body__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.icebreaker-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid rgba(125, 211, 252, 0.4);
  background: linear-gradient(120deg, rgba(56, 189, 248, 0.18), rgba(232, 199, 122, 0.16));
  color: var(--text);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease;
  white-space: nowrap;
}
.icebreaker-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(56, 189, 248, 0.25);
}
.icebreaker-btn:disabled { opacity: 0.6; cursor: progress; }

.ai-mention-tip {
  font-size: 0.72rem;
  color: var(--text-muted);
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px dashed rgba(125, 211, 252, 0.35);
  white-space: nowrap;
}

.icebreaker-card {
  margin: 10px 16px 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.12), rgba(232, 199, 122, 0.1));
  border: 1px solid rgba(125, 211, 252, 0.3);
}
.icebreaker-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.icebreaker-card__title { font-size: 0.82rem; font-weight: 700; color: var(--primary); }
.icebreaker-card__close {
  background: none; border: none; color: var(--text-muted);
  font-size: 1.2rem; line-height: 1; cursor: pointer;
}
.icebreaker-card__body {
  font-size: 0.86rem; color: var(--text); line-height: 1.5; margin: 0 0 8px;
  white-space: pre-wrap;
}
.icebreaker-card__actions { display: flex; justify-content: flex-end; }

/* AI（星空使者）消息气泡 */
.message-avatar--ai {
  background: linear-gradient(135deg, #38bdf8, #e8c77a) !important;
  color: #06121f !important;
  box-shadow: 0 0 14px rgba(56, 189, 248, 0.45);
}
.message-ai-name {
  display: block;
  font-size: 0.68rem;
  font-weight: 700;
  color: #7dd3fc;
  margin-bottom: 2px;
  letter-spacing: 0.03em;
}
.message-bubble--ai {
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.16), rgba(232, 199, 122, 0.12)) !important;
  border: 1px solid rgba(125, 211, 252, 0.3) !important;
}
.message-bubble-wrapper.ai .message-bubble-container { max-width: 78%; }
</style>
