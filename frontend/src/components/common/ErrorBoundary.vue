<script setup lang="ts">
/**
 * R31: global error boundary using Vue 3 onErrorCaptured.
 *
 * Why this exists:
 *   - Vue's errorHandler in main.ts only *logs*. The user still sees a blank
 *     white pane. ErrorBoundary turns that into a branded fallback page
 *     that explains what happened, lets the user retry, and ships the
 *     stack to the support widget if they choose to.
 *   - The boundary is recursive: each <slot/> is wrapped, so a child
 *     ErrorBoundary can catch a sibling crash without nuking the whole app.
 *
 * Boundaries vs try/catch (interview note):
 *   - try/catch runs *synchronously* in one stack frame. It cannot catch
 *     errors thrown asynchronously by another component's render function
 *     or by a lifecycle hook like onMounted.
 *   - onErrorCaptured runs in the parent's render context *after* a child
 *     has thrown, so it can isolate the failure, swap the slot for a
 *     fallback, and keep the rest of the tree alive. That's the only way
 *     to get a true "wall" around a component subtree in Vue.
 */
import { onErrorCaptured, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

interface Props {
  /** Optional scoped fallback key — surfaces in the error report so the
   *  support team knows which museum "room" blew up. */
  scope?: string
  /** When true, the fallback shows the raw stack. Off in production. */
  showStack?: boolean
}
const props = withDefaults(defineProps<Props>(), { scope: 'unknown', showStack: false })

const { t, locale } = useI18n()
const router = useRouter()

const errorInfo = ref<{ message: string; stack?: string; source?: string } | null>(null)
const copied = ref(false)

onErrorCaptured((err, _instance, info) => {
  // err is `unknown` in Vue 3.3+ — narrow before reading message.
  const e = err as { message?: string; stack?: string; name?: string } | null
  errorInfo.value = {
    message: e?.message ?? String(err),
    stack: e?.stack,
    source: info,
  }
  // Returning false STOPS propagation up the tree. That's the whole point:
  // we don't want main.ts's global errorHandler to also log this twice,
  // and we don't want a *parent* ErrorBoundary to wrap the fallback.
  return false
})

const hasError = computed(() => errorInfo.value !== null)
const reportHref = computed(() => {
  if (!errorInfo.value) return '#'
  const subject = encodeURIComponent(`[R31 ErrorBoundary] ${props.scope}`)
  const body = encodeURIComponent(
    `${t('errorBoundary.componentLabel')}: ${props.scope}\n` +
    `${t('errorBoundary.detail')}: ${errorInfo.value.message}\n` +
    (errorInfo.value.stack ?? '')
  )
  return `mailto:support@mnemoscape.app?subject=${subject}&body=${body}`
})

function retry() {
  errorInfo.value = null
  // Re-mount the slot by toggling a key would be cleaner, but the simplest
  // and most predictable recovery is a full router refresh.
  router.replace({ path: '/', query: { __retry: String(Date.now()) } }).catch(() => {})
}

function goHome() {
  errorInfo.value = null
  router.push('/').catch(() => {})
}

async function copyError() {
  if (!errorInfo.value) return
  const text = `${props.scope}\n${errorInfo.value.message}\n${errorInfo.value.stack ?? ''}`
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => (copied.value = false), 1800)
  } catch {
    // The text lives in the DOM, the user can still select-and-copy manually.
  }
}
</script>

<template>
  <div class="error-boundary" :class="{ 'error-boundary--active': hasError }">
    <slot v-if="!hasError" />
    <div v-else class="error-boundary__fallback" role="alert" aria-live="assertive">
      <div class="error-boundary__aurora" aria-hidden="true" />
      <div class="error-boundary__content">
        <span class="error-boundary__eyebrow">R31 · ErrorBoundary</span>
        <h1 class="error-boundary__title">{{ t('errorBoundary.title') }}</h1>
        <p class="error-boundary__subtitle">{{ t('errorBoundary.subtitle') }}</p>

        <div class="error-boundary__actions">
          <button type="button" class="error-boundary__btn error-boundary__btn--primary" @click="retry">
            {{ t('errorBoundary.retry') }}
          </button>
          <button type="button" class="error-boundary__btn" @click="goHome">
            {{ t('errorBoundary.goHome') }}
          </button>
          <a class="error-boundary__btn error-boundary__btn--ghost" :href="reportHref">
            {{ t('errorBoundary.reportHint') }}
          </a>
        </div>

        <details v-if="props.showStack || errorInfo" class="error-boundary__detail">
          <summary>{{ t('errorBoundary.detail') }}</summary>
          <dl>
            <dt>{{ t('errorBoundary.componentLabel') }}</dt>
            <dd><code>{{ scope }}</code></dd>
            <dt>{{ t('errorBoundary.stackLabel') }}</dt>
            <dd>
              <pre><code>{{ errorInfo?.message }}\n{{ errorInfo?.stack }}</code></pre>
              <button type="button" class="error-boundary__copy" @click="copyError">
                {{ copied ? t('errorBoundary.copySuccess') : t('errorBoundary.copyFailed') }}
              </button>
            </dd>
            <dt>locale</dt>
            <dd><code>{{ locale }}</code></dd>
          </dl>
        </details>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-boundary {
  position: relative;
  display: contents;
}

.error-boundary__fallback {
  min-height: 60vh;
  display: grid;
  place-items: center;
  padding: 48px 24px;
  position: relative;
  overflow: hidden;
  border-radius: var(--radius-lg, 16px);
  border: 1px solid rgba(239, 111, 122, 0.18);
  background: linear-gradient(180deg, rgba(20, 12, 18, 0.92), rgba(8, 10, 14, 0.96));
}

.error-boundary__aurora {
  position: absolute;
  inset: -40% -20% auto -20%;
  height: 360px;
  background: radial-gradient(circle at 30% 30%, rgba(239, 111, 122, 0.32), transparent 60%),
              radial-gradient(circle at 70% 60%, rgba(242, 185, 92, 0.22), transparent 60%);
  filter: blur(40px);
  pointer-events: none;
}

.error-boundary__content {
  position: relative;
  z-index: 1;
  max-width: 560px;
  text-align: center;
  display: grid;
  gap: 18px;
}

.error-boundary__eyebrow {
  font-family: var(--font-mono, monospace);
  font-size: 0.78rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: rgba(255, 200, 200, 0.72);
}

.error-boundary__title {
  font-family: var(--font-display, serif);
  font-size: clamp(1.6rem, 2.4vw + 1rem, 2.4rem);
  font-weight: 800;
  background: linear-gradient(135deg, #ffb4b4, #ffd28a);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  margin: 0;
}

.error-boundary__subtitle {
  color: rgba(245, 248, 252, 0.78);
  line-height: 1.55;
  margin: 0;
}

.error-boundary__actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  margin-top: 6px;
}

.error-boundary__btn {
  appearance: none;
  border: 1px solid var(--border, rgba(255, 255, 255, 0.12));
  background: rgba(255, 255, 255, 0.04);
  color: var(--text, #f4f5f7);
  padding: 10px 18px;
  border-radius: 999px;
  font-weight: 600;
  font-size: 0.92rem;
  cursor: pointer;
  text-decoration: none;
  transition: background 180ms ease, transform 180ms ease;
}

.error-boundary__btn:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-1px);
}

.error-boundary__btn--primary {
  background: linear-gradient(135deg, #36d8b4, #b6f077);
  color: #052017;
  border-color: transparent;
}

.error-boundary__btn--ghost {
  background: transparent;
}

.error-boundary__detail {
  margin-top: 16px;
  text-align: left;
  background: rgba(0, 0, 0, 0.32);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  padding: 14px 16px;
  font-size: 0.84rem;
  color: rgba(245, 248, 252, 0.84);
}

.error-boundary__detail summary {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: 8px;
  list-style: none;
}

.error-boundary__detail summary::-webkit-details-marker {
  display: none;
}

.error-boundary__detail dl {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 4px 12px;
  margin: 0;
}

.error-boundary__detail dt {
  color: rgba(245, 248, 252, 0.5);
  font-family: var(--font-mono, monospace);
  font-size: 0.78rem;
}

.error-boundary__detail dd {
  margin: 0;
}

.error-boundary__detail pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow: auto;
  font-family: var(--font-mono, monospace);
  font-size: 0.78rem;
  color: rgba(255, 220, 220, 0.85);
}

.error-boundary__copy {
  margin-top: 8px;
  appearance: none;
  background: transparent;
  border: 1px dashed rgba(255, 255, 255, 0.2);
  color: rgba(245, 248, 252, 0.7);
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.74rem;
  cursor: pointer;
}
</style>
