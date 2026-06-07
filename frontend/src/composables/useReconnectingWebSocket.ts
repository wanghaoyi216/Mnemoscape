import { ref, onUnmounted, onMounted, onBeforeUnmount } from 'vue'

/**
 * 带指数退避 + 抖动 + visibility 暂停的 WebSocket 客户端。
 *
 * <p>解决的问题：浏览器原生 WebSocket 没有自动重连；当网络抖动、网关短暂重启、
 * 浏览器 tab 切到后台时容易断。固定 5s 重连在多 client 同时断时会引发"重连风暴"
 * —— N 个 client 在同一秒尝试打回服务器，服务器瞬间 CPU 飙升。
 *
 * <p>特性：
 * <ul>
 *   <li><b>指数退避</b>：连续失败时 1s → 2s → 4s → ... → 上限 30s</li>
 *   <li><b>Jitter 抖动</b>：每次间隔加 ±20% 随机偏移，多 client 错峰重连</li>
 *   <li><b>visibility 暂停</b>：tab 隐藏时不发新消息、停止重连计时；切回时立即重连一次</li>
 *   <li><b>onError 重置</b>：连接成功一次后退避计数器清零</li>
 *   <li><b>优雅关闭</b>：组件卸载时主动 close，不再重连</li>
 * </ul>
 *
 * <p>用法（替换裸 new WebSocket）：
 * <pre>
 *   const { state, send, on, off, close } = useReconnectingWebSocket(
 *     () => `${proto}//${host}/ws/chat?userId=${uid}`,
 *     { onMessage: (raw) => { ... } }
 *   )
 * </pre>
 */
export function useReconnectingWebSocket(
  urlBuilder: () => string | null,
  options: {
    onMessage?: (data: any) => void
    onOpen?: () => void
    onClose?: () => void
    minIntervalMs?: number
    maxIntervalMs?: number
  } = {}
) {
  const connected = ref(false)
  const reconnectAttempts = ref(0)
  const state = ref<'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'>('idle')

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let explicitClosed = false
  const listeners = new Map<string, Set<(data: any) => void>>()

  const minMs = options.minIntervalMs ?? 1000
  const maxMs = options.maxIntervalMs ?? 30_000

  function backoff() {
    const exp = Math.min(reconnectAttempts.value, 5)
    const base = Math.min(maxMs, minMs * Math.pow(2, exp))
    // ±20% 抖动
    const jitter = base * 0.2 * (Math.random() * 2 - 1)
    return Math.max(minMs, Math.floor(base + jitter))
  }

  function connect() {
    explicitClosed = false
    if (typeof document !== 'undefined' && document.hidden) {
      // tab 隐藏 —— 等切回来再连
      state.value = 'reconnecting'
      return
    }
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    const url = urlBuilder()
    if (!url) {
      state.value = 'idle'
      return
    }
    try {
      state.value = reconnectAttempts.value > 0 ? 'reconnecting' : 'connecting'
      ws = new WebSocket(url)
    } catch (e) {
      scheduleReconnect()
      return
    }
    const current = ws

    current.onopen = () => {
      if (ws !== current) return
      connected.value = true
      reconnectAttempts.value = 0
      state.value = 'open'
      options.onOpen?.()
    }
    current.onclose = () => {
      if (ws !== current) return
      connected.value = false
      state.value = 'closed'
      options.onClose?.()
      if (!explicitClosed) scheduleReconnect()
    }
    current.onerror = () => {
      if (ws !== current) return
      // 浏览器 onerror 几乎不带信息；onclose 紧跟其后由我们处理
    }
    current.onmessage = (ev) => {
      if (ws !== current) return
      try {
        const data = JSON.parse(ev.data)
        listeners.get('*')?.forEach((fn) => fn(data))
        if (data?.type) listeners.get(data.type)?.forEach((fn) => fn(data))
        options.onMessage?.(data)
      } catch (e) {
        console.warn('[ws] message parse failed', e)
      }
    }
  }

  function scheduleReconnect() {
    if (explicitClosed || reconnectTimer) return
    reconnectAttempts.value++
    state.value = 'reconnecting'
    const delay = backoff()
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  function send(data: any) {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(typeof data === 'string' ? data : JSON.stringify(data))
      return true
    }
    return false
  }

  function on(type: string, fn: (data: any) => void) {
    if (!listeners.has(type)) listeners.set(type, new Set())
    listeners.get(type)!.add(fn)
  }

  function off(type: string, fn: (data: any) => void) {
    listeners.get(type)?.delete(fn)
  }

  function close() {
    explicitClosed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      try { ws.close() } catch {}
      ws = null
    }
    connected.value = false
    state.value = 'closed'
  }

  function onVisibilityChange() {
    if (explicitClosed) return
    if (document.hidden) return
    if (state.value === 'closed' || state.value === 'reconnecting') {
      // 切回前台：立即重置退避、连一次
      reconnectAttempts.value = 0
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      connect()
    }
  }

  if (typeof document !== 'undefined') {
    onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
    onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisibilityChange))
  }

  onUnmounted(close)

  return {
    state,
    connected,
    reconnectAttempts,
    connect,
    send,
    on,
    off,
    close,
  }
}
