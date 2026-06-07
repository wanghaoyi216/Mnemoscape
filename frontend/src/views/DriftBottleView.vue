<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useMemoryStore } from '../stores/memory'
import axios from 'axios'

const auth = useAuthStore()
const memoryStore = useMemoryStore()

const activeTab = ref<'throw' | 'pick' | 'my' | 'picked'>('pick')
const throwing = ref(false)
const picking = ref(false)
const selectedMemory = ref('')
const snippet = ref('')
const pickedBottle = ref<any>(null)
const myBottles = ref<any[]>([])
const pickedBottles = ref<any[]>([])

onMounted(async () => {
  if (!memoryStore.memories.length) {
    await memoryStore.fetchList(0, 20)
  }
  loadMyBottles()
  loadPickedBottles()
})

async function throwBottle() {
  if (!selectedMemory.value || !snippet.value) return
  throwing.value = true
  try {
    await axios.post('/api/v1/bottles/throw', {
      memoryId: selectedMemory.value,
      snippet: snippet.value,
    }, {
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    snippet.value = ''
    selectedMemory.value = ''
    alert('漂流瓶已投入大海！')
    loadMyBottles()
  } catch (e) {
    alert('投掷失败')
  } finally {
    throwing.value = false
  }
}

async function pickBottle() {
  picking.value = true
  try {
    const res = await axios.post('/api/v1/bottles/pick', {}, {
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    if (res.data.data) {
      pickedBottle.value = res.data.data
    } else {
      alert('海面上暂时没有漂流瓶')
    }
  } catch (e) {
    alert('捡瓶失败')
  } finally {
    picking.value = false
  }
}

async function loadMyBottles() {
  const res = await axios.get('/api/v1/bottles/my', {
    headers: { Authorization: `Bearer ${auth.token}` },
  })
  myBottles.value = res.data.data || []
}

async function loadPickedBottles() {
  const res = await axios.get('/api/v1/bottles/picked', {
    headers: { Authorization: `Bearer ${auth.token}` },
  })
  pickedBottles.value = res.data.data || []
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section class="hero-card" style="margin-bottom: 24px;">
      <div class="stack stack--lg">
        <p class="eyebrow">DRIFT BOTTLE · 漂流瓶</p>
        <h1 class="display-title text-gradient">记忆漂流瓶</h1>
        <p class="lead">将你的记忆片段装入瓶中，投入时光之海。也许某天，会有陌生人捡起你的故事。</p>
      </div>
    </section>

    <div class="tabs">
      <button class="tab" :class="{ 'tab--active': activeTab === 'pick' }" @click="activeTab = 'pick'">捡瓶子</button>
      <button class="tab" :class="{ 'tab--active': activeTab === 'throw' }" @click="activeTab = 'throw'">投瓶子</button>
      <button class="tab" :class="{ 'tab--active': activeTab === 'my' }" @click="activeTab = 'my'">我的瓶子</button>
      <button class="tab" :class="{ 'tab--active': activeTab === 'picked' }" @click="activeTab = 'picked'">捡到的</button>
    </div>

    <section class="section-card">
      <div v-if="activeTab === 'pick'" class="pick-panel">
        <div v-if="pickedBottle" class="bottle-card">
          <p class="bottle-snippet">{{ pickedBottle.snippet }}</p>
          <div class="bottle-meta">
            <span v-if="pickedBottle.location">{{ pickedBottle.location }}</span>
            <span v-if="pickedBottle.year">{{ pickedBottle.year }}</span>
            <span v-if="pickedBottle.emotion">{{ pickedBottle.emotion }}</span>
          </div>
        </div>
        <div v-else class="empty-state">
          <p>点击下方按钮，从时光之海中捡起一个漂流瓶</p>
        </div>
        <button class="button button--primary" :disabled="picking" @click="pickBottle">
          {{ picking ? '捡瓶中...' : '捡一个瓶子' }}
        </button>
      </div>

      <div v-else-if="activeTab === 'throw'" class="throw-panel">
        <div class="form-group">
          <label class="form-label">选择记忆</label>
          <select v-model="selectedMemory" class="form-input">
            <option value="">请选择</option>
            <option v-for="m in memoryStore.memories" :key="m.id" :value="m.id">{{ m.title }}</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">记忆片段（最多 500 字）</label>
          <textarea v-model="snippet" class="form-textarea" maxlength="500" rows="6"></textarea>
        </div>
        <button class="button button--primary" :disabled="throwing || !selectedMemory || !snippet" @click="throwBottle">
          {{ throwing ? '投掷中...' : '投入大海' }}
        </button>
      </div>

      <div v-else-if="activeTab === 'my'" class="list-panel">
        <div v-if="myBottles.length === 0" class="empty-state">你还没有投掷过漂流瓶</div>
        <div v-for="b in myBottles" :key="b.id" class="bottle-card">
          <p class="bottle-snippet">{{ b.snippet }}</p>
          <div class="bottle-meta">
            <span>{{ b.thrownAt }}</span>
            <span v-if="b.pickedAt">已被捡起</span>
          </div>
        </div>
      </div>

      <div v-else-if="activeTab === 'picked'" class="list-panel">
        <div v-if="pickedBottles.length === 0" class="empty-state">你还没有捡到过漂流瓶</div>
        <div v-for="b in pickedBottles" :key="b.id" class="bottle-card">
          <p class="bottle-snippet">{{ b.snippet }}</p>
          <div class="bottle-meta">
            <span v-if="b.location">{{ b.location }}</span>
            <span v-if="b.year">{{ b.year }}</span>
            <span>{{ b.pickedAt }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 24px;
}

.tab {
  padding: 10px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-soft);
  cursor: pointer;
  transition: all 0.2s ease;
}

.tab:hover {
  border-color: var(--primary);
}

.tab--active {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
}

.pick-panel, .throw-panel {
  display: flex;
  flex-direction: column;
  gap: 24px;
  align-items: center;
}

.bottle-card {
  width: 100%;
  max-width: 600px;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(255, 255, 255, 0.02);
}

.bottle-snippet {
  margin: 0 0 16px;
  font-size: 1.05rem;
  line-height: 1.7;
  color: var(--text);
}

.bottle-meta {
  display: flex;
  gap: 12px;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.empty-state {
  text-align: center;
  color: var(--text-muted);
  padding: 48px 24px;
}

.form-group {
  width: 100%;
  max-width: 600px;
}

.form-label {
  display: block;
  margin-bottom: 8px;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-soft);
}

.form-input, .form-textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.03);
  color: var(--text);
  font-size: 0.95rem;
}

.form-textarea {
  resize: vertical;
  font-family: inherit;
}

.list-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
