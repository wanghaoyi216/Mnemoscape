<script setup lang="ts">
/**
 * Admin home — overview grid of all 8 panels (R5.1 / R5.2 / R5.3).
 *
 * Upgraded with "Enter Cinematic HUD Mode" to provide an extremely rich,
 * high-tech, glowing, dark-themed cyberpunk aesthetic with a spinning 3D Heatmap globe
 * centerpiece, and customized sci-fi borders and scanning micro-animations.
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import ActiveUsersView from './ActiveUsersView.vue'
import MemoryTrendsView from './MemoryTrendsView.vue'
import EmotionDistView from './EmotionDistView.vue'
import HeatmapView from './HeatmapView.vue'
import ContributorsView from './ContributorsView.vue'
import FragmentDiscoveryView from './FragmentDiscoveryView.vue'
import ResonanceOverviewView from './ResonanceOverviewView.vue'
import SystemHealthView from './SystemHealthView.vue'

const { t } = useI18n()
const router = useRouter()

const isHudActive = ref(false)
const currentTimeString = ref('')
let timer = 0

function updateTime() {
  const d = new Date()
  currentTimeString.value = d.toLocaleTimeString()
}

onMounted(() => {
  updateTime()
  timer = window.setInterval(updateTime, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

interface CellSpec {
  routeName: string
  navKey: string
  span: 'half' | 'full'
}

const cells: CellSpec[] = [
  { routeName: 'AdminActiveUsers',  navKey: 'admin.nav.activeUsers',  span: 'half' },
  { routeName: 'AdminMemoryTrends', navKey: 'admin.nav.memoryTrends', span: 'half' },
  { routeName: 'AdminEmotion',      navKey: 'admin.nav.emotion',      span: 'half' },
  { routeName: 'AdminContributors', navKey: 'admin.nav.contributors', span: 'half' },
  { routeName: 'AdminHeatmap',      navKey: 'admin.nav.heatmap',      span: 'full' },
  { routeName: 'AdminResonance',    navKey: 'admin.nav.resonance',    span: 'full' },
  { routeName: 'AdminFragments',    navKey: 'admin.nav.fragments',    span: 'half' },
  { routeName: 'AdminHealth',       navKey: 'admin.nav.health',       span: 'half' },
]

function componentForCell(routeName: string) {
  switch (routeName) {
    case 'AdminActiveUsers':  return ActiveUsersView
    case 'AdminMemoryTrends': return MemoryTrendsView
    case 'AdminEmotion':      return EmotionDistView
    case 'AdminHeatmap':      return HeatmapView
    case 'AdminContributors': return ContributorsView
    case 'AdminFragments':    return FragmentDiscoveryView
    case 'AdminResonance':    return ResonanceOverviewView
    case 'AdminHealth':       return SystemHealthView
    default: return null
  }
}

function openPanel(name: string): void {
  void router.push({ name })
}
</script>

<template>
  <div class="admin-wrapper" :class="{ 'hud-active': isHudActive }">
    <!-- Header control bar (only shown in normal grid mode) -->
    <div v-if="!isHudActive" class="hud-control-bar">
      <button
        type="button"
        class="hud-toggle-btn"
        @click="isHudActive = !isHudActive"
      >
        <span>{{ t('admin.hud.toggle') }}</span>
      </button>
    </div>

    <!-- Regular Grid Mode -->
    <div v-if="!isHudActive" class="admin-grid">
      <article
        v-for="cell in cells"
        :key="cell.routeName"
        class="admin-grid__cell"
        :class="{
          'admin-grid__cell--full': cell.span === 'full',
        }"
      >
        <header class="admin-grid__cell-head">
          <button
            type="button"
            class="admin-grid__open"
            :aria-label="t(cell.navKey)"
            @click="openPanel(cell.routeName)"
          >
            <span>{{ t(cell.navKey) }}</span>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
              <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
        </header>

        <div class="admin-grid__panel-wrap">
          <component :is="componentForCell(cell.routeName)" />
        </div>
      </article>
    </div>

    <!-- Sci-Fi HUD Mode -->
    <div v-else class="hud-dashboard">
      <!-- Digital scanlines background -->
      <div class="hud-scanlines"></div>
      <div class="hud-grid-overlay"></div>

      <!-- HUD Header -->
      <header class="hud-header">
        <div class="hud-header__corner hud-header__corner--left"></div>
        <div class="hud-header__corner hud-header__corner--right"></div>
        <div class="hud-header__title-group">
          <h1 class="hud-header__title">{{ t('admin.hud.title') }}</h1>
          <p class="hud-header__desc">{{ t('admin.hud.description') }}</p>
        </div>
        <div class="hud-header__right">
          <div class="hud-header__time">
            <span class="hud-header__pulse">● ONLINE</span>
            <span class="hud-header__clock">{{ currentTimeString }}</span>
          </div>
          <button
            type="button"
            class="hud-exit-btn"
            @click="isHudActive = false"
          >
            <span class="pulse-dot pulse-dot--red"></span>
            <span>{{ t('admin.hud.exit') }}</span>
          </button>
        </div>
      </header>

      <!-- HUD Columns Grid -->
      <div class="hud-layout">
        <!-- Left Column (Active Users, Trends, Health) -->
        <aside class="hud-col hud-col--left">
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.activeUsers') }}</h2>
              <span class="hud-card__badge hud-card__badge--cyan">SYS.ACT_MONITOR</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ TRACKING ACTIVE INGRESS CHANNELS...</p>
            <div class="hud-card__content">
              <ActiveUsersView />
            </div>
          </div>
          
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.memoryTrends') }}</h2>
              <span class="hud-card__badge hud-card__badge--purple">MEM.LOG_DYNAMICS</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ ANALYZING RECONSTRUCTION FREQUENCIES...</p>
            <div class="hud-card__content">
              <MemoryTrendsView />
            </div>
          </div>
          
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.health') }}</h2>
              <span class="hud-card__badge hud-card__badge--green">CORE.NODE_HEARTBEAT</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ SCANNING SERVICES LATENCY MATRIX...</p>
            <div class="hud-card__content">
              <SystemHealthView />
            </div>
          </div>
        </aside>

        <!-- Center Column (Heatmap Globe centerpiece) -->
        <main class="hud-col hud-col--center">
          <div class="hud-card hud-card--globe">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <!-- Sci-fi scanning scope overlay -->
            <div class="hud-globe-scope"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.heatmap') }}</h2>
              <span class="hud-card__badge hud-card__badge--rose">RESONANCE.GLOBAL_GEO</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ MONITORING WORLDWIDE EMOTIONAL ANOMALIES...</p>
            <div class="hud-card__content hud-card__content--globe">
              <HeatmapView />
            </div>
          </div>
        </main>

        <!-- Right Column (Emotion Radar, Contributors, Fragments) -->
        <aside class="hud-col hud-col--right">
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.emotion') }}</h2>
              <span class="hud-card__badge hud-card__badge--cyan">AESTHETICS.MIND_RADAR</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ COMPUTING EMOTION DISTRIBUTION SPECTRA...</p>
            <div class="hud-card__content">
              <EmotionDistView />
            </div>
          </div>
          
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.contributors') }}</h2>
              <span class="hud-card__badge hud-card__badge--purple">NET.NODE_ENGAGEMENT</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ RANKING SYNAPSE AMPLITUDE PROVIDERS...</p>
            <div class="hud-card__content">
              <ContributorsView />
            </div>
          </div>
          
          <div class="hud-card">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.fragments') }}</h2>
              <span class="hud-card__badge hud-card__badge--rose">MND.UNSTABLE_FRAGMENTS</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ SCANNING FOR FORGOTTEN DATA FLASHOVER...</p>
            <div class="hud-card__content">
              <FragmentDiscoveryView />
            </div>
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-wrapper {
  position: relative;
  width: 100%;
}

.hud-control-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
  z-index: 10000;
  position: relative;
}

.hud-toggle-btn {
  appearance: none;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(6, 182, 212, 0.4);
  color: #06b6d4;
  padding: 8px 16px;
  border-radius: 8px;
  font-family: inherit;
  font-weight: 700;
  letter-spacing: 0.1em;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  backdrop-filter: blur(8px);
  box-shadow: 0 0 15px rgba(6, 182, 212, 0.15);
  transition: all 0.3s ease;
}

.hud-toggle-btn:hover {
  background: rgba(6, 182, 212, 0.2);
  box-shadow: 0 0 25px rgba(6, 182, 212, 0.4);
  color: #22d3ee;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background-color: #22c55e;
  border-radius: 50%;
  box-shadow: 0 0 8px #22c55e;
  animation: pulse 1.5s infinite;
  flex-shrink: 0;
}

.pulse-dot--red {
  background-color: #f43f5e;
  box-shadow: 0 0 8px #f43f5e;
}

@keyframes pulse {
  0% { transform: scale(0.9); opacity: 0.6; }
  50% { transform: scale(1.2); opacity: 1; }
  100% { transform: scale(0.9); opacity: 0.6; }
}

.admin-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 18px;
}

.admin-grid__cell {
  grid-column: span 6;
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 12px;
}

.admin-grid__cell--full {
  grid-column: span 12;
}

.admin-grid__cell-head {
  display: flex;
  justify-content: flex-end;
  padding: 4px 8px 0;
}

.admin-grid__open {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.78rem;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: background-color 160ms ease, color 160ms ease;
}

.admin-grid__open:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.04);
}

/* Inside the grid, panels rendered by sub-components should NOT keep their
   own border/background (the grid cell already owns the box). */
:deep(.admin-panel) {
  border: none;
  background: transparent;
  padding: 0;
}

@media (max-width: 1279px) {
  .admin-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .admin-grid__cell,
  .admin-grid__cell--full {
    grid-column: span 1;
  }
}

@media (max-width: 768px) {
  .admin-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .admin-grid__cell,
  .admin-grid__cell--full {
    grid-column: span 1;
  }
}

/* Sci-fi HUD Dashboard Layout */
.hud-dashboard {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #03050c;
  color: #22d3ee;
  overflow: hidden;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  font-family: 'Outfit', 'Inter', system-ui, -apple-system, sans-serif;
}

.hud-scanlines {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    rgba(18, 16, 16, 0) 50%,
    rgba(0, 0, 0, 0.35) 50%
  );
  background-size: 100% 4px;
  pointer-events: none;
  z-index: 20;
}

.hud-grid-overlay {
  position: absolute;
  inset: 0;
  background-image: 
    linear-gradient(rgba(6, 182, 212, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(6, 182, 212, 0.03) 1px, transparent 1px);
  background-size: 40px 40px;
  pointer-events: none;
  z-index: 10;
}

.hud-header {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background: rgba(6, 11, 23, 0.7);
  border: 1px solid rgba(6, 182, 212, 0.3);
  border-radius: 8px;
  backdrop-filter: blur(12px);
  box-shadow: inset 0 0 20px rgba(6, 182, 212, 0.15), 0 0 10px rgba(6, 182, 212, 0.1);
  z-index: 30;
}

.hud-header__title-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hud-header__title {
  font-size: 1.4rem;
  font-weight: 800;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: #fff;
  text-shadow: 0 0 10px rgba(6, 182, 212, 0.6);
  margin: 0;
}

.hud-header__desc {
  font-size: 0.8rem;
  color: #8be6ff;
  margin: 0;
  opacity: 0.85;
}

.hud-header__time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.hud-header__right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.hud-exit-btn {
  appearance: none;
  background: rgba(244, 63, 94, 0.08);
  border: 1px solid rgba(244, 63, 94, 0.4);
  color: #f43f5e;
  padding: 8px 18px;
  border-radius: 6px;
  font-family: 'Courier New', Courier, monospace;
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  backdrop-filter: blur(8px);
  box-shadow: 0 0 12px rgba(244, 63, 94, 0.1);
  transition: all 0.25s ease;
  white-space: nowrap;
}

.hud-exit-btn:hover {
  background: rgba(244, 63, 94, 0.18);
  border-color: rgba(244, 63, 94, 0.7);
  box-shadow: 0 0 20px rgba(244, 63, 94, 0.35);
  color: #fb7185;
}

.hud-header__pulse {
  font-size: 0.75rem;
  font-weight: 800;
  color: #22c55e;
  letter-spacing: 0.05em;
  text-shadow: 0 0 6px #22c55e;
}

.hud-header__clock {
  font-family: 'Courier New', Courier, monospace;
  font-size: 1.1rem;
  font-weight: bold;
  color: #22d3ee;
  text-shadow: 0 0 8px rgba(6, 182, 212, 0.5);
}

.hud-layout {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 20px;
  overflow: hidden;
  z-index: 30;
}

.hud-col {
  display: flex;
  flex-direction: column;
  gap: 20px;
  overflow-y: auto;
  padding-right: 4px;
}

/* Custom Scrollbar for Sci-Fi HUD columns */
.hud-col::-webkit-scrollbar {
  width: 4px;
}
.hud-col::-webkit-scrollbar-track {
  background: rgba(6, 182, 212, 0.02);
}
.hud-col::-webkit-scrollbar-thumb {
  background: rgba(6, 182, 212, 0.2);
  border-radius: 2px;
}

.hud-col--left {
  grid-column: span 3;
}

.hud-col--center {
  grid-column: span 6;
  overflow: hidden; /* Main centerpiece shouldn't scroll */
  display: flex;
  flex-direction: column;
}

.hud-col--right {
  grid-column: span 3;
}

/* Glowing Cyberpunk Card design */
.hud-card {
  position: relative;
  background: rgba(6, 12, 24, 0.65);
  border: 1px solid rgba(6, 182, 212, 0.25);
  border-radius: 8px;
  padding: 16px;
  backdrop-filter: blur(16px);
  box-shadow: inset 0 0 15px rgba(6, 182, 212, 0.05), 0 8px 32px rgba(0, 0, 0, 0.6);
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.3s ease, box-shadow 0.3s ease;
}

.hud-card:hover {
  border-color: rgba(6, 182, 212, 0.5);
  box-shadow: inset 0 0 20px rgba(6, 182, 212, 0.1), 0 12px 40px rgba(6, 182, 212, 0.2);
}

.hud-card__header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}

.hud-card__badge {
  font-family: 'Courier New', Courier, monospace;
  font-size: 0.65rem;
  font-weight: 800;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid;
  letter-spacing: 0.05em;
  text-shadow: 0 0 4px currentColor;
}

.hud-card__badge--cyan {
  color: #22d3ee;
  background: rgba(34, 211, 238, 0.08);
  border-color: rgba(34, 211, 238, 0.3);
}

.hud-card__badge--purple {
  color: #a855f7;
  background: rgba(168, 85, 247, 0.08);
  border-color: rgba(168, 85, 247, 0.3);
}

.hud-card__badge--green {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.08);
  border-color: rgba(34, 197, 94, 0.3);
}

.hud-card__badge--rose {
  color: #f43f5e;
  background: rgba(244, 63, 94, 0.08);
  border-color: rgba(244, 63, 94, 0.3);
}

.hud-card__subtitle {
  font-family: 'Courier New', Courier, monospace;
  font-size: 0.68rem;
  color: rgba(6, 182, 212, 0.7);
  margin: 0 0 4px 0;
  letter-spacing: 0.04em;
  text-shadow: 0 0 2px rgba(6, 182, 212, 0.3);
  animation: typingGlow 3s infinite alternate;
}

@keyframes typingGlow {
  from { opacity: 0.75; text-shadow: 0 0 2px rgba(6, 182, 212, 0.2); }
  to { opacity: 1; text-shadow: 0 0 5px rgba(6, 182, 212, 0.6); }
}

.hud-card--globe {
  flex: 1;
  min-height: 0;
  background: rgba(3, 5, 12, 0.85);
  border-color: rgba(6, 182, 212, 0.4);
  box-shadow: inset 0 0 30px rgba(6, 182, 212, 0.1);
}

.hud-card__title {
  font-size: 0.95rem;
  font-weight: 800;
  text-transform: uppercase;
  color: #fff;
  letter-spacing: 0.08em;
  margin: 0;
  border-left: 3px solid #06b6d4;
  padding-left: 8px;
  text-shadow: 0 0 8px rgba(6, 182, 212, 0.4);
}

.hud-card__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  position: relative;
}

.hud-card__content--globe {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: stretch;
  border-radius: 6px;
  overflow: hidden;
  border: 1px dashed rgba(6, 182, 212, 0.2);
  background: radial-gradient(circle, rgba(6, 182, 212, 0.05) 0%, transparent 75%);
}

/* Sci-fi crosshair details in corners */
.hud-header__corner,
.hud-card__corner {
  position: absolute;
  width: 10px;
  height: 10px;
  pointer-events: none;
}

.hud-header__corner--left {
  left: -1px; top: -1px;
  border-left: 2px solid #06b6d4;
  border-top: 2px solid #06b6d4;
}

.hud-header__corner--right {
  right: -1px; bottom: -1px;
  border-right: 2px solid #06b6d4;
  border-bottom: 2px solid #06b6d4;
}

.hud-card__corner--tl {
  left: -1px; top: -1px;
  border-left: 2px solid #06b6d4;
  border-top: 2px solid #06b6d4;
}

.hud-card__corner--br {
  right: -1px; bottom: -1px;
  border-right: 2px solid #06b6d4;
  border-bottom: 2px solid #06b6d4;
}

/* Holographic scanner sight overlay for globe */
.hud-globe-scope {
  position: absolute;
  inset: 20px;
  border: 1px solid rgba(6, 182, 212, 0.05);
  border-radius: 50%;
  pointer-events: none;
  animation: rotateScope 24s linear infinite;
  z-index: 5;
}

.hud-globe-scope::before {
  content: '';
  position: absolute;
  top: -5px; left: 50%;
  transform: translateX(-50%);
  width: 10px;
  height: 10px;
  background: #06b6d4;
  border-radius: 50%;
  box-shadow: 0 0 10px #06b6d4;
}

@keyframes rotateScope {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Inside HUD, deep force custom panels styles */
:deep(.hud-dashboard .admin-panel) {
  border: none;
  background: transparent;
  padding: 0;
  box-shadow: none;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}
:deep(.hud-dashboard .admin-panel__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
:deep(.hud-dashboard .admin-panel__title),
:deep(.hud-dashboard .admin-panel__subtitle),
:deep(.hud-dashboard .admin-panel__header) {
  display: none !important; /* Hide original titles so HUD titles take precedence */
}

/* Make map stretch in HUD */
:deep(.hud-dashboard .admin-heatmap) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}
:deep(.hud-dashboard .admin-heatmap .admin-panel-controls) {
  display: none; /* Hide resolution controls in HUD — globe speaks for itself */
}
:deep(.hud-dashboard .admin-heatmap__map) {
  flex: 1;
  min-height: 0;
  height: 100% !important;
  background: transparent !important;
  border: 1px solid rgba(6, 182, 212, 0.1);
}

/* Clean controls and inputs inside HUD */
:deep(.hud-dashboard select),
:deep(.hud-dashboard button:not(.hud-exit-btn)) {
  background: rgba(6, 182, 212, 0.08) !important;
  border: 1px solid rgba(6, 182, 212, 0.3) !important;
  color: #fff !important;
  font-family: inherit;
  font-size: 0.8rem;
  border-radius: 4px;
}

:deep(.hud-dashboard select:focus),
:deep(.hud-dashboard button:not(.hud-exit-btn):hover) {
  background: rgba(6, 182, 212, 0.2) !important;
  border-color: rgba(6, 182, 212, 0.6) !important;
  box-shadow: 0 0 10px rgba(6, 182, 212, 0.3);
}

/* Deep overwrite colors of ECharts charts inside HUD mode to achieve maximum cyber vibe */
:deep(.hud-dashboard canvas) {
  filter: hue-rotate(20deg) saturate(130%) drop-shadow(0 0 4px rgba(6, 182, 212, 0.15));
}
</style>
