<script setup lang="ts">
/**
 * Active users panel (R6.8 / R6.9).
 *
 * Renders a single ECharts line chart over the bucket series returned by
 * /admin/stats/active-users. Operators flip between DAILY / WEEKLY / MONTHLY /
 * YEARLY via a pill toggle row; switching the dimension re-fetches with the
 * backend's default window (30/12/12/5 buckets).
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
} from 'echarts/components'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminActiveUsers } from '../../composables/useAdminActiveUsers'
import type { AdminDimension } from '../../api/admin'

echartsUse([
  CanvasRenderer,
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
])

const { t } = useI18n()
const {
  dimension,
  data,
  loading,
  error,
  degraded,
  degradedReasons,
  fetch,
} = useAdminActiveUsers()

const dimensions: AdminDimension[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY']

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (loading.value) return 'loading'
  if (error.value) return 'error'
  if (!data.value) return 'idle'
  if (data.value.length === 0) return 'empty'
  return 'ready'
})

const chartOption = computed(() => {
  const buckets = data.value ?? []
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(255, 255, 255, 0.12)' } },
      backgroundColor: 'rgba(15, 23, 42, 0.85)',
      borderColor: 'rgba(255, 255, 255, 0.1)',
      borderWidth: 1,
      textStyle: { color: '#ffffff' },
    },
    legend: {
      data: [t('admin.activeUsers.legend.activeUsers')],
      textStyle: { color: '#cdd5dd' },
      top: 4,
    },
    grid: {
      left: 56,
      right: 24,
      top: 48,
      bottom: 56,
      containLabel: false,
    },
    xAxis: {
      type: 'category',
      data: buckets.map((b) => b.bucket),
      axisLabel: { color: '#8b95a1' },
      axisLine: { lineStyle: { color: 'rgba(255, 255, 255, 0.1)' } },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#8b95a1' },
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(255, 255, 255, 0.04)' } },
    },
    series: [
      {
        name: t('admin.activeUsers.legend.activeUsers'),
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { 
          width: 2.6, 
          color: '#22d3ee',
          shadowColor: 'rgba(34, 211, 238, 0.3)',
          shadowBlur: 8,
          shadowOffsetY: 4
        },
        itemStyle: { color: '#22d3ee' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(34, 211, 238, 0.22)' },
              { offset: 1, color: 'rgba(34, 211, 238, 0)' },
            ],
          },
        },
        data: buckets.map((b) => b.activeUserCount),
      },
    ],
  }
})

function selectDimension(d: AdminDimension): void {
  if (dimension.value !== d) {
    dimension.value = d
    // watch() inside the composable triggers the fetch; nothing else to do.
  }
}

onMounted(() => {
  void fetch()
})
</script>

<template>
  <AdminPanel
    title="admin.activeUsers.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-active-users">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.activeUsers.subtitle') }}</p>
        <div class="admin-toggle" role="tablist" :aria-label="t('admin.activeUsers.title')">
          <button
            v-for="d in dimensions"
            :key="d"
            type="button"
            role="tab"
            class="admin-toggle__btn"
            :class="{ 'admin-toggle__btn--active': dimension === d }"
            :aria-selected="dimension === d"
            @click="selectDimension(d)"
          >
            {{ t(`admin.activeUsers.dimensions.${d}`) }}
          </button>
        </div>
      </header>

      <VChart class="admin-chart" :option="chartOption" autoresize />
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-active-users {
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

.admin-toggle {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 4px;
  background: rgba(14, 17, 22, 0.6);
}

.admin-toggle__btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.admin-toggle__btn:hover {
  color: var(--text);
}

.admin-toggle__btn--active {
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.28);
}

.admin-chart {
  width: 100%;
  height: 360px;
}
</style>
