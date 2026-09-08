import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'

const routes = [
  {
    path: '/',
    redirect: '/memories',
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { guest: true },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/RegisterView.vue'),
    meta: { guest: true },
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories',
    name: 'Memories',
    component: () => import('../views/MemoryListView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories/new',
    name: 'MemoryBuilder',
    component: () => import('../views/MemoryBuilderView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories/graph',
    name: 'MemoryGraph',
    component: () => import('../views/MemoryGraphView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories/timeline',
    name: 'MemoryTimeline',
    component: () => import('../views/MemoryTimelineView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories/atlas',
    name: 'MemoryAtlas',
    component: () => import('../views/MemoryAtlasView.vue'),
    meta: { requiresAuth: true },
  },
  {
    // R20 — InstancedMesh 3D memory viewer
    // (legacy /memories/:id with pentagram was 12 FPS at 50k
    // particles; this view uses ONE InstancedMesh per (emotion, layer)
    // → 24 constant draw calls, 55+ FPS).
    path: '/memories/3d',
    name: 'Memory3dViewer',
    component: () => import('../views/Memory3dViewer.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/memories/:id',
    name: 'MemoryDetail',
    component: () => import('../views/MemoryDetailView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/scene/:id',
    name: 'SceneViewer',
    component: () => import('../views/SceneViewer.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/resonance',
    name: 'ResonanceHub',
    component: () => import('../views/ResonanceHubView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/resonance/:id',
    name: 'ResonanceSpace',
    component: () => import('../views/ResonanceSpaceView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/ChatView.vue'),
    meta: { requiresAuth: true },
  },
  // ── Admin dashboard subtree (R4.1) ────────────────────────────────────────
  // Top-level entry mounts a nested <router-view> so each panel owns its own
  // route, allowing operators to deep-link / share specific panels (design.md
  // §"路由命名"). All children inherit `requiresAuth` + `requiresAdmin` via the
  // parent meta so the beforeEach guard need only inspect `to.meta.requiresAdmin`.
  {
    path: '/admin',
    component: () => import('../views/admin/AdminEntryView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'AdminHome',
        component: () => import('../views/admin/AdminHomeView.vue'),
      },
      {
        path: 'active-users',
        name: 'AdminActiveUsers',
        component: () => import('../views/admin/ActiveUsersView.vue'),
      },
      {
        path: 'memory-trends',
        name: 'AdminMemoryTrends',
        component: () => import('../views/admin/MemoryTrendsView.vue'),
      },
      {
        path: 'emotion',
        name: 'AdminEmotion',
        component: () => import('../views/admin/EmotionDistView.vue'),
      },
      {
        path: 'heatmap',
        name: 'AdminHeatmap',
        component: () => import('../views/admin/HeatmapView.vue'),
      },
      {
        path: 'top-contributors',
        name: 'AdminContributors',
        component: () => import('../views/admin/ContributorsView.vue'),
      },
      {
        path: 'fragments',
        name: 'AdminFragments',
        component: () => import('../views/admin/FragmentDiscoveryView.vue'),
      },
      {
        path: 'resonance',
        name: 'AdminResonance',
        component: () => import('../views/admin/ResonanceOverviewView.vue'),
      },
      {
        path: 'health',
        name: 'AdminHealth',
        component: () => import('../views/admin/SystemHealthView.vue'),
      },
      {
        path: 'users-management',
        name: 'AdminUsersManagement',
        component: () => import('../views/admin/AdminUsersManagementView.vue'),
      },
      {
        path: 'memories-management',
        name: 'AdminMemoriesManagement',
        component: () => import('../views/admin/AdminMemoriesManagementView.vue'),
      },
      {
        path: 'resonance-management',
        name: 'AdminResonanceManagement',
        component: () => import('../views/admin/AdminResonanceManagementView.vue'),
      },
      {
        path: 'support',
        name: 'AdminSupport',
        component: () => import('../views/admin/AdminSupportInboxView.vue'),
      },
      {
        path: 'maintenance',
        name: 'AdminMaintenance',
        component: () => import('../views/admin/AdminMaintenanceView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()

  // Existing guest / requiresAuth behaviour preserved; the admin subtree
  // also carries `requiresAuth` so anonymous visits get caught here first
  // and redirected to /login with a `redirect` query (R4.2).
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }
  if (to.meta.guest && auth.isLoggedIn) {
    return next('/memories')
  }

  // R4.1 / R4.2 / R4.3 — admin guard. The `requiresAuth` branch above already
  // handles the unauthenticated case, but we re-check here so the guard is
  // self-contained should anyone ever drop `requiresAuth` from a /admin route.
  if (to.meta.requiresAdmin) {
    if (!auth.isLoggedIn) {
      return next({ path: '/login', query: { redirect: to.fullPath } })
    }
    if (auth.user?.role !== 'ADMIN') {
      // Non-blocking warning toast; redirect to the user's home (R4.3).
      useToastStore().push({ key: 'admin.guard.notAdmin', tone: 'warning' })
      return next('/memories')
    }
  }

  return next()
})

export default router
