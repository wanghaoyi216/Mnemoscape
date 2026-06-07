<script setup lang="ts">
/**
 * Resonance overview panel (R12.4 / R16.2).
 *
 * Two-section layout:
 *   - top: KPI cards (totalEdges, averageScore) plus a status-breakdown chip row;
 *   - bottom: a force-directed graph over /admin/stats/resonance-top, where
 *     nodes are memory ids and edge weights are similarity scores.
 *
 * Memory titles / descriptions are NOT exposed by the endpoint (R12.3 / R15.1)
 * so node labels render `memoryId.substring(0,8)` for readability without
 * leaking content.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GraphChart } from 'echarts/charts'
import {
  TooltipComponent,
  LegendComponent,
  TitleComponent,
} from 'echarts/components'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import client from '../../api/client'
import {
  useAdminResonanceOverview,
  useAdminResonanceTop,
} from '../../composables/useAdminResonance'

echartsUse([
  CanvasRenderer,
  GraphChart,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
])

const { t } = useI18n()
const overview = useAdminResonanceOverview()
const top = useAdminResonanceTop()

const memoryDetails = ref<Record<string, { title: string; description: string }>>({})

watch(
  () => top.data.value,
  async (newData) => {
    if (!newData || newData.length === 0) return
    const ids = new Set<string>()
    for (const e of newData) {
      ids.add(e.memoryAId)
      ids.add(e.memoryBId)
    }
    const idList = [...ids]
    if (idList.length === 0) return
    try {
      const resp = await client.post('/admin/memories/batch-details', { ids: idList })
      if (resp.data?.success && resp.data?.data) {
        memoryDetails.value = resp.data.data
      }
    } catch (err) {
      console.warn('Failed to fetch memory details for resonance overview:', err)
    }
  },
  { immediate: true }
)

const TOP_DEFAULT_LIMIT = 20


const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (overview.loading.value && !overview.data.value) return 'loading'
  if (overview.error.value) return 'error'
  if (!overview.data.value) return 'idle'
  if (overview.data.value.totalEdges === 0) return 'empty'
  return 'ready'
})

const aggregatedDegraded = computed(
  () => overview.degraded.value || top.degraded.value,
)
const aggregatedDegradedReasons = computed(() => [
  ...overview.degradedReasons.value,
  ...top.degradedReasons.value,
])

const statusEntries = computed<Array<{ key: string; label: string; count: number }>>(() => {
  const map = overview.data.value?.statusBreakdown ?? {}
  return Object.entries(map)
    .map(([key, count]) => ({
      key,
      label: t(`admin.resonance.status.${key}`, key),
      count: typeof count === 'number' ? count : Number(count),
    }))
    .sort((a, b) => b.count - a.count)
})

const formattedAverage = computed(() => {
  const v = overview.data.value?.averageScore ?? 0
  return v.toFixed(3)
})

const graphOption = computed(() => {
  const edges = top.data.value ?? []
  if (edges.length === 0) {
    return {
      tooltip: { show: false },
      series: [],
    }
  }

  const nodeIds = new Set<string>()
  for (const e of edges) {
    nodeIds.add(e.memoryAId)
    nodeIds.add(e.memoryBId)
  }

  const getTruncatedTitle = (id: string) => {
    const title = memoryDetails.value[id]?.title || id.substring(0, 8)
    return title.length > 10 ? title.substring(0, 10) + '…' : title
  }

  const nodes = [...nodeIds].map((id) => ({
    id,
    name: getTruncatedTitle(id),
    symbolSize: 24,
    itemStyle: { 
      color: {
        type: 'radial',
        x: 0.3, y: 0.3, r: 0.8,
        colorStops: [
          { offset: 0, color: '#a78bfa' },
          { offset: 1, color: '#7c3aed' }
        ]
      },
      shadowColor: 'rgba(124, 58, 237, 0.6)',
      shadowBlur: 10,
      borderColor: 'rgba(255, 255, 255, 0.25)',
      borderWidth: 1.5
    },
    label: { 
      show: true,
      position: 'right',
      color: '#e2e8f0', 
      fontSize: 10.5,
      fontWeight: 500,
      fontFamily: 'Outfit, var(--font-sans), sans-serif'
    },
  }))
  const links = edges.map((e) => ({
    source: getTruncatedTitle(e.memoryAId),
    target: getTruncatedTitle(e.memoryBId),
    value: e.resonanceScore,
    lineStyle: {
      width: 1.5 + e.resonanceScore * 5.0,
      color: {
        type: 'linear',
        x: 0, y: 0, x2: 1, y2: 1,
        colorStops: [
          { offset: 0, color: '#7c3aed' },
          { offset: 1, color: '#06b6d4' }
        ]
      },
      opacity: 0.65,
      curveness: 0.2,
    },
  }))

  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(10, 14, 22, 0.9)',
      borderColor: 'rgba(255, 255, 255, 0.08)',
      borderWidth: 1,
      textStyle: { color: '#e2e8f0' },
      extraCssText: 'backdrop-filter: blur(12px); box-shadow: 0 10px 30px rgba(0,0,0,0.5); border-radius: 8px;',
      formatter: (p: { dataType?: string; data?: { value?: number; source?: string; target?: string; name?: string; id?: string } }) => {
        if (p.dataType === 'edge' && p.data) {
          // Find actual source/target ids matching names
          const findIdByName = (name: string) => {
            return [...nodeIds].find(id => getTruncatedTitle(id) === name) || name
          }
          const idA = findIdByName(p.data.source ?? '')
          const idB = findIdByName(p.data.target ?? '')
          const titleA = memoryDetails.value[idA]?.title || idA.substring(0, 8)
          const titleB = memoryDetails.value[idB]?.title || idB.substring(0, 8)
          return `
            <div style="font-family: var(--font-sans), sans-serif; padding: 4px 8px;">
              <strong style="color: #6cc6ff; font-size: 12px; display: block; margin-bottom: 6px;">共鸣链接</strong>
              <div style="font-size: 11px; color: #cdd5dd; margin-bottom: 2px;">
                节点 A: <span style="color: #fff; font-weight: 500;">${titleA}</span>
              </div>
              <div style="font-size: 11px; color: #cdd5dd; margin-bottom: 6px;">
                节点 B: <span style="color: #fff; font-weight: 500;">${titleB}</span>
              </div>
              <div style="font-size: 11px; color: #36d8b4; font-weight: bold; border-top: 1px solid rgba(255,255,255,0.08); padding-top: 4px;">
                ${t('admin.resonance.top.scoreLabel')}: ${(p.data.value ?? 0).toFixed(3)}
              </div>
            </div>
          `
        }
        if (p.dataType === 'node' && p.data) {
          const findIdByName = (name: string) => {
            return [...nodeIds].find(id => getTruncatedTitle(id) === name) || name
          }
          const id = findIdByName(p.data.name ?? '')
          const detail = memoryDetails.value[id]
          const title = detail?.title || id.substring(0, 8)
          const desc = detail?.description || '暂无描述'
          return `
            <div style="font-family: var(--font-sans), sans-serif; padding: 6px 10px; max-width: 280px; white-space: normal; word-break: break-all;">
              <strong style="color: #a78bfa; font-size: 12px; display: block; margin-bottom: 4px;">记忆节点</strong>
              <div style="font-weight: 600; color: #ffffff; font-size: 12px; margin-bottom: 4px;">${title}</div>
              <div style="font-size: 11px; color: #94a3b8; line-height: 1.4;">${desc}</div>
            </div>
          `
        }
        return ''
      },
    },
    legend: { show: false },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        force: {
          repulsion: 320,
          edgeLength: [120, 200],
          gravity: 0.04,
        },
        emphasis: { focus: 'adjacency', lineStyle: { width: 6 } },
        data: nodes,
        edges: links,
        edgeSymbol: ['none', 'none'],
      },
    ],
  }

})

function refresh(): void {
  void overview.fetch()
  void top.fetch()
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <AdminPanel
    title="admin.resonance.title"
    :state="panelState"
    :error="overview.error.value"
    :degraded="aggregatedDegraded"
    :degraded-reasons="aggregatedDegradedReasons"
    :on-retry="refresh"
  >
    <div class="admin-resonance">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.resonance.subtitle') }}</p>
      </header>

      <section class="admin-resonance__kpis">
        <div class="admin-kpi">
          <span class="admin-kpi__label">{{ t('admin.resonance.kpi.totalEdges') }}</span>
          <strong class="admin-kpi__value">{{ overview.data.value?.totalEdges ?? 0 }}</strong>
        </div>
        <div class="admin-kpi">
          <span class="admin-kpi__label">{{ t('admin.resonance.kpi.averageScore') }}</span>
          <strong class="admin-kpi__value">{{ formattedAverage }}</strong>
        </div>
      </section>

      <section v-if="statusEntries.length > 0" class="admin-resonance__statuses">
        <h4 class="admin-resonance__h">{{ t('admin.resonance.statusBreakdown') }}</h4>
        <ul class="admin-status-list">
          <li
            v-for="s in statusEntries"
            :key="s.key"
            class="admin-status-chip"
          >
            <span class="admin-status-chip__label">{{ s.label }}</span>
            <span class="admin-status-chip__count">{{ s.count }}</span>
          </li>
        </ul>
      </section>

      <section class="admin-resonance__graph">
        <h4 class="admin-resonance__h">
          {{ t('admin.resonance.top.title', { n: TOP_DEFAULT_LIMIT }) }}
        </h4>
        <VChart
          v-if="(top.data.value ?? []).length > 0"
          class="admin-resonance__chart"
          :option="graphOption"
          autoresize
        />
        <p v-else class="admin-resonance__empty">{{ t('admin.common.empty') }}</p>
      </section>
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-resonance {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.admin-panel-controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.admin-panel-subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.86rem;
}

.admin-resonance__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.admin-kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
}

.admin-kpi__label {
  color: var(--text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.admin-kpi__value {
  font-family: var(--font-display);
  font-size: 1.8rem;
  font-weight: 600;
  color: var(--text);
}

.admin-resonance__h {
  margin: 0 0 10px;
  font-size: 0.92rem;
  color: var(--text-soft);
  font-weight: 600;
}

.admin-status-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.admin-status-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.03);
  color: var(--text-soft);
  font-size: 0.84rem;
}

.admin-status-chip__count {
  color: var(--primary);
  font-weight: 600;
}

.admin-resonance__chart {
  width: 100%;
  height: 480px;
}

.admin-resonance__empty {
  color: var(--text-muted);
  padding: 32px 0;
  text-align: center;
  font-size: 0.86rem;
}
</style>
