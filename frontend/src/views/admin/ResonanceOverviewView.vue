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
import { computed, onMounted } from 'vue'
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
  const nodes = [...nodeIds].map((id) => ({
    id,
    name: id.substring(0, 8),
    symbolSize: 18,
    itemStyle: { color: '#36d8b4' },
    label: { color: '#cdd5dd', fontSize: 11 },
  }))
  const links = edges.map((e) => ({
    source: e.memoryAId,
    target: e.memoryBId,
    value: e.resonanceScore,
    lineStyle: {
      width: 1 + e.resonanceScore * 3,
      color: 'rgba(108, 198, 255, 0.55)',
      opacity: 0.7,
    },
  }))

  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: { dataType?: string; data?: { value?: number; source?: string; target?: string; name?: string } }) => {
        if (p.dataType === 'edge' && p.data) {
          return `
            ${(p.data.source ?? '').substring(0, 8)} ↔ ${(p.data.target ?? '').substring(0, 8)}<br/>
            ${t('admin.resonance.top.scoreLabel')}: ${(p.data.value ?? 0).toFixed(3)}
          `
        }
        if (p.dataType === 'node' && p.data) {
          return p.data.name ?? ''
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
          repulsion: 200,
          edgeLength: [80, 160],
          gravity: 0.06,
        },
        emphasis: { focus: 'adjacency', lineStyle: { width: 4 } },
        data: nodes,
        edges: links,
        edgeSymbol: ['none', 'none'],
        lineStyle: { curveness: 0.18 },
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
