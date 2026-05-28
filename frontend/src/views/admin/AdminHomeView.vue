<script setup lang="ts">
/**
 * Admin home — overview grid of all 8 panels (R5.1 / R5.2 / R5.3).
 *
 * Layout:
 *   - desktop (≥ 1280px): 12-column grid, panels span 6/6 or 4/8 to balance
 *     density vs readability;
 *   - tablet (768–1279px): 2-column grid, panels each span 1 column;
 *   - mobile (< 768px): single column.
 *
 * Each cell mounts the same panel components used on the dedicated routes,
 * so the home page is a thin composition layer rather than a separate
 * implementation. The panel CSS already de-doubles `padding-top` when nested
 * inside `.admin-grid` (see AdminPanel.vue's :where(.admin-grid) selector).
 */
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

interface CellSpec {
  routeName: string
  navKey: string
  span: 'half' | 'full'
}

// Mounting order is also visual order. The two stacked-density panels
// (active-users line + memory-trends column) take the top row; emotion radar
// + contributors bar share the second row; heatmap (which needs space) +
// resonance graph share the third row; fragments + health bring up the rear.
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
  <div class="admin-grid">
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
</template>

<style scoped>
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

.admin-grid__panel-wrap {
  /* Reset the AdminPanel's top let-pass-AppHeader padding inside the grid cell —
     selector mirrors AdminPanel.vue's `:where(.admin-grid) > .admin-panel`. */
}

/* Inside the grid, panels rendered by sub-components should NOT keep their
   own border/background (the grid cell already owns the box). We can't easily
   override scoped styles, so rely on the class-defined --app-header-h reset. */
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
</style>
