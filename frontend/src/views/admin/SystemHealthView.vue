<script setup lang="ts">
/**
 * System health panel (R18.4).
 *
 * Renders the gateway-aggregated downstream status table:
 *   - top "overall" card
 *   - 6 component cards (auth-service / memory-service / resonance-service /
 *     asset-service / ai-service / redis), each showing status + latency + reason
 *
 * Status → colour mapping per design:
 *   UP = emerald (--primary), DEGRADED = ochre (--gold), DOWN = red (--danger).
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPanel from '../../components/admin/AdminPanel.vue'
import { useAdminHealth } from '../../composables/useAdminHealth'
import type { AdminHealthComponent, AdminHealthStatus } from '../../api/admin'

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

onMounted(() => {
  void fetch()
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
        <p class="admin-panel-subtitle">{{ t('admin.health.subtitle') }}</p>
        <button class="button button--ghost admin-health__refresh" type="button" @click="fetch">
          {{ t('common.refresh') }}
        </button>
      </header>

      <section v-if="data" class="admin-health__overall">
        <span class="admin-health__overall-label">{{ t('admin.health.overall') }}</span>
        <span
          class="admin-health__pill"
          :class="statusToneClass(data.overall)"
        >
          {{ t(`admin.health.status.${data.overall}`, data.overall) }}
        </span>
      </section>

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
          <p class="admin-health-card__meta">
            {{ t('admin.health.latency', { ms: row.latencyMs }) }}
          </p>
          <p v-if="row.reason" class="admin-health-card__reason">{{ row.reason }}</p>
        </article>
      </section>
    </div>
  </AdminPanel>
</template>

<style scoped>
.admin-health {
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

.admin-health__refresh {
  padding: 6px 14px;
  font-size: 0.78rem;
}

.admin-health__overall {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
}

.admin-health__overall-label {
  font-family: var(--font-display);
  font-size: 1.05rem;
  color: var(--text-soft);
}

.admin-health__pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  font-size: 0.82rem;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  border: 1px solid transparent;
}

.admin-health__pill--small {
  padding: 4px 10px;
  font-size: 0.72rem;
}

.admin-health-status--up {
  color: #34d399;
  background: rgba(52, 211, 153, 0.12);
  border-color: rgba(52, 211, 153, 0.32);
}

.admin-health-status--degraded {
  color: #fde68a;
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.36);
}

.admin-health-status--down {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.36);
}

.admin-health__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}

.admin-health-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.02);
  transition: border-color 200ms ease;
}

.admin-health-card[data-status="DEGRADED"] {
  border-color: rgba(245, 158, 11, 0.32);
}

.admin-health-card[data-status="DOWN"] {
  border-color: rgba(239, 68, 68, 0.32);
}

.admin-health-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.admin-health-card__name {
  font-family: var(--font-mono);
  font-size: 0.85rem;
  color: var(--text);
  font-weight: 500;
}

.admin-health-card__meta {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.8rem;
}

.admin-health-card__reason {
  margin: 0;
  color: var(--text-faint);
  font-size: 0.76rem;
  line-height: 1.5;
  word-break: break-word;
}
</style>
