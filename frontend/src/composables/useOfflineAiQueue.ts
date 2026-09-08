/**
 * R31: offline AI request queue.
 *
 * The service worker parks failed POST /api/ai/* requests in a Background Sync
 * queue. This composable gives the UI a way to:
 *   1. enqueue a request explicitly when it knows offline (so the SW queue is
 *      populated even before the first fetch attempt),
 *   2. listen to the SW "replay" message so the mascot dock can show
 *      "已同步" once the user comes back online.
 *
 * Persistence layer is the SW's IndexedDB queue — the page never reads from
 * it directly. The page only emits intent and reacts to "synced" events.
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

interface QueuedRequest {
  id: string
  url: string
  body: string
  timestamp: number
}

const QUEUE_CHANNEL = 'mnemoscape-ai-queue'
const SW_CHANNEL = 'mnemoscape-sw-bridge'

// Shared singleton state — the dock and the offline banner both want it.
const queuedCount = ref(0)
const lastSyncedAt = ref<number | null>(null)
const onlineSinceBoot = typeof navigator === 'undefined' ? true : navigator.onLine

export function useOfflineAiQueue() {
  const { t } = useI18n()

  let bc: BroadcastChannel | null = null
  let swController: ServiceWorker | null = null

  function onQueueChange(event: MessageEvent) {
    const data = event.data
    if (!data || typeof data !== 'object') return
    if (data.type === 'AI_QUEUE_COUNT' && typeof data.count === 'number') {
      queuedCount.value = data.count
    }
    if (data.type === 'AI_QUEUE_SYNCED' && typeof data.id === 'string') {
      lastSyncedAt.value = Date.now()
    }
  }

  function onSwMessage(event: MessageEvent) {
    // The SW posts back to all clients when a BackgroundSync replay succeeds.
    const data = event.data
    if (!data || typeof data !== 'object') return
    if (data.type === 'AI_SYNCED') {
      lastSyncedAt.value = Date.now()
    }
  }

  function onOnline() {
    // Ask the SW to flush immediately. The SW will broadcast AI_SYNCED for
    // each successful replay.
    swController?.postMessage({ type: 'REPLAY_AI_QUEUE' })
  }

  function enqueueAiRequest(req: Omit<QueuedRequest, 'timestamp'>) {
    const payload: QueuedRequest = { ...req, timestamp: Date.now() }
    bc?.postMessage({ type: 'AI_QUEUE_ENQUEUE', payload })
    queuedCount.value += 1
  }

  /** Human-readable label for the offline banner. */
  function formatQueueHint() {
    if (queuedCount.value === 0) return ''
    return t('pwa.offline.queueHint', { count: queuedCount.value })
  }

  onMounted(() => {
    if (typeof window === 'undefined') return
    bc = new BroadcastChannel(QUEUE_CHANNEL)
    bc.addEventListener('message', onQueueChange)
    swController = navigator.serviceWorker?.controller ?? null
    navigator.serviceWorker?.addEventListener('message', onSwMessage)
    window.addEventListener('online', onOnline)
  })

  onBeforeUnmount(() => {
    bc?.removeEventListener('message', onQueueChange)
    bc?.close()
    bc = null
    navigator.serviceWorker?.removeEventListener('message', onSwMessage)
    window.removeEventListener('online', onOnline)
  })

  return {
    queuedCount,
    lastSyncedAt,
    enqueueAiRequest,
    formatQueueHint,
  }
}

export { queuedCount, lastSyncedAt, onlineSinceBoot, SW_CHANNEL }
