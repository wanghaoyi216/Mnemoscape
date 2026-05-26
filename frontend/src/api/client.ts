import axios from 'axios'
import { useAuthStore } from '../stores/auth'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

client.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
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
