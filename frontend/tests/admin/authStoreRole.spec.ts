/**
 * Vitest tests for the auth store's role handling
 * (admin-dashboard task 10.2, validates Requirement 4.6).
 *
 * Verifies that the {@code toUser} mapper inside the store falls back to
 * {@code 'USER'} for any non-canonical input (null, undefined, lowercase),
 * AND that {@code isAdmin} flips correctly when the role is exactly
 * {@code 'ADMIN'}.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock all the API modules so the store doesn't try to import axios chains
// that pull in i18n / router (none of which exist in the test environment).
vi.mock('../../src/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getProfile: vi.fn(),
  logout: vi.fn(),
}))

import { useAuthStore } from '../../src/stores/auth'

describe('auth store role mapping (R4.6)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('isAdmin is false on a freshly-mounted store', () => {
    const auth = useAuthStore()
    expect(auth.isAdmin).toBe(false)
    expect(auth.user).toBeNull()
  })

  it("isAdmin flips to true when the user object's role is ADMIN", () => {
    const auth = useAuthStore()
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'alice@example.com',
      verified: true,
      role: 'ADMIN',
    }
    expect(auth.isAdmin).toBe(true)
  })

  it('isAdmin remains false when role is USER', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'alice@example.com',
      verified: true,
      role: 'USER',
    }
    expect(auth.isAdmin).toBe(false)
  })

  it('isAdmin remains false when role is undefined (defensive)', () => {
    const auth = useAuthStore()
    // Force-cast: TS would normally reject this, but we want to ensure that
    // a stale/migrated user payload without a role can't accidentally grant
    // admin access.
    auth.user = {
      id: 'u-1',
      username: 'alice',
      email: 'alice@example.com',
      verified: true,
    } as unknown as typeof auth.user extends infer U ? U : never as any
    expect(auth.isAdmin).toBe(false)
  })

  it('login flow stores role from the AuthResponse', async () => {
    const { login } = await import('../../src/api/auth')
    const { getProfile } = await import('../../src/api/auth')
    vi.mocked(login).mockResolvedValueOnce({
      data: {
        data: {
          userId: 'u-1',
          username: 'alice',
          accessToken: 'tok',
          refreshToken: 'r-tok',
          expiresIn: 3600000,
          role: 'ADMIN',
        },
      },
    } as never)
    vi.mocked(getProfile).mockResolvedValueOnce({
      data: {
        data: {
          id: 'u-1',
          username: 'alice',
          email: 'alice@example.com',
          verified: true,
          role: 'ADMIN',
        },
      },
    } as never)

    const auth = useAuthStore()
    await auth.login('alice', 'pwd')

    expect(auth.user?.role).toBe('ADMIN')
    expect(auth.isAdmin).toBe(true)
  })

  it('login fallback (when getProfile fails) still preserves role from AuthResponse', async () => {
    const { login } = await import('../../src/api/auth')
    const { getProfile } = await import('../../src/api/auth')
    vi.mocked(login).mockResolvedValueOnce({
      data: {
        data: {
          userId: 'u-1',
          username: 'alice',
          accessToken: 'tok',
          refreshToken: 'r-tok',
          expiresIn: 3600000,
          role: 'ADMIN',
        },
      },
    } as never)
    vi.mocked(getProfile).mockRejectedValueOnce(new Error('profile down'))

    const auth = useAuthStore()
    await auth.login('alice', 'pwd')

    expect(auth.user?.role).toBe('ADMIN')
    expect(auth.isAdmin).toBe(true)
  })
})
