import axios, { type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../stores/auth'
import { useHealthCheck } from '../composables/useHealthCheck'
import i18n from '../i18n'

// ============================================================
// 高并发优化：请求去重 + 自动重试 + token 刷新 + 防抖
// ============================================================
// 设计目标：
//   1) 防止用户快速双击/连点提交按钮触发重复 POST（写操作幂等性问题）
//   2) 同一 GET 在飞行中只发一次，后续相同请求复用结果（节省后端压力）
//   3) 网络抖动 / 限流 429 时自动重试 GET 请求（提升用户体验）
//   4) 401 时自动刷新 token 并重放原请求，并发请求只刷一次；刷新失败再登出
//   5) 离线检测增强，区分业务 500 和基础设施断连

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// 独立实例调 refresh，避免走 client 拦截器造成循环（refresh 401 又触发 refresh）
const refreshClient = axios.create({ baseURL: '/api/v1', timeout: 10000 })

// ----------------- 请求去重池 -----------------
interface PendingRequest {
  promise: Promise<AxiosResponse>
  timestamp: number
}
const pendingRequests = new Map<string, PendingRequest>()

function buildRequestKey(config: InternalAxiosRequestConfig): string {
  const method = (config.method || 'get').toLowerCase()
  const url = config.url || ''
  const paramsKey = method === 'get'
    ? JSON.stringify(config.params || {})
    : JSON.stringify(config.data || {}).slice(0, 200)
  return `${method}:${url}:${paramsKey}`
}

function canDedupe(method: string | undefined, config: AxiosRequestConfig): boolean {
  const m = (method || 'get').toLowerCase()
  if (m === 'get') return true
  // POST/PUT/DELETE 默认不去重；调用方可显式 config.dedupe = true 开启
  return Boolean((config as any).dedupe)
}

// ----------------- token 刷新（并发去重）-----------------
// 多个 401 同时到达时，只发一次 refresh，共享同一个 Promise
let refreshPromise: Promise<string> | null = null

async function doRefresh(): Promise<string> {
  const auth = useAuthStore()
  const rt = auth.refreshToken
  if (!rt) throw new Error('no refresh token')
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post('/auth/refresh', undefined, { headers: { Authorization: `Bearer ${rt}` } })
      .then(({ data }) => {
        const newToken = data.data.accessToken
        const newRefresh = data.data.refreshToken
        auth.token = newToken
        auth.refreshToken = newRefresh
        localStorage.setItem('token', newToken)
        localStorage.setItem('refreshToken', newRefresh)
        return newToken
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

async function redirectToLogin() {
  // dynamic import 避免与 router 模块的循环依赖（router -> auth store -> api/auth -> client）
  const { default: router } = await import('../router')
  router.push('/login')
}

// ----------------- 请求拦截器 -----------------
client.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  // R13.4：把当前 UI 语言透传给后端，让 GlobalExceptionHandler 据此本地化错误消息
  if (config.headers['Accept-Language'] === undefined && config.headers['accept-language'] === undefined) {
    config.headers['Accept-Language'] = i18n.global.locale.value
  }

  // 写操作自动带随机 Idempotency-Key（仅对未显式设置 key 的 POST 生效），
  // 后端 IdempotencyGuard 用这个 key 做幂等去重，避免双击重复创建。
  const method = (config.method || 'get').toLowerCase()
  if (method === 'post' && !config.headers['Idempotency-Key'] && !(config as any).skipIdempotency) {
    config.headers['Idempotency-Key'] = `ui-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  }

  return config
})

// ----------------- 响应拦截器 -----------------
client.interceptors.response.use(
  (res) => {
    const { setOffline } = useHealthCheck()
    setOffline(false)
    pendingRequests.delete(buildRequestKey(res.config))
    return res
  },
  async (err) => {
    if (err.config) pendingRequests.delete(buildRequestKey(err.config))

    const requestUrl = String(err.config?.url || '')
    const authRequest = requestUrl.includes('/auth/login')
      || requestUrl.includes('/auth/register')
      || requestUrl.includes('/auth/refresh')
      || requestUrl.includes('/auth/logout')

    // 401：非 auth 请求 -> 尝试 refresh -> 重放原请求；失败 -> 登出 + 跳登录
    if (err.response?.status === 401 && !authRequest && !(err.config as any).__replayed) {
      try {
        const newToken = await doRefresh()
        ;(err.config as any).__replayed = true
        err.config.headers.Authorization = `Bearer ${newToken}`
        return client.request(err.config)
      } catch {
        const auth = useAuthStore()
        await auth.logout()
        redirectToLogin()
        return Promise.reject(err)
      }
    }

    // 离线检测：只在传输层失败或网关 5xx 时触发
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

    // 限流 429：自动等待 Retry-After 后重试一次（仅读操作）
    const method = (err.config?.method || 'get').toLowerCase()
    if (
      err.response?.status === 429 &&
      method === 'get' &&
      !(err.config as any)._retried429
    ) {
      const retryAfter = Number(err.response.headers?.['retry-after'] || '1')
      return new Promise((resolve) => setTimeout(resolve, retryAfter * 1000))
        .then(() => {
          ;(err.config as any)._retried429 = true
          return client.request(err.config)
        })
    }

    return Promise.reject(err)
  },
)

// ----------------- 去重：覆盖 request + 所有便捷方法 -----------------
// axios 的 get/post 等便捷方法内部调 this.request；覆盖实例 request 后需同步覆盖便捷方法，
// 否则旧版 axios 会绕过去重直接走原始 request。
const originalRequest = client.request.bind(client)
function dedupedRequest(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const method = config.method || 'get'
  if (!canDedupe(method, config)) {
    return originalRequest(config)
  }
  const key = buildRequestKey(config as InternalAxiosRequestConfig)
  const existing = pendingRequests.get(key)
  if (existing) {
    return existing.promise
  }
  const promise = originalRequest(config).finally(() => {
    pendingRequests.delete(key)
  })
  pendingRequests.set(key, { promise, timestamp: Date.now() })
  return promise
}
client.request = dedupedRequest as typeof client.request
;(['get', 'delete', 'head', 'options'] as const).forEach((method) => {
  ;(client as any)[method] = (url: string, config?: AxiosRequestConfig) =>
    dedupedRequest({ ...config, method, url })
})
;(['post', 'put', 'patch'] as const).forEach((method) => {
  ;(client as any)[method] = (url: string, data?: unknown, config?: AxiosRequestConfig) =>
    dedupedRequest({ ...config, method, url, data })
})

export default client
