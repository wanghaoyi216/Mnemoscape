<script setup lang="ts">
/**
 * Fragment discovery panel (R11.4 / R16.2).
 *
 * Renders two layered views over the /admin/stats/fragment-discovery endpoint:
 *   - a single ECharts gauge for the platform-wide discovery rate (overall),
 *   - a horizontal bar chart broken down by fragment type.
 *
 * Both share the same panel shell; the gauge fronts the eye, the bar chart
 * quantifies the breakdown. Type labels go through the three-arg t() fallback
 * so any backend value not yet i18n-mapped renders the raw enum string.
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GaugeChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
} from 'echarts/components'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import {
  useAdminFragmentsOverall,
  useAdminFragmentsByType,
} from '../../composables/useAdminFragments'

echartsUse([
  CanvasRenderer,
  GaugeChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
])

const { t } = useI18n()
const overall = useAdminFragmentsOverall()
const byType = useAdminFragmentsByType()

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  // Either source loading → loading state. Either source erroring → error state
  // (panel surfaces the more critical: overall errors are loud, byType absence
  // shows an empty list).
  if (overall.loading.value && !overall.data.value) return 'loading'
  if (overall.error.value) return 'error'
  if (!overall.data.value) return 'idle'
  return 'ready'
})

const aggregatedDegraded = computed(
  () => overall.degraded.value || byType.degraded.value,
)
const aggregatedDegradedReasons = computed(() => [
  ...overall.degradedReasons.value,
  ...byType.degradedReasons.value,
])

const gaugeOption = computed(() => {
  const rate = overall.data.value?.discoveryRate ?? 0
  return {
    series: [
      {
        type: 'gauge',
        radius: '90%',
        center: ['50%', '60%'],
        startAngle: 200,
        endAngle: -20,
        min: 0,
        max: 1,
        progress: { show: true, width: 18 },
        pointer: { show: false },
        axisLine: {
          lineStyle: {
            width: 18,
            color: [[1, 'rgba(255,255,255,0.08)']],
          },
        },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        anchor: { show: false },
        title: { show: false },
        detail: {
          valueAnimation: true,
          formatter: (v: number) => `${(v * 100).toFixed(1)}%`,
          color: '#36d8b4',
          fontSize: 30,
          fontWeight: 600,
          offsetCenter: [0, '0%'],
        },
        itemStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 1, y2: 0,
            colorStops: [
              { offset: 0, color: '#36d8b4' },
              { offset: 1, color: '#6cc6ff' },
            ],
          },
        },
        data: [{ value: rate }],
      },
    ],
  }
})

const byTypeOption = computed(() => {
  const rows = byType.data.value ?? []
  // Reverse so the highest discovery rate sits at the top.
  const ordered = [...rows].sort((a, b) => a.discoveryRate - b.discoveryRate)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: { dataIndex: number }[]) => {
        const idx = params[0].dataIndex
        const row = ordered[idx]
        if (!row) return ''
        return `
          <strong>${t(`admin.fragments.types.${row.fragmentType}`, row.fragmentType)}</strong>
          <br/>${(row.discoveryRate * 100).toFixed(1)}% (${row.discoveredFragments} / ${row.totalFragments})
        `
      },
    },
    grid: { left: 140, right: 80, top: 8, bottom: 24, containLabel: true },
    xAxis: {
      type: 'value',
      min: 0,
      max: 1,
      axisLabel: {
        color: '#8b95a1',
        formatter: (v: number) => `${Math.round(v * 100)}%`,
      },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
    },
    yAxis: {
      type: 'category',
      data: ordered.map((r) =>
        t(`admin.fragments.types.${r.fragmentType}`, r.fragmentType),
      ),
      axisLabel: { color: '#cdd5dd', fontSize: 12 },
      axisLine: { lineStyle: { color: '#3a4250' } },
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: ordered.map((r) => r.discoveryRate),
        itemStyle: {
          color: '#f2b95c',
          borderRadius: [0, 6, 6, 0],
        },
        label: {
          show: true,
          position: 'right',
          color: '#cdd5dd',
          formatter: (p: { value: number }) => `${(p.value * 100).toFixed(1)}%`,
        },
      },
    ],
  }
})

function refresh(): void {
  void overall.fetch()
  void byType.fetch()
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <AdminPanel
    title="admin.fragments.title"
    :state="panelState"
    :error="overall.error.value"
    :degraded="aggregatedDegraded"
    :degraded-reasons="aggregatedDegradedReasons"
    :on-retry="refresh"
  >
    <div class="admin-fragments">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.fragments.subtitle') }}</p>
      </header>

      <div class="admin-fragments__grid">
        <section class="admin-fragments__cell">
          <h4 class="admin-fragments__h">{{ t('admin.fragments.gauge.label') }}</h4>
          <VChart class="admin-fragments__gauge" :option="gaugeOption" autoresize />
          <div class="admin-fragments__counts">
            <span>
              {{ overall.data.value?.discoveredFragments ?? 0 }}
              / {{ overall.data.value?.totalFragments ?? 0 }}
            </span>
          </div>
        </section>

        <section class="admin-fragments__cell">
          <h4 class="admin-fragments__h">{{ t('admin.fragments.byType') }}</h4>
          <VChart
            v-if="(byType.data.value ?? []).length > 0"
            class="admin-fragments__bars"
            :option="byTypeOption"
            autoresize
          />
          <p v-else class="admin-fragments__empty">{{ t('admin.common.empty') }}</p>
        </section>
      </div>
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-fragments {
  display: flex;
  flex-direction: column;
  gap: 18px;
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

.admin-fragments__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 24px;
}

@media (max-width: 900px) {
  .admin-fragments__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.admin-fragments__cell {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.admin-fragments__h {
  margin: 0;
  font-size: 0.92rem;
  color: var(--text-soft);
  font-weight: 600;
  letter-spacing: -0.005em;
}

.admin-fragments__gauge { width: 100%; height: 280px; }
.admin-fragments__bars  { width: 100%; height: 320px; }

.admin-fragments__counts {
  text-align: center;
  color: var(--text-muted);
  font-size: 0.86rem;
}

.admin-fragments__empty {
  color: var(--text-muted);
  font-size: 0.86rem;
  padding: 32px 0;
  text-align: center;
}
</style>
