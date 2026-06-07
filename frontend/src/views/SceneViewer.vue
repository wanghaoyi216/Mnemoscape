<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { fallbackSceneCover } from '../assets/media-catalog'
import client from '../api/client'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'

echartsUse([CanvasRenderer, GraphChart, TooltipComponent])

const { t } = useI18n()
const route = useRoute()
const memoryStore = useMemoryStore()
const chartRef = ref<any>(null)

const sceneError = ref('')
const neo4jData = ref<{
  entities: Array<{ type: string; name: string }>
  sharedMemories: Array<{ id: string; title: string; sharedEntity: string; entityType: string }>
}>({
  entities: [],
  sharedMemories: []
})
const graphDataReady = ref(false)

const fallbackCover = computed(() => fallbackSceneCover(memoryStore.current?.id).src)
const objectCount = computed(() => neo4jData.value.entities.length)
const fragmentCount = computed(() => memoryStore.currentFragments.length)

async function fetchGraphData(id: string) {
  try {
    const resp = await client.get(`/memories/${id}/graph`)
    if (resp.data?.success && resp.data?.data) {
      neo4jData.value = resp.data.data
      graphDataReady.value = true
    }
  } catch (e) {
    console.error('Failed to fetch Neo4j graph data:', e)
    sceneError.value = t('scene.loadError')
  }
}

onMounted(async () => {
  const id = route.params.id as string
  sceneError.value = ''
  graphDataReady.value = false
  try {
    await memoryStore.fetchOne(id)
    await memoryStore.fetchDrift(id)
    await memoryStore.fetchFragments(id)
    await fetchGraphData(id)
  } catch (e: any) {
    sceneError.value = e.response?.data?.message || t('scene.loadError')
  }
})

function highlightNode(name: string) {
  const chartInstance = chartRef.value?.chart
  if (!chartInstance) return
  chartInstance.dispatchAction({
    type: 'highlight',
    seriesIndex: 0,
    name: name
  })
}

const graphOption = computed(() => {
  if (!graphDataReady.value) return {}

  const nodes: any[] = []
  const links: any[] = []

  // 1. Center node (the current memory)
  const centerTitle = memoryStore.current?.title || '记忆核心'
  nodes.push({
    id: 'center',
    name: centerTitle,
    symbolSize: 38,
    itemStyle: {
      color: {
        type: 'radial',
        x: 0.3, y: 0.3, r: 0.8,
        colorStops: [
          { offset: 0, color: '#36d8b4' },
          { offset: 1, color: '#115e59' }
        ]
      },
      borderColor: '#5ee5d9',
      borderWidth: 2.5,
      shadowColor: 'rgba(54, 216, 180, 0.6)',
      shadowBlur: 14,
    },
    label: {
      show: true,
      position: 'top',
      color: '#fff',
      fontWeight: 'bold',
      fontSize: 12,
      fontFamily: 'Outfit, var(--font-sans), sans-serif'
    }
  })

  // 2. Entity nodes
  const entityColors: Record<string, string> = {
    Person: '#c084fc',
    Location: '#34d399',
    Object: '#fb923c',
    Emotion: '#f43f5e'
  }

  const addedEntities = new Set<string>()
  for (const e of neo4jData.value.entities) {
    if (addedEntities.has(e.name)) continue
    addedEntities.add(e.name)

    nodes.push({
      id: `entity:${e.name}`,
      name: e.name,
      symbolSize: 22,
      itemStyle: {
        color: entityColors[e.type] || '#94a3b8',
        borderColor: 'rgba(255,255,255,0.2)',
        borderWidth: 1.5,
        shadowColor: entityColors[e.type] || '#94a3b8',
        shadowBlur: 8
      },
      label: {
        show: true,
        position: 'bottom',
        color: '#cdd5dd',
        fontSize: 10,
        fontFamily: 'Outfit, var(--font-sans), sans-serif'
      }
    })

    links.push({
      source: centerTitle,
      target: e.name,
      lineStyle: {
        color: 'rgba(255, 255, 255, 0.25)',
        width: 1.5,
        curveness: 0.1
      }
    })
  }

  // 3. Shared memories
  const addedMemories = new Set<string>()
  for (const m of neo4jData.value.sharedMemories) {
    if (addedMemories.has(m.id)) continue
    addedMemories.add(m.id)

    nodes.push({
      id: `memory:${m.id}`,
      name: m.title,
      symbolSize: 26,
      itemStyle: {
        color: '#3b82f6',
        borderColor: 'rgba(255,255,255,0.3)',
        borderWidth: 1.5,
        shadowColor: 'rgba(59, 130, 246, 0.5)',
        shadowBlur: 10
      },
      label: {
        show: true,
        position: 'right',
        color: '#e2e8f0',
        fontSize: 11,
        fontFamily: 'Outfit, var(--font-sans), sans-serif'
      }
    })

    links.push({
      source: m.title,
      target: m.sharedEntity,
      lineStyle: {
        color: 'rgba(59, 130, 246, 0.45)',
        width: 1.2,
        type: 'dashed',
        curveness: 0.15
      }
    })
  }

  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(10, 14, 22, 0.92)',
      borderColor: 'rgba(255,255,255,0.08)',
      borderWidth: 1,
      textStyle: { color: '#e2e8f0' },
      extraCssText: 'backdrop-filter: blur(12px); border-radius: 8px; box-shadow: 0 10px 30px rgba(0,0,0,0.5);',
      formatter: (p: any) => {
        if (p.dataType === 'node') {
          if (p.data.id === 'center') {
            return `<div style="padding:4px 8px; font-family:var(--font-sans), sans-serif;"><strong style="color:#36d8b4;">记忆核心</strong><br/>${p.data.name}</div>`
          }
          if (p.data.id.startsWith('entity:')) {
            return `<div style="padding:4px 8px; font-family:var(--font-sans), sans-serif;"><span style="color:#a78bfa;">提及实体</span><br/><strong>${p.data.name}</strong></div>`
          }
          if (p.data.id.startsWith('memory:')) {
            return `<div style="padding:4px 8px; font-family:var(--font-sans), sans-serif;"><span style="color:#60a5fa;">关联记忆</span><br/><strong>${p.data.name}</strong></div>`
          }
        }
        return ''
      }
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        force: {
          repulsion: 380,
          edgeLength: [120, 220],
          gravity: 0.05
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 5 }
        },
        data: nodes,
        edges: links
      }
    ]
  }
})
</script>

<template>
  <div class="page-shell page-shell--wide">
    <div class="detail-nav-bar" style="margin-bottom: 16px;">
      <RouterLink :to="`/memories/${route.params.id}`" class="button button--ghost" style="backdrop-filter: blur(10px); background: rgba(255, 255, 255, 0.05); display: inline-flex; align-items: center; gap: 8px;">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="19" y1="12" x2="5" y2="12"></line>
          <polyline points="12 19 5 12 12 5"></polyline>
        </svg>
        <span>返回记忆档案</span>
      </RouterLink>
    </div>

    <section
      class="hero-card hero-card--split scene-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.82) 0%, rgba(8,10,14,0.42) 55%, rgba(8,10,14,0.92) 100%), url(${fallbackCover})` }"
    >
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('scene.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ memoryStore.current?.title || t('scene.loading') }}</h1>
        <p class="lead">{{ t('scene.lead') }}</p>
      </div>

      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.objects') }}</span>
          <strong class="metric-card__value">{{ objectCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.fragments') }}</span>
          <strong class="metric-card__value">{{ fragmentCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('scene.metrics.drift') }}</span>
          <strong class="metric-card__value">{{ Math.round((memoryStore.currentDrift?.fadeLevel || 0) * 100) }}%</strong>
        </div>
      </div>
    </section>

    <div v-if="sceneError" class="scene-error empty-state" role="alert" style="margin-top: 24px;">
      <h3 class="empty-state__title">{{ t('scene.errorTitle') }}</h3>
      <p class="empty-state__text">{{ sceneError }}</p>
    </div>

    <div v-else class="graph-layout">
      <main class="graph-main">
        <div class="scene-canvas graph-canvas-wrapper">
          <VChart
            v-if="graphDataReady"
            class="knowledge-graph-chart"
            :option="graphOption"
            autoresize
            ref="chartRef"
          />
          <div v-else class="reconstruct-veil" style="background: rgba(7, 7, 20, 0.95); position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;">
            <div class="reconstruct-veil__copy" style="text-align: center;">
              <p class="eyebrow" style="letter-spacing: 0.15em; color: var(--primary);">正在分析 Neo4j 记忆关联网络...</p>
              <div class="reconstruct-veil__dots" style="display: flex; gap: 8px; justify-content: center; margin-top: 12px;">
                <span class="dot" style="width:8px; height:8px; border-radius:50%; background:var(--primary); animation: dotPulse 1.4s infinite ease-in-out;"></span>
                <span class="dot" style="width:8px; height:8px; border-radius:50%; background:var(--gold); animation: dotPulse 1.4s infinite ease-in-out; animation-delay: 0.2s;"></span>
                <span class="dot" style="width:8px; height:8px; border-radius:50%; background:var(--accent); animation: dotPulse 1.4s infinite ease-in-out; animation-delay: 0.4s;"></span>
              </div>
            </div>
          </div>
        </div>
      </main>

      <aside class="graph-sidebar">
        <div class="sidebar-card glass-panel">
          <h3 class="sidebar-title">记忆多维索引</h3>
          <div class="accordion">
            <!-- Time Category -->
            <details class="accordion-item" open>
              <summary class="accordion-header">
                <span>📅 记忆时间</span>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>
              </summary>
              <div class="accordion-content">
                <div class="meta-item"><strong>年份:</strong> <span>{{ memoryStore.current?.memoryYear || '未知' }} 年</span></div>
                <div class="meta-item"><strong>季节:</strong> <span>{{ memoryStore.current?.memorySeason || '未知' }}</span></div>
                <div class="meta-item"><strong>具体日期:</strong> <span>{{ memoryStore.current?.memoryDate || '未知' }}</span></div>
                <div class="meta-item"><strong>时间段:</strong> <span>{{ memoryStore.current?.memoryTimeOfDay || '未知' }}</span></div>
              </div>
            </details>

            <!-- Location Category -->
            <details class="accordion-item" open>
              <summary class="accordion-header">
                <span>📍 记忆地点</span>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>
              </summary>
              <div class="accordion-content">
                <div class="meta-item"><strong>地点名称:</strong> <span>{{ memoryStore.current?.memoryLocation || '未知' }}</span></div>
                <div v-if="memoryStore.current?.coords" class="meta-item">
                  <strong>坐标定位:</strong> <span>[{{ memoryStore.current.coords[0].toFixed(4) }}, {{ memoryStore.current.coords[1].toFixed(4) }}]</span>
                </div>
              </div>
            </details>

            <!-- Details Category -->
            <details class="accordion-item">
              <summary class="accordion-header">
                <span>📝 详细情况</span>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>
              </summary>
              <div class="accordion-content">
                <div class="meta-desc">{{ memoryStore.current?.description }}</div>
              </div>
            </details>

            <!-- Process Category -->
            <details class="accordion-item">
              <summary class="accordion-header">
                <span>⚡ 记忆流程 (碎片)</span>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>
              </summary>
              <div class="accordion-content fragment-list">
                <div v-if="memoryStore.currentFragments.length === 0" class="meta-item empty">暂无记忆碎片流程</div>
                <div
                  v-for="f in memoryStore.currentFragments"
                  :key="f.id"
                  class="fragment-item"
                  :class="{ 'fragment-item--discovered': f.isDiscovered }"
                >
                  <span class="fragment-badge">{{ f.fragmentType }}</span>
                  <p class="fragment-text">{{ f.content }}</p>
                </div>
              </div>
            </details>

            <!-- Association Category -->
            <details class="accordion-item">
              <summary class="accordion-header">
                <span>🔗 关联网络</span>
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>
              </summary>
              <div class="accordion-content entity-list">
                <div v-if="neo4jData.entities.length === 0 && neo4jData.sharedMemories.length === 0" class="meta-item empty">暂无关联实体</div>
                <div v-if="neo4jData.entities.length > 0" class="entity-section">
                  <h4>已提及实体 (点击高亮图谱节点)</h4>
                  <div class="entity-chips">
                    <span 
                      v-for="e in neo4jData.entities" 
                      :key="e.name"
                      class="entity-chip"
                      :class="'entity-chip--' + e.type.toLowerCase()"
                      @click="highlightNode(e.name)"
                    >
                      {{ e.name }}
                    </span>
                  </div>
                </div>
                <div v-if="neo4jData.sharedMemories.length > 0" class="entity-section" style="margin-top:12px;">
                  <h4>关联的其他记忆</h4>
                  <ul class="shared-memory-list">
                    <li 
                      v-for="m in neo4jData.sharedMemories" 
                      :key="m.id"
                      class="shared-memory-item"
                    >
                      <RouterLink :to="`/memories/${m.id}`" class="shared-mem-link">
                        {{ m.title }}
                      </RouterLink>
                      <small class="shared-reason">通过 [{{ m.sharedEntity }}] 关联</small>
                    </li>
                  </ul>
                </div>
              </div>
            </details>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.scene-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.graph-layout {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 24px;
  margin-top: 24px;
}

.graph-main {
  min-width: 0;
}

.graph-canvas-wrapper {
  position: relative;
  width: 100%;
  height: 650px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border);
  background:
    radial-gradient(circle at top, rgba(34, 211, 238, 0.08), transparent 30%),
    rgba(2, 6, 23, 0.38);
}

.knowledge-graph-chart {
  width: 100%;
  height: 100%;
}

.graph-sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.glass-panel {
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-lg);
  padding: 24px;
}

.sidebar-title {
  font-family: var(--font-display);
  font-size: 1.1rem;
  font-weight: 700;
  margin-bottom: 18px;
  color: var(--text);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  padding-bottom: 12px;
}

.accordion {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.accordion-item {
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: rgba(255, 255, 255, 0.01);
  transition: all 0.2s ease;
}

.accordion-item[open] {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(54, 216, 180, 0.2);
}

.accordion-header {
  padding: 12px 16px;
  font-weight: 600;
  font-size: 0.9rem;
  color: var(--text-soft);
  cursor: pointer;
  user-select: none;
  list-style: none;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.accordion-header::-webkit-details-marker {
  display: none;
}

.accordion-header svg {
  transition: transform 0.2s ease;
  opacity: 0.6;
}

.accordion-item[open] .accordion-header svg {
  transform: rotate(180deg);
  opacity: 1;
}

.accordion-content {
  padding: 0 16px 16px;
  font-size: 0.86rem;
  color: var(--text-muted);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.meta-item {
  display: flex;
  justify-content: space-between;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.04);
  padding-bottom: 6px;
}

.meta-item strong {
  color: var(--text-soft);
}

.meta-item span {
  color: var(--text-muted);
}

.meta-desc {
  line-height: 1.6;
  color: var(--text-soft);
  white-space: pre-wrap;
}

.fragment-list {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fragment-item {
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-sm);
  padding: 10px;
  background: rgba(255,255,255,0.01);
  opacity: 0.6;
}

.fragment-item--discovered {
  opacity: 1;
  border-color: rgba(54, 216, 180, 0.15);
  background: rgba(54, 216, 180, 0.03);
}

.fragment-badge {
  display: inline-block;
  font-size: 0.7rem;
  text-transform: uppercase;
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  background: rgba(255,255,255,0.08);
  color: var(--text-muted);
  margin-bottom: 6px;
}

.fragment-item--discovered .fragment-badge {
  background: rgba(54, 216, 180, 0.15);
  color: var(--primary);
}

.fragment-text {
  margin: 0;
  line-height: 1.4;
  color: var(--text-soft);
}

.entity-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.entity-section h4 {
  font-size: 0.8rem;
  color: var(--text-soft);
  margin: 6px 0 2px;
  font-weight: 650;
}

.entity-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.entity-chip {
  display: inline-block;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: 0.76rem;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.2s ease;
}

.entity-chip:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

.entity-chip--person {
  background: rgba(192, 132, 252, 0.1);
  color: #c084fc;
  border-color: rgba(192, 132, 252, 0.2);
}
.entity-chip--person:hover {
  background: rgba(192, 132, 252, 0.2);
}

.entity-chip--location {
  background: rgba(52, 211, 153, 0.1);
  color: #34d399;
  border-color: rgba(52, 211, 153, 0.2);
}
.entity-chip--location:hover {
  background: rgba(52, 211, 153, 0.2);
}

.entity-chip--object {
  background: rgba(251, 146, 60, 0.1);
  color: #fb923c;
  border-color: rgba(251, 146, 60, 0.2);
}
.entity-chip--object:hover {
  background: rgba(251, 146, 60, 0.2);
}

.entity-chip--emotion {
  background: rgba(244, 63, 94, 0.1);
  color: #f43f5e;
  border-color: rgba(244, 63, 94, 0.2);
}
.entity-chip--emotion:hover {
  background: rgba(244, 63, 94, 0.2);
}

.shared-memory-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.shared-memory-item {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.04);
}

.shared-mem-link {
  color: #60a5fa;
  font-weight: 600;
  text-decoration: none;
}

.shared-mem-link:hover {
  text-decoration: underline;
}

.shared-reason {
  font-size: 0.72rem;
  color: var(--text-muted);
  margin-top: 2px;
}

.scene-error {
  min-height: min(48vh, 520px);
  display: grid;
  place-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: rgba(8, 9, 8, 0.36);
}

.empty {
  text-align: center;
  color: var(--text-muted);
  padding: 12px 0;
}

@keyframes dotPulse {
  0%, 100% { transform: scale(0.6); opacity: 0.35; }
  50% { transform: scale(1.25); opacity: 1; }
}

@media (max-width: 900px) {
  .graph-layout {
    grid-template-columns: 1fr;
  }
}
</style>
