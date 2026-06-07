/**
 * Admin panel composable: global heatmap by grid resolution (R9.5 / R9.6).
 *
 * Owns the resolution toggle (LOW / MEDIUM / HIGH). View layer is responsible
 * for the maplibre + deck.gl HexagonLayer rendering (with ResizeObserver
 * lifecycle), and for the 350_000 / 80_000 / 25_000 metre radius mapping.
 */
import { ref, watch } from 'vue'
import {
  getHeatmap,
  type AdminGridResolution,
  type HeatmapPoint,
} from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminHeatmap() {
  const gridResolution = ref<AdminGridResolution>('MEDIUM')

  const state = useAdminApi<HeatmapPoint[]>(() =>
    getHeatmap({ gridResolution: gridResolution.value }),
  )

  watch(gridResolution, () => {
    void state.fetch()
  })

  return {
    gridResolution,
    ...state,
  }
}
