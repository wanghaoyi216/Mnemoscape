/**
 * Admin panel composable: top contributors by memory count (R10.5).
 *
 * Backend's `data` carries `{items, degraded, degradedReasons}` so that the
 * composable's `data` ref naturally aligns with the panel's bar chart input,
 * while `useAdminApi` already extracts top-level `degraded` flags for the
 * shared `<AdminPanel>` badge.
 */
import { ref, watch } from 'vue'
import {
  getTopContributors,
  type GetTopContributorsParams,
  type TopContributorsResponse,
} from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminContributors() {
  const limit = ref<number | undefined>(undefined)
  const fromDate = ref<string | undefined>(undefined)
  const toDate = ref<string | undefined>(undefined)

  function buildParams(): GetTopContributorsParams {
    const p: GetTopContributorsParams = {}
    if (limit.value !== undefined) p.limit = limit.value
    if (fromDate.value) p.from = fromDate.value
    if (toDate.value) p.to = toDate.value
    return p
  }

  const state = useAdminApi<TopContributorsResponse>(() =>
    getTopContributors(buildParams()),
  )

  watch([limit, fromDate, toDate], () => {
    if (state.loading.value) return
    void state.fetch()
  })

  return {
    limit,
    fromDate,
    toDate,
    ...state,
  }
}
