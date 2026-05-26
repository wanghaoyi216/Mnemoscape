import { ref, onUnmounted } from 'vue'
import type { WebSocketMessage } from '../types'

export function useWebSocket() {
  const connected = ref(false)
  const lastMessage = ref<WebSocketMessage | null>(null)
  let ws: WebSocket | null = null
  const listeners = new Map<string, Set<(msg: WebSocketMessage) => void>>()

  function connect(token: string) {
    if (ws && ws.readyState !== WebSocket.CLOSED) {
      ws.close()
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/resonance?token=${token}`
    ws = new WebSocket(url)

    ws.onopen = () => { connected.value = true }
    ws.onclose = () => { connected.value = false }
    ws.onmessage = (event) => {
      try {
        const msg: WebSocketMessage = JSON.parse(event.data)
        lastMessage.value = msg
        listeners.get(msg.type)?.forEach((fn) => fn(msg))
        listeners.get('*')?.forEach((fn) => fn(msg))
      } catch { /* ignore parse errors */ }
    }
  }

  function send(msg: WebSocketMessage) {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg))
    }
  }

  function on(type: string, fn: (msg: WebSocketMessage) => void) {
    if (!listeners.has(type)) listeners.set(type, new Set())
    listeners.get(type)!.add(fn)
  }

  function off(type: string, fn: (msg: WebSocketMessage) => void) {
    listeners.get(type)?.delete(fn)
  }

  function disconnect() {
    ws?.close()
    ws = null
    connected.value = false
  }

  onUnmounted(disconnect)

  return { connected, lastMessage, connect, send, on, off, disconnect }
}
