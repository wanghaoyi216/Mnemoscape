import axios, { type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../stores/auth'
import { useHealthCheck } from '../composables/useHealthCheck'
import i18n from '../i18n'

// ============================================================
// 高并发优化：请求去重 + 自动重试 + 防抖
// ============================================================
// 设计目标：
//   1) 防止用户快速双击/连点提交按钮触发重复 POST（写操作幂等性问题）
//   2) 同一 GET 在飞行中只发一次，后续相同请求复用结果（节省后端压力）
//   3) 网络抖动 / 限流 429 时自动重试 GET 请求（提升用户体验）
//   4) 离线检测增强，区分业务 500 和基础设施断连

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// ----------------- 请求去重池 -----------------
// 同一 method+url+params 还在飞行中时，后续相同请求复用 Promise，不重复发请求
interface PendingRequest {
  promise: Promise<AxiosResponse>
  timestamp: number
}
const pendingRequests = new Map<string, PendingRequest>()

// 生成请求唯一 key：method + url + 参数（POST 用 body 摘要）
function buildRequestKey(config: InternalAxiosRequestConfig): string {
  const method = (config.method || 'get').toLowerCase()
  const url = config.url || ''
  // 写操作（POST/PUT/DELETE）的 body 也要纳入 key，避免不同 body 被当重复
  let paramsKey = ''
  if (method === 'get') {
    paramsKey = JSON.stringify(config.params || {})
  } else {
    paramsKey = JSON.stringify(config.data || {}).slice(0, 200)
  }
  return `${method}:${url}:${paramsKey}`
}

// 判断是否允许去重：写操作不去重（除非显式声明幂等），读操作去重
function canDedupe(method: string | undefined, config: AxiosRequestConfig): boolean {
  const m = (method || 'get').toLowerCase()
  if (m === 'get') return true
  // POST/PUT/DELETE 默认不去重，避免把"创建两次不同实体"误判为重复
  // 但调用方可以显式 config.dedupe = true 来开启
  return Boolean((config as any).dedupe)
}

// 去重池清理
function removePending(config: InternalAxiosRequestConfig | AxiosRequestConfig) {
  const key = buildRequestKey(config as InternalAxiosRequestConfig)
  pendingRequests.delete(key)
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
    // 请求成功，清除离线状态
    const { setOffline } = useHealthCheck()
    setOffline(false)
    // 完成后从去重池移除（已完成不再复用）
    removePending(res.config)
    return res
  },
  (err) => {
    // 清理去重池，让下次可以重试
    if (err.config) removePending(err.config)

    const requestUrl = String(err.config?.url || '')
    const authRequest = requestUrl.includes('/auth/login')
      || requestUrl.includes('/auth/register')
      || requestUrl.includes('/auth/refresh')

    if (err.response?.status === 401 && !authRequest) {
      const auth = useAuthStore()
      auth.logout()
      window.location.href = '/login'
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

// ----------------- 包装 request 实现去重 -----------------
// 覆盖 axios 实例的 request 方法：对允许去重的请求复用飞行中的 Promise
const originalRequest = client.request.bind(client)
;(client as any).request = function (config: AxiosRequestConfig): Promise<AxiosResponse> {
  const method = config.method || 'get'
  if (!canDedupe(method, config)) {
    return originalRequest(config)
  }
  const key = buildRequestKey(config as InternalAxiosRequestConfig)
  const existing = pendingRequests.get(key)
  if (existing) {
    // 命中去重：复用飞行中的 Promise，不重复发请求
    return existing.promise
  }
  const promise = originalRequest(config).finally(() => {
    pendingRequests.delete(key)
  })
  pendingRequests.set(key, { promise, timestamp: Date.now() })
  return promise
}

export default client
