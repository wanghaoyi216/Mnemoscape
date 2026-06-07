/**
 * Admin panel composable: active users by time bucket (R6.8 / R6.9).
 *
 * Owns the panel-local state (dimension toggle + optional from/to range) and
 * delegates HTTP / state-machine work to {@link useAdminApi}. The dimension
 * watch policy implements R6.9: when the user flips DAILY ↔ WEEKLY etc., the
 * existing `from`/`to` are preserved if still legal for the new dimension,
 * otherwise reset to "use backend default" (both undefined).
 */
import { ref, watch } from 'vue'
import {
  getActiveUsers,
  type ActiveUserBucket,
  type AdminDimension,
  type GetActiveUsersParams,
} from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminActiveUsers() {
  const dimension = ref<AdminDimension>('DAILY')
  const fromDate = ref<string | undefined>(undefined)
  const toDate = ref<string | undefined>(undefined)

  function buildParams(): GetActiveUsersParams {
    return {
      dimension: dimension.value,
      from: fromDate.value,
      to: toDate.value,
    }
  }

  const state = useAdminApi<ActiveUserBucket[]>(() => getActiveUsers(buildParams()))

  // R6.9 — switching dimension drops range only when it's no longer "obviously
  // valid". For now we always reset on dimension switch because the range
  // semantics change with the bucket size; preserving "30 calendar days" across
  // a switch from DAILY → WEEKLY would silently produce a 4-bucket window which
  // is rarely what the operator wants.
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
