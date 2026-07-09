<script setup lang="ts">
/**
 * Admin dashboard entry view (R5.1 / 路由父节点 — design.md §Frontend Architecture).
 *
 * Vertical Sidebar layout:
 *  1. Left vertical sidebar: Fixed width (250px), sticky position. Stacked with beautiful SVG icons.
 *  2. Right main container: Displays the active panel view dynamically.
 *  3. Responsive behavior: Automatically collapses to compact icon-only column (72px) under 1200px.
 *     Transitions to horizontal scrolling layout on mobile screens (<768px).
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()

const isCollapsed = ref(false)

interface SubNavItem {
  name: string
  path: string
  labelKey: string
  iconPaths: string[]
}

const items: SubNavItem[] = [
  {
    name: 'AdminHome',
    path: '/admin',
    labelKey: 'admin.nav.home',
    iconPaths: ['M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', 'M9 22V12h6v10']
  },
  {
    name: 'AdminActiveUsers',
    path: '/admin/active-users',
    labelKey: 'admin.nav.activeUsers',
    iconPaths: ['M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M23 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75']
  },
  {
    name: 'AdminMemoryTrends',
    path: '/admin/memory-trends',
    labelKey: 'admin.nav.memoryTrends',
    iconPaths: ['M23 6l-9.5 9.5-5-5L1 18', 'M17 6h6v6']
  },
  {
    name: 'AdminEmotion',
    path: '/admin/emotion',
    labelKey: 'admin.nav.emotion',
    iconPaths: ['M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z']
  },
  {
    name: 'AdminHeatmap',
    path: '/admin/heatmap',
    labelKey: 'admin.nav.heatmap',
    iconPaths: ['M12 2a8 8 0 0 0-8 8c0 5.25 8 12 8 12s8-6.75 8-12a8 8 0 0 0-8-8z', 'M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z']
  },
  {
    name: 'AdminContributors',
    path: '/admin/top-contributors',
    labelKey: 'admin.nav.contributors',
    iconPaths: ['M6 9H4.5a2.5 2.5 0 0 1 0-5H6', 'M18 9h1.5a2.5 2.5 0 0 0 0-5H18', 'M4 22h16', 'M10 14.66V17c0 .55-.45 1-1 1H4v2h16v-2h-5c-.55 0-1-.45-1-1v-2.34', 'M12 2a4 4 0 0 0-4 4v5a4 4 0 0 0 8 0V6a4 4 0 0 0-4-4z']
  },
  {
    name: 'AdminFragments',
    path: '/admin/fragments',
    labelKey: 'admin.nav.fragments',
    iconPaths: ['M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z', 'M3.27 6.96L12 12.01l8.73-5.05', 'M12 22.08V12']
  },
  {
    name: 'AdminResonance',
    path: '/admin/resonance',
    labelKey: 'admin.nav.resonance',
    iconPaths: ['M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z', 'M12 6v12', 'M6 12h12']
  },
  {
    name: 'AdminHealth',
    path: '/admin/health',
    labelKey: 'admin.nav.health',
    iconPaths: ['M22 12h-4l-3 9L9 3l-3 9H2']
  },
  {
    name: 'AdminUsersManagement',
    path: '/admin/users-management',
    labelKey: 'admin.nav.usersManagement',
    iconPaths: ['M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2', 'M8 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M19 16v6', 'M16 19h6']
  },
  {
    name: 'AdminMemoriesManagement',
    path: '/admin/memories-management',
    labelKey: 'admin.nav.memoriesManagement',
    iconPaths: ['M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z']
  },
  {
    name: 'AdminResonanceManagement',
    path: '/admin/resonance-management',
    labelKey: 'admin.nav.resonanceManagement',
    iconPaths: ['M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71', 'M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71']
  },
  {
    name: 'AdminSupport',
    path: '/admin/support',
    labelKey: 'admin.nav.supportInbox',
    iconPaths: ['M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z', 'M22 6l-10 7L2 6']
  },
  {
    name: 'AdminMaintenance',
    path: '/admin/maintenance',
    labelKey: 'admin.nav.maintenance',
    iconPaths: ['M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z']
  }
]

const activeName = computed(() => route.name)
</script>

<template>
  <div class="admin-shell">
    <!-- Left Sidebar -->
    <aside class="admin-sidebar" :class="{ 'admin-sidebar--collapsed': isCollapsed }">
      <div class="admin-sidebar__logo">
        <span class="admin-sidebar__logo-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 3 4 6v6c0 4.5 3.4 8.4 8 9 4.6-.6 8-4.5 8-9V6l-8-3z" />
            <path d="m9 12 2.2 2.2L15 10.5" />
          </svg>
        </span>
        <span class="admin-sidebar__logo-text">{{ t('admin.nav.entry') }}</span>
      </div>

      <nav class="admin-sidebar__nav">
        <RouterLink
          v-for="it in items"
          :key="it.name"
          :to="it.path"
          class="admin-sidebar__link"
          :class="{ 'admin-sidebar__link--active': activeName === it.name }"
          :title="t(it.labelKey)"
        >
          <svg class="admin-sidebar__link-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path v-for="(p, idx) in it.iconPaths" :key="idx" :d="p" />
          </svg>
          <span class="admin-sidebar__link-text">{{ t(it.labelKey) }}</span>
        </RouterLink>
      </nav>

      <!-- Manual Collapse Toggle -->
      <button class="admin-sidebar__toggle" @click="isCollapsed = !isCollapsed" :aria-label="isCollapsed ? '展开菜单' : '收起菜单'">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path :d="isCollapsed ? 'M13 5l7 7-7 7M5 5l7 7-7 7' : 'M11 19l-7-7 7-7M19 19l-7-7 7-7'" />
        </svg>
      </button>
    </aside>

    <!-- Main Content Area -->
    <div class="admin-shell__container">
      <main class="admin-shell__main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-shell {
  position: relative;
  width: 100%;
  padding: calc(var(--app-header-h, 88px) + 24px) 24px 64px;
  display: flex;
  flex-direction: row;
  gap: 28px;
  min-height: calc(100vh - var(--app-header-h, 88px));
  box-sizing: border-box;
}

.admin-sidebar {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: rgba(14, 17, 22, 0.4);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 16px;
  box-sizing: border-box;
  position: sticky;
  top: calc(var(--app-header-h, 88px) + 24px);
  height: calc(100vh - var(--app-header-h, 88px) - 88px);
  width: 250px;
  transition: width 0.25s cubic-bezier(0.16, 1, 0.3, 1), padding 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  overflow-y: auto;
  overflow-x: hidden;
  gap: 20px;
  scrollbar-width: none; /* Hide scrollbar for sidebar nav */
}

.admin-sidebar::-webkit-scrollbar {
  display: none;
}

.admin-sidebar--collapsed {
  width: 72px;
  padding: 16px 8px;
  align-items: center;
}

.admin-sidebar__logo {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 8px;
  height: 36px;
  box-sizing: border-box;
}

.admin-sidebar__logo-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--primary);
  flex-shrink: 0;
}

.admin-sidebar__logo-text {
  font-family: var(--font-display);
  font-size: 1.1rem;
  font-weight: 750;
  color: var(--text);
  white-space: nowrap;
}

.admin-sidebar--collapsed .admin-sidebar__logo {
  justify-content: center;
  padding: 4px 0;
}
.admin-sidebar--collapsed .admin-sidebar__logo-text {
  display: none;
}

.admin-sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-grow: 1;
  width: 100%;
}

.admin-sidebar__link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  color: rgba(245, 248, 252, 0.85);
  font-size: 0.94rem;
  font-weight: 700;
  transition: all 180ms ease;
  white-space: nowrap;
  text-decoration: none;
  background-image: none;
  -webkit-text-fill-color: currentColor;
  box-sizing: border-box;
  width: 100%;
  
  /* 覆盖 style.css 里的 overflow: hidden，确保标签能在主侧边栏完整渲染 */
  overflow: visible;
  text-overflow: clip;
}

.admin-sidebar__link:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.05);
}

.admin-sidebar__link--active,
.admin-sidebar__link.router-link-exact-active {
  color: #052017;
  -webkit-text-fill-color: #052017;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.28);
}

.admin-sidebar__link-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
}

.admin-sidebar__link-text {
  transition: opacity 0.2s ease;
}

.admin-sidebar--collapsed .admin-sidebar__link {
  justify-content: center;
  padding: 10px 0;
  border-radius: var(--radius-full);
}

.admin-sidebar--collapsed .admin-sidebar__link-text {
  display: none;
}

.admin-sidebar__toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text-soft);
  cursor: pointer;
  transition: all 180ms ease;
  margin: 0 auto;
  flex-shrink: 0;
}

.admin-sidebar__toggle:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.2);
}

.admin-shell__container {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: center;
}

.admin-shell__main {
  width: 100%;
  max-width: 1600px;
}

/* Responsive collapse on medium screens */
@media (max-width: 1200px) {
  .admin-sidebar {
    width: 72px;
    padding: 16px 8px;
    align-items: center;
  }
  .admin-sidebar__logo-text,
  .admin-sidebar__link-text,
  .admin-sidebar__toggle {
    display: none !important;
  }
  .admin-sidebar__logo {
    justify-content: center;
    padding: 4px 0;
  }
  .admin-sidebar__link {
    justify-content: center;
    padding: 10px 0;
    border-radius: var(--radius-full);
  }
}

/* Mobile responsive horizontal scrolling nav */
@media (max-width: 768px) {
  .admin-shell {
    flex-direction: column;
    padding: calc(var(--app-header-h, 88px) + 16px) 16px 48px;
    gap: 16px;
  }
  .admin-sidebar {
    width: 100%;
    height: auto;
    position: static;
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 8px;
    border-radius: var(--radius-md);
    gap: 8px;
  }
  .admin-sidebar__logo {
    display: none !important;
  }
  .admin-sidebar__nav {
    flex-direction: row;
    width: auto;
    gap: 8px;
  }
  .admin-sidebar__link {
    width: auto;
    padding: 8px 12px;
    border-radius: var(--radius-full);
  }
  .admin-sidebar--collapsed {
    width: 100%;
    padding: 8px;
    align-items: stretch;
  }
  .admin-sidebar--collapsed .admin-sidebar__link-text {
    display: inline;
  }
  .admin-sidebar--collapsed .admin-sidebar__link {
    width: auto;
    padding: 8px 12px;
    border-radius: var(--radius-full);
  }
}
</style>
