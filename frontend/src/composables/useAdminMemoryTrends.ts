/**
 * Admin panel composable: memory trends (created vs modified) by bucket (R7.4).
 *
 * Same dimension / range semantics as `useAdminActiveUsers`; the only
 * difference is the response shape (each bucket carries both
 * `createdCount` and `modifiedCount`).
 */
import { ref, watch } from 'vue'
import {
  getMemoryTrends,
  type AdminDimension,
  type GetMemoryTrendsParams,
  type MemoryTrendBucket,
} from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminMemoryTrends() {
  const dimension = ref<AdminDimension>('DAILY')
  const fromDate = ref<string | undefined>(undefined)
  const toDate = ref<string | undefined>(undefined)

  function buildParams(): GetMemoryTrendsParams {
    return {
      dimension: dimension.value,
      from: fromDate.value,
      to: toDate.value,
    }
  }

  const state = useAdminApi<MemoryTrendBucket[]>(() => getMemoryTrends(buildParams()))

  watch(dimension, () => {
    fromDate.value = undefined
    toDate.value = undefined
    void state.fetch()
  })

  watch([fromDate, toDate], () => {
    if (state.loading.value) return
    void state.fetch()
  })

  return {
    dimension,
    fromDate,
    toDate,
    ...state,
  }
}
