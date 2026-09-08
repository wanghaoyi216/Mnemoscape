import { ref } from 'vue'
import type { WebSocketMessage } from '../types'
import { useReconnectingWebSocket } from './useReconnectingWebSocket'

export function useWebSocket() {
  const lastMessage = ref<WebSocketMessage | null>(null)
  let token: string | null = null
  const listeners = new Map<string, Set<(msg: WebSocketMessage) => void>>()

  const socket = useReconnectingWebSocket(
    () => {
      if (!token) return null
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      return `${protocol}//${window.location.host}/ws/resonance?token=${encodeURIComponent(token)}`
    },
    {
      onMessage(data) {
        const msg = data as WebSocketMessage
        lastMessage.value = msg
        listeners.get(msg.type)?.forEach((fn) => fn(msg))
        listeners.get('*')?.forEach((fn) => fn(msg))
      },
    }
  )

  function connect(nextToken: string) {
    token = nextToken
    socket.close()
    socket.connect()
  }

  function send(msg: WebSocketMessage) {
    return socket.send(msg)
  }

  function on(type: string, fn: (msg: WebSocketMessage) => void) {
    if (!listeners.has(type)) listeners.set(type, new Set())
    listeners.get(type)!.add(fn)
  }

  function off(type: string, fn: (msg: WebSocketMessage) => void) {
    listeners.get(type)?.delete(fn)
  }

  function disconnect() {
    token = null
    socket.close()
  }

  return {
    connected: socket.connected,
    lastMessage,
    connect,
    send,
    on,
    off,
    disconnect,
  }
}
