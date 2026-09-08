<script setup lang="ts">
/**
 * CoverPickerModal — 全屏对话框形式的封面选择器。
 *
 * 三 tab 架构：
 *   1. 内置画库 (built-in)：来自 src/assets/media-catalog
 *   2. 资源池 (library)：来自 useDynamicMedia（MinIO + 本地 resource/）
 *   3. 上传图片 (upload)：拖拽 / 选择文件 → POST /assets/upload → 拿到 presigned URL
 *
 * 设计要点：
 *   - 大尺寸 modal，缩略图大、间距宽，告别"窄网格"体验
 *   - 严格去重：以 stripQuery(url) 为 key 去除 presigned 签名 query 后比对
 *     —— 内置 + 资源池可能指向同一文件（资源被预置进 builtIn 后又出现在 MinIO）
 *   - 上传成功立即把新资源 push 进 dynamicMedia.state，同时刷新一次拉取
 *   - 模态打开时禁用 body 滚动，按 Esc 关闭
 *   - 选中即 emit('select', url)；本组件不处理"父表单回填"
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import client from '../../api/client'
import { generatedGallery } from '../../assets/media-catalog'
import { useDynamicMedia } from '../../composables/useDynamicMedia'

const props = defineProps<{
  open: boolean
  /** 当前已选中的封面 URL（用于高亮 + 滚动定位） */
  selectedUrl?: string
}>()
const emit = defineEmits<{
  select: [url: string]
  close: []
}>()

const { t } = useI18n()
const dynamicMedia = useDynamicMedia()

type CoverItem = {
  key: string
  src: string
  thumb: string
  origin: string
  badge: 'builtIn' | 'minio' | 'local' | 'uploaded'
}

type Tab = 'builtIn' | 'library' | 'upload'
const activeTab = ref<Tab>('builtIn')
const searchQuery = ref('')

// 上传过程刚拿到的本地新增（在 dynamicMedia.refresh() 完成前先临时显示）
const justUploaded = ref<CoverItem[]>([])

// 上传状态
const uploadDragOver = ref(false)
const uploadBusy = ref(false)
const uploadError = ref('')
const uploadSuccess = ref('')
const fileInputEl = ref<HTMLInputElement | null>(null)

// 关闭/打开时副作用：清搜索、锁定 body、监听 Esc
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && props.open) emit('close')
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      searchQuery.value = ''
      uploadError.value = ''
      uploadSuccess.value = ''
      activeTab.value = 'builtIn'
      document.body.style.overflow = 'hidden'
      // 进入时刷一次资源池，让最近上传到 MinIO 的图能立刻看到
      void dynamicMedia.refresh()
    } else {
      document.body.style.overflow = ''
    }
  },
)

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})

/** 去除 URL 的 query / hash 用于去重，避免 MinIO presigned 每次签名变化导致看似"重复" */
function stripQuery(url: string): string {
  const idx = url.indexOf('?')
  return idx >= 0 ? url.slice(0, idx) : url
}

const builtInItems = computed<CoverItem[]>(() =>
  generatedGallery.map((v) => ({
    key: `local:${v.number ?? v.src}`,
    src: v.src,
    thumb: v.thumb || v.src,
    origin: v.origin,
    badge: 'builtIn' as const,
  })),
)

const libraryItems = computed<CoverItem[]>(() => {
  const dyn = [...dynamicMedia.state.photos, ...dynamicMedia.state.gifs]
  // 资源池条目：基于内置已存在的 src 做一次去重（用 stripQuery）
  const builtSrcs = new Set(builtInItems.value.map((b) => stripQuery(b.src)))
  const seen = new Set<string>()
  const items: CoverItem[] = []
  for (const a of dyn) {
    const key = stripQuery(a.url)
    if (builtSrcs.has(key) || seen.has(key)) continue
    seen.add(key)
    items.push({
      key: `dyn:${a.name}:${a.lastModified}`,
      src: a.url,
      thumb: a.url,
      origin: a.name.replace(/\.[^.]+$/, ''),
      badge: a.url.includes('X-Amz-Signature') ? 'minio' : 'local',
    })
  }
  // 把临时"刚上传"放在最前
  return [...justUploaded.value, ...items]
})

function filtered(items: CoverItem[]): CoverItem[] {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return items
  return items.filter((i) => i.origin.toLowerCase().includes(q))
}

const filteredBuiltIn = computed(() => filtered(builtInItems.value))
const filteredLibrary = computed(() => filtered(libraryItems.value))

function handleSelect(url: string) {
  emit('select', url)
  emit('close')
}

function isCurrent(url: string): boolean {
  if (!props.selectedUrl) return false
  return stripQuery(props.selectedUrl) === stripQuery(url)
}

/* ============ 上传相关 ============ */
const MAX_MB = 8
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']

function pickFile() {
  fileInputEl.value?.click()
}

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) void uploadFile(file)
  target.value = ''
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  uploadDragOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) void uploadFile(file)
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  uploadDragOver.value = true
}

function onDragLeave() {
  uploadDragOver.value = false
}

async function uploadFile(file: File) {
  uploadError.value = ''
  uploadSuccess.value = ''

  if (!ALLOWED_TYPES.includes(file.type)) {
    uploadError.value = t('memory.builder.coverPicker.upload.errorType')
    return
  }
  if (file.size > MAX_MB * 1024 * 1024) {
    uploadError.value = t('memory.builder.coverPicker.upload.errorSize', { mb: MAX_MB })
    return
  }

  uploadBusy.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const { data } = await client.post('/assets/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    const url: string | undefined = data?.data?.url
    if (!url) throw new Error('No url returned')

    // 推到"刚上传"队列，让 library tab 立刻看到
    justUploaded.value.unshift({
      key: `uploaded:${file.name}:${Date.now()}`,
      src: url,
      thumb: url,
      origin: file.name.replace(/\.[^.]+$/, ''),
      badge: 'uploaded',
    })
    uploadSuccess.value = t('memory.builder.coverPicker.upload.success')

    // 触发一次资源池刷新（异步，不阻塞用户）
    void dynamicMedia.refresh()

    // 自动切到资源池让用户立刻看到刚上传的图
    activeTab.value = 'library'
  } catch (e) {
    console.error('cover upload failed', e)
    uploadError.value = t('memory.builder.coverPicker.upload.errorNetwork')
  } finally {
    uploadBusy.value = false
  }
}
</script>

<template>
  <transition name="cover-picker">
    <div
      v-if="open"
      class="cover-picker-overlay"
      role="dialog"
      aria-modal="true"
      :aria-label="t('memory.builder.coverPicker.title')"
      @click.self="emit('close')"
    >
      <div class="cover-picker">
        <header class="cover-picker__head">
          <div class="stack">
            <h2 class="section-title">{{ t('memory.builder.coverPicker.title') }}</h2>
            <p class="subtitle" style="margin: 0;">{{ t('memory.builder.coverPicker.subtitle') }}</p>
          </div>
          <button
            type="button"
            class="cover-picker__close"
            :aria-label="t('memory.builder.coverPicker.actions.close')"
            @click="emit('close')"
          >×</button>
        </header>

        <div class="cover-picker__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            class="cover-picker__tab"
            :class="{ 'cover-picker__tab--active': activeTab === 'builtIn' }"
            :aria-selected="activeTab === 'builtIn'"
            @click="activeTab = 'builtIn'"
          >{{ t('memory.builder.coverPicker.tabs.builtIn') }}</button>
          <button
            type="button"
            role="tab"
            class="cover-picker__tab"
            :class="{ 'cover-picker__tab--active': activeTab === 'library' }"
            :aria-selected="activeTab === 'library'"
            @click="activeTab = 'library'"
          >{{ t('memory.builder.coverPicker.tabs.library') }}</button>
          <button
            type="button"
            role="tab"
            class="cover-picker__tab"
            :class="{ 'cover-picker__tab--active': activeTab === 'upload' }"
            :aria-selected="activeTab === 'upload'"
            @click="activeTab = 'upload'"
          >{{ t('memory.builder.coverPicker.tabs.upload') }}</button>
        </div>

        <!-- 搜索框：仅 built-in / library 可用 -->
        <div v-if="activeTab !== 'upload'" class="cover-picker__search">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
            <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.6" />
            <path d="m20 20-3.5-3.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          <input
            v-model="searchQuery"
            class="input"
            type="search"
            :placeholder="t('memory.builder.coverPicker.search')"
          />
        </div>

        <!-- Tab 内容 -->
        <div class="cover-picker__body">
          <!-- 内置画库 -->
          <div v-show="activeTab === 'builtIn'" class="cover-grid">
            <button
              v-for="opt in filteredBuiltIn"
              :key="opt.key"
              type="button"
              :class="['cover-card', isCurrent(opt.src) ? 'cover-card--active' : '']"
              :title="opt.origin"
              @click="handleSelect(opt.src)"
            >
              <img :src="opt.thumb" :alt="opt.origin" loading="lazy" />
              <span class="cover-card__overlay">
                <span class="cover-card__name">{{ opt.origin }}</span>
              </span>
              <span class="cover-card__badge cover-card__badge--builtIn">
                {{ t('memory.builder.coverPicker.badge.builtIn') }}
              </span>
              <span v-if="isCurrent(opt.src)" class="cover-card__check">✓</span>
            </button>
            <p v-if="filteredBuiltIn.length === 0" class="cover-empty">
              {{ t('memory.builder.coverPicker.empty.search', { q: searchQuery }) }}
            </p>
          </div>

          <!-- 资源池 -->
          <div v-show="activeTab === 'library'" class="cover-grid">
            <button
              v-for="opt in filteredLibrary"
              :key="opt.key"
              type="button"
              :class="['cover-card', isCurrent(opt.src) ? 'cover-card--active' : '']"
              :title="opt.origin"
              @click="handleSelect(opt.src)"
            >
              <img :src="opt.thumb" :alt="opt.origin" loading="lazy" />
              <span class="cover-card__overlay">
                <span class="cover-card__name">{{ opt.origin }}</span>
              </span>
              <span :class="['cover-card__badge', `cover-card__badge--${opt.badge}`]">
                {{ t(`memory.builder.coverPicker.badge.${opt.badge}`) }}
              </span>
              <span v-if="isCurrent(opt.src)" class="cover-card__check">✓</span>
            </button>
            <p v-if="filteredLibrary.length === 0 && !searchQuery" class="cover-empty">
              {{ t('memory.builder.coverPicker.empty.library') }}
            </p>
            <p v-else-if="filteredLibrary.length === 0" class="cover-empty">
              {{ t('memory.builder.coverPicker.empty.search', { q: searchQuery }) }}
            </p>
          </div>

          <!-- 上传 -->
          <div v-show="activeTab === 'upload'" class="cover-upload">
            <div
              :class="['cover-upload__drop', uploadDragOver ? 'cover-upload__drop--over' : '']"
              @drop="onDrop"
              @dragover="onDragOver"
              @dragleave="onDragLeave"
            >
              <svg viewBox="0 0 24 24" width="42" height="42" fill="none" aria-hidden="true">
                <path d="M12 16V4m0 0-4 4m4-4 4 4M5 20h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <p class="cover-upload__hint">{{ t('memory.builder.coverPicker.upload.drag') }}</p>
              <button
                type="button"
                class="button button--primary"
                :disabled="uploadBusy"
                @click="pickFile"
              >
                <span v-if="uploadBusy" class="auth-spinner" aria-hidden="true"></span>
                <span>{{ uploadBusy
                  ? t('memory.builder.coverPicker.upload.uploading')
                  : t('memory.builder.coverPicker.upload.btn') }}</span>
              </button>
              <input
                ref="fileInputEl"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                hidden
                @change="onFileChange"
              />
            </div>

            <transition name="alert">
              <p v-if="uploadError" class="status-pill status-pill--danger" role="alert">
                {{ uploadError }}
              </p>
            </transition>
            <transition name="alert">
              <p v-if="uploadSuccess" class="status-pill status-pill--success" role="status">
                {{ uploadSuccess }}
              </p>
            </transition>
          </div>
        </div>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.cover-picker-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  place-items: center;
  background: rgba(2, 6, 23, 0.62);
  backdrop-filter: blur(14px);
  padding: 32px 16px;
}

.cover-picker {
  width: min(960px, 100%);
  max-height: min(86vh, 820px);
  background: rgba(8, 10, 14, 0.96);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.cover-picker__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 22px 26px 14px;
  gap: 16px;
  border-bottom: 1px solid var(--border);
}

.cover-picker__close {
  background: none;
  border: 1px solid var(--border);
  color: var(--text-soft);
  width: 36px;
  height: 36px;
  border-radius: var(--radius-full);
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  transition: all 200ms ease;
}
.cover-picker__close:hover {
  border-color: var(--border-accent);
  color: var(--text);
  transform: rotate(90deg);
}

.cover-picker__tabs {
  display: flex;
  gap: 4px;
  padding: 12px 26px;
  border-bottom: 1px solid var(--border);
  background: rgba(14, 17, 22, 0.5);
}

.cover-picker__tab {
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-muted);
  padding: 7px 16px;
  border-radius: var(--radius-full);
  font-size: 0.86rem;
  cursor: pointer;
  transition: all 200ms ease;
}
.cover-picker__tab:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.05);
}
.cover-picker__tab--active {
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
  font-weight: 600;
  border-color: var(--primary);
  box-shadow: 0 0 12px rgba(54, 216, 180, 0.45);
}

.cover-picker__search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 26px;
  border-bottom: 1px solid var(--border);
  background: rgba(14, 17, 22, 0.35);
  color: var(--text-muted);
}
.cover-picker__search .input {
  flex: 1;
}

.cover-picker__body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 18px 26px 26px;
  background: rgba(8, 10, 14, 0.5);
}

/* ============ Cover grid ============ */
.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 14px;
}

.cover-card {
  position: relative;
  aspect-ratio: 4 / 3;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.6);
  overflow: hidden;
  cursor: pointer;
  padding: 0;
  transition: transform 220ms cubic-bezier(0.165, 0.84, 0.44, 1),
              box-shadow 220ms ease,
              border-color 220ms ease;
}
.cover-card:hover {
  transform: translateY(-3px) scale(1.015);
  border-color: var(--border-accent);
  box-shadow: 0 8px 24px rgba(54, 216, 180, 0.18);
}
.cover-card--active {
  border-color: var(--primary);
  box-shadow: 0 0 18px rgba(54, 216, 180, 0.42), inset 0 0 0 1px var(--primary);
}

.cover-card img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-card__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: 10px 12px;
  background: linear-gradient(180deg, transparent 0%, transparent 55%, rgba(8, 10, 14, 0.86) 100%);
  pointer-events: none;
}
.cover-card__name {
  font-size: 0.78rem;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.7);
}

.cover-card__badge {
  position: absolute;
  top: 8px;
  left: 8px;
  font-size: 0.62rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  color: #052017;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
}
.cover-card__badge--builtIn  { background: linear-gradient(135deg, #fde68a, #f59e0b); }
.cover-card__badge--minio    { background: linear-gradient(135deg, #38bdf8, #6366f1); color: #fff; }
.cover-card__badge--local    { background: linear-gradient(135deg, #94a3b8, #64748b); color: #fff; }
.cover-card__badge--uploaded { background: linear-gradient(135deg, #34d399, #06b6d4); }

.cover-card__check {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--primary);
  color: #052017;
  font-size: 0.78rem;
  font-weight: 800;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 8px rgba(54, 216, 180, 0.55);
}

.cover-empty {
  grid-column: 1 / -1;
  text-align: center;
  color: var(--text-muted);
  font-size: 0.88rem;
  padding: 32px 12px;
}

/* ============ Upload tab ============ */
.cover-upload {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: stretch;
}
.cover-upload__drop {
  border: 1.5px dashed var(--border);
  border-radius: var(--radius-md);
  padding: 44px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  color: var(--text-muted);
  background: rgba(14, 17, 22, 0.4);
  transition: all 220ms ease;
}
.cover-upload__drop--over {
  border-color: var(--primary);
  background: rgba(54, 216, 180, 0.08);
  color: var(--text);
}
.cover-upload__hint {
  font-size: 0.92rem;
  text-align: center;
  margin: 0;
  max-width: 360px;
}

/* ============ Animations ============ */
.cover-picker-enter-active,
.cover-picker-leave-active {
  transition: opacity 220ms ease;
}
.cover-picker-enter-active .cover-picker,
.cover-picker-leave-active .cover-picker {
  transition: transform 240ms cubic-bezier(0.34, 1.56, 0.64, 1), opacity 240ms ease;
}
.cover-picker-enter-from,
.cover-picker-leave-to {
  opacity: 0;
}
.cover-picker-enter-from .cover-picker,
.cover-picker-leave-to .cover-picker {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
}

.alert-enter-active,
.alert-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}
.alert-enter-from,
.alert-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.auth-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(5, 32, 23, 0.32);
  border-top-color: #052017;
  border-radius: 50%;
  animation: auth-spin 0.7s linear infinite;
  margin-right: 8px;
  vertical-align: -2px;
}
@keyframes auth-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 720px) {
  .cover-picker { max-height: 92vh; }
  .cover-picker__head { padding: 18px 18px 12px; }
  .cover-picker__tabs,
  .cover-picker__search,
  .cover-picker__body { padding-left: 18px; padding-right: 18px; }
  .cover-grid { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; }
}
</style>
