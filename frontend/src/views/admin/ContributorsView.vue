<script setup lang="ts">
/**
 * Top contributors panel (R10.5): horizontal bar ranked by memory count.
 * Strict whitelist — only renders fields the API actually returns
 * (userId, username, memoryCount); no avatars or other PII.
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
import { useAdminContributors } from '../../composables/useAdminContributors'

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
  data,
  loading,
  error,
  degraded,
  degradedReasons,
  fetch,
} = useAdminContributors()

const items = computed(() => data.value?.items ?? [])

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (loading.value && !data.value) return 'loading'
  if (error.value) return 'error'
  if (!data.value) return 'idle'
  if (items.value.length === 0) return 'empty'
  return 'ready'
})

const chartOption = computed(() => {
  // Reverse so the largest count sits at the top of the horizontal bar.
  const ordered = [...items.value].reverse()
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: { dataIndex: number; value: number }[]) => {
        const idx = params[0].dataIndex
        const row = ordered[idx]
        if (!row) return ''
        return `${row.username}<br/>${row.memoryCount} ${t('admin.contributors.countSuffix')}`
      },
    },
    grid: { left: 120, right: 32, top: 8, bottom: 24, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#8b95a1' },
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
    },
    yAxis: {
      type: 'category',
      data: ordered.map((it) => it.username),
      axisLabel: { color: '#cdd5dd', fontSize: 12 },
      axisLine: { lineStyle: { color: '#3a4250' } },
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: ordered.map((it) => it.memoryCount),
        itemStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 1, y2: 0,
            colorStops: [
              { offset: 0, color: '#36d8b4' },
              { offset: 1, color: '#6cc6ff' },
            ],
          },
          borderRadius: [0, 6, 6, 0],
        },
        label: {
          show: true,
          position: 'right',
          color: '#cdd5dd',
          formatter: (p: { value: number }) => `${p.value}`,
        },
        emphasis: { focus: 'series' },
      },
    ],
  }
})

onMounted(() => {
  void fetch()
})
</script>

<template>
  <AdminPanel
    title="admin.contributors.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-contributors">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.contributors.subtitle') }}</p>
      </header>

      <VChart class="admin-chart" :option="chartOption" autoresize />
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-contributors {
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

.admin-chart {
  width: 100%;
  height: 420px;
}
</style>
