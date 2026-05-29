<script setup lang="ts">
/**
 * Admin dashboard entry view (R5.1 / 路由父节点 — design.md §Frontend Architecture).
 *
 * 三段式布局：
 *  1. 顶部 sub-nav：横向滚动条，复用 AppHeader 同款 pill 视觉，只在 admin 子树内出现，
 *     高亮当前面板。
 *  2. 中间 router-view：让具体面板（home / active-users / heatmap / …）渲染。
 *  3. 这一层负责让位 AppHeader（`var(--app-header-h, 88px)`），下层 panel CSS 中已经把
 *     `:where(.admin-grid) > .admin-panel` 的 padding-top 抵消掉，所以在 home 网格里
 *     panel 不会重复让位。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const { t } = useI18n()
const route = useRoute()

interface SubNavItem {
  name: string          // RouterLink :to + 用作 key
  path: string
  labelKey: string      // i18n key
}

const items: SubNavItem[] = [
  { name: 'AdminHome',          path: '/admin',                  labelKey: 'admin.nav.home' },
  { name: 'AdminActiveUsers',   path: '/admin/active-users',     labelKey: 'admin.nav.activeUsers' },
  { name: 'AdminMemoryTrends',  path: '/admin/memory-trends',    labelKey: 'admin.nav.memoryTrends' },
  { name: 'AdminEmotion',       path: '/admin/emotion',          labelKey: 'admin.nav.emotion' },
  { name: 'AdminHeatmap',       path: '/admin/heatmap',          labelKey: 'admin.nav.heatmap' },
  { name: 'AdminContributors',  path: '/admin/top-contributors', labelKey: 'admin.nav.contributors' },
  { name: 'AdminFragments',     path: '/admin/fragments',        labelKey: 'admin.nav.fragments' },
  { name: 'AdminResonance',     path: '/admin/resonance',        labelKey: 'admin.nav.resonance' },
  { name: 'AdminHealth',        path: '/admin/health',           labelKey: 'admin.nav.health' },
  { name: 'AdminUsersManagement',     path: '/admin/users-management',     labelKey: 'admin.nav.usersManagement' },
  { name: 'AdminMemoriesManagement',  path: '/admin/memories-management',  labelKey: 'admin.nav.memoriesManagement' },
  { name: 'AdminResonanceManagement', path: '/admin/resonance-management', labelKey: 'admin.nav.resonanceManagement' },
  { name: 'AdminSupport',             path: '/admin/support',              labelKey: 'admin.nav.supportInbox' },
]

const activeName = computed(() => route.name)
</script>

<template>
  <div class="admin-shell">
    <header class="admin-shell__header">
      <div class="admin-shell__title-row">
        <h1 class="admin-shell__title">{{ t('admin.nav.entry') }}</h1>
      </div>

      <nav class="admin-subnav" :aria-label="t('admin.nav.entry')">
        <RouterLink
          v-for="it in items"
          :key="it.name"
          :to="it.path"
          class="admin-subnav__link"
          :class="{ 'admin-subnav__link--active': activeName === it.name }"
        >
          {{ t(it.labelKey) }}
        </RouterLink>
      </nav>
    </header>

    <main class="admin-shell__main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  position: relative;
  width: var(--page-width-wide);
  margin: 0 auto;
  padding: calc(var(--app-header-h, 88px) + 24px) 0 64px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  min-height: calc(100vh - var(--app-header-h, 88px));
}

.admin-shell__header {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.admin-shell__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.admin-shell__title {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(1.6rem, 2.4vw, 2.2rem);
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}

.admin-subnav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: rgba(14, 17, 22, 0.6);
  backdrop-filter: blur(12px);
  overflow-x: auto;
  scrollbar-width: thin;
}

.admin-subnav::-webkit-scrollbar {
  height: 6px;
}
.admin-subnav::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 3px;
}

.admin-subnav__link {
  flex-shrink: 0;
  padding: 8px 16px;
  border-radius: var(--radius-full);
  color: rgba(245, 248, 252, 0.88);
  font-size: 0.95rem;
  font-weight: 700;
  letter-spacing: 0.005em;
  transition: background-color 180ms ease, color 180ms ease;
  white-space: nowrap;
  /* v12：与 .app-nav__link 同源修复 — 强制不参与全局七彩渐变 */
  background-image: none;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  animation: none;
}

.admin-subnav__link:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.06);
}

.admin-subnav__link--active,
.admin-subnav__link.router-link-exact-active {
  color: #052017;
  -webkit-text-fill-color: #052017;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  box-shadow: 0 6px 18px rgba(54, 216, 180, 0.32);
}

.admin-shell__main {
  flex: 1 1 auto;
}

@media (max-width: 720px) {
  .admin-shell {
    width: calc(100vw - 24px);
    padding: calc(var(--app-header-h, 88px) + 16px) 0 48px;
  }
}
</style>
