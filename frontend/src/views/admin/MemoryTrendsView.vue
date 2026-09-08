<script setup lang="ts">
/**
 * Memory trends panel (R7.4): stacked column chart with two series
 * (created vs modified) per bucket; same dimension toggle as ActiveUsersView.
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
} from 'echarts/components'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminMemoryTrends } from '../../composables/useAdminMemoryTrends'
import type { AdminDimension } from '../../api/admin'

echartsUse([
  CanvasRenderer,
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
} = useAdminMemoryTrends()

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
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15, 23, 42, 0.85)',
      borderColor: 'rgba(255, 255, 255, 0.1)',
      borderWidth: 1,
      textStyle: { color: '#ffffff' },
    },
    legend: {
      data: [t('admin.memoryTrends.legend.created'), t('admin.memoryTrends.legend.modified')],
      textStyle: { color: '#cdd5dd' },
      top: 4,
    },
    grid: { left: 56, right: 24, top: 48, bottom: 56 },
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
        name: t('admin.memoryTrends.legend.created'),
        type: 'bar',
        stack: 'total',
        itemStyle: { 
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#3b82f6' },
              { offset: 1, color: '#8b5cf6' }
            ]
          },
          borderRadius: [4, 4, 0, 0] 
        },
        emphasis: { focus: 'series' },
        data: buckets.map((b) => b.createdCount),
      },
      {
        name: t('admin.memoryTrends.legend.modified'),
        type: 'bar',
        stack: 'total',
        itemStyle: { 
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#f43f5e' },
              { offset: 1, color: '#fb7185' }
            ]
          },
          borderRadius: [4, 4, 0, 0] 
        },
        emphasis: { focus: 'series' },
        data: buckets.map((b) => b.modifiedCount),
      },
    ],
  }
})

function selectDimension(d: AdminDimension): void {
  if (dimension.value !== d) {
    dimension.value = d
  }
}

onMounted(() => {
  void fetch()
})
</script>

<template>
  <AdminPanel
    title="admin.memoryTrends.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-memory-trends">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.memoryTrends.subtitle') }}</p>
        <div class="admin-toggle" role="tablist" :aria-label="t('admin.memoryTrends.title')">
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
.admin-memory-trends {
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
