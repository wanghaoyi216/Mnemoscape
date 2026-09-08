<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

export interface DrawerDetailData {
  title: string
  category: string
  status: 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'STANDBY'
  value: string | number
  unit?: string
  trend?: string
  sparkline?: number[]
  metrics?: { label: string; value: string | number; state?: 'good' | 'warn' | 'alert' }[]
  logs?: { time: string; level: 'INFO' | 'WARN' | 'EXEC'; message: string }[]
  agentInfo?: { name: string; role: string; tps: number; p99: string }
}

const props = defineProps<{
  open: boolean
  data: DrawerDetailData | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { locale } = useI18n()

const statusColor = computed(() => {
  if (!props.data) return 'var(--text-muted)'
  switch (props.data.status) {
    case 'HEALTHY': return 'var(--success, #10b981)'
    case 'WARNING': return 'var(--gold, #f59e0b)'
    case 'CRITICAL': return '#ef4444'
    default: return 'var(--primary, #00f2fe)'
  }
})

function handleBackdropClick() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="modal-backdrop" @click="handleBackdropClick" aria-hidden="true"></div>
    </transition>

    <aside
      class="drawer-right"
      :class="{ 'drawer-right--open': open }"
      role="dialog"
      :aria-label="data?.title || 'Inspection Drawer'"
      aria-modal="true"
    >
      <div v-if="data" class="drawer-content">
        <!-- Header -->
        <header class="drawer-header">
          <div class="drawer-header__meta">
            <span class="drawer-cat">{{ data.category }}</span>
            <div class="drawer-status" :style="{ '--st-color': statusColor }">
              <span class="drawer-status__dot"></span>
              <span class="drawer-status__text">{{ data.status }}</span>
            </div>
          </div>
          <div class="drawer-header__main">
            <h2 class="drawer-title">{{ data.title }}</h2>
            <button class="drawer-close-btn" type="button" :title="locale === 'zh-CN' ? '关闭' : 'Close'" @click="emit('close')">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
                <path d="M6 6l12 12M18 6l-12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </header>

        <!-- Body -->
        <div class="drawer-body">
          <!-- Main KPI Highlight -->
          <div class="drawer-kpi-card">
            <span class="drawer-kpi-card__label">{{ locale === 'zh-CN' ? '核心度量' : 'Core Metric' }}</span>
            <div class="drawer-kpi-card__num-row">
              <span class="drawer-kpi-card__val numeric">{{ data.value }}</span>
              <span v-if="data.unit" class="drawer-kpi-card__unit">{{ data.unit }}</span>
              <span v-if="data.trend" class="drawer-kpi-card__trend">{{ data.trend }}</span>
            </div>
          </div>

          <!-- Structured Metrics Grid -->
          <section v-if="data.metrics && data.metrics.length" class="drawer-section">
            <h3 class="drawer-section__title">{{ locale === 'zh-CN' ? '系统诊断指标' : 'System Telemetry' }}</h3>
            <div class="drawer-metrics-grid">
              <div
                v-for="(m, idx) in data.metrics"
                :key="idx"
                class="drawer-metric-item"
                :class="m.state ? `drawer-metric-item--${m.state}` : ''"
              >
                <span class="drawer-metric-item__label">{{ m.label }}</span>
                <span class="drawer-metric-item__val numeric">{{ m.value }}</span>
              </div>
            </div>
          </section>

          <!-- Agent Info Card -->
          <section v-if="data.agentInfo" class="drawer-section">
            <h3 class="drawer-section__title">{{ locale === 'zh-CN' ? '智能体负载与拓扑' : 'Agent Workload' }}</h3>
            <div class="drawer-agent-card">
              <div class="drawer-agent-card__head">
                <span class="drawer-agent-card__name">{{ data.agentInfo.name }}</span>
                <span class="drawer-agent-card__role">{{ data.agentInfo.role }}</span>
              </div>
              <div class="drawer-agent-card__stats">
                <div class="stat-pill">
                  <span>TPS</span>
                  <strong class="numeric">{{ data.agentInfo.tps }}/s</strong>
                </div>
                <div class="stat-pill">
                  <span>P99</span>
                  <strong class="numeric">{{ data.agentInfo.p99 }}</strong>
                </div>
              </div>
            </div>
          </section>

          <!-- Live Event Logs -->
          <section v-if="data.logs && data.logs.length" class="drawer-section">
            <h3 class="drawer-section__title">{{ locale === 'zh-CN' ? '实时执行追踪' : 'Execution Trace' }}</h3>
            <div class="drawer-logs">
              <div v-for="(l, lIdx) in data.logs" :key="lIdx" class="drawer-log-item">
                <span class="drawer-log-time numeric">{{ l.time }}</span>
                <span class="drawer-log-level" :class="`drawer-log-level--${l.level.toLowerCase()}`">{{ l.level }}</span>
                <span class="drawer-log-msg">{{ l.message }}</span>
              </div>
            </div>
          </section>
        </div>
      </div>
    </aside>
  </Teleport>
</template>

<style scoped>
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.25s ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow-y: auto;
}

.drawer-header {
  padding: 24px 28px 16px;
  border-bottom: 1px solid var(--glass-border);
  background: rgba(4, 9, 20, 0.4);
}
.drawer-header__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.drawer-cat {
  font-size: 0.72rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--primary);
}
.drawer-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.72rem;
  font-family: var(--font-mono);
  color: var(--st-color);
}
.drawer-status__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--st-color);
  box-shadow: 0 0 8px var(--st-color);
}
.drawer-header__main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.drawer-title {
  margin: 0;
  font-size: 1.35rem;
  font-weight: 800;
  color: #fff;
}
.drawer-close-btn {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--glass-border);
  border-radius: 8px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
}
.drawer-close-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.drawer-body {
  padding: 24px 28px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* KPI Card */
.drawer-kpi-card {
  padding: 20px;
  background: linear-gradient(135deg, rgba(0, 242, 254, 0.08) 0%, rgba(79, 172, 254, 0.02) 100%);
  border: 1px solid rgba(0, 242, 254, 0.25);
  border-radius: 14px;
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.15);
}
.drawer-kpi-card__label {
  font-size: 0.76rem;
  color: var(--text-muted);
}
.drawer-kpi-card__num-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 6px;
}
.drawer-kpi-card__val {
  font-size: 2.2rem;
  font-weight: 900;
  color: #fff;
  letter-spacing: -0.02em;
}
.drawer-kpi-card__unit {
  font-size: 0.9rem;
  color: var(--primary);
  font-weight: 700;
}
.drawer-kpi-card__trend {
  margin-left: auto;
  font-size: 0.8rem;
  font-family: var(--font-mono);
  color: var(--success);
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(16, 185, 129, 0.12);
}

/* Section */
.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.drawer-section__title {
  margin: 0;
  font-size: 0.85rem;
  font-weight: 800;
  color: var(--text-soft);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

/* Metrics Grid */
.drawer-metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.drawer-metric-item {
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.drawer-metric-item__label {
  font-size: 0.72rem;
  color: var(--text-muted);
}
.drawer-metric-item__val {
  font-size: 1.05rem;
  font-weight: 700;
  color: #fff;
}
.drawer-metric-item--good .drawer-metric-item__val { color: var(--success); }
.drawer-metric-item--warn .drawer-metric-item__val { color: var(--gold); }
.drawer-metric-item--alert .drawer-metric-item__val { color: #ef4444; }

/* Agent Card */
.drawer-agent-card {
  padding: 14px 16px;
  background: rgba(168, 85, 247, 0.08);
  border: 1px solid rgba(168, 85, 247, 0.25);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.drawer-agent-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.drawer-agent-card__name {
  font-weight: 800;
  color: #fff;
}
.drawer-agent-card__role {
  font-size: 0.74rem;
  color: #c084fc;
}
.drawer-agent-card__stats {
  display: flex;
  gap: 12px;
}
.stat-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.75rem;
  background: rgba(0, 0, 0, 0.35);
  padding: 4px 10px;
  border-radius: 6px;
  color: var(--text-muted);
}
.stat-pill strong { color: #fff; }

/* Logs */
.drawer-logs {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  overflow-y: auto;
  padding: 10px;
  background: rgba(2, 6, 14, 0.6);
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.04);
}
.drawer-log-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 0.72rem;
  font-family: var(--font-mono);
  line-height: 1.5;
}
.drawer-log-time { color: var(--text-muted); flex-shrink: 0; }
.drawer-log-level {
  font-weight: bold;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 0.65rem;
  flex-shrink: 0;
}
.drawer-log-level--info { background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
.drawer-log-level--warn { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
.drawer-log-level--exec { background: rgba(16, 185, 129, 0.2); color: #34d399; }
.drawer-log-msg { color: var(--text-soft); }
</style>
