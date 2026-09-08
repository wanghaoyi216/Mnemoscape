<script setup lang="ts">
/**
 * R31: PWA banner — surfaces three states to the user:
 *   1. App is offline (navigator.onLine === false) → red/amber banner.
 *   2. Service worker detected a new version ready  → "refresh" toast.
 *   3. Browser fired the install prompt             → "install" CTA.
 *
 * It does NOT auto-reload on update — that breaks forms and is jarring.
 * Instead it shows a one-click "立即刷新" button.
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useOfflineAiQueue } from '../../composables/useOfflineAiQueue'

const { t } = useI18n()
const { queuedCount, formatQueueHint, lastSyncedAt } = useOfflineAiQueue()

const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
const showUpdateToast = ref(false)
const showInstallPrompt = ref(false)
const deferredPrompt = ref<any>(null)
const updateVersion = ref<string>('31')

function setOnline(value: boolean) {
  online.value = value
}

function onBeforeInstallPrompt(e: Event) {
  e.preventDefault()
  deferredPrompt.value = e
  showInstallPrompt.value = true
}

function onAppInstalled() {
  showInstallPrompt.value = false
  deferredPrompt.value = null
}

async function installApp() {
  if (!deferredPrompt.value) return
  deferredPrompt.value.prompt()
  const choice = await deferredPrompt.value.userChoice
  if (choice?.outcome === 'accepted') {
    showInstallPrompt.value = false
  }
  deferredPrompt.value = null
}

function dismissInstall() {
  showInstallPrompt.value = false
  deferredPrompt.value = null
}

function refreshApp() {
  // Tell the SW to skipWaiting, then reload.
  navigator.serviceWorker?.controller?.postMessage({ type: 'SKIP_WAITING' })
  // Give the SW a tick to take over.
  setTimeout(() => window.location.reload(), 200)
}

function dismissUpdate() {
  showUpdateToast.value = false
}

onMounted(() => {
  window.addEventListener('online', () => setOnline(true))
  window.addEventListener('offline', () => setOnline(false))
  window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  window.addEventListener('appinstalled', onAppInstalled)
  // Vite-plugin-pwa exposes a virtual module for update notifications.
  import('virtual:pwa-register')
    .then(({ registerSW }) => {
      registerSW({
        immediate: true,
        onNeedRefresh() {
          showUpdateToast.value = true
        },
        onOfflineReady() {
          // Already covered by the offline banner.
        },
        onRegisterError(err) {
          console.warn('[pwa] SW registration failed', err)
        }
      })
    })
    .catch(() => {
      // Dev mode or virtual module not present — silently skip.
    })
})

onBeforeUnmount(() => {
  window.removeEventListener('online', () => setOnline(true))
  window.removeEventListener('offline', () => setOnline(false))
  window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  window.removeEventListener('appinstalled', onAppInstalled)
})
</script>

<template>
  <div class="pwa-banner" aria-live="polite">
    <!-- Offline banner (red) -->
    <transition name="pwa-slide">
      <div v-if="!online" class="pwa-banner__offline" role="status">
        <div class="pwa-banner__offline-inner">
          <strong>{{ t('pwa.offline.title') }}</strong>
          <span>{{ t('pwa.offline.body') }}</span>
          <span v-if="queuedCount > 0" class="pwa-banner__queue">
            · {{ formatQueueHint() }}
          </span>
          <span v-else-if="lastSyncedAt" class="pwa-banner__queue">
            · {{ t('pwa.offline.synced') }}
          </span>
        </div>
      </div>
    </transition>

    <!-- Update toast (mint) -->
    <transition name="pwa-slide">
      <div v-if="showUpdateToast" class="pwa-banner__update" role="status">
        <div>
          <strong>{{ t('pwa.update.available') }}</strong>
          <span>{{ t('pwa.update.body', { version: updateVersion }) }}</span>
        </div>
        <div class="pwa-banner__actions">
          <button type="button" class="pwa-banner__btn pwa-banner__btn--primary" @click="refreshApp">
            {{ t('pwa.update.refresh') }}
          </button>
          <button type="button" class="pwa-banner__btn" @click="dismissUpdate">
            {{ t('pwa.update.later') }}
          </button>
        </div>
      </div>
    </transition>

    <!-- Install CTA (gold) -->
    <transition name="pwa-slide">
      <div v-if="showInstallPrompt" class="pwa-banner__install" role="status">
        <div>
          <strong>{{ t('pwa.install.title') }}</strong>
          <span>{{ t('pwa.install.body') }}</span>
        </div>
        <div class="pwa-banner__actions">
          <button type="button" class="pwa-banner__btn pwa-banner__btn--primary" @click="installApp">
            {{ t('pwa.install.install') }}
          </button>
          <button type="button" class="pwa-banner__btn" @click="dismissInstall">
            {{ t('pwa.install.dismiss') }}
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.pwa-banner {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: grid;
  gap: 10px;
  z-index: 9100;
  width: min(640px, calc(100vw - 32px));
  pointer-events: none;
}

.pwa-banner__offline,
.pwa-banner__update,
.pwa-banner__install {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 14px;
  font-size: 0.9rem;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(18px);
}

.pwa-banner__offline {
  background: linear-gradient(90deg, rgba(239, 111, 122, 0.92), rgba(242, 185, 92, 0.92));
  color: #1c0a0a;
}

.pwa-banner__update {
  background: linear-gradient(90deg, rgba(54, 216, 180, 0.95), rgba(182, 240, 119, 0.92));
  color: #052017;
}

.pwa-banner__install {
  background: linear-gradient(90deg, rgba(242, 185, 92, 0.95), rgba(255, 196, 100, 0.9));
  color: #2a1a04;
}

.pwa-banner__offline-inner,
.pwa-banner__update > div:first-child,
.pwa-banner__install > div:first-child {
  display: grid;
  gap: 2px;
  flex: 1;
}

.pwa-banner__queue {
  font-size: 0.78rem;
  font-family: var(--font-mono, monospace);
  opacity: 0.85;
}

.pwa-banner__actions {
  display: inline-flex;
  gap: 8px;
  flex-shrink: 0;
}

.pwa-banner__btn {
  appearance: none;
  border: 1px solid currentColor;
  background: rgba(0, 0, 0, 0.08);
  color: inherit;
  padding: 6px 12px;
  border-radius: 999px;
  font-weight: 600;
  cursor: pointer;
  font-size: 0.84rem;
}

.pwa-banner__btn:hover {
  background: rgba(0, 0, 0, 0.18);
}

.pwa-banner__btn--primary {
  background: rgba(255, 255, 255, 0.85);
  color: #052017;
  border-color: transparent;
}

.pwa-banner__btn--primary:hover {
  background: #fff;
}

.pwa-slide-enter-active,
.pwa-slide-leave-active {
  transition: transform 220ms cubic-bezier(0.16, 1, 0.3, 1), opacity 220ms ease;
}
.pwa-slide-enter-from,
.pwa-slide-leave-to {
  transform: translateY(20px);
  opacity: 0;
}
</style>
