<script setup lang="ts">
/**
 * 星空使者 / Memory Echo AI Mascot Dock.
 *
 * 设计书 3.1 节要求的常驻 AI 入口 — 右下角呼吸的 3D 粒子球，
 * 点击向左展开为毛玻璃聊天面板，承载意图识别 + Plan-and-Execute UI。
 *
 *  - 客户端意图识别：用极简启发式判断 chat / plan 两类。
 *  - Plan 渲染：JSON-shape 的 PlanStep 数组，○ → ✓ 演进。
 *  - ReAct 工具调用：tool 节点带"齿轮旋转"指示，点击展开入参出参。
 *  - 太极双鱼边框：CSS conic-gradient + 旋转动画，仅在 Loading/Streaming 时显示。
 *  - 跑马灯等待：waiting 状态下面板顶部一道渐变光带。
 *  - 后端串流：尝试 /api/ai/stream（SSE）；若 404/网络失败，回退到本地模拟流。
 *
 * 该组件与多模态结果呈现的 Minio 卡片预留接口（assistant 消息上的 attachments）。
 */
import { computed, nextTick, onMounted, onUnmounted, onUpdated, reactive, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
// NOTE: 仅保留 `images` 作为「装饰性 / 预热」的静态资源（设计书 3.8：
// media-catalog 只能在非业务场景使用）。聊天附件不再来自 media-catalog，
// 全部走 SSE `tool_end` 里 `minioMediaFetchTool` 返回的 presigned URL
// （URL 含 `X-Amz-Signature` 查询参数）—— 详见下方 streamFromBackend()。
import { images } from '../../assets/media-catalog'
import client from '../../api/client'
import { useMemoryStore } from '../../stores/memory'
import { useAuthStore } from '../../stores/auth'
import { useDynamicMedia } from '../../composables/useDynamicMedia'
import { renderMarkdown, ensureMermaidRendered } from '../../composables/useMarkdown'

/** 从 SSE `tool_end` 的 `minioMediaFetchTool` 输出里把 media[] 转成
 *  聊天卡片附件。MinIO 的 presigned URL 含 X-Amz-Signature，
 *  本地降级条目 `source==='local'` 也会被一起携带。 */
function attachmentsFromMinioTool(output: any): Attachment[] {
  if (!output || !Array.isArray(output.media)) return []
  const out: Attachment[] = []
  for (const m of output.media) {
    const src: string = typeof m.url === 'string' ? m.url : ''
    if (!src) continue
    const mime: string = typeof m.mime === 'string' ? m.mime : ''
    const kindHint: string = typeof m.kind === 'string' ? m.kind.toLowerCase() : ''
    let kind: Attachment['kind']
    if (mime.startsWith('video/') || kindHint === 'video') kind = 'video'
    else if (mime.startsWith('audio/') || kindHint === 'audio') kind = 'audio'
    else kind = 'image'
    out.push({ kind, src, caption: m.name || undefined })
  }
  return out
}

type ToolCallStatus = 'running' | 'done' | 'error'

interface ToolCall {
  id: string
  name: string
  label: string
  status: ToolCallStatus
  input?: Record<string, unknown>
  output?: Record<string, unknown>
  expanded?: boolean
}

interface Attachment {
  kind: 'image' | 'audio' | 'video'
  src: string
  caption?: string
}

interface PlanStep {
  id: string
  label: string
  status: 'pending' | 'running' | 'done' | 'error'
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  text: string
  streaming?: boolean
  toolCalls?: ToolCall[]
  plan?: PlanStep[]
  attachments?: Attachment[]
  /** AI 上游（NVIDIA / MiniMax）拒绝调用时的标记 — UI 据此渲染明显的错误卡片，
   *  不会再用模板拼接冒充正常回答（Bugfix 2.5）。 */
  upstreamError?: boolean
  errorCode?: string
  /** 多模态混合检索：本条 assistant 消息使用了视觉前置（Qwen3.5-VL / Kimi-K2.5）
   *  → 在消息泡里渲染一个"🔍 已分析 N 张图片 · model"小标签。 */
  visionUsed?: boolean
  visionModel?: string
  attachmentCount?: number
  /** P3-13 动态 plan：'heuristic' 表示硬编码 4 步；'llm' 表示后端基于本次问题动态生成。 */
  planSource?: 'heuristic' | 'llm'
  createdAt: number
}

interface ChatConversation {
  id: string
  title: string
  messages: ChatMessage[]
  updatedAt: number
}

const CONV_STORAGE_KEY = 'ai_conversations_v1'
// 仅记录"当前活跃会话 id"，不再用于挂载时恢复；启动后默认开新对话。
const CONV_ACTIVE_KEY = 'ai_conversation_active_v1'

const { t, locale } = useI18n()
const memoryStore = useMemoryStore()
const authStore = useAuthStore()
const dynamicMedia = useDynamicMedia()

const open = ref(false)
const minimized = ref(false)
const inputText = ref('')
const conversations = ref<ChatConversation[]>([])
const activeConvId = ref<string>('')
const historyOpen = ref(false)
const streaming = ref(false)
const waiting = ref(false)
const panelEl = ref<HTMLElement | null>(null)
const transcriptEl = ref<HTMLElement | null>(null)

const greetingShown = ref(false)

/* ============ 多模态附件（v2 — hybrid retrieval）============
 * 用户点📎 → 选/拖图 → asset-service /assets/upload → 拿到 presigned URL
 * → 推到 pendingAttachments。send() 时把 URLs 注入 SSE body 的 images 字段。
 * 后端检测到 images 非空 → 先调 Qwen3.5-VL 拿"图片→中文密集描述"，再喂基座 M2.7。 */
interface PendingAttachment {
  id: string
  url: string
  thumb: string
  name: string
  uploading: boolean
  /** asset-service 拒绝 / 网络失败时记录原因，让用户能感知 */
  error?: string
}
const pendingAttachments = ref<PendingAttachment[]>([])
const attachmentUploadBusy = ref(false)
const attachInputEl = ref<HTMLInputElement | null>(null)
const ATTACH_MAX_MB = 8
const ATTACH_ACCEPT = 'image/jpeg,image/png,image/webp,image/gif'
const ATTACH_ALLOWED = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
/** AI 球目前只支持图片附件；后续接 Whisper / Gemini Audio 时再开 audio/video */
const ATTACH_MAX_COUNT = 4

function pickAttachment() {
  if (streaming.value) return
  if (pendingAttachments.value.length >= ATTACH_MAX_COUNT) return
  attachInputEl.value?.click()
}

async function onAttachmentChange(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files || [])
  target.value = ''
  for (const f of files) {
    if (pendingAttachments.value.length >= ATTACH_MAX_COUNT) break
    await uploadAttachment(f)
  }
}

async function uploadAttachment(file: File) {
  if (!ATTACH_ALLOWED.includes(file.type)) {
    pendingAttachments.value.push({
      id: shortId(),
      url: '',
      thumb: '',
      name: file.name,
      uploading: false,
      error: locale.value === 'zh-CN' ? '仅支持 JPEG/PNG/WebP/GIF' : 'JPEG/PNG/WebP/GIF only',
    })
    return
  }
  if (file.size > ATTACH_MAX_MB * 1024 * 1024) {
    pendingAttachments.value.push({
      id: shortId(),
      url: '',
      thumb: '',
      name: file.name,
      uploading: false,
      error: locale.value === 'zh-CN' ? `文件超过 ${ATTACH_MAX_MB} MB` : `> ${ATTACH_MAX_MB} MB`,
    })
    return
  }

  // 临时本地预览（blob URL）→ 上传成功后替换为 presigned URL
  const localPreview = URL.createObjectURL(file)
  const item: PendingAttachment = reactive<PendingAttachment>({
    id: shortId(),
    url: '',
    thumb: localPreview,
    name: file.name,
    uploading: true,
  })
  pendingAttachments.value.push(item)
  attachmentUploadBusy.value = true

  try {
    const fd = new FormData()
    fd.append('file', file)
    const { data } = await client.post('/assets/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    const url: string | undefined = data?.data?.url
    if (!url) throw new Error('No url returned')
    item.url = url
    item.uploading = false
  } catch (e: any) {
    item.uploading = false
    item.error = locale.value === 'zh-CN' ? '上传失败' : 'Upload failed'
  } finally {
    // 至少有一个仍在上传则保持 busy
    attachmentUploadBusy.value = pendingAttachments.value.some((a) => a.uploading)
  }
}

function removeAttachment(id: string) {
  const idx = pendingAttachments.value.findIndex((a) => a.id === id)
  if (idx === -1) return
  // ⚠️ 不 revokeObjectURL：blob URL 可能被已发送的用户消息泡复用作缩略图。
  //    内存代价上限是 ATTACH_MAX_COUNT × ATTACH_MAX_MB ≈ 32MB，浏览器关页时自动回收。
  pendingAttachments.value.splice(idx, 1)
}

function clearAttachments() {
  // 同上：不 revoke，让历史消息泡能继续展示缩略图。
  pendingAttachments.value = []
}

const activeConv = computed<ChatConversation | null>(() =>
  conversations.value.find((c) => c.id === activeConvId.value) || null
)
const messages = computed<ChatMessage[]>(() => activeConv.value?.messages || [])

const ambientBgUrl = computed(() => `url("${images.emotionPrism.src}")`)
// 装饰性 ambient video — 优先用 asset-service 动态发现的视频，没有则空字符串，CSS 自然降级
const ambientVideo = computed(() => {
  const v = dynamicMedia.pickVideo('ai-mascot-ambient')
  return v?.url || ''
})

const fallbackHints = computed(() =>
  locale.value === 'zh-CN'
    ? ['寻找2023年大理的记忆', '帮我整理本月的情绪轨迹', '哪个朋友与我共鸣最强', '基于回忆推荐一段冥想']
    : ['Find my Dali memories from 2023', 'Summarize my mood this month', 'Who resonates with me most?', 'Suggest a meditation from my memories'],
)

// 动态推荐问题：登录后由 ai-service 结合用户最近记忆生成；失败回退到 fallbackHints。
const dynamicHints = ref<string[]>([])
const intentHints = computed(() =>
  dynamicHints.value.length > 0 ? dynamicHints.value : fallbackHints.value,
)

let hintsFetched = false
async function fetchDynamicHints() {
  if (hintsFetched) return
  hintsFetched = true
  try {
    const token = authStore.token
    if (!token) return
    if ((memoryStore.memories || []).length === 0) {
      try { await memoryStore.fetchList(0, 50) } catch { /* silent */ }
    }
    const context = buildMemoryDigest().map((m) => ({
      title: m.title, location: m.location, year: m.year,
    }))
    if (context.length === 0) return
    const { data } = await client.post('/reconstruct/chat/hints', {
      locale: locale.value,
      context,
    })
    const hints = data?.data?.hints
    if (Array.isArray(hints) && hints.length > 0) {
      dynamicHints.value = hints.filter((h: any) => typeof h === 'string' && h.trim()).slice(0, 4)
    }
  } catch {
    // 静默：保留 fallbackHints
  }
}

const placeholder = computed(() =>
  locale.value === 'zh-CN' ? '与星空使者对话…按 Enter 发送' : 'Talk to the Echo Envoy… press Enter',
)

function shortId(): string {
  return Math.random().toString(36).slice(2, 9)
}

/* ============ 多会话持久化 ============ */
function loadConversations() {
  try {
    const raw = localStorage.getItem(CONV_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as ChatConversation[]
      if (Array.isArray(parsed)) conversations.value = parsed
    }
  } catch { /* ignore */ }

  // 启动 / 登录后默认开启一段新对话（旧对话保留在历史里可切回）。
  // 例外：如果上一条会话本身就是空白新对话（没有任何用户消息），直接复用，
  //       避免每次刷新都堆出一堆空白 "New chat" 把历史撑爆。
  const lastConv = conversations.value[0]
  const lastIsEmpty = !!lastConv && !lastConv.messages.some((m) => m.role === 'user')
  if (lastIsEmpty) {
    activeConvId.value = lastConv.id
    greetingShown.value = lastConv.messages.length > 0
    persistConversations()
  } else {
    // startNewChat 会 unshift 进 conversations 顶端并设置 activeConvId
    startNewChat(true)
  }
}

function persistConversations() {
  try {
    // 只持久化非 streaming 的消息（避免半成品状态写盘）
    const snapshot = conversations.value.map((c) => ({
      ...c,
      messages: c.messages.map((m) => ({ ...m, streaming: false })),
    }))
    localStorage.setItem(CONV_STORAGE_KEY, JSON.stringify(snapshot))
    if (activeConvId.value) localStorage.setItem(CONV_ACTIVE_KEY, activeConvId.value)
  } catch { /* ignore quota */ }
}

function startNewChat(addGreeting = true) {
  const zh = locale.value === 'zh-CN'
  const id = shortId()
  const conv: ChatConversation = {
    id,
    title: zh ? '新的对话' : 'New chat',
    messages: [],
    updatedAt: Date.now(),
  }
  if (addGreeting) {
    conv.messages.push({
      id: shortId(),
      role: 'assistant',
      text: zh
        ? '你好，我是星空使者。可以为你检索记忆、做情绪复盘，或在他人记忆中寻找共鸣节点。'
        : "Hi, I'm your Echo Envoy. I can retrace memories, recap moods, and seek resonance across others.",
      createdAt: Date.now(),
    })
  }
  conversations.value.unshift(conv)
  activeConvId.value = id
  greetingShown.value = addGreeting
  historyOpen.value = false
  persistConversations()
}

function switchConv(id: string) {
  if (streaming.value) return
  activeConvId.value = id
  historyOpen.value = false
  greetingShown.value = true
  persistConversations()
  void scrollToBottom()
}

function deleteConv(id: string) {
  const idx = conversations.value.findIndex((c) => c.id === id)
  if (idx === -1) return
  conversations.value.splice(idx, 1)
  if (activeConvId.value === id) {
    if (conversations.value.length > 0) {
      activeConvId.value = conversations.value[0].id
    } else {
      startNewChat(false)
      return
    }
  }
  persistConversations()
}

function convTitle(c: ChatConversation, max = 28): string {
  if (c.title && c.title !== '新的对话' && c.title !== 'New chat') return c.title
  const firstUser = c.messages.find((m) => m.role === 'user')
  if (firstUser?.text) {
    const t = firstUser.text.replace(/\s+/g, ' ').trim()
    return t.length > max ? t.slice(0, max) + '…' : t
  }
  return c.title
}

function fmtConvTime(ts: number): string {
  const d = new Date(ts)
  const today = new Date()
  if (d.toDateString() === today.toDateString()) {
    return d.toLocaleTimeString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { month: '2-digit', day: '2-digit' })
}

function appendMessage(msg: Omit<ChatMessage, 'id' | 'createdAt'>): ChatMessage {
  // ⚠️ 用 reactive() 包一次：SSE 里 `reply.text += token` 是直接修改对象属性，
  // 普通对象字面量在塞进 reactive 数组后，**对象本身不是 Proxy**——
  // 修改属性不会触发模板更新。fallout：F12 能看到 token 流过，但 UI 不动。
  // 详见 https://vuejs.org/api/reactivity-core.html#reactive 的 "Reactive Proxy vs Original" 段落。
  const created: ChatMessage = reactive<ChatMessage>({
    id: shortId(), createdAt: Date.now(), ...msg,
  } as ChatMessage)
  if (!activeConv.value) startNewChat(false)
  activeConv.value!.messages.push(created)
  activeConv.value!.updatedAt = Date.now()
  // 首条用户消息时更新会话标题
  if (msg.role === 'user' && activeConv.value!.messages.filter((m) => m.role === 'user').length === 1) {
    const t = msg.text.replace(/\s+/g, ' ').trim()
    activeConv.value!.title = t.length > 26 ? t.slice(0, 26) + '…' : t
  }
  void scrollToBottom()
  persistConversations()
  return created
}

async function scrollToBottom() {
  await nextTick()
  const el = transcriptEl.value
  if (el) el.scrollTop = el.scrollHeight
}

/* v12：每次 transcript DOM 更新后，把新出现的 ```mermaid``` 块异步渲染成 SVG。
   onUpdated 的频次 ~= SSE token 的频次；ensureMermaidRendered 内部对已渲染的
   节点用 data-mermaid-rendered 跳过，所以不会重复跑。 */
onUpdated(() => {
  void ensureMermaidRendered(transcriptEl.value)
})

function toggleOpen() {
  open.value = !open.value
  if (open.value && !greetingShown.value && messages.value.length === 0) {
    greetingShown.value = true
    appendMessage({
      role: 'assistant',
      text:
        locale.value === 'zh-CN'
          ? '你好，我是星空使者。可以为你检索记忆、做情绪复盘，或在他人记忆中寻找共鸣节点。'
          : "Hi, I'm your Echo Envoy. I can retrace memories, recap moods, and seek resonance across others.",
    })
  }
  // 首次打开时拉取个性化推荐问题（失败静默回退到 fallbackHints）
  if (open.value) {
    void fetchDynamicHints()
  }
}

function toggleMinimize() {
  minimized.value = !minimized.value
}

/** 启发式意图识别 — 闲聊 vs 复杂规划。
 *
 *  规则（v2，更保守）：
 *   - 任何**寒暄 / 自我介绍 / 你是谁 / 你好** → 一律 chat，绝不规划。
 *   - 短句（< 8 个有效字符）→ chat。
 *   - 命中"具体的检索/分析意图词"且语句长度 ≥ 8 → plan。
 *
 *  之前的版本把 `谁/who/which` 也算检索关键词，导致"你是谁?"被误判成 plan，
 *  立刻渲染 4 步硬编码 plan 卡片 — 这是用户反馈的 bug。 */
function classifyIntent(text: string): 'chat' | 'plan' {
  const raw = (text || '').trim()
  if (!raw) return 'chat'

  // 寒暄白名单：命中即 chat
  const greetingPatterns = [
    /^你好[\s,，.。!！?？]*$/, /^您好/, /^早上好/, /^晚上好/, /^嗨[\s!！]*$/,
    /^你是谁/, /^自我介绍/, /^介绍一下你自己/,
    /^hi[\s!.]*$/i, /^hello[\s!.]*$/i, /^hey[\s!.]*$/i,
    /^who\s+are\s+you/i, /^introduce\s+yourself/i,
    /^thanks?\b/i, /^谢谢/, /^感谢/,
  ]
  if (greetingPatterns.some((p) => p.test(raw))) return 'chat'

  // 短句一律 chat
  if (raw.replace(/\s/g, '').length < 8) return 'chat'

  // 真正的检索/规划关键词（去掉 "谁/who/which" 这类太泛的）
  const planSignals = [
    '帮我找', '帮我检索', '帮我搜索', '帮我整理', '帮我推荐',
    '检索', '搜索', '匹配', '共鸣', '路径', '路线',
    '对比', '相似', '推荐一段', '推荐一个',
    'find my', 'search my', 'look up', 'compare', 'similar',
    'recommend', 'summarize', 'analyse', 'analyze',
  ]
  const t = raw.toLowerCase()
  if (planSignals.some((kw) => t.includes(kw.toLowerCase()))) return 'plan'

  // "包含 4 位年份 + 长度 > 12" 也算 plan（典型："2023 年大理的记忆"）
  if (/\b(19|20)\d{2}\b/.test(t) && raw.length > 12) return 'plan'

  return 'chat'
}

function buildPlan(text: string): PlanStep[] {
  const zh = locale.value === 'zh-CN'
  const base: PlanStep[] = [
    { id: shortId(), label: zh ? '解析时间/地点/情绪关键词' : 'Parse time, place, emotion keywords', status: 'pending' },
    { id: shortId(), label: zh ? '检索个人记忆向量库' : 'Search personal memory vectors', status: 'pending' },
    { id: shortId(), label: zh ? '匹配相似情感节点（脱敏）' : 'Match similar emotion nodes (anonymized)', status: 'pending' },
    { id: shortId(), label: zh ? '组装多模态结果卡' : 'Assemble multimodal cards', status: 'pending' },
  ]
  if (/路径|路线|route|map|大理|atlas|2023/i.test(text)) {
    base.splice(3, 0, { id: shortId(), label: zh ? '比对地理轨迹重叠' : 'Compare geographic overlap', status: 'pending' })
  }
  return base
}
// buildPlan 当前未直接调用 — plan 完全交给后端 SSE meta 驱动；保留实现以便回退。
void buildPlan

function buildTools(_text: string): ToolCall[] {
  // 保留签名以便后续扩展，但当前实现不再前端伪造工具调用 —
  // 全交给后端 SSE `event: tool_start / tool_end` 驱动。
  return []
}
void buildTools

function toggleToolExpansion(msg: ChatMessage, callId: string) {
  if (!msg.toolCalls) return
  for (const c of msg.toolCalls) if (c.id === callId) c.expanded = !c.expanded
}

async function sleep(ms: number): Promise<void> {
  return new Promise((res) => setTimeout(res, ms))
}

/* simulateStreaming — 已移除。Bugfix 2.5 要求：上游 AI 不可用时绝不能用本地
 * 模板冒充 AI 回答。失败路径在 send() 里渲染明确的 "AI 暂不可用" 错误卡片。
 *
 * （原先的实现做关键词匹配 + 拼装 "让我把脉络梳理一下..." 模板，正是 Bug 1
 * 描述的"硬编码 mock"。删掉。） */

/** 把 memoryStore 的最近记忆压缩成给后端的 digest，让 AI 答案基于真实数据。 */
function buildMemoryDigest(): Array<{
  id: string; title: string; location: string; year: number | null; snippet: string
}> {
  const list = memoryStore.memories || []
  return list.slice(0, 20).map((m: any) => ({
    id: m.id,
    title: m.title || '',
    location: m.memoryLocation || '',
    year: m.memoryYear || (m.memoryDate ? new Date(m.memoryDate).getFullYear() : null),
    snippet: (m.description || '').slice(0, 240),
  }))
}

/** 后端 SSE 路径 — 通过 client 的 baseURL（/api/v1）来到 gateway 再到 ai-service。
 *  返回 {@link StreamResult} 让调用方区分 "连接失败" / "上游不可用" / "成功"，
 *  以前那个 "失败就静默切到 simulateStreaming 模板" 的兜底已经删掉 —
 *  Bugfix 2.5 要求前端在上游 NVIDIA / MiniMax 不可用时显示明确错误，
 *  不能再用本地模板冒充 AI 回答。 */
async function streamFromBackend(reply: ChatMessage, question: string, images?: string[]): Promise<StreamResult> {
  const token = authStore.token
  if (!token) return { ok: false, upstreamError: false, errorCode: 'NO_AUTH' }

  // 提前确保拉过记忆 — 否则 digest 是空的，答案会很干
  if ((memoryStore.memories || []).length === 0) {
    try { await memoryStore.fetchList(0, 50) } catch { /* silent */ }
  }

  const ctrl = new AbortController()
  let gotFirst = false
  let doneSeen = false
  let upstreamError = false
  let errorCode: string | undefined
  let errorDetail: string | undefined
  try {
    const resp = await fetch('/api/v1/reconstruct/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        question,
        context: buildMemoryDigest(),
        locale: locale.value,
        // 多模态混合检索：非空时后端会先过一遍视觉模型再喂基座
        ...(images && images.length ? { images } : {}),
      }),
      signal: ctrl.signal,
    })
    if (!resp.ok || !resp.body) {
      // 后端 502/503 — 把 body 解析一次提取 detail
      try {
        const body = await resp.json()
        upstreamError = true
        errorCode = body?.code || `HTTP_${resp.status}`
        errorDetail = body?.detail || body?.message
      } catch {
        upstreamError = true
        errorCode = `HTTP_${resp.status}`
      }
      return { ok: false, upstreamError, errorCode, detail: errorDetail }
    }

    const reader = resp.body.getReader()
    const dec = new TextDecoder()
    let buf = ''
    let pendingEvent: string | null = null

    // SSE 解析（按空行分帧）
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      let sep
      while ((sep = buf.indexOf('\n\n')) !== -1) {
        const frame = buf.slice(0, sep); buf = buf.slice(sep + 2)
        const lines = frame.split('\n')
        let evt = 'message'
        const data: string[] = []
        for (const ln of lines) {
          if (ln.startsWith('event:')) evt = ln.slice(6).trim()
          else if (ln.startsWith('data:')) data.push(ln.slice(5).trim())
        }
        const payload = data.join('\n')
        pendingEvent = evt
        if (evt === 'meta') {
          try {
            const m = JSON.parse(payload)
            // 后端给的 plan 覆盖前端启发式的 plan
            if (Array.isArray(m.plan) && m.plan.length) {
              reply.plan = m.plan.map((label: string) => ({
                id: shortId(), label, status: 'pending' as const,
              }))
            }
            // 多模态混合检索：把 visionUsed 标记记入 reply，UI 据此渲染"🔍 已分析 N 张图片"
            if (m.vision_used) {
              reply.visionUsed = true
              reply.visionModel = m.vision_model
              reply.attachmentCount = m.attachment_count
            }
          } catch { /* ignore parse */ }
        } else if (evt === 'plan_update') {
          // P3-13 动态 plan：后端 1-3s 内异步生成的 LLM plan，覆盖之前 meta 帧里的硬编码 plan。
          // 保留已经完成（done）的步骤状态，新步骤都设为 pending。
          try {
            const u = JSON.parse(payload)
            if (Array.isArray(u.plan) && u.plan.length) {
              const doneCount = (reply.plan || []).filter((s) => s.status === 'done').length
              reply.plan = u.plan.map((label: string, idx: number) => ({
                id: shortId(),
                label,
                status: idx < doneCount ? 'done' as const : 'pending' as const,
              }))
              reply.planSource = u.source || 'llm'
            }
          } catch { /* ignore parse */ }
        } else if (evt === 'tool_start') {
          try {
            const tc = JSON.parse(payload)
            const id = shortId()
            reply.toolCalls = reply.toolCalls || []
            reply.toolCalls.push({
              id, name: tc.name, label: tc.label,
              status: 'running', input: tc.input,
            })
          } catch {}
        } else if (evt === 'tool_end') {
          try {
            const tc = JSON.parse(payload)
            if (reply.toolCalls) {
              const last = [...reply.toolCalls].reverse().find((c) => c.name === tc.name && c.status === 'running')
              if (last) { last.status = 'done'; last.output = tc.output }
            }
            // 真实多模态：minioMediaFetchTool 的输出包含 presigned URL（含 X-Amz-Signature），
            // 直接转成附件。kind=minio 的条目优先；本地降级条目也带上。
            if (tc.name === 'minioMediaFetchTool') {
              const minioAtts = attachmentsFromMinioTool(tc.output)
              if (minioAtts.length) {
                // 追加而不是覆盖（如果多次工具调用）
                const existing = reply.attachments ?? []
                reply.attachments = [...existing, ...minioAtts]
              }
            }
          } catch {}
        } else if (evt === 'token') {
          // SSE data 是 JSON 解码后的字符串（后端用 .data(String)）
          const txt = payload.startsWith('"') ? safeJsonParse(payload) : payload
          reply.text += txt
          if (!gotFirst) { gotFirst = true; waiting.value = false }
          // 推进 plan
          if (reply.plan) {
            const next = reply.plan.find((s) => s.status === 'pending')
            if (next) next.status = 'running'
            const justDone = reply.plan.filter((s) => s.status === 'running')
            if (justDone.length > 1) justDone[0].status = 'done'
          }
          await scrollToBottom()
        } else if (evt === 'error') {
          // 后端在 SSE 流中显式声明上游不可用（NVIDIA_API_KEY 占位 / 超时 / 5xx）。
          // 把结构化错误透传给 UI，不再用模板伪造答案。
          upstreamError = true
          try {
            const er = JSON.parse(payload)
            errorCode = er?.code || 'AI_UPSTREAM_UNAVAILABLE'
            errorDetail = er?.detail || er?.reason || er?.message
          } catch {
            errorCode = 'AI_UPSTREAM_UNAVAILABLE'
          }
        } else if (evt === 'done') {
          if (reply.plan) for (const s of reply.plan) if (s.status !== 'done') s.status = 'done'
          // 真实多模态：附件已经在 tool_end (minioMediaFetchTool) 时通过 presigned URL
          // 注入到 reply.attachments；不再从 media-catalog / dynamicMedia 兜底。
          // 如果模型没调 minioMediaFetchTool 就没有附件，对应没有卡片 — 这是正确行为。
          doneSeen = true
          reply.streaming = false
        }
      }
    }
    void pendingEvent
    // 成功判定：只要后端发了 done 帧（流正常结束）就算成功，即便模型这次输出为空。
    // 之前用 `gotFirst` 会把"连上了但模型返回空"误判成"无法连接到 AI 服务"。
    // 上游真不可用时后端发的是 error 帧（upstreamError=true），与此区分。
    const ok = (doneSeen || gotFirst) && !upstreamError
    return { ok, upstreamError, errorCode, detail: errorDetail }
  } catch (e: any) {
    return {
      ok: false,
      upstreamError: false,
      errorCode: 'NETWORK_ERROR',
      detail: e?.message || 'network error',
    }
  } finally {
    ctrl.abort()
  }
}

function safeJsonParse(s: string): string {
  try { return JSON.parse(s) } catch { return s }
}

/** 图片加载失败兜底：把 img 隐藏，让父 .ai-attach 的渐变占位裸出来，
 *  同时在 alt 区域显示。常见触发：blob URL 已 revoke、MinIO 内网不可达。 */
function onAttachImgError(ev: Event) {
  const el = ev.target as HTMLImageElement | null
  if (!el) return
  el.style.display = 'none'
  // 给父节点加一个 class，让 CSS 露出兜底文案
  el.parentElement?.classList.add('ai-attach--broken')
}

/** 把 model id 简化成 "厂商/末段"。例：
 *   meta/llama-3.2-11b-vision-instruct → llama-3.2-11b
 *   qwen/qwen3.5-397b-a17b              → qwen3.5
 *   moonshotai/kimi-k2.5                → kimi-k2.5
 *  让 vision 胶囊在窄消息泡里也不溢出。 */
function shortVisionModel(id: string | undefined): string {
  if (!id) return ''
  const tail = id.includes('/') ? id.split('/').pop()! : id
  // 去掉 "-instruct" / "-vision-instruct" 等后缀，截 5 段以内
  const cleaned = tail
    .replace(/-vision-instruct$/i, '')
    .replace(/-instruct$/i, '')
    .replace(/-it$/i, '')
  // 再做一次安全长度限制，避免极长 model id
  return cleaned.length > 22 ? cleaned.slice(0, 22) + '…' : cleaned
}

/** streamFromBackend 的结果。
 *  - ok=true   ：成功收到至少一个 token，正常完成。
 *  - ok=false  ：连接 / 鉴权 / 网关层失败（无法建立 SSE），由调用方决定文案。
 *  - upstreamError：后端连上了，但下游模型不可用 — 后端已发 `event: error`，
 *    payload 已经写进 reply.text，UI 不再注入任何模板兜底。 */
interface StreamResult {
  ok: boolean
  upstreamError: boolean
  errorCode?: string
  detail?: string
}

async function send() {
  const text = inputText.value.trim()
  if (!text || streaming.value) return
  // 等待所有上传完成（用户已点 send 但还有图在传）
  if (attachmentUploadBusy.value) return

  // 把已上传成功的图片信息收集起来：
  //  - url:   asset-service 返回的 MinIO presigned URL → 发给后端，让 ai-service / vision 用
  //  - thumb: 本地 blob URL → 用户消息泡里显示，永远可见（不依赖网络），
  //           因为浏览器无法直接访问 Tailscale 内网的 MinIO。
  const ready = pendingAttachments.value
    .filter((a) => !a.error && !a.uploading && a.url)
  const readyImages = ready.map((a) => a.url)
  // user-bubble 缩略图候选：拷贝一份 thumb / url，避免之后 clearAttachments revoke 影响显示
  const userBubbleAttachments = ready.map((a) => ({
    kind: 'image' as const,
    src: a.thumb || a.url,
  }))

  inputText.value = ''
  // 用户消息泡里附带显示已上传的缩略图（便于追溯）
  appendMessage({
    role: 'user',
    text,
    attachments: userBubbleAttachments.length ? userBubbleAttachments : undefined,
  })

  const intent = classifyIntent(text)
  const reply = appendMessage({
    role: 'assistant',
    text: '',
    streaming: true,
    // ⚠️ Plan 步骤完全交给后端 SSE 的 `event: meta` 驱动 — 前端不再写硬编码 4 步。
    // 这样简单问候（"你是谁?"）即使被误判成 plan，也不会立刻渲染 mock plan 卡片。
    plan: undefined,
    toolCalls: undefined,
  })
  // intent 仍然保留（用于跑马灯 / waiting 状态），但不再生成假 plan
  void intent

  streaming.value = true
  waiting.value = true

  // 留出可见的"等待首包"窗口，给跑马灯一段表演时间。
  await sleep(360)

  try {
    // 真后端 SSE — 失败时 NOT silently fall back to a mock template
    // (Bugfix 2.5: 上游不可用必须以明确文案告知用户, 而非伪装成正常回答)
    const result = await streamFromBackend(reply, text, readyImages.length ? readyImages : undefined)
    if (!result.ok) {
      const zh = locale.value === 'zh-CN'
      // 抹掉之前部分写入的 text；以错误卡片替代
      reply.text = ''
      // 还原 plan 状态 — 没真正执行
      if (reply.plan) for (const s of reply.plan) s.status = 'error'
      reply.upstreamError = true
      reply.errorCode = result.errorCode || 'AI_UPSTREAM_UNAVAILABLE'
      const baseMsg = result.upstreamError
        ? (zh
            ? '⚠ AI 暂不可用 —— 后端拒绝调用大模型。'
            : '⚠ AI is currently unavailable — backend refused to call the model.')
        : (zh
            ? '⚠ 无法连接到 AI 服务，请检查后端 / 网关 / 鉴权。'
            : '⚠ Could not reach AI service. Check backend / gateway / auth.')
      const hint = result.errorCode === 'AI_UPSTREAM_UNAVAILABLE' || /MISSING_KEY|placeholder/i.test(result.detail || '')
        ? (zh
            ? '\n\n原因：服务端 NVIDIA_API_KEY 仍是占位符。请到 ai-service 的环境变量里设置真实 key 后重启 (export NVIDIA_API_KEY=nvapi-…)。'
            : '\n\nReason: server-side NVIDIA_API_KEY is still a placeholder. Set a real key (export NVIDIA_API_KEY=nvapi-…) and restart ai-service.')
        : (result.detail ? `\n\n${result.detail}` : '')
      reply.text = baseMsg + hint
      // 显式不再注入 dynamicMedia / media-catalog 的兜底附件
      reply.attachments = undefined
    } else if (!reply.text || !reply.text.trim()) {
      // 流正常结束但模型这次没有输出任何文本 —— 给一句温和提示，而不是留空泡。
      const zh = locale.value === 'zh-CN'
      reply.text = zh
        ? '（这次我没有想到合适的回答，可以换个说法再问我一次吗？）'
        : "(I didn't have a good answer this time — could you rephrase and ask again?)"
    }
  } finally {
    streaming.value = false
    waiting.value = false
    reply.streaming = false
    if (activeConv.value) activeConv.value.updatedAt = Date.now()
    persistConversations()
    // 发送完成后清空待发附件队列；用户消息泡里仍保留缩略图记录
    clearAttachments()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

function pickHint(hint: string) {
  inputText.value = hint
  void send()
}

function planIcon(status: PlanStep['status']) {
  if (status === 'done') return '✓'
  if (status === 'running') return '◐'
  if (status === 'error') return '✕'
  return '○'
}

const closeOnEsc = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && open.value) open.value = false
}

const positionX = ref<number | null>(null)
const positionY = ref<number | null>(null)

/* GIF/PNG/SVG 吉祥物 — 从 asset-service 动态发现。
 * 命名约定：文件名包含 "ai-mascot" / "mascot" / "envoy" 或位于 icon/ 目录的 GIF。
 * 找不到时降级到 CSS aurora 粒子球（无外部资源依赖）。 */
const mascotGifUrl = ref<string | null>(null)

async function discoverMascotAsset() {
  try {
    const resp = await client.get('/assets/static/resources')
    const files: Array<{ name: string; path: string; type: string }> = resp.data?.data || []
    const isMascot = (n: string) => /mascot|envoy|star\-?envoy|ai-?orb|ai-?mascot|spirit/i.test(n)
    // ⚠️ 只接受文件名命中"吉祥物"约定的资源；以前 fallback 到"任意一个 GIF"，
    //    会把用户上传的随机封面（紫色球之类）当吉祥物盖在 conic 彩虹球之上 → 球瞬间变实心紫
    const gif = files.find((f) => f.type === 'gif' && isMascot(f.name))
    const icon = files.find((f) => (f.type === 'icon' || f.type === 'photo') && isMascot(f.name))
    const chosen = gif || icon
    if (chosen) {
      mascotGifUrl.value = chosen.path.startsWith('http') ? chosen.path : chosen.path
    }
  } catch {
    /* 静默降级到 CSS 动画 */
  }
}

onMounted(() => {
  window.addEventListener('keydown', closeOnEsc)
  loadConversations()
  const savedX = localStorage.getItem('ai_dock_x')
  const savedY = localStorage.getItem('ai_dock_y')
  if (savedX !== null && savedY !== null) {
    positionX.value = parseFloat(savedX)
    positionY.value = parseFloat(savedY)
  }
  void discoverMascotAsset()
})

onUnmounted(() => window.removeEventListener('keydown', closeOnEsc))

const isLeftHalf = computed(() => {
  if (positionX.value === null) return false
  return positionX.value < window.innerWidth / 2
})

const dockStyle = computed(() => {
  if (positionX.value === null || positionY.value === null) {
    return {
      right: '28px',
      bottom: '28px',
    }
  }
  return {
    left: `${positionX.value}px`,
    top: `${positionY.value}px`,
    right: 'auto',
    bottom: 'auto',
  }
})

let isDragging = false
let dragThresholdPassed = false
let startMouseX = 0
let startMouseY = 0
let startPosX = 0
let startPosY = 0

function handlePointerDown(e: PointerEvent) {
  if (e.button !== 0) return // Left click only
  dragThresholdPassed = false
  isDragging = true
  startMouseX = e.clientX
  startMouseY = e.clientY

  const rect = (e.currentTarget as HTMLElement).parentElement?.getBoundingClientRect()
  if (rect) {
    startPosX = rect.left
    startPosY = rect.top
  } else {
    startPosX = window.innerWidth - 72 - 28
    startPosY = window.innerHeight - 72 - 28
  }

  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', handlePointerUp)
}

function handlePointerMove(e: PointerEvent) {
  if (!isDragging) return
  const deltaX = e.clientX - startMouseX
  const deltaY = e.clientY - startMouseY

  if (!dragThresholdPassed && Math.sqrt(deltaX * deltaX + deltaY * deltaY) > 6) {
    dragThresholdPassed = true
  }

  if (dragThresholdPassed) {
    const newX = Math.max(0, Math.min(window.innerWidth - 72, startPosX + deltaX))
    const newY = Math.max(0, Math.min(window.innerHeight - 72, startPosY + deltaY))
    positionX.value = newX
    positionY.value = newY
  }
}

function handlePointerUp() {
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', handlePointerUp)

  if (dragThresholdPassed && positionX.value !== null && positionY.value !== null) {
    localStorage.setItem('ai_dock_x', positionX.value.toString())
    localStorage.setItem('ai_dock_y', positionY.value.toString())
  }

  setTimeout(() => {
    isDragging = false
  }, 50)
}

function handleOrbClick() {
  if (dragThresholdPassed) {
    return
  }
  toggleOpen()
}

/* ============ 面板拖动（从 header 拖整个 dock） ============
 * 用户反馈：球能拖，但点开后的对话面板不能拖。这里把 `<header>` 也接管 PointerEvents，
 * 通过移动 dock 的左上角实现整体拖拽。点击关闭/最小化按钮（.ai-icon-btn）时不触发，
 * 因为我们用 closest('.ai-icon-btn') 提前过滤了。 */
let panelDragging = false
let panelStartMouseX = 0
let panelStartMouseY = 0
let panelStartPosX = 0
let panelStartPosY = 0

function handlePanelPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  // 点击 header 上的图标按钮（关闭、最小化、新建、历史）时不进入拖动
  const target = e.target as HTMLElement | null
  if (target && target.closest('.ai-icon-btn')) return

  panelDragging = true
  panelStartMouseX = e.clientX
  panelStartMouseY = e.clientY

  // 用 dock 当前矩形作为锚点，避免拖到一半因为 dockStyle 还在用 right/bottom 而跳变
  const dockEl = (e.currentTarget as HTMLElement).closest('.ai-dock') as HTMLElement | null
  if (dockEl) {
    const rect = dockEl.getBoundingClientRect()
    panelStartPosX = rect.left
    panelStartPosY = rect.top
  } else {
    panelStartPosX = positionX.value ?? (window.innerWidth - 100 - 28)
    panelStartPosY = positionY.value ?? (window.innerHeight - 100 - 28)
  }

  // 第一拖动时把 dock 切到 left/top 模式（dockStyle 计算）
  if (positionX.value === null || positionY.value === null) {
    positionX.value = panelStartPosX
    positionY.value = panelStartPosY
  }

  window.addEventListener('pointermove', handlePanelPointerMove)
  window.addEventListener('pointerup', handlePanelPointerUp)
  // 防止文字被选中
  e.preventDefault()
}

function handlePanelPointerMove(e: PointerEvent) {
  if (!panelDragging) return
  const dx = e.clientX - panelStartMouseX
  const dy = e.clientY - panelStartMouseY
  const newX = Math.max(0, Math.min(window.innerWidth - 80, panelStartPosX + dx))
  const newY = Math.max(0, Math.min(window.innerHeight - 80, panelStartPosY + dy))
  positionX.value = newX
  positionY.value = newY
}

function handlePanelPointerUp() {
  window.removeEventListener('pointermove', handlePanelPointerMove)
  window.removeEventListener('pointerup', handlePanelPointerUp)
  if (panelDragging && positionX.value !== null && positionY.value !== null) {
    localStorage.setItem('ai_dock_x', positionX.value.toString())
    localStorage.setItem('ai_dock_y', positionY.value.toString())
  }
  panelDragging = false
}

// 让浏览器在 idle 时预拉静态资源，AI 触发时已暖好。
shallowRef([images.goldenAfternoon.src, images.memoryCorona.src, images.resonanceTwins.src]).value.forEach((u) => {
  const img = new Image()
  img.src = u
})
</script>

<template>
  <!-- 永远右下角悬浮的入口；面板按需挂载。 -->
  <div class="ai-dock" :class="{ 'ai-dock--open': open, 'ai-dock--left-half': isLeftHalf }" :style="dockStyle">
      <button
        class="ai-orb"
        :class="{ 'ai-orb--has-mascot': mascotGifUrl }"
        type="button"
        :aria-label="t('ai.toggle')"
        :aria-expanded="open"
        @pointerdown="handlePointerDown"
        @click="handleOrbClick"
      >
        <img
          v-if="mascotGifUrl"
          class="ai-orb__mascot"
          :src="mascotGifUrl"
          alt=""
          aria-hidden="true"
          draggable="false"
        />
        <span class="ai-orb__aurora" aria-hidden="true">
          <span class="ai-orb__ribbon ai-orb__ribbon--a"></span>
          <span class="ai-orb__ribbon ai-orb__ribbon--b"></span>
          <span class="ai-orb__ribbon ai-orb__ribbon--c"></span>
        </span>
        <span class="ai-orb__core" aria-hidden="true"></span>
        <span class="ai-orb__halo ai-orb__halo--a" aria-hidden="true"></span>
        <span class="ai-orb__halo ai-orb__halo--b" aria-hidden="true"></span>
        <span class="ai-orb__particles" aria-hidden="true">
          <i v-for="i in 9" :key="i" :style="{ '--idx': i }"></i>
        </span>
        <span class="ai-orb__label">{{ t('ai.shortName') }}</span>
      </button>

    <transition name="dock-panel">
      <section
        v-if="open"
        ref="panelEl"
        class="ai-panel"
        :class="{ 'ai-panel--minimized': minimized }"
        role="dialog"
        :aria-label="t('ai.title')"
      >
        <video class="ai-panel__bg-video" :src="ambientVideo" autoplay muted loop playsinline aria-hidden="true"></video>
        <div class="ai-panel__bg-overlay" :style="{ backgroundImage: ambientBgUrl }" aria-hidden="true"></div>

        <header class="ai-panel__head" @pointerdown="handlePanelPointerDown">
          <span class="ai-panel__title-wrap">
            <span class="ai-panel__title">{{ t('ai.title') }}</span>
            <span class="ai-panel__subtitle">
              {{ activeConv ? convTitle(activeConv) : t('ai.subtitle') }}
            </span>
          </span>
          <span class="ai-panel__actions">
            <button class="ai-icon-btn" type="button" :title="locale === 'zh-CN' ? '新建对话' : 'New chat'" :disabled="streaming" @click="startNewChat(true)">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
            </button>
            <button class="ai-icon-btn" type="button" :class="{ 'ai-icon-btn--active': historyOpen }" :title="locale === 'zh-CN' ? '历史对话' : 'Chat history'" @click="historyOpen = !historyOpen">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8"/>
                <path d="M12 7v5l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </button>
            <button class="ai-icon-btn" type="button" :title="t('ai.minimize')" @click="toggleMinimize">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none"><path d="M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
            </button>
            <button class="ai-icon-btn" type="button" :title="t('ai.close')" @click="open = false">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none"><path d="M6 6l12 12M18 6l-12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
            </button>
          </span>
        </header>

        <!-- 历史对话面板 -->
        <transition name="history-panel">
          <div v-if="historyOpen" class="ai-history">
            <div class="ai-history__head">
              <span>{{ locale === 'zh-CN' ? '历史对话' : 'Conversation history' }}</span>
              <button class="ai-history__new" type="button" :disabled="streaming" @click="startNewChat(true)">
                + {{ locale === 'zh-CN' ? '新建' : 'New' }}
              </button>
            </div>
            <div v-if="conversations.length === 0" class="ai-history__empty">
              {{ locale === 'zh-CN' ? '还没有历史会话' : 'No conversations yet' }}
            </div>
            <ul v-else class="ai-history__list">
              <li v-for="c in conversations" :key="c.id"
                  class="ai-history__item"
                  :class="{ 'ai-history__item--active': c.id === activeConvId }">
                <button class="ai-history__pick" type="button" @click="switchConv(c.id)">
                  <span class="ai-history__title">{{ convTitle(c) }}</span>
                  <span class="ai-history__meta">{{ c.messages.length }} · {{ fmtConvTime(c.updatedAt) }}</span>
                </button>
                <button class="ai-history__del" type="button" :title="locale === 'zh-CN' ? '删除' : 'Delete'" @click.stop="deleteConv(c.id)">
                  <svg viewBox="0 0 24 24" width="13" height="13" fill="none"><path d="M6 6l12 12M18 6l-12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
                </button>
              </li>
            </ul>
          </div>
        </transition>

        <!-- 等待首包：跑马灯渐变光带 -->
        <div class="ai-marquee" :class="{ 'ai-marquee--on': waiting }" aria-hidden="true">
          <span></span>
        </div>

        <div v-show="!minimized" class="ai-panel__body">
          <div class="ai-transcript" ref="transcriptEl">
            <div v-for="m in messages" :key="m.id" class="ai-msg" :class="`ai-msg--${m.role}`">
              <div class="ai-msg__avatar" :class="`ai-msg__avatar--${m.role}`">
                <span v-if="m.role === 'user'">U</span>
                <span v-else>✦</span>
              </div>
              <div class="ai-msg__bubble">
                <!-- Plan list -->
                <ul v-if="m.plan && m.plan.length" class="ai-plan">
                  <li v-for="step in m.plan" :key="step.id" :class="`ai-plan__item ai-plan__item--${step.status}`">
                    <span class="ai-plan__icon">{{ planIcon(step.status) }}</span>
                    <span class="ai-plan__label">{{ step.label }}</span>
                  </li>
                </ul>

                <!-- Tool calls -->
                <div v-if="m.toolCalls && m.toolCalls.length" class="ai-toolstack">
                  <div
                    v-for="call in m.toolCalls"
                    :key="call.id"
                    class="ai-tool"
                    :class="[`ai-tool--${call.status}`, call.expanded ? 'ai-tool--expanded' : '']"
                  >
                    <button class="ai-tool__row" type="button" @click="toggleToolExpansion(m, call.id)">
                      <span class="ai-tool__gear" :class="{ 'ai-tool__gear--spin': call.status === 'running' }">
                        <svg viewBox="0 0 24 24" width="12" height="12" fill="none">
                          <path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z" stroke="currentColor" stroke-width="1.6" />
                          <path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.4.8a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.5a7 7 0 0 0-2 1.2l-2.4-.8-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.4-.8c.6.5 1.3.9 2 1.2L10 21h4l.5-2.5c.7-.3 1.4-.7 2-1.2l2.4.8 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z" stroke="currentColor" stroke-width="1.2" />
                        </svg>
                      </span>
                      <span class="ai-tool__text">[{{ call.label }}]</span>
                      <span class="ai-tool__name">{{ call.name }}</span>
                    </button>
                    <pre v-if="call.expanded" class="ai-tool__detail">{{ JSON.stringify({ input: call.input, output: call.output ?? null }, null, 2) }}</pre>
                  </div>
                </div>

                <!-- 多模态视觉前置标签：本条 assistant 回复使用了 vision 预读 -->
                <div v-if="m.role === 'assistant' && m.visionUsed" class="ai-vision-badge" role="note" :title="m.visionModel">
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="none" aria-hidden="true">
                    <circle cx="12" cy="12" r="3.5" stroke="currentColor" stroke-width="1.6" />
                    <path d="M3 12s3-7 9-7 9 7 9 7-3 7-9 7-9-7-9-7z" stroke="currentColor" stroke-width="1.4" />
                  </svg>
                  <span>{{ locale === 'zh-CN'
                    ? `已分析 ${m.attachmentCount || 0} 张图片`
                    : `Analyzed ${m.attachmentCount || 0} image${(m.attachmentCount || 0) === 1 ? '' : 's'}` }}</span>
                  <span v-if="m.visionModel" class="ai-vision-badge__model">· {{ shortVisionModel(m.visionModel) }}</span>
                </div>

                <!-- Streamed text — markdown rendered (v3 后)。
                     用 v-html 渲染 marked + DOMPurify 清洗过的 HTML；
                     插入流末尾的光标用一个独立 <span> 拼，避免动到 sanitized html。 -->
                <div v-if="m.text" class="ai-msg__text ai-msg__text--md">
                  <div class="ai-md-body" v-html="renderMarkdown(m.text)"></div>
                  <span v-if="m.streaming" class="ai-msg__caret">▍</span>
                </div>

                <!-- Multimodal attachments -->
                <div v-if="m.attachments && m.attachments.length" class="ai-attachments">
                  <a v-for="(a, idx) in m.attachments" :key="idx" class="ai-attach" :href="a.src" target="_blank" rel="noopener">
                    <img
                      v-if="a.kind === 'image'"
                      :src="a.src"
                      :alt="a.caption || (locale === 'zh-CN' ? '图片附件' : 'image attachment')"
                      loading="lazy"
                      @error="(e) => onAttachImgError(e)"
                    />
                    <span class="ai-attach__caption">{{ a.caption }}</span>
                  </a>
                </div>
              </div>
            </div>

            <div v-if="!messages.length" class="ai-empty">
              <span class="ai-empty__title">{{ t('ai.greeting') }}</span>
              <span class="ai-empty__hint">{{ t('ai.hintsTitle') }}</span>
            </div>
          </div>

          <div class="ai-hints">
            <button
              v-for="hint in intentHints"
              :key="hint"
              type="button"
              class="ai-hint"
              :disabled="streaming"
              @click="pickHint(hint)"
            >
              {{ hint }}
            </button>
          </div>

          <!-- 多模态附件队列：已选 / 上传中 / 失败 状态 -->
          <div v-if="pendingAttachments.length" class="ai-attach-queue" role="list">
            <div
              v-for="a in pendingAttachments"
              :key="a.id"
              class="ai-attach-chip"
              :class="{
                'ai-attach-chip--uploading': a.uploading,
                'ai-attach-chip--error': a.error,
              }"
              role="listitem"
              :title="a.error || a.name"
            >
              <img v-if="a.thumb" :src="a.thumb" :alt="a.name" />
              <span v-else class="ai-attach-chip__file">📄</span>
              <span class="ai-attach-chip__name">{{ a.name }}</span>
              <span v-if="a.uploading" class="ai-attach-chip__spin" aria-hidden="true"></span>
              <span v-else-if="a.error" class="ai-attach-chip__err" aria-hidden="true">!</span>
              <button
                type="button"
                class="ai-attach-chip__close"
                :aria-label="locale === 'zh-CN' ? '移除附件' : 'Remove attachment'"
                @click="removeAttachment(a.id)"
              >×</button>
            </div>
          </div>

          <div class="ai-input" :class="{ 'ai-input--active': streaming || waiting }">
            <span class="ai-input__taiji" aria-hidden="true">
              <span class="ai-input__fish ai-input__fish--yang" aria-hidden="true"></span>
              <span class="ai-input__fish ai-input__fish--yin" aria-hidden="true"></span>
            </span>

            <!-- 📎 附件按钮：图片/GIF，最多 4 张 -->
            <button
              type="button"
              class="ai-attach-btn"
              :disabled="streaming || pendingAttachments.length >= ATTACH_MAX_COUNT"
              :title="locale === 'zh-CN'
                ? `添加图片附件（最多 ${ATTACH_MAX_COUNT} 张，将由视觉模型预读）`
                : `Attach images (up to ${ATTACH_MAX_COUNT}, processed by vision model)`"
              :aria-label="locale === 'zh-CN' ? '添加附件' : 'Attach files'"
              @click="pickAttachment"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
                <path d="m21 12-8.5 8.5a5 5 0 1 1-7-7L13.5 5a3.5 3.5 0 0 1 5 5L10 19" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <input
              ref="attachInputEl"
              type="file"
              :accept="ATTACH_ACCEPT"
              multiple
              hidden
              @change="onAttachmentChange"
            />

            <textarea
              v-model="inputText"
              class="ai-input__field"
              :placeholder="placeholder"
              rows="1"
              :disabled="streaming"
              @keydown="onKeydown"
            ></textarea>
            <button
              class="ai-send"
              type="button"
              :disabled="streaming || attachmentUploadBusy || !inputText.trim()"
              @click="send"
            >
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
                <path d="M5 12l14-7-5 14-3-6-6-1z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
              </svg>
              <span>{{ streaming ? t('ai.sending') : t('ai.send') }}</span>
            </button>
          </div>
        </div>
      </section>
    </transition>
  </div>
</template>

<style scoped>
.ai-dock {
  position: fixed;
  z-index: 9000;
  display: flex;
  align-items: flex-end;
  gap: 16px;
  pointer-events: none;
  flex-direction: row-reverse; /* default: orb on the right, panel on the left */
}
.ai-dock.ai-dock--left-half {
  flex-direction: row; /* orb on the left, panel on the right */
}
.ai-dock > * { pointer-events: auto; }

/* ============ 炫彩流光球（呼吸 + 多色 conic 旋转 + 粒子轨道） ============
 * 旧版本是单一 radial(青→紫→深) 渐变，看起来纯紫；现在改用 conic-gradient
 * 让 8 段彩虹色绕中心慢转 + 一层柔和高光，整体呈"星云 / 极光"质感。
 * 上面盖一层 ribbons (mix-blend-mode: screen) 与 9 颗轨道粒子。 */
.ai-orb {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  /* 用 conic + radial 两层叠加：conic 提供彩虹流转，radial 提供中央高光 */
  background:
    radial-gradient(circle at 32% 28%, rgba(255,255,255,0.85) 0%, rgba(255,255,255,0) 28%),
    conic-gradient(from var(--orb-rot, 0deg),
      #5ee5d9 0deg,
      #58c4ff 45deg,
      #c084fc 90deg,
      #ff6cb6 135deg,
      #ffd76a 180deg,
      #b6f077 225deg,
      #5ee5d9 270deg,
      #58c4ff 315deg,
      #5ee5d9 360deg);
  background-blend-mode: screen, normal;
  /* 外晕从"紫色 50px"换成中性"青+金"双层，避免核心被紫色外晕回染 */
  box-shadow:
    0 14px 40px rgba(94, 229, 217, 0.42),
    0 0 0 1px rgba(255, 255, 255, 0.18) inset,
    0 0 36px rgba(94, 229, 217, 0.32),
    0 0 60px rgba(255, 215, 106, 0.18);
  animation: orbBreath 4s ease-in-out infinite, orbColorSpin 9s linear infinite;
  display: grid;
  place-items: center;
  /* 让 conic 用 CSS 变量驱动旋转，比直接 transform 更稳（不影响子元素布局） */
  --orb-rot: 0deg;
}
.ai-orb:hover { transform: translateY(-3px) scale(1.04); }

@keyframes orbBreath {
  0%, 100% {
    box-shadow:
      0 14px 40px rgba(94,229,217,0.42),
      0 0 0 1px rgba(255,255,255,0.18) inset,
      0 0 36px rgba(94,229,217,0.32),
      0 0 60px rgba(255,215,106,0.18);
  }
  50% {
    box-shadow:
      0 18px 56px rgba(94,229,217,0.6),
      0 0 0 1px rgba(255,255,255,0.28) inset,
      0 0 56px rgba(94,229,217,0.5),
      0 0 88px rgba(255,215,106,0.32);
  }
}
@keyframes orbColorSpin {
  from { --orb-rot: 0deg; }
  to   { --orb-rot: 360deg; }
}
@property --orb-rot {
  syntax: '<angle>';
  inherits: false;
  initial-value: 0deg;
}

.ai-orb__core {
  position: absolute;
  /* inset 从 18px → 26px，缩小白色高光占比，把更多 conic 彩虹露出来 */
  inset: 26px;
  border-radius: 50%;
  /* 高光本身改为冷白色 + overlay 混合，让它对下层是"提亮"而不是"覆盖" */
  background: radial-gradient(circle at 35% 30%, rgba(255,255,255,0.95) 0%, rgba(190,225,255,0.4) 45%, transparent 75%);
  filter: blur(3px);
  opacity: 0.35;
  mix-blend-mode: overlay;
  pointer-events: none;
}

/* 若 asset-service 提供 GIF mascot，盖在最上层 */
.ai-orb__mascot {
  position: absolute;
  inset: 6px;
  width: calc(100% - 12px);
  height: calc(100% - 12px);
  border-radius: 50%;
  object-fit: cover;
  z-index: 3;
  pointer-events: none;
  filter: drop-shadow(0 0 8px rgba(94,229,217,0.45));
}
/* 有 GIF 时弱化背景动效，让 GIF 当主角 */
.ai-orb--has-mascot .ai-orb__aurora,
.ai-orb--has-mascot .ai-orb__core,
.ai-orb--has-mascot .ai-orb__particles { opacity: 0.4; }

/* Aurora ribbons — 三条柔光丝带反方向慢转，模拟 GIF 般的流动感 */
.ai-orb__aurora {
  position: absolute;
  inset: 4px;
  border-radius: 50%;
  overflow: hidden;
  filter: blur(2px) saturate(140%);
  pointer-events: none;
}
.ai-orb__ribbon {
  position: absolute;
  left: 50%; top: 50%;
  width: 160%; height: 36%;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  mix-blend-mode: screen;
  opacity: 0.78;
}
.ai-orb__ribbon--a {
  background: linear-gradient(90deg, transparent 0%, rgba(94,229,217,0.85) 30%, rgba(192,132,252,0.85) 70%, transparent 100%);
  animation: ribbonSpin 6.5s linear infinite;
}
.ai-orb__ribbon--b {
  background: linear-gradient(90deg, transparent 0%, rgba(255,215,106,0.75) 25%, rgba(94,229,217,0.85) 75%, transparent 100%);
  animation: ribbonSpin 8.5s linear infinite reverse;
  opacity: 0.6;
}
.ai-orb__ribbon--c {
  background: linear-gradient(90deg, transparent 0%, rgba(182,240,119,0.7) 50%, transparent 100%);
  animation: ribbonSpin 11s linear infinite;
  opacity: 0.45;
}
@keyframes ribbonSpin {
  from { transform: translate(-50%, -50%) rotate(0deg); }
  to   { transform: translate(-50%, -50%) rotate(360deg); }
}

.ai-orb__halo {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.18);
  animation: orbHalo 6s linear infinite;
}
.ai-orb__halo--b { inset: -16px; animation-duration: 9s; animation-direction: reverse; }
@keyframes orbHalo {
  from { transform: rotate(0); opacity: 0.6; }
  to   { transform: rotate(360deg); opacity: 0.2; }
}

.ai-orb__particles {
  position: absolute;
  inset: 0;
  display: block;
  pointer-events: none;
}
.ai-orb__particles i {
  position: absolute;
  left: 50%; top: 50%;
  width: 3px; height: 3px;
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 0 8px rgba(180, 220, 255, 0.9);
  transform: rotate(calc(var(--idx) * 40deg)) translate(28px);
  opacity: 0.85;
  animation: orbParticle 5s linear infinite;
  animation-delay: calc(var(--idx) * -0.3s);
}
@keyframes orbParticle {
  0%   { transform: rotate(calc(var(--idx) * 40deg)) translate(28px) scale(1); opacity: 0.95; }
  50%  { transform: rotate(calc(var(--idx) * 40deg + 180deg)) translate(34px) scale(0.6); opacity: 0.55; }
  100% { transform: rotate(calc(var(--idx) * 40deg + 360deg)) translate(28px) scale(1); opacity: 0.95; }
}

.ai-orb__label {
  position: absolute;
  bottom: -22px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 0.7rem;
  letter-spacing: 0.05em;
  color: rgba(255,255,255,0.65);
  white-space: nowrap;
}

/* ============ 面板 ============ */
.ai-panel {
  position: relative;
  width: min(440px, calc(100vw - 60px));
  max-height: 70vh;
  border-radius: 20px;
  border: 1px solid rgba(255,255,255,0.12);
  background: rgba(8, 10, 14, 0.72);
  backdrop-filter: blur(28px) saturate(160%);
  -webkit-backdrop-filter: blur(28px) saturate(160%);
  box-shadow: 0 24px 80px rgba(0,0,0,0.5), 0 0 0 1px rgba(54, 216, 180, 0.16) inset;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  color: #e8eaf0;
}
.ai-panel--minimized { max-height: 88px; }

.ai-panel__bg-video,
.ai-panel__bg-overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
  z-index: 0;
}
.ai-panel__bg-video { opacity: 0.18; filter: hue-rotate(-15deg) saturate(140%); }
.ai-panel__bg-overlay {
  background-size: cover;
  background-position: center;
  opacity: 0.22;
  mix-blend-mode: overlay;
}

.ai-panel__head {
  position: relative;
  z-index: 2;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  background: linear-gradient(180deg, rgba(14,18,24,0.6), rgba(14,18,24,0));
  cursor: grab;
  user-select: none;
  touch-action: none;  /* 让 PointerEvents 接管，避免移动端浏览器吞掉拖动 */
}
.ai-panel__head:active { cursor: grabbing; }
.ai-panel__title-wrap { display: flex; flex-direction: column; gap: 2px; }
.ai-panel__title { font-size: 0.96rem; font-weight: 600; letter-spacing: 0.02em; }
.ai-panel__subtitle { font-size: 0.72rem; color: rgba(255,255,255,0.55); }
.ai-panel__actions { display: inline-flex; gap: 6px; }

.ai-icon-btn {
  width: 28px; height: 28px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 50%;
  color: #d7dae3;
  cursor: pointer;
  display: grid; place-items: center;
  transition: background 160ms ease;
}
.ai-icon-btn:hover { background: rgba(255,255,255,0.14); }
.ai-icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.ai-icon-btn--active {
  background: rgba(54,216,180,0.18);
  border-color: rgba(54,216,180,0.45);
  color: #5ee5d9;
}

/* ============ 历史对话面板 ============ */
.ai-history {
  position: relative;
  z-index: 3;
  margin: 8px 12px 0;
  background: rgba(8,12,16,0.78);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 8px 8px 6px;
  max-height: 240px;
  overflow: auto;
  backdrop-filter: blur(20px) saturate(140%);
}
.ai-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 8px;
  font-size: 0.74rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255,255,255,0.55);
}
.ai-history__new {
  background: rgba(54,216,180,0.16);
  border: 1px solid rgba(54,216,180,0.4);
  color: #b6f077;
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 0.7rem;
  cursor: pointer;
  letter-spacing: 0.05em;
  transition: all 0.18s ease;
}
.ai-history__new:hover:not(:disabled) {
  background: rgba(54,216,180,0.3);
  color: #fff;
}
.ai-history__new:disabled { opacity: 0.5; cursor: not-allowed; }
.ai-history__empty {
  padding: 16px 8px;
  text-align: center;
  font-size: 0.78rem;
  color: rgba(255,255,255,0.4);
}
.ai-history__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ai-history__item {
  display: flex;
  align-items: stretch;
  border-radius: 8px;
  transition: background 0.18s ease;
}
.ai-history__item:hover { background: rgba(255,255,255,0.05); }
.ai-history__item--active {
  background: rgba(54,216,180,0.12);
  box-shadow: inset 0 0 0 1px rgba(54,216,180,0.32);
}
.ai-history__pick {
  flex: 1;
  background: transparent;
  border: none;
  text-align: left;
  padding: 8px 10px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: rgba(255,255,255,0.85);
  min-width: 0;
}
.ai-history__title {
  font-size: 0.82rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ai-history__meta {
  font-size: 0.68rem;
  color: rgba(255,255,255,0.4);
  letter-spacing: 0.04em;
}
.ai-history__del {
  background: transparent;
  border: none;
  padding: 0 10px;
  color: rgba(255,255,255,0.35);
  cursor: pointer;
  border-radius: 8px;
  display: grid;
  place-items: center;
}
.ai-history__del:hover { color: #ff6b6b; background: rgba(255,107,107,0.08); }

.history-panel-enter-active, .history-panel-leave-active {
  transition: opacity 220ms ease, transform 220ms cubic-bezier(0.16, 1, 0.3, 1), max-height 240ms ease;
}
.history-panel-enter-from, .history-panel-leave-to {
  opacity: 0; transform: translateY(-6px); max-height: 0;
}

.ai-marquee {
  position: relative;
  z-index: 2;
  height: 2px;
  overflow: hidden;
  background: rgba(255,255,255,0.05);
}
.ai-marquee span {
  display: block;
  height: 100%; width: 40%;
  background: linear-gradient(90deg, transparent, #5ee5d9, #b6f077, #c084fc, transparent);
  filter: blur(1px);
  transform: translateX(-50%);
  opacity: 0;
}
.ai-marquee--on span {
  opacity: 1;
  animation: marquee 1.6s linear infinite;
}
@keyframes marquee {
  from { transform: translateX(-50%); }
  to   { transform: translateX(250%); }
}

.ai-panel__body {
  position: relative; z-index: 2;
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

/* ============ 消息区 ============ */
.ai-transcript {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ai-transcript::-webkit-scrollbar { width: 4px; }
.ai-transcript::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.08); border-radius: 99px; }

.ai-msg { display: flex; gap: 10px; align-items: flex-start; }
.ai-msg--user { flex-direction: row-reverse; }
.ai-msg__avatar {
  flex: 0 0 28px;
  width: 28px; height: 28px;
  border-radius: 50%;
  display: grid; place-items: center;
  font-size: 0.78rem; font-weight: 700;
}
.ai-msg__avatar--assistant {
  background: linear-gradient(135deg, #36d8b4, #c084fc);
  color: #042018;
}
.ai-msg__avatar--user {
  background: rgba(255,255,255,0.08);
  color: #f3f5fa;
  border: 1px solid rgba(255,255,255,0.12);
}

.ai-msg__bubble {
  max-width: 82%;
  background: rgba(20,24,32,0.7);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 14px;
  padding: 14px 16px;
  line-height: 1.62;
  font-size: 1rem;
  font-weight: 500;
  letter-spacing: 0.005em;
  font-family: "Mnemoscape Mono", "Mnemoscape Hand", var(--font-sans);
}
.ai-msg--user .ai-msg__bubble {
  background: linear-gradient(135deg, rgba(54,216,180,0.18), rgba(192,132,252,0.16));
  border-color: rgba(54,216,180,0.32);
}

.ai-msg__text { margin: 0; white-space: pre-wrap; word-break: break-word; }
/* v3: AI 回答采用 markdown 渲染，给容器内的 markdown 元素一套与气泡风格搭配的样式。
   全站字体已经是 ComicShannsMono + 华文行楷（root --font-sans），这里只调字号 / 间距。
   v9 调整：所有字号上调一档；标题继承全站 .text-aurora 的渐变 + 7px 加粗。
   v12 调整：泡内字号偏小，七彩渐变在小字上易模糊"看着像没渲染"。改成实心彩色
            （亮青/金/绿三档），与全站渐变风格相呼应但保证清晰可读。 */
.ai-msg__text--md { white-space: normal; }
.ai-md-body { display: block; }
.ai-md-body :deep(p) { margin: 0 0 10px; line-height: 1.62; font-weight: 500; }
.ai-md-body :deep(p:last-child) { margin-bottom: 0; }
.ai-md-body :deep(h1),
.ai-md-body :deep(h2),
.ai-md-body :deep(h3),
.ai-md-body :deep(h4) {
  margin: 16px 0 10px;
  font-family: var(--font-art), var(--font-sans);
  font-weight: 800;
  letter-spacing: 0.02em;
  line-height: 1.3;
  /* v12：覆盖全局七彩渐变，改用实心色 + text-shadow 提升小字清晰度。
     这是聊天泡专属重置 —— 全站其他位置的标题仍然保留全局七彩渐变效果。 */
  background-image: none;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  animation: none;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
}
.ai-md-body :deep(h1) {
  font-size: 1.45rem;
  color: #5ee5d9; /* 亮青 */
  border-bottom: 2px solid rgba(54, 216, 180, 0.32);
  padding-bottom: 6px;
}
.ai-md-body :deep(h2) {
  font-size: 1.28rem;
  color: #fcd76a; /* 金黄 */
}
.ai-md-body :deep(h3) {
  font-size: 1.16rem;
  color: #b6f077; /* 嫩绿 */
}
.ai-md-body :deep(h4) {
  font-size: 1.06rem;
  color: #93c5fd; /* 浅蓝 */
}
.ai-md-body :deep(ul),
.ai-md-body :deep(ol) {
  margin: 8px 0 12px 4px;
  padding: 0 0 0 22px;
  list-style-position: outside;
}
.ai-md-body :deep(ul) { list-style-type: disc; }
.ai-md-body :deep(ol) { list-style-type: decimal; }
.ai-md-body :deep(li) {
  margin: 5px 0;
  line-height: 1.62;
  font-weight: 500;
  padding-left: 4px;
}
.ai-md-body :deep(li::marker) { color: var(--gold); font-weight: 700; }
.ai-md-body :deep(li > ul),
.ai-md-body :deep(li > ol) { margin: 4px 0 4px 0; }
.ai-md-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 16px;
  border-left: 4px solid rgba(54, 216, 180, 0.6);
  background: rgba(54, 216, 180, 0.08);
  color: var(--text-soft);
  border-radius: 0 8px 8px 0;
  font-style: italic;
  font-size: 0.98rem;
}
.ai-md-body :deep(blockquote p) { margin: 0; }
.ai-md-body :deep(code):not(pre code) {
  font-family: "Mnemoscape Mono", var(--font-mono);
  background: rgba(0, 0, 0, 0.45);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.92em;
  font-weight: 600;
  color: #fcd76a;
  border: 1px solid rgba(252, 215, 106, 0.22);
}
.ai-md-body :deep(pre) {
  background: rgba(0, 0, 0, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 10px 0;
  position: relative;
}
.ai-md-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: #e8e8f0;
  font-size: 0.92em;
  font-weight: 500;
  font-family: "Mnemoscape Mono", var(--font-mono);
  display: block;
  line-height: 1.55;
}
/* Mermaid 图表容器 — 由 useMarkdown 处理后用 .mermaid 标记，
   AiMascotDock onUpdated 钩子会识别并 mermaid.run() 渲染。 */
.ai-md-body :deep(.mermaid) {
  margin: 10px 0;
  padding: 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  text-align: center;
  overflow-x: auto;
}
.ai-md-body :deep(.mermaid svg) {
  max-width: 100%;
  height: auto;
}
.ai-md-body :deep(a) {
  text-decoration: underline;
  text-underline-offset: 3px;
  font-weight: 600;
}
.ai-md-body :deep(strong) {
  font-weight: 800;
}
.ai-md-body :deep(em) { color: var(--text-soft); font-style: italic; }
.ai-md-body :deep(hr) {
  border: none;
  border-top: 1px dashed rgba(255, 255, 255, 0.18);
  margin: 14px 0;
}
.ai-md-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 0.94em;
}
.ai-md-body :deep(th),
.ai-md-body :deep(td) {
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  text-align: left;
}
.ai-md-body :deep(th) { background: rgba(255, 255, 255, 0.06); font-weight: 700; color: #5ee5d9; }
.ai-msg__caret {
  display: inline-block;
  animation: caret 1s steps(2) infinite;
  color: #5ee5d9;
  margin-left: 2px;
}
@keyframes caret { from { opacity: 1; } to { opacity: 0.2; } }

/* Plan list */
.ai-plan {
  margin: 0 0 10px;
  padding: 10px 12px;
  list-style: none;
  background: rgba(8,12,16,0.55);
  border-radius: 10px;
  border: 1px dashed rgba(255,255,255,0.1);
  display: flex; flex-direction: column; gap: 6px;
}
.ai-plan__item {
  display: inline-flex; align-items: center; gap: 8px;
  font-size: 0.82rem; color: rgba(255,255,255,0.78);
}
.ai-plan__icon { font-family: var(--font-mono); width: 16px; text-align: center; opacity: 0.85; }
.ai-plan__item--done { color: #b6f077; }
.ai-plan__item--done .ai-plan__label { text-decoration: line-through; opacity: 0.85; }
.ai-plan__item--running { color: #5ee5d9; }
.ai-plan__item--running .ai-plan__icon { animation: running 1s linear infinite; display: inline-block; }
@keyframes running { from { transform: rotate(0); } to { transform: rotate(360deg); } }

/* Tool calls */
.ai-toolstack { display: flex; flex-direction: column; gap: 4px; margin: 0 0 8px; }
.ai-tool { background: rgba(5,8,11,0.45); border-radius: 8px; border: 1px solid rgba(255,255,255,0.05); }
.ai-tool__row {
  display: flex; align-items: center; gap: 8px;
  width: 100%;
  padding: 6px 10px;
  background: transparent; border: none; cursor: pointer;
  color: rgba(180,200,220,0.78);
  font-size: 0.74rem;
  text-align: left;
  font-family: var(--font-mono);
}
.ai-tool__row:hover { background: rgba(255,255,255,0.04); }
.ai-tool__gear { display: inline-grid; place-items: center; opacity: 0.85; }
.ai-tool__gear--spin svg { animation: running 1s linear infinite; }
.ai-tool__text { flex: 1; }
.ai-tool__name { opacity: 0.6; font-size: 0.7rem; }
.ai-tool--done .ai-tool__text { color: rgba(120,200,180,0.92); }
.ai-tool__detail {
  margin: 0;
  padding: 8px 10px;
  font-size: 0.7rem;
  background: rgba(0,0,0,0.4);
  color: #cdd5e0;
  white-space: pre-wrap;
  border-top: 1px solid rgba(255,255,255,0.05);
  font-family: var(--font-mono);
}

/* Multimodal attachments */
.ai-attachments {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
}
.ai-attach {
  display: flex; flex-direction: column;
  background: rgba(0,0,0,0.3);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 10px;
  overflow: hidden;
  transition: transform 240ms ease, border-color 200ms ease;
  /* 占位渐变：图片加载失败 / blob 失效时仍能看到一个有形状的卡片，
   *  而不是完全透明。 */
  background-image: linear-gradient(135deg, rgba(54,216,180,0.08), rgba(192,132,252,0.10));
}
.ai-attach:hover { transform: translateY(-2px); border-color: rgba(54,216,180,0.45); }
.ai-attach img {
  width: 100%;
  height: 88px;
  object-fit: cover;
  display: block;
  background: rgba(8,10,14,0.55);
}
/* 图片加载失败兜底：用 ::before 显示一个图标 + 文案，避免容器纯透明 */
.ai-attach--broken {
  min-height: 88px;
  display: grid;
  place-items: center;
  position: relative;
}
.ai-attach--broken::before {
  content: '🖼️';
  font-size: 1.4rem;
  opacity: 0.7;
}
.ai-attach__caption {
  font-size: 0.7rem;
  padding: 6px 8px;
  color: rgba(255,255,255,0.78);
  letter-spacing: 0.01em;
}

/* Empty state */
.ai-empty { display: flex; flex-direction: column; gap: 6px; opacity: 0.7; text-align: center; padding: 30px 0; }
.ai-empty__title { font-size: 0.95rem; font-weight: 600; }
.ai-empty__hint { font-size: 0.78rem; color: rgba(255,255,255,0.55); }

/* Hint chips */
.ai-hints {
  padding: 0 16px 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ai-hint {
  padding: 5px 10px;
  font-size: 0.74rem;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.08);
  color: rgba(255,255,255,0.78);
  border-radius: 999px;
  cursor: pointer;
  transition: all 160ms ease;
}
.ai-hint:hover { background: rgba(54,216,180,0.18); color: #f3f5fa; border-color: rgba(54,216,180,0.42); }
.ai-hint:disabled { opacity: 0.4; cursor: not-allowed; }

/* ============ 输入框：太极双鱼"双灯环绕" ============
 * 输入框本体保持完全静止；两条发光"鱼"分别沿椭圆轨道反向追逐，
 * 用 motion-path（offset-path）让位置 + 法线方向一并演进，避免视觉上"整圈在转"。
 */
.ai-input {
  position: relative;
  margin: 0 14px 14px;
  padding: 8px 8px 8px 12px;
  border-radius: 14px;
  background: rgba(14,18,24,0.85);
  border: 1px solid rgba(255,255,255,0.1);
  display: flex;
  align-items: flex-end;
  gap: 8px;
  isolation: isolate;
}
/* 一个"轨道层" — 仅承载两条鱼，本身不旋转、不变形 */
.ai-input__taiji {
  position: absolute;
  inset: -3px;
  border-radius: 17px;
  pointer-events: none;
  z-index: -1;
  opacity: 0;
  transition: opacity 320ms ease;
  overflow: visible;
}
.ai-input--active .ai-input__taiji {
  opacity: 1;
}

/* 单条"鱼" — 一抹细长发光弧，骑在输入框的圆角矩形周长上 */
.ai-input__fish {
  position: absolute;
  width: 60px;
  height: 6px;
  border-radius: 999px;
  filter: blur(1px) drop-shadow(0 0 6px currentColor);
  /* 让鱼沿 input 边框跑：offset-path = 跟外框等大的圆角矩形 */
  offset-path: inset(0 round 17px);
  -webkit-offset-path: inset(0 round 17px);
  offset-rotate: auto;
  -webkit-offset-rotate: auto;
  top: 0;
  left: 0;
  /* 提示 ↓: 用 background-image 做"鱼形"渐变 — 头亮尾散 */
}
.ai-input__fish--yang {
  color: #5ee5d9;
  background: linear-gradient(90deg,
    rgba(94,229,217,0) 0%,
    rgba(94,229,217,0.35) 30%,
    #5ee5d9 70%,
    #b6f077 100%);
  animation: fishOrbit 3.4s linear infinite;
}
.ai-input__fish--yin {
  color: #c084fc;
  background: linear-gradient(90deg,
    rgba(192,132,252,0) 0%,
    rgba(192,132,252,0.35) 30%,
    #c084fc 70%,
    #ffd76a 100%);
  /* 反向 + 半圈相位差 = 双鱼互追的"太极" */
  animation: fishOrbitReverse 3.4s linear infinite;
}
@keyframes fishOrbit {
  from { offset-distance: 0%; }
  to   { offset-distance: 100%; }
}
@keyframes fishOrbitReverse {
  from { offset-distance: 50%; }
  to   { offset-distance: 150%; }
}

/* 兜底 — 不支持 offset-path 的浏览器降级为静态彩色边光（避免出现转的输入框） */
@supports not ((offset-path: inset(0 round 17px)) or (-webkit-offset-path: inset(0 round 17px))) {
  .ai-input__fish { display: none; }
  .ai-input--active .ai-input__taiji {
    box-shadow:
      0 0 14px rgba(94,229,217,0.45),
      0 0 28px rgba(192,132,252,0.32);
  }
}

.ai-input__field {
  flex: 1;
  background: transparent;
  border: none;
  color: #f3f5fa;
  font-size: 0.88rem;
  line-height: 1.45;
  resize: none;
  max-height: 120px;
  outline: none;
  padding: 4px 0;
  font-family: inherit;
}
.ai-input__field::placeholder { color: rgba(255,255,255,0.4); }

.ai-send {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 12px;
  background: linear-gradient(135deg, #36d8b4, #b6f077);
  color: #052017;
  font-weight: 600;
  font-size: 0.8rem;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  transition: filter 160ms ease, transform 160ms ease;
}
.ai-send:hover:not(:disabled) { filter: brightness(1.08); transform: translateY(-1px); }
.ai-send:disabled { opacity: 0.55; cursor: not-allowed; }

/* ============ 多模态附件队列 ============ */
.ai-attach-btn {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin-right: 4px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  border-radius: 8px;
  color: var(--text-soft, rgba(255, 255, 255, 0.7));
  cursor: pointer;
  transition: all 160ms ease;
  flex-shrink: 0;
}
.ai-attach-btn:hover:not(:disabled) {
  border-color: rgba(54, 216, 180, 0.55);
  color: #36d8b4;
  background: rgba(54, 216, 180, 0.08);
}
.ai-attach-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.ai-attach-queue {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 14px 6px;
}
.ai-attach-chip {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 22px 4px 4px;
  background: rgba(14, 17, 22, 0.65);
  border: 1px solid var(--border, rgba(255, 255, 255, 0.1));
  border-radius: 8px;
  font-size: 0.72rem;
  color: var(--text-soft, rgba(255, 255, 255, 0.78));
  max-width: 180px;
}
.ai-attach-chip img {
  width: 28px;
  height: 28px;
  object-fit: cover;
  border-radius: 5px;
  flex-shrink: 0;
}
.ai-attach-chip__file { font-size: 1.1rem; padding: 0 4px; }
.ai-attach-chip__name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 110px;
}
.ai-attach-chip__close {
  position: absolute;
  top: 50%;
  right: 4px;
  transform: translateY(-50%);
  width: 16px;
  height: 16px;
  border: none;
  background: transparent;
  color: var(--text-muted, rgba(255, 255, 255, 0.5));
  cursor: pointer;
  font-size: 0.96rem;
  line-height: 1;
  border-radius: 50%;
  transition: all 160ms ease;
}
.ai-attach-chip__close:hover {
  background: rgba(248, 113, 113, 0.18);
  color: #f87171;
}
.ai-attach-chip__spin {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(54, 216, 180, 0.25);
  border-top-color: #36d8b4;
  border-radius: 50%;
  animation: ai-attach-spin 0.7s linear infinite;
  flex-shrink: 0;
}
@keyframes ai-attach-spin { to { transform: rotate(360deg); } }
.ai-attach-chip--uploading { border-color: rgba(54, 216, 180, 0.45); }
.ai-attach-chip--error {
  border-color: rgba(248, 113, 113, 0.55);
  background: rgba(248, 113, 113, 0.08);
}
.ai-attach-chip__err {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #f87171;
  color: #1a0808;
  font-size: 0.7rem;
  font-weight: 800;
  flex-shrink: 0;
}

/* 多模态视觉前置标签：贴在 assistant 消息上方 */
.ai-vision-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px 3px 8px;
  margin: 0 0 8px;
  font-size: 0.7rem;
  color: #052017;
  background: linear-gradient(135deg, #c084fc, #6cc6ff);
  border-radius: 999px;
  /* 限制最大宽度并让超长 model id 省略；外层泡再多层也撑不爆 */
  max-width: 100%;
  overflow: hidden;
  letter-spacing: 0.02em;
  box-shadow: 0 2px 8px rgba(192, 132, 252, 0.28);
}
.ai-vision-badge svg { color: #052017; flex-shrink: 0; }
.ai-vision-badge > span:not(.ai-vision-badge__model) { flex-shrink: 0; }
.ai-vision-badge__model {
  font-family: var(--font-mono, monospace);
  font-size: 0.65rem;
  opacity: 0.85;
  /* model id 可能很长（如 meta/llama-3.2-90b-vision-instruct），单行省略 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

/* ============ 进出场动效 ============ */
.dock-panel-enter-active, .dock-panel-leave-active {
  transition: opacity 220ms ease, transform 280ms cubic-bezier(0.16, 1, 0.3, 1);
}
.dock-panel-enter-from {
  opacity: 0; transform: translateY(12px) scale(0.96);
}
.dock-panel-leave-to {
  opacity: 0; transform: translateY(12px) scale(0.96);
}

/* ============ 响应式 ============ */
@media (max-width: 720px) {
  .ai-dock { right: 14px; bottom: 14px; flex-direction: column; align-items: flex-end; }
  .ai-panel { width: min(380px, calc(100vw - 30px)); max-height: 78vh; }
  .ai-orb { width: 60px; height: 60px; }
  .ai-orb__particles i { transform: rotate(calc(var(--idx) * 40deg)) translate(22px); }
}
</style>
