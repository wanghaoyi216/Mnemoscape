import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

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
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    next('/login')
  } else if (to.meta.guest && auth.isLoggedIn) {
    next('/memories')
  } else {
    next()
  }
})

export default router
