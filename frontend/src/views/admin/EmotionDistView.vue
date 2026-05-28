<script setup lang="ts">
/**
 * Emotion distribution panel (R8.5): 8-axis radar chart over the eight
 * canonical emotion components (joy / sadness / anger / fear / surprise /
 * nostalgia / peace / melancholy). Sample size is shown below the chart so
 * operators know whether the average is statistically meaningful.
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { RadarChart } from 'echarts/charts'
import {
  RadarComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
} from 'echarts/components'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminEmotion } from '../../composables/useAdminEmotion'

echartsUse([
  CanvasRenderer,
  RadarChart,
  RadarComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
])

const { t } = useI18n()
const { data, loading, error, degraded, degradedReasons, fetch } = useAdminEmotion()

const COMPONENTS = [
  'joy', 'sadness', 'anger', 'fear',
  'surprise', 'nostalgia', 'peace', 'melancholy',
] as const

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (loading.value) return 'loading'
  if (error.value) return 'error'
  if (!data.value) return 'idle'
  // sampleSize === 0 means no qualifying memories — that's an "empty" state, not an error
  if (data.value.sampleSize === 0) return 'empty'
  return 'ready'
})

const chartOption = computed(() => {
  const d = data.value
  // Always materialise an indicators array; even when data is null we render
  // the radar shape (Vue's v-if hides the chart for non-ready states anyway).
  return {
    tooltip: { trigger: 'item' },
    legend: { textStyle: { color: '#cdd5dd' }, top: 4 },
    radar: {
      indicator: COMPONENTS.map((k) => ({
        name: t(`admin.emotion.components.${k}`),
        max: 1.0,
      })),
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.10)' } },
      splitArea: {
        areaStyle: {
          color: ['rgba(54,216,180,0.04)', 'rgba(54,216,180,0.02)'],
        },
      },
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.18)' } },
      axisName: { color: '#cdd5dd', fontSize: 12 },
      radius: '64%',
    },
    series: [
      {
        type: 'radar',
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: '#36d8b4', width: 2 },
        itemStyle: { color: '#36d8b4' },
        areaStyle: { color: 'rgba(54, 216, 180, 0.18)' },
        data: d
          ? [
              {
                name: t('admin.emotion.title'),
                value: COMPONENTS.map((k) => d[k] ?? 0),
              },
            ]
          : [],
      },
    ],
  }
})

const sampleLabel = computed(() => {
  const n = data.value?.sampleSize ?? 0
  return t('admin.emotion.sampleSize', { count: n })
})

onMounted(() => {
  void fetch()
})
</script>

<template>
  <AdminPanel
    title="admin.emotion.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-emotion">
      <header class="admin-panel-controls">
        <p class="admin-panel-subtitle">{{ t('admin.emotion.subtitle') }}</p>
        <span class="admin-emotion__sample">{{ sampleLabel }}</span>
      </header>

      <VChart class="admin-chart" :option="chartOption" autoresize />
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-emotion {
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

.admin-emotion__sample {
  color: var(--text-soft);
  font-size: 0.82rem;
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.03);
}

.admin-chart {
  width: 100%;
  height: 420px;
}
</style>
