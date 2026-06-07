/**
 * Admin panel composable: resonance overview + top edges (R12.4).
 *
 * Two distinct endpoints (`resonance-overview` for KPI cards, `resonance-top`
 * for the force-graph) — exposed as separate state machines so a degraded
 * overview doesn't block the top-edges visualisation and vice versa.
 */
import { ref, watch } from 'vue'
import {
  getResonanceOverview,
  getResonanceTop,
  type GetResonanceTopParams,
  type ResonanceOverview,
  type ResonanceTopEdge,
} from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminResonanceOverview() {
  return useAdminApi<ResonanceOverview>(() => getResonanceOverview())
}

export function useAdminResonanceTop() {
  const limit = ref<number | undefined>(20)

  function buildParams(): GetResonanceTopParams {
    return limit.value === undefined ? {} : { limit: limit.value }
  }

  const state = useAdminApi<ResonanceTopEdge[]>(() => getResonanceTop(buildParams()))

  watch(limit, () => {
    if (state.loading.value) return
    void state.fetch()
  })

  return {
    limit,
    ...state,
  }
}
