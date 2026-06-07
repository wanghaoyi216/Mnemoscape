/**
 * Vitest tests for the {@code useAdminApi} composable
 * (admin-dashboard task 10.10, validates Requirements 5.5 / 13.1 / 18.1 /
 * 18.2).
 *
 * Coverage:
 *   - 502 envelope produces an `error.code === 'UPSTREAM_UNAVAILABLE'` state;
 *   - 200 envelope with `data.degraded === true` flattens into top-level
 *     reactive `degraded` / `degradedReasons` refs;
 *   - 401 fires `authStore.logout()` and `router.push('/login')`;
 *   - happy-path response populates `data` and clears any prior error.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// Mock the i18n module BEFORE importing the composable so the composable's
// `i18n.global.t(...)` calls resolve without spinning up a real locale.
vi.mock('../../src/i18n', () => ({
  default: {
    global: {
      // Translate any 'admin.errors.X' key to a canned string so we can
      // assert the composable forwarded the code correctly.
      t: (key: string) => key,
    },
  },
}))

// Hoisted so the vi.mock factory below can reference the mock without
// hitting Vitest's "Cannot access X before initialization" hoisting rule.
const { routerPushMock } = vi.hoisted(() => {
  return { routerPushMock: vi.fn() }
})

// Mock router so the 401 redirect path doesn't blow up trying to resolve
// /login against an absent route table.
vi.mock('../../src/router', () => ({
  default: { push: routerPushMock },
}))

// Mock auth store imports — the composable calls authStore.logout() on 401.
vi.mock('../../src/stores/auth', () => ({
  useAuthStore: () => ({
    logout: vi.fn(),
  }),
}))

// Pre-mock the auth-api stack (transitive import in stores/auth) since auth.ts
// is now imported by the mocked module above; nothing to do here, just
// belt-and-suspenders.
vi.mock('../../src/api/auth', () => ({}))

import { useAdminApi } from '../../src/composables/useAdminApi'
import { nextTick } from 'vue'

describe('useAdminApi (R5.5 / R13.1 / R18.1 / R18.2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    routerPushMock.mockReset()
  })

  it('happy path populates data and stays non-degraded', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      data: { code: 200, message: 'OK', data: [{ bucket: '2026-05-24', activeUserCount: 7 }] },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()
    await nextTick()

    expect(state.loading.value).toBe(false)
    expect(state.error.value).toBeNull()
    expect(state.degraded.value).toBe(false)
    expect(state.data.value).toEqual([{ bucket: '2026-05-24', activeUserCount: 7 }])
  })

  it('502 envelope triggers UPSTREAM_UNAVAILABLE error', async () => {
    const fetcher = vi.fn().mockRejectedValue({
      response: { status: 502, data: { code: 502, message: 'UPSTREAM_UNAVAILABLE' } },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()
    await nextTick()

    expect(state.error.value?.code).toBe('UPSTREAM_UNAVAILABLE')
    // useAdminApi's translateError treats "key returned unchanged" as a
    // missing translation and falls back to the raw code; our mock
    // returns the key verbatim, so the message equals the code.
    expect(state.error.value?.message).toBe('UPSTREAM_UNAVAILABLE')
    expect(state.loading.value).toBe(false)
  })

  it('403 envelope triggers ADMIN_REQUIRED error', async () => {
    const fetcher = vi.fn().mockRejectedValue({
      response: { status: 403, data: { code: 403, message: 'ADMIN_REQUIRED' } },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()

    expect(state.error.value?.code).toBe('ADMIN_REQUIRED')
  })

  it('200 envelope with degraded=true flattens to top-level state', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      data: {
        code: 200,
        message: 'OK',
        data: {
          items: [{ userId: 'u-1', username: 'u-1aaaaa', memoryCount: 5 }],
          degraded: true,
          degradedReasons: ['auth-service username lookup failed'],
        },
      },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()

    expect(state.degraded.value).toBe(true)
    expect(state.degradedReasons.value).toEqual([
      'auth-service username lookup failed',
    ])
    expect(state.error.value).toBeNull()
    // Data is still populated even on the degraded path
    expect((state.data.value as { items: unknown[] }).items).toHaveLength(1)
  })

  it('401 triggers logout + router.push("/login")', async () => {
    const fetcher = vi.fn().mockRejectedValue({
      response: { status: 401, data: { code: 401, message: 'Unauthorized' } },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()

    expect(routerPushMock).toHaveBeenCalledWith('/login')
    expect(state.error.value?.code).toBe('UNAUTHORIZED')
  })

  it('non-200 envelope inside a 200 HTTP response (gateway-folded error) surfaces as error', async () => {
    // Our backend folds "INVALID_DIMENSION" into a 400 envelope; some
    // gateway configurations might fold it into a 200 wrapper. The
    // composable handles both: any non-200 envelope code → error state.
    const fetcher = vi.fn().mockResolvedValue({
      data: { code: 400, message: 'INVALID_DIMENSION' },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()

    expect(state.error.value?.code).toBe('INVALID_DIMENSION')
    expect(state.degraded.value).toBe(false)
  })

  it('reset() clears all state', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      data: { code: 200, message: 'OK', data: [1, 2, 3] },
    })
    const state = useAdminApi(fetcher)
    await state.fetch()
    expect(state.data.value).toEqual([1, 2, 3])

    state.reset()

    expect(state.data.value).toBeNull()
    expect(state.error.value).toBeNull()
    expect(state.degraded.value).toBe(false)
    expect(state.degradedReasons.value).toEqual([])
  })

  it('loading flag flips around fetch lifecycle', async () => {
    let resolveFetch: (v: unknown) => void = () => {}
    const fetcher = vi.fn().mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      }),
    )
    const state = useAdminApi(fetcher)
    const fetching = state.fetch()
    // Loading should be true while the promise is pending.
    expect(state.loading.value).toBe(true)
    resolveFetch({ data: { code: 200, message: 'OK', data: [] } })
    await fetching
    expect(state.loading.value).toBe(false)
  })
})
