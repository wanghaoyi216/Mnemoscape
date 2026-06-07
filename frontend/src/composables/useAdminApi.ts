/**
 * Generic admin-panel state machine composable (R5.4 / R5.5 / R13.1 / R13.4 / R18.1 / R18.2).
 *
 * Each admin panel runs its own request lifecycle (loading / ready / empty / error / degraded).
 * Rather than have eight panels duplicate the axios + ApiResponse + error-translation logic,
 * we centralise it here and per-panel composables only own the params + fetcher closure.
 *
 * The fetcher contract (typed in `frontend/src/api/admin.ts`) returns
 * `Promise<{ data: ApiResponse<T> }>` — i.e. an axios response whose body is our `{code,
 * message, data}` envelope. This composable:
 *
 *   1. unwraps the envelope, surfacing `data` only on `code === 200`;
 *   2. flattens `data.degraded` / `data.degradedReasons` into top-level reactive state so
 *      panels can render a "数据降级" badge without re-introspecting payloads (R18.1 / R18.2);
 *   3. translates HTTP-level errors and envelope-level `message` codes through
 *      `t('admin.errors.<CODE>')` (per design §Frontend Error Handling);
 *   4. on 401, triggers `authStore.logout()` + `router.push('/login')` defensively — the
 *      shared axios interceptor already handles 401, but doing it here keeps the composable
 *      correct in tests that mock the interceptor away.
 *
 * Signature note: the design sketch uses `(endpoint, params)` strings, but admin.ts already
 * exposes typed `getXxx(params)` fetchers. Taking a fetcher closure preserves end-to-end
 * type safety (panel sees `T`, not a raw axios `<unknown>` cast) and lets the composable
 * stay agnostic of axios specifics.
 */
import { ref, type Ref } from 'vue'
import type { ApiResponse } from '../types'
import i18n from '../i18n'
import router from '../router'
import { useAuthStore } from '../stores/auth'

/** Normalised error shape consumed by panels' error cards (R5.5). */
export interface AdminApiError {
  /** Machine-readable code: backend `ApiResponse.message` for 200/4xx envelope errors,
   *  or one of {`ADMIN_REQUIRED`, `UPSTREAM_UNAVAILABLE`, `NETWORK`} for HTTP-level failures. */
  code: string
  /** Localised, user-facing string (`t('admin.errors.<code>')`). */
  message: string
  /** Diagnostic detail extracted from the backend `data` field on failure (v7).
   *  For UPSTREAM_UNAVAILABLE, this carries `{ upstreamName, failureKind, detail }`
   *  so panels can render an actionable hint instead of an opaque banner. */
  diagnostic?: {
    upstreamName?: string
    failureKind?: string
    detail?: string
    causeType?: string
  }
}

/** Public surface returned by `useAdminApi`. All fields are reactive refs so panels can
 *  bind directly in `<template>`. `fetch` triggers a request; `reset` clears state to its
 *  pristine initial values (used e.g. when a panel unmounts or a parent route changes). */
export interface AdminApiState<T> {
  data: Ref<T | null>
  loading: Ref<boolean>
  error: Ref<AdminApiError | null>
  degraded: Ref<boolean>
  degradedReasons: Ref<string[]>
  fetch: () => Promise<void>
  reset: () => void
}

/** Type guard: does the unwrapped `data` payload carry the degraded envelope (R18.1)?
 *  Backend always tops-level the flag inside `data` (not on `ApiResponse`), so we
 *  introspect generically without leaking shape requirements into `T`. */
function readDegraded(payload: unknown): { degraded: boolean; reasons: string[] } {
  if (payload === null || typeof payload !== 'object') {
    return { degraded: false, reasons: [] }
  }
  const obj = payload as Record<string, unknown>
  const degraded = obj.degraded === true
  const reasons = Array.isArray(obj.degradedReasons)
    ? (obj.degradedReasons as unknown[]).filter((r): r is string => typeof r === 'string')
    : []
  return { degraded, reasons }
}

/**
 * Translate an admin error code through the i18n dictionary, falling back to the raw
 * code when the key is missing. We use `i18n.global.t` (not `useI18n()`) so the composable
 * can be invoked from contexts where Vue's setup-scope injection isn't active, matching
 * the pattern already used in `api/client.ts` for `Accept-Language` propagation.
 */
function translateError(code: string): string {
  const key = `admin.errors.${code}`
  const translated = i18n.global.t(key)
  // vue-i18n returns the key itself when no translation exists; surface the raw code in
  // that case so panel UIs at least show something actionable.
  return translated === key ? code : translated
}

export function useAdminApi<T>(
  fetcher: () => Promise<{ data: ApiResponse<T> }>,
): AdminApiState<T> {
  const data = ref<T | null>(null) as Ref<T | null>
  const loading = ref<boolean>(false)
  const error = ref<AdminApiError | null>(null)
  const degraded = ref<boolean>(false)
  const degradedReasons = ref<string[]>([])

  function reset(): void {
    data.value = null
    loading.value = false
    error.value = null
    degraded.value = false
    degradedReasons.value = []
  }

  async function fetch(): Promise<void> {
    loading.value = true
    error.value = null
    // Don't clear `data` here — keeping the previous payload visible during refetch
    // gives a better UX than flashing the empty state. Panels that want a hard reset
    // can call `reset()` explicitly before `fetch()`.
    try {
      const resp = await fetcher()
      const envelope = resp.data
      if (envelope.code !== 200) {
        // Backend returned a structured error inside the envelope (e.g. validation
        // failures that bubble up as 400 + `INVALID_DIMENSION`). The HTTP layer didn't
        // throw because axios was given a non-rejecting status, or the gateway folded
        // the error into a 200 envelope. Treat `message` as the machine code.
        const code = envelope.message || 'NETWORK'
        error.value = { code, message: translateError(code) }
        // Reset degraded flags — an error response shouldn't keep stale degradation.
        degraded.value = false
        degradedReasons.value = []
        return
      }
      data.value = envelope.data
      const { degraded: isDegraded, reasons } = readDegraded(envelope.data)
      degraded.value = isDegraded
      degradedReasons.value = reasons
    } catch (err: unknown) {
      // axios attaches `response` for HTTP-level failures and leaves it undefined for
      // transport errors (network down, CORS, request cancelled). Discriminate on that.
      const status = (err as { response?: { status?: number } })?.response?.status
      if (status === 401) {
        // The shared axios interceptor (api/client.ts) already calls logout +
        // window.location.href on 401. We re-do the auth-store side defensively so the
        // composable is correct under unit-test mocks where the interceptor is stubbed,
        // and so any consumer importing `useAdminApi` directly without going through
        // the configured axios instance still ends up in a sane state.
        try {
          const auth = useAuthStore()
          auth.logout()
        } catch {
          // Pinia not active (e.g. during SSR or tests without a pinia plugin):
          // swallowing here is safe because the interceptor's hard-redirect path
          // is the real fallback.
        }
        try {
          router.push('/login')
        } catch {
          // Same defensive rationale as above.
        }
        // Surface a meaningful error so panels stuck on the page during the redirect
        // don't show a stale loader. `unauthorized` is the closest existing key.
        error.value = { code: 'UNAUTHORIZED', message: translateError('UNAUTHORIZED') }
      } else if (status === 403) {
        error.value = { code: 'ADMIN_REQUIRED', message: translateError('ADMIN_REQUIRED') }
      } else if (status === 502) {
        // v7: 502 envelope now carries a structured `data` with upstreamName / failureKind / detail.
        // Extract it so the dashboard can render an actionable hint (e.g. "memory-service is not
        // registered in Nacos") instead of a generic "上游服务暂不可达" banner.
        const envelopeData = (
          err as { response?: { data?: { data?: Record<string, string> } } }
        )?.response?.data?.data
        error.value = {
          code: 'UPSTREAM_UNAVAILABLE',
          message: translateError('UPSTREAM_UNAVAILABLE'),
          diagnostic: envelopeData
            ? {
                upstreamName: envelopeData.upstreamName,
                failureKind: envelopeData.failureKind,
                detail: envelopeData.detail,
                causeType: envelopeData.causeType,
              }
            : undefined,
        }
      } else {
        // 4xx with a structured envelope reaching the catch (e.g. axios configured to
        // reject on 4xx) — try to surface the envelope `message`, else fall back to
        // `NETWORK` for transport-level failures.
        const envelopeMessage = (
          err as { response?: { data?: { message?: string } } }
        )?.response?.data?.message
        const code = envelopeMessage || 'NETWORK'
        error.value = { code, message: translateError(code) }
      }
      degraded.value = false
      degradedReasons.value = []
    } finally {
      loading.value = false
    }
  }

  return { data, loading, error, degraded, degradedReasons, fetch, reset }
}
