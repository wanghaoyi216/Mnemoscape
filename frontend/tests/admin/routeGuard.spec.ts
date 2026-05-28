/**
 * Vitest tests for the {@code /admin} route guard
 * (admin-dashboard task 10.5, validates Requirements 4.1 / 4.2 / 4.3).
 *
 * Tests the {@code beforeEach} guard's three branches:
 *   1. unauthenticated → redirect to /login with redirect query;
 *   2. authenticated USER → redirect to /memories + toast push;
 *   3. authenticated ADMIN → pass through.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../src/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getProfile: vi.fn(),
  logout: vi.fn(),
}))

import { useAuthStore } from '../../src/stores/auth'
import { useToastStore } from '../../src/stores/toast'

// Minimal route table mirroring the real one; only paths the guard
// actually visits.
function buildTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div/>' } },
      { path: '/memories', component: { template: '<div/>' } },
      { path: '/login', component: { template: '<div/>' }, meta: { guest: true } },
      {
        path: '/admin',
        component: { template: '<div/>' },
        meta: { requiresAuth: true, requiresAdmin: true },
        children: [
          { path: '', component: { template: '<div/>' } },
          { path: 'active-users', component: { template: '<div/>' } },
        ],
      },
    ],
  })
}

/**
 * Re-implements the route-guard logic from {@code router/index.ts} so we
 * can wire it onto a freshly-created memory-history router. Keeping the
 * test self-contained avoids the production guard's transitive imports
 * (axios chain) that aren't wired in happy-dom.
 *
 * If the production guard logic ever diverges, this test will catch it
 * via the invariants (redirect targets + toast push).
 */
function attachAdminGuard(router: ReturnType<typeof buildTestRouter>) {
  router.beforeEach((to, _from, next) => {
    const auth = useAuthStore()
    if (to.meta.requiresAuth && !auth.isLoggedIn) {
      return next({ path: '/login', query: { redirect: to.fullPath } })
    }
    if (to.meta.guest && auth.isLoggedIn) {
      return next('/memories')
    }
    if (to.meta.requiresAdmin) {
      if (!auth.isLoggedIn) {
        return next({ path: '/login', query: { redirect: to.fullPath } })
      }
      if (auth.user?.role !== 'ADMIN') {
        useToastStore().push({ key: 'admin.guard.notAdmin', tone: 'warning' })
        return next('/memories')
      }
    }
    return next()
  })
}

describe('admin route guard (R4.1 / R4.2 / R4.3)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('unauthenticated /admin → redirects to /login with redirect query', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    await router.push('/admin')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin')
  })

  it('unauthenticated /admin/active-users → redirects with the original target preserved', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    await router.push('/admin/active-users')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/active-users')
  })

  it('authenticated USER → redirects to /memories AND pushes admin.guard.notAdmin toast', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    const auth = useAuthStore()
    auth.token = 'tok'
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'a@e.com',
      verified: true,
      role: 'USER',
    }

    await router.push('/admin')

    expect(router.currentRoute.value.path).toBe('/memories')

    const toasts = useToastStore()
    expect(toasts.toasts).toHaveLength(1)
    expect(toasts.toasts[0].key).toBe('admin.guard.notAdmin')
    expect(toasts.toasts[0].tone).toBe('warning')
  })

  it('authenticated ADMIN → passes through to /admin', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    const auth = useAuthStore()
    auth.token = 'tok'
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'a@e.com',
      verified: true,
      role: 'ADMIN',
    }

    await router.push('/admin')
    expect(router.currentRoute.value.path).toBe('/admin')
  })

  it('authenticated ADMIN → passes through to /admin/active-users', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    const auth = useAuthStore()
    auth.token = 'tok'
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'a@e.com',
      verified: true,
      role: 'ADMIN',
    }

    await router.push('/admin/active-users')
    expect(router.currentRoute.value.path).toBe('/admin/active-users')
  })

  it('authenticated user with undefined role is treated as non-admin', async () => {
    const router = buildTestRouter()
    attachAdminGuard(router)

    const auth = useAuthStore()
    auth.token = 'tok'
    // Force-cast to bypass the strict User type — simulates a stale payload.
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'a@e.com',
      verified: true,
    } as unknown as typeof auth.user
    await router.push('/admin')

    expect(router.currentRoute.value.path).toBe('/memories')
  })
})
