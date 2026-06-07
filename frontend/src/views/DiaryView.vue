<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import axios from 'axios'

const auth = useAuthStore()

const generating = ref(false)
const diary = ref<any>(null)
const error = ref('')

const filters = ref({
  year: null as number | null,
  season: '',
  emotion: '',
  limit: 10,
})

const seasons = ['spring', 'summer', 'autumn', 'winter']
const emotions = ['joy', 'nostalgia', 'calm', 'melancholy', 'gratitude']

async function generate() {
  generating.value = true
  error.value = ''
  diary.value = null

  try {
    const response = await axios.post('/api/v1/diary/generate', filters.value, {
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    diary.value = response.data.data
  } catch (e: any) {
    error.value = e.response?.data?.message || '生成失败，请稍后重试'
  } finally {
    generating.value = false
  }
}

function downloadMarkdown() {
  if (!diary.value) return
  const content = `# ${diary.value.title}\n\n${diary.value.content}\n\n---\n生成时间：${diary.value.date}\n`
  const blob = new Blob([content], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `diary-${diary.value.date}.md`
  a.click()
  URL.revokeObjectURL(url)
}

function copyToClipboard() {
  if (!diary.value) return
  const content = `# ${diary.value.title}\n\n${diary.value.content}`
  navigator.clipboard.writeText(content).then(() => {
    alert('已复制到剪贴板')
  })
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section class="hero-card" style="margin-bottom: 24px;">
      <div class="stack stack--lg">
        <p class="eyebrow">AI DIARY GENERATOR · AI 日记</p>
        <h1 class="display-title text-gradient">记忆日记生成器</h1>
        <p class="lead">AI 分析你的记忆，创作富有文学性和情感深度的日记。每一篇都是独一无二的时光诗篇。</p>
      </div>
    </section>

    <div class="diary-layout">
      <aside class="diary-sidebar section-card">
        <h3 class="section-title">筛选条件</h3>

        <div class="form-group">
          <label class="form-label">年份</label>
          <input
            v-model.number="filters.year"
            type="number"
            class="form-input"
            placeholder="留空表示全部"
            min="1900"
            max="2100"
          />
        </div>

        <div class="form-group">
          <label class="form-label">季节</label>
          <select v-model="filters.season" class="form-input">
            <option value="">全部</option>
            <option v-for="s in seasons" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>

        <div class="form-group">
          <label class="form-label">情感</label>
          <select v-model="filters.emotion" class="form-input">
            <option value="">全部</option>
            <option v-for="e in emotions" :key="e" :value="e">{{ e }}</option>
          </select>
        </div>

        <div class="form-group">
          <label class="form-label">记忆数量</label>
          <input
            v-model.number="filters.limit"
            type="number"
            class="form-input"
            min="1"
            max="20"
          />
        </div>

        <button
          class="button button--primary"
          :disabled="generating"
          @click="generate"
          style="width: 100%;"
        >
          {{ generating ? '生成中...' : '生成日记' }}
        </button>
      </aside>

      <main class="diary-main section-card">
        <div v-if="generating" class="diary-loading">
          <div class="spinner"></div>
          <p>AI 正在创作中，请稍候...</p>
        </div>

        <div v-else-if="error" class="diary-error">
          <p>{{ error }}</p>
        </div>

        <div v-else-if="diary" class="diary-content">
          <header class="diary-header">
            <h2 class="diary-title">{{ diary.title }}</h2>
            <p class="diary-meta">
              <span>{{ diary.date }}</span>
              <span>·</span>
              <span>{{ diary.mood }}</span>
              <span>·</span>
              <span>{{ diary.memoryIds.length }} 条记忆</span>
            </p>
          </header>

          <div class="diary-body" v-html="formatMarkdown(diary.content)"></div>

          <footer class="diary-actions">
            <button class="button button--ghost" @click="copyToClipboard">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
              复制
            </button>
            <button class="button button--ghost" @click="downloadMarkdown">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="7 10 12 15 17 10"></polyline>
                <line x1="12" y1="15" x2="12" y2="3"></line>
              </svg>
              下载 Markdown
            </button>
          </footer>
        </div>

        <div v-else class="diary-empty">
          <p>点击左侧"生成日记"按钮，AI 将为你创作一篇独特的记忆日记。</p>
        </div>
      </main>
    </div>
  </div>
</template>

<script lang="ts">
function formatMarkdown(text: string): string {
  return text
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/\n\n/g, '</p><p>')
    .replace(/^(.+)$/gm, '<p>$1</p>')
}
</script>

<style scoped>
.diary-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 24px;
  align-items: start;
}

.diary-sidebar {
  position: sticky;
  top: 24px;
}

.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  margin-bottom: 6px;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-soft);
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.03);
  color: var(--text);
  font-size: 0.9rem;
}

.diary-main {
  min-height: 600px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.diary-loading,
.diary-error,
.diary-empty {
  text-align: center;
  color: var(--text-muted);
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(108, 99, 255, 0.2);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.diary-content {
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
}

.diary-header {
  margin-bottom: 32px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--border);
}

.diary-title {
  margin: 0 0 12px;
  font-size: 2rem;
  font-weight: 700;
  color: var(--text);
}

.diary-meta {
  margin: 0;
  font-size: 0.85rem;
  color: var(--text-muted);
  display: flex;
  gap: 8px;
}

.diary-body {
  line-height: 1.8;
  font-size: 1.05rem;
  color: var(--text-soft);
  margin-bottom: 32px;
}

.diary-body :deep(p) {
  margin: 0 0 16px;
}

.diary-actions {
  display: flex;
  gap: 12px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
}

@media (max-width: 768px) {
  .diary-layout {
    grid-template-columns: 1fr;
  }

  .diary-sidebar {
    position: static;
  }
}
</style>
