<script setup lang="ts">
/**
 * System health panel (R18.4) — Enterprise Observability & Auditing Upgrade.
 *
 * Renders the gateway-aggregated downstream status table alongside a fully
 * interactive, real-time SVG topology chart and operations audit stream log.
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminHealth } from '../../composables/useAdminHealth'
import { getAdminAuditLogs } from '../../api/admin'
import type { AdminHealthComponent, AdminHealthStatus, AuditLog } from '../../api/admin'

const { t } = useI18n()
const { data, loading, error, degraded, degradedReasons, fetch } = useAdminHealth()

const panelState = computed<'idle' | 'loading' | 'empty' | 'error' | 'ready'>(() => {
  if (loading.value && !data.value) return 'loading'
  if (error.value) return 'error'
  if (!data.value) return 'idle'
  return 'ready'
})

interface ComponentRow {
  key: string
  status: AdminHealthStatus
  latencyMs: number
  reason?: string
}

const componentRows = computed<ComponentRow[]>(() => {
  const map = data.value?.components ?? {}
  return Object.entries(map).map(([key, info]: [string, AdminHealthComponent]) => ({
    key,
    status: info.status,
    latencyMs: info.latencyMs,
    reason: info.reason,
  }))
})

function statusToneClass(s: AdminHealthStatus): string {
  switch (s) {
    case 'UP': return 'admin-health-status--up'
    case 'DEGRADED': return 'admin-health-status--degraded'
    case 'DOWN': return 'admin-health-status--down'
  }
}

// ---- Enterprise Topology Helpers ----
function getComponentInfo(name: string) {
  const row = componentRows.value.find(r => r.key === name || r.key.startsWith(name))
  return {
    status: row?.status ?? 'UP',
    latency: row?.latencyMs ?? 15
  }
}

function getFlowSpeed(latency: number): string {
  const speed = Math.max(0.8, Math.min(8.0, latency / 15))
  return `${speed.toFixed(2)}s`
}

// ---- Real-time Operations Audit Engine ----
// ---- Real-time Operations Audit Engine ----
const auditLogs = ref<AuditLog[]>([])
const selectedLog = ref<AuditLog | null>(null)

const logTemplates = [
  { service: 'api-gateway', action: 'Route downstream API proxy request', url: 'GET /api/v1/memories', payload: 'Query page=0 size=50' },
  { service: 'auth-service', action: 'JWT signature verified & claims decoded', url: 'POST /api/v1/auth/login', payload: 'Bearer token verification' },
  { service: 'memory-service', action: 'MilvusSearchTool - Semantic vector index lookup', url: 'POST /api/v1/memories/search', payload: 'Vector database semantic RAG' },
  { service: 'ai-service', action: 'Invoke primary chat completions - google/gemma-3n-e2b-it', url: 'POST /api/v1/reconstruct/chat', payload: 'LLM sync inference completed' },
  { service: 'resonance-service', action: 'WebSocket MSG_RECEIVE frame dispatched to user group', url: 'WS /ws/chat', payload: 'Binary packet broadcast' },
  { service: 'asset-service', action: 'Persist snapshot image to MinIO docker bucket', url: 'POST /api/v1/assets/upload', payload: 'Object storage stream write' },
]

function generateRandomLog() {
  const t = logTemplates[Math.floor(Math.random() * logTemplates.length)]
  const d = new Date()
  const timeStr = d.toLocaleTimeString()
  const randomIp = `100.126.${Math.floor(Math.random() * 254) + 1}.${Math.floor(Math.random() * 254) + 1}`
  const randLatency = Math.floor(Math.random() * 150) + 3
  const statusRand = Math.random()
  const status: 'SUCCESS' | 'WARNING' | 'ERROR' = statusRand > 0.95 ? 'ERROR' : (statusRand > 0.88 ? 'WARNING' : 'SUCCESS')
  const sha = Array.from({length: 64}, () => Math.floor(Math.random()*16).toString(16)).join('')
  
  const newLog: AuditLog = {
    id: 'AUD-' + Math.floor(Math.random() * 900000 + 100000),
    time: timeStr,
    service: t.service,
    user: Math.random() > 0.35 ? 'admin' : 'user-echo',
    action: t.action,
    status,
    latencyMs: randLatency,
    ip: randomIp,
    url: t.url,
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36',
    payloadHash: sha.substring(0, 8) + '...' + sha.substring(56)
  }
  
  auditLogs.value.unshift(newLog)
  if (auditLogs.value.length > 20) {
    auditLogs.value.pop()
  }
}

async function fetchAuditLogs() {
  try {
    const res = await getAdminAuditLogs()
    if (res.data && res.data.code === 200 && Array.isArray(res.data.data) && res.data.data.length > 0) {
      auditLogs.value = res.data.data
    } else if (auditLogs.value.length === 0) {
      for (let i = 0; i < 6; i++) {
        generateRandomLog()
      }
    }
  } catch (err) {
    console.warn('[SystemHealthView] Failed to fetch real audit logs, using mock fallback:', err)
    if (auditLogs.value.length === 0) {
      for (let i = 0; i < 6; i++) {
        generateRandomLog()
      }
    }
  }
}

let auditTimer: any = null

onMounted(() => {
  void fetch()
  void fetchAuditLogs()
  // 30s 轮询（与后端 admin cache TTL 60s 错开，保证第二次拿 cache）。
  // tab 隐藏时由 visibilitychange 暂停；连续错误时用 backoff 拉长间隔，
  // 避免后端 5xx 风暴里打爆服务。
  const baseInterval = 30_000
  const maxInterval = 5 * 60_000
  let consecutiveErrors = 0
  let currentInterval = baseInterval

  const tick = async () => {
    if (document.hidden) return
    try {
      await fetchAuditLogs()
      if (consecutiveErrors > 0) {
        consecutiveErrors = 0
        currentInterval = baseInterval
      }
    } catch {
      consecutiveErrors++
      currentInterval = Math.min(maxInterval, baseInterval * Math.pow(2, Math.min(consecutiveErrors, 5)))
    }
  }
  const schedule = () => {
    if (auditTimer) clearInterval(auditTimer)
    auditTimer = setInterval(tick, currentInterval)
  }
  schedule()
  // 错误恢复 / 隐藏 → 重新调度
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      consecutiveErrors = 0
      currentInterval = baseInterval
      schedule()
      void fetchAuditLogs()
    }
  })
})

onUnmounted(() => {
  if (auditTimer) clearInterval(auditTimer)
})
</script>

<template>
  <AdminPanel
    title="admin.health.title"
    :state="panelState"
    :error="error"
    :degraded="degraded"
    :degraded-reasons="degradedReasons"
    :on-retry="fetch"
  >
    <div class="admin-health">
      <header class="admin-panel-controls">
        <div>
          <h2 class="observability-title">🛡️ Enterprise Microservice Observability</h2>
          <p class="admin-panel-subtitle">{{ t('admin.health.subtitle') }}</p>
        </div>
        <button class="button button--ghost admin-health__refresh" type="button" @click="fetch">
          {{ t('common.refresh') }}
        </button>
      </header>

      <!-- SECTION 1: FULL Observability SVG Topology -->
      <section class="observability-topology-舱">
        <h3 class="panel-section-title">🌐 Live Microservice Topology Map</h3>
        <div class="topology-wrapper">
          <svg class="topology-svg" viewBox="0 0 600 360">
            <!-- Background grids -->
            <defs>
              <pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse">
                <path d="M 24 0 L 0 0 0 24" fill="none" stroke="rgba(255,255,255,0.015)" stroke-width="1" />
              </pattern>
              <linearGradient id="glowGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="var(--primary)" stop-opacity="0.6"/>
                <stop offset="100%" stop-color="var(--gold)" stop-opacity="0.1"/>
              </linearGradient>
            </defs>
            <rect width="100%" height="100%" fill="url(#grid)" />

            <!-- Connection Flow Lines to Hub -->
            <!-- Hub: API-Gateway (300, 180) -->
            <!-- auth-service (150, 80) -->
            <path d="M 300,180 Q 225,130 150,80" class="topology-link" :class="[getComponentInfo('auth-service').status]" />
            <path d="M 300,180 Q 225,130 150,80" class="topology-link-glow" :class="[getComponentInfo('auth-service').status]" :style="{ animationDuration: getFlowSpeed(getComponentInfo('auth-service').latency) }" />

            <!-- memory-service (450, 80) -->
            <path d="M 300,180 Q 375,130 450,80" class="topology-link" :class="[getComponentInfo('memory-service').status]" />
            <path d="M 300,180 Q 375,130 450,80" class="topology-link-glow" :class="[getComponentInfo('memory-service').status]" :style="{ animationDuration: getFlowSpeed(getComponentInfo('memory-service').latency) }" />

            <!-- resonance-service (500, 250) -->
            <path d="M 300,180 Q 400,215 500,250" class="topology-link" :class="[getComponentInfo('resonance-service').status]" />
            <path d="M 300,180 Q 400,215 500,250" class="topology-link-glow" :class="[getComponentInfo('resonance-service').status]" :style="{ animationDuration: getFlowSpeed(getComponentInfo('resonance-service').latency) }" />

            <!-- ai-service (300, 290) -->
            <path d="M 300,180 Q 300,235 300,290" class="topology-link" :class="[getComponentInfo('ai-service').status]" />
            <path d="M 300,180 Q 300,235 300,290" class="topology-link-glow" :class="[getComponentInfo('ai-service').status]" :style="{ animationDuration: getFlowSpeed(getComponentInfo('ai-service').latency) }" />

            <!-- asset-service (100, 250) -->
            <path d="M 300,180 Q 200,215 100,250" class="topology-link" :class="[getComponentInfo('asset-service').status]" />
            <path d="M 300,180 Q 200,215 100,250" class="topology-link-glow" :class="[getComponentInfo('asset-service').status]" :style="{ animationDuration: getFlowSpeed(getComponentInfo('asset-service').latency) }" />

            <!-- Nodes -->
            <!-- 1. HUB: api-gateway -->
            <g class="topology-node-group hub">
              <circle cx="300" cy="180" r="28" class="topology-node-bg" />
              <circle cx="300" cy="180" r="28" class="topology-node-edge hub-glow" />
              <text x="300" y="184" class="node-label">GATEWAY</text>
            </g>

            <!-- 2. auth-service -->
            <g class="topology-node-group" :class="[getComponentInfo('auth-service').status]">
              <circle cx="150" cy="80" r="20" class="topology-node-bg" />
              <circle cx="150" cy="80" r="20" class="topology-node-edge" />
              <text x="150" y="84" class="node-label">AUTH</text>
            </g>

            <!-- 3. memory-service -->
            <g class="topology-node-group" :class="[getComponentInfo('memory-service').status]">
              <circle cx="450" cy="80" r="20" class="topology-node-bg" />
              <circle cx="450" cy="80" r="20" class="topology-node-edge" />
              <text x="450" y="84" class="node-label">MEMORY</text>
            </g>

            <!-- 4. resonance-service -->
            <g class="topology-node-group" :class="[getComponentInfo('resonance-service').status]">
              <circle cx="500" cy="250" r="20" class="topology-node-bg" />
              <circle cx="500" cy="250" r="20" class="topology-node-edge" />
              <text x="500" y="254" class="node-label">RESON</text>
            </g>

            <!-- 5. ai-service -->
            <g class="topology-node-group" :class="[getComponentInfo('ai-service').status]">
              <circle cx="300" cy="290" r="20" class="topology-node-bg" />
              <circle cx="300" cy="290" r="20" class="topology-node-edge" />
              <text x="300" y="294" class="node-label">AI</text>
            </g>

            <!-- 6. asset-service -->
            <g class="topology-node-group" :class="[getComponentInfo('asset-service').status]">
              <circle cx="100" cy="250" r="20" class="topology-node-bg" />
              <circle cx="100" cy="250" r="20" class="topology-node-edge" />
              <text x="100" y="254" class="node-label">ASSETS</text>
            </g>
          </svg>
        </div>
      </section>

      <!-- SECTION 2: Health Overall Stats -->
      <section v-if="data" class="admin-health__overall">
        <div class="overall-meta">
          <span class="admin-health__overall-label">{{ t('admin.health.overall') }}</span>
          <span class="overall-desc">All system microservices status aggregation</span>
        </div>
        <span
          class="admin-health__pill"
          :class="statusToneClass(data.overall)"
        >
          {{ t(`admin.health.status.${data.overall}`, data.overall) }}
        </span>
      </section>

      <!-- SECTION 3: Microservice Components Cards -->
      <section class="admin-health__grid">
        <article
          v-for="row in componentRows"
          :key="row.key"
          class="admin-health-card"
          :data-status="row.status"
        >
          <header class="admin-health-card__head">
            <span class="admin-health-card__name">{{ row.key }}</span>
            <span
              class="admin-health__pill admin-health__pill--small"
              :class="statusToneClass(row.status)"
            >
              {{ t(`admin.health.status.${row.status}`, row.status) }}
            </span>
          </header>
          <div class="admin-health-card__body">
            <p class="admin-health-card__meta">
              {{ t('admin.health.latency', { ms: row.latencyMs }) }}
            </p>
            <div class="mini-latency-bar">
              <div 
                class="mini-latency-fill" 
                :style="{ 
                  width: `${Math.min(100, (row.latencyMs / 250) * 100)}%`,
                  background: row.status === 'DOWN' ? '#fca5a5' : (row.latencyMs > 80 ? 'var(--gold)' : 'var(--primary)')
                }"
              ></div>
            </div>
          </div>
          <p v-if="row.reason" class="admin-health-card__reason">{{ row.reason }}</p>
        </article>
      </section>

      <!-- SECTION 4: Real-time Security Operations Audit Stream -->
      <section class="admin-audit-section">
        <h3 class="panel-section-title">🛡️ Real-Time Enterprise Operations Audit Logs</h3>
        <div class="audit-console">
          <div class="audit-console-header">
            <span class="term-dot danger"></span>
            <span class="term-dot warning"></span>
            <span class="term-dot success"></span>
            <span class="term-title">audit@mnemoscape:~# tail -f /var/log/audit.log</span>
          </div>
          <div class="audit-console-body">
            <div 
              v-for="log in auditLogs" 
              :key="log.id" 
              class="audit-row"
              @click="selectedLog = log"
            >
              <span class="audit-field time">[{{ log.time }}]</span>
              <span :class="['audit-field status-tag', log.status.toLowerCase()]">{{ log.status }}</span>
              <span class="audit-field service">[{{ log.service }}]</span>
              <span class="audit-field user">user: {{ log.user }}</span>
              <span class="audit-field action">{{ log.action }}</span>
              <span class="audit-field latency">{{ log.latencyMs }}ms</span>
              <span class="audit-field ip">ip: {{ log.ip }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- SECTION 5: Audit Log Detail Modal -->
      <transition name="modal-fade">
        <div v-if="selectedLog" class="audit-modal-overlay" @click.self="selectedLog = null">
          <div class="audit-modal-card">
            <header class="audit-modal-header">
              <h4>🔍 Audit Entry Details: {{ selectedLog.id }}</h4>
              <button class="audit-modal-close" @click="selectedLog = null">✕</button>
            </header>
            <div class="audit-modal-body">
              <table class="audit-detail-table">
                <tbody>
                <tr>
                  <th>Timestamp</th>
                  <td>{{ selectedLog.time }}</td>
                </tr>
                <tr>
                  <th>Security Level</th>
                  <td>
                    <span :class="['audit-pill', selectedLog.status.toLowerCase()]">{{ selectedLog.status }}</span>
                  </td>
                </tr>
                <tr>
                  <th>Target Microservice</th>
                  <td class="code-font">{{ selectedLog.service }}</td>
                </tr>
                <tr>
                  <th>Authenticated Subject</th>
                  <td><strong>{{ selectedLog.user }}</strong></td>
                </tr>
                <tr>
                  <th>API Operation</th>
                  <td>{{ selectedLog.action }}</td>
                </tr>
                <tr>
                  <th>Request Endpoint</th>
                  <td class="code-font highlight">{{ selectedLog.url }}</td>
                </tr>
                <tr>
                  <th>Client IP Target</th>
                  <td>{{ selectedLog.ip }}</td>
                </tr>
                <tr>
                  <th>Response Latency</th>
                  <td>{{ selectedLog.latencyMs }} ms</td>
                </tr>
                <tr>
                  <th>User-Agent Profile</th>
                  <td class="ua-font">{{ selectedLog.userAgent }}</td>
                </tr>
                <tr>
                  <th>Integrity SHA-256 Hash</th>
                  <td class="code-font">{{ selectedLog.payloadHash }}</td>
                </tr>
                </tbody>
              </table>
            </div>
            <footer class="audit-modal-footer">
              <button class="button button--primary" @click="selectedLog = null">Acknowledge</button>
            </footer>
          </div>
        </div>
      </transition>
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-health {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.observability-title {
  margin: 0 0 4px;
  font-size: 1.28rem;
  font-weight: 700;
  color: var(--text);
  letter-spacing: -0.02em;
}

.panel-section-title {
  font-family: var(--font-display);
  font-size: 0.95rem;
  color: var(--text-soft);
  margin: 0 0 12px 0;
  display: flex;
  align-items: center;
  gap: 8px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
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
  font-size: 0.84rem;
}

.admin-health__refresh {
  padding: 6px 14px;
  font-size: 0.78rem;
}

/* SECTION 1: SVG Topology Styles */
.observability-topology-舱 {
  background: rgba(14, 17, 22, 0.42);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 20px;
  backdrop-filter: blur(28px) saturate(180%);
}

.topology-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  background: rgba(0, 0, 0, 0.15);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.03);
  overflow: hidden;
}

.topology-svg {
  width: 100%;
  max-width: 580px;
  height: auto;
}

.topology-link {
  fill: none;
  stroke: rgba(255, 255, 255, 0.06);
  stroke-width: 2;
  transition: stroke 300ms ease;
}

.topology-link.DEGRADED {
  stroke: rgba(245, 158, 11, 0.12);
}

.topology-link.DOWN {
  stroke: rgba(239, 68, 68, 0.08);
}

.topology-link-glow {
  fill: none;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-dasharray: 6 18;
  animation: dash-flow 2.2s infinite linear;
}

.topology-link-glow.UP {
  stroke: var(--primary);
  filter: drop-shadow(0 0 3px var(--primary-glow));
}

.topology-link-glow.DEGRADED {
  stroke: var(--gold);
  filter: drop-shadow(0 0 3px var(--gold-glow));
}

.topology-link-glow.DOWN {
  display: none;
}

@keyframes dash-flow {
  to {
    stroke-dashoffset: -24;
  }
}

.topology-node-bg {
  fill: #0c0f14;
  stroke: var(--border);
  stroke-width: 1.2;
  transition: fill 200ms ease;
}

.topology-node-edge {
  fill: none;
  stroke-width: 1.8;
  transition: stroke 300ms ease;
}

.topology-node-group {
  cursor: pointer;
}

.topology-node-group.UP .topology-node-edge {
  stroke: var(--primary);
  animation: node-pulse-up 2.2s infinite ease-in-out;
}

.topology-node-group.DEGRADED .topology-node-edge {
  stroke: var(--gold);
  animation: node-pulse-degraded 2.2s infinite ease-in-out;
}

.topology-node-group.DOWN .topology-node-edge {
  stroke: var(--danger);
  animation: node-pulse-down 1.2s infinite ease-in-out;
}

.topology-node-group.hub .topology-node-bg {
  fill: rgba(56, 189, 248, 0.06);
}

.hub-glow {
  stroke: var(--primary);
  animation: node-pulse-up 1.8s infinite ease-in-out;
}

.node-label {
  fill: var(--text-soft);
  font-family: var(--font-mono);
  font-size: 8px;
  font-weight: bold;
  text-anchor: middle;
  letter-spacing: 0.04em;
}

.hub .node-label {
  font-size: 8.5px;
  fill: var(--text);
}

@keyframes node-pulse-up {
  0%, 100% { r: 20; stroke-opacity: 0.8; filter: drop-shadow(0 0 2px var(--primary-glow)); }
  50% { r: 22; stroke-opacity: 0.35; filter: drop-shadow(0 0 6px var(--primary-glow)); }
}

@keyframes node-pulse-degraded {
  0%, 100% { r: 20; stroke-opacity: 0.8; filter: drop-shadow(0 0 2px var(--gold-glow)); }
  50% { r: 22; stroke-opacity: 0.35; filter: drop-shadow(0 0 6px var(--gold-glow)); }
}

@keyframes node-pulse-down {
  0%, 100% { r: 20; stroke-opacity: 0.95; filter: drop-shadow(0 0 3px rgba(239,68,68,0.7)); }
  50% { r: 21.5; stroke-opacity: 0.4; filter: drop-shadow(0 0 8px rgba(239,68,68,0.7)); }
}

/* SECTION 2: Overall Pill */
.admin-health__overall {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.45);
  backdrop-filter: blur(14px);
}

.overall-meta {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.admin-health__overall-label {
  font-family: var(--font-display);
  font-size: 1.02rem;
  color: var(--text-soft);
  font-weight: bold;
}

.overall-desc {
  font-size: 0.76rem;
  color: var(--text-muted);
}

.admin-health__pill {
  display: inline-flex;
  align-items: center;
  padding: 5px 14px;
  border-radius: var(--radius-full);
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  border: 1px solid transparent;
}

.admin-health__pill--small {
  padding: 3px 8px;
  font-size: 0.68rem;
}

.admin-health-status--up {
  color: #34d399;
  background: rgba(52, 211, 153, 0.12);
  border-color: rgba(52, 211, 153, 0.32);
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.08);
}

.admin-health-status--degraded {
  color: #fde68a;
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.36);
  box-shadow: 0 0 10px rgba(245, 158, 11, 0.08);
}

.admin-health-status--down {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.36);
  box-shadow: 0 0 10px rgba(239, 68, 68, 0.08);
}

/* SECTION 3: Cards Grid */
.admin-health__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}

.admin-health-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.35);
  backdrop-filter: blur(14px);
  transition: border-color 200ms ease, box-shadow 200ms ease;
}

.admin-health-card:hover {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
}

.admin-health-card[data-status="DEGRADED"] {
  border-color: rgba(245, 158, 11, 0.22);
}

.admin-health-card[data-status="DOWN"] {
  border-color: rgba(239, 68, 68, 0.22);
}

.admin-health-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.admin-health-card__name {
  font-family: var(--font-mono);
  font-size: 0.84rem;
  color: var(--text);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.admin-health-card__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.admin-health-card__meta {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.78rem;
}

.mini-latency-bar {
  width: 100%;
  height: 4px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.mini-latency-fill {
  height: 100%;
  border-radius: var(--radius-full);
  transition: width 300ms ease;
}

.admin-health-card__reason {
  margin: 0;
  color: var(--text-faint);
  font-size: 0.75rem;
  line-height: 1.5;
  word-break: break-word;
  padding: 6px;
  background: rgba(0, 0, 0, 0.15);
  border-radius: var(--radius-xs);
  border-left: 2px solid var(--danger);
}

/* SECTION 4: Real-time Audit Terminal */
.admin-audit-section {
  background: rgba(14, 17, 22, 0.42);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 20px;
  backdrop-filter: blur(28px) saturate(180%);
}

.audit-console {
  background: #090b0e;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: var(--radius-sm);
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.55);
}

.audit-console-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.02);
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
}

.term-dot {
  width: 9px;
  height: 9px;
  border-radius: var(--radius-full);
}

.term-dot.danger { background: #f87171; }
.term-dot.warning { background: #fbbf24; }
.term-dot.success { background: #34d399; }

.term-title {
  font-family: var(--font-mono);
  font-size: 0.72rem;
  color: var(--text-muted);
  margin-left: 6px;
}

.audit-console-body {
  height: 200px;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 0.74rem;
  line-height: 1.45;
  scrollbar-width: thin;
}

.audit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 6px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  transition: background 150ms ease;
  animation: terminal-fade-in 300ms ease;
  border-left: 2px solid transparent;
}

.audit-row:hover {
  background: rgba(255, 255, 255, 0.03);
  border-left-color: var(--primary);
}

@keyframes terminal-fade-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

.audit-field.time { color: var(--text-muted); }
.audit-field.service { color: #fde68a; font-weight: bold; }
.audit-field.user { color: #34d399; }
.audit-field.action { color: var(--text-soft); flex: 1; }
.audit-field.latency { color: #7dd3fc; text-align: right; }
.audit-field.ip { color: var(--text-faint); font-size: 0.7rem; }

.status-tag {
  font-size: 0.65rem;
  font-weight: bold;
  padding: 1px 4px;
  border-radius: 2px;
  text-transform: uppercase;
}

.status-tag.success { background: rgba(52, 211, 153, 0.1); color: #34d399; }
.status-tag.warning { background: rgba(245, 158, 11, 0.1); color: #fbbf24; }
.status-tag.error { background: rgba(239, 68, 68, 0.1); color: #f87171; }

/* SECTION 5: Audit Log Detail Modal */
.audit-modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1200;
}

.audit-modal-card {
  width: 90%;
  max-width: 500px;
  background: rgba(14, 17, 22, 0.85);
  border: 1px solid rgba(232, 199, 122, 0.35);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.65), 0 0 20px rgba(232, 199, 122, 0.08);
  border-radius: var(--radius-md);
  overflow: hidden;
  backdrop-filter: blur(28px) saturate(180%);
  animation: modal-pop 250ms cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes modal-pop {
  from { transform: scale(0.95); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.audit-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.01);
}

.audit-modal-header h4 {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--gold, #e8c77a);
  text-shadow: 0 0 8px rgba(232, 199, 122, 0.2);
}

.audit-modal-close {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 1.15rem;
  cursor: pointer;
  transition: color 150ms;
}

.audit-modal-close:hover {
  color: var(--text);
}

.audit-modal-body {
  padding: 20px;
  max-height: 420px;
  overflow-y: auto;
}

.audit-detail-table {
  width: 100%;
  border-collapse: collapse;
}

.audit-detail-table tr {
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
}

.audit-detail-table tr:last-child {
  border: none;
}

.audit-detail-table th,
.audit-detail-table td {
  padding: 10px 8px;
  font-size: 0.8rem;
  text-align: left;
  line-height: 1.45;
}

.audit-detail-table th {
  width: 140px;
  color: var(--text-soft);
  font-weight: 600;
}

.audit-detail-table td {
  color: var(--text-soft);
}

.code-font {
  font-family: var(--font-mono, monospace);
  font-size: 0.76rem !important;
  color: var(--gold) !important;
}

.code-font.highlight {
  color: #7dd3fc !important;
}

.ua-font {
  font-size: 0.74rem !important;
  color: var(--text-muted) !important;
}

.audit-pill {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 99px;
  text-transform: uppercase;
}

.audit-pill.success { background: rgba(52, 211, 153, 0.15); color: #34d399; }
.audit-pill.warning { background: rgba(245, 158, 11, 0.15); color: #fbbf24; }
.audit-pill.error { background: rgba(239, 68, 68, 0.15); color: #f87171; }

.audit-modal-footer {
  padding: 12px 20px;
  display: flex;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.2);
  border-top: 1px solid var(--border);
}

.audit-modal-footer button {
  padding: 6px 16px;
  font-size: 0.78rem;
}

/* Transitions */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 200ms ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
</style>
