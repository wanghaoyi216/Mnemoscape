import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import i18n from '../i18n'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

client.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  // R13.4：把当前 UI 语言透传给后端，让 GlobalExceptionHandler 据此本地化错误消息。
  // 仅当调用方未显式覆盖时才注入，避免破坏特殊场景（例如 Nominatim 反向地理）。
  if (config.headers['Accept-Language'] === undefined && config.headers['accept-language'] === undefined) {
    config.headers['Accept-Language'] = i18n.global.locale.value
  }
  return config
})

import { useHealthCheck } from '../composables/useHealthCheck'

client.interceptors.response.use(
  (res) => {
    // If request succeeds, automatically clear the offline alert status
    const { setOffline } = useHealthCheck()
    setOffline(false)
    return res
  },
  (err) => {
    const requestUrl = String(err.config?.url || '')
    const authRequest = requestUrl.includes('/auth/login')
      || requestUrl.includes('/auth/register')
      || requestUrl.includes('/auth/refresh')

    if (err.response?.status === 401 && !authRequest) {
      const auth = useAuthStore()
      auth.logout()
      window.location.href = '/login'
    }

    // Capture infrastructure connection errors (Docker closed / databases offline).
    // ONLY trip on transport-level failures — application 500s (including the
    // poetic "坍塌" wrapper from common.GlobalExceptionHandler) are business errors,
    // not infra outages. Treating them as offline produced false positives whenever
    // a downstream service threw any exception (e.g. asset-service path collisions).
    if (
      err.message === 'Network Error' ||
      err.code === 'ERR_NETWORK' ||
      err.code === 'ECONNABORTED' ||
      !err.response ||
      err.response.status === 502 ||
      err.response.status === 503 ||
      err.response.status === 504
    ) {
      const { setOffline } = useHealthCheck()
      setOffline(true)
    }

    return Promise.reject(err)
  },
)

export default client
