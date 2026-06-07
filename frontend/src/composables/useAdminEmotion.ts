/**
 * Admin panel composable: 8-component emotion distribution radar (R8.5).
 *
 * Backend defaults to a 365-day window when from / to are absent; this
 * composable just trips a single fetch on mount.
 */
import { getEmotionDistribution, type EmotionDistribution } from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminEmotion() {
  return useAdminApi<EmotionDistribution>(() => getEmotionDistribution())
}
