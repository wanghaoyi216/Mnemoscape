/**
 * Admin panel composable: fragment discovery rate (R11.4).
 *
 * Two endpoints share the cache key family: an "overall" call (no params) and
 * a "byType" call (`groupBy=fragmentType`). The view chooses which to mount
 * based on a local mode toggle; both are exposed as separate state machines
 * so loading / error handling stay independent.
 *
 * The shared `getFragmentDiscovery` axios call returns a union response shape
 * (overall record OR list of by-type records). Each composable casts the
 * union into the concrete shape it owns by passing a typed `fetcher` closure
 * to {@link useAdminApi} — the cast is the smallest surface that lets
 * `useAdminApi<T>` keep `T` non-union for downstream chart code.
 */
import {
  getFragmentDiscovery,
  type FragmentDiscoveryByType,
  type FragmentDiscoveryOverall,
} from '../api/admin'
import type { ApiResponse } from '../types'
import { useAdminApi } from './useAdminApi'

export function useAdminFragmentsOverall() {
  return useAdminApi<FragmentDiscoveryOverall>(
    () =>
      getFragmentDiscovery() as unknown as Promise<{
        data: ApiResponse<FragmentDiscoveryOverall>
      }>,
  )
}

export function useAdminFragmentsByType() {
  return useAdminApi<FragmentDiscoveryByType[]>(
    () =>
      getFragmentDiscovery({ groupBy: 'fragmentType' }) as unknown as Promise<{
        data: ApiResponse<FragmentDiscoveryByType[]>
      }>,
  )
}
