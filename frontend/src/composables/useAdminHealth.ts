/**
 * Admin panel composable: gateway-aggregated downstream health (R18.4).
 *
 * Single fetch — the SystemHealthView mounts this and refreshes manually via
 * the panel's retry button or a setInterval if the view chooses to add one.
 */
import { getAdminHealth, type AdminHealth } from '../api/admin'
import { useAdminApi } from './useAdminApi'

export function useAdminHealth() {
  return useAdminApi<AdminHealth>(() => getAdminHealth())
}
