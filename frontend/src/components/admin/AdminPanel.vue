<script setup lang="ts">
/**
 * AdminPanel — 8 个管理端面板共用的状态外壳。
 *
 * 五态渲染（design.md §共享组件 `<AdminPanel>`）：
 *   - idle    : 空 body，仅 header 占位（首次挂载、未触发 fetch）
 *   - loading : 三条骨架条
 *   - empty   : `admin.common.empty` 文案
 *   - error   : `admin.common.error` + 错误码 + message + 重试按钮（点击 onRetry）
 *   - ready   : 渲染默认 slot（具体面板自己塞图表）
 *
 * 同时不论 state 为何，只要 `degraded === true`：
 *   - header 右侧渲染 `admin.common.degradedBadge` 徽标
 *   - 底部渲染 `admin.common.degradedHint` + degradedReasons 列表
 *
 * CSS 约定（R5.6）：
 *   `.admin-panel` 默认带 `padding-top: var(--app-header-h, 88px)`，让独立成页
 *   （`/admin/active-users` 直接访问时）的面板不被 AppHeader 压住。
 *   `/admin` 首页网格里通过 `.admin-grid > .admin-panel` 与
 *   `.admin-panel-cell .admin-panel` 选择器把这个 padding 重置为 0，避免重复让位。
 */
import { useI18n } from 'vue-i18n'

defineProps<{
  /** i18n key, e.g. `admin.activeUsers.title`. 内部用 t(title) 翻译。 */
  title: string
  state: 'idle' | 'loading' | 'empty' | 'error' | 'ready'
  error?: {
    code: string
    message: string
    /** v7：UPSTREAM_UNAVAILABLE 时携带的 upstream/failureKind/detail，让错误卡能展示根因。 */
    diagnostic?: {
      upstreamName?: string
      failureKind?: string
      detail?: string
      causeType?: string
    }
  } | null
  degraded?: boolean
  degradedReasons?: string[]
  onRetry?: () => void
}>()

const { t } = useI18n()
</script>

<template>
  <section class="admin-panel" :data-state="state">
    <header class="admin-panel__header">
      <h3 class="admin-panel__title">{{ t(title) }}</h3>
      <span
        v-if="degraded"
        class="admin-panel__badge admin-panel__badge--degraded"
        role="status"
      >
        {{ t('admin.common.degradedBadge') }}
      </span>
    </header>

    <div class="admin-panel__body">
      <!-- ready -->
      <slot v-if="state === 'ready'" />

      <!-- loading 骨架 -->
      <div
        v-else-if="state === 'loading'"
        class="admin-panel__skel"
        role="status"
        :aria-label="t('admin.common.loading')"
      >
        <div v-for="n in 3" :key="n" class="admin-panel__skel-bar" />
      </div>

      <!-- empty -->
      <div v-else-if="state === 'empty'" class="admin-panel__empty">
        <p>{{ t('admin.common.empty') }}</p>
      </div>

      <!-- error -->
      <div v-else-if="state === 'error'" class="admin-panel__error" role="alert">
        <p class="admin-panel__error-head">
          <strong>{{ t('admin.common.error') }}</strong>
          <span v-if="error?.code" class="admin-panel__error-code">{{ error.code }}</span>
        </p>
        <p v-if="error?.message" class="admin-panel__error-msg">{{ error.message }}</p>

        <!-- v7：UPSTREAM_UNAVAILABLE 根因诊断卡。仅在 backend 返回了 diagnostic
             payload 时展示。failureKind / detail / upstreamName 对管理员是可执行
             的（"memory-service is not registered in Nacos" 直接告诉你下一步要
             做什么）。 -->
        <div v-if="error?.diagnostic" class="admin-panel__error-diagnostic">
          <p class="admin-panel__diag-head">{{ t('admin.common.diagnosticHead') }}</p>
          <ul class="admin-panel__diag-list">
            <li v-if="error.diagnostic.upstreamName">
              <span class="admin-panel__diag-k">{{ t('admin.common.diagUpstream') }}</span>
              <code>{{ error.diagnostic.upstreamName }}</code>
            </li>
            <li v-if="error.diagnostic.failureKind">
              <span class="admin-panel__diag-k">{{ t('admin.common.diagFailureKind') }}</span>
              <code>{{ error.diagnostic.failureKind }}</code>
            </li>
            <li v-if="error.diagnostic.detail" class="admin-panel__diag-detail">
              <span class="admin-panel__diag-k">{{ t('admin.common.diagDetail') }}</span>
              <span>{{ error.diagnostic.detail }}</span>
            </li>
            <li v-if="error.diagnostic.causeType">
              <span class="admin-panel__diag-k">{{ t('admin.common.diagCauseType') }}</span>
              <code>{{ error.diagnostic.causeType }}</code>
            </li>
          </ul>
          <p class="admin-panel__diag-hint">{{ t('admin.common.diagHint') }}</p>
        </div>

        <button
          class="button button--ghost admin-panel__retry"
          type="button"
          @click="onRetry?.()"
        >
          {{ t('admin.common.retry') }}
        </button>
      </div>
      <!-- state === 'idle' → 不渲染任何 body 内容 -->
    </div>

    <footer
      v-if="degraded"
      class="admin-panel__degraded-footer"
      role="status"
    >
      <p>{{ t('admin.common.degradedHint') }}</p>
      <ul v-if="degradedReasons && degradedReasons.length" class="admin-panel__degraded-list">
        <li v-for="r in degradedReasons" :key="r">{{ r }}</li>
      </ul>
    </footer>
  </section>
</template>

<style scoped>
/* 独立成页时让位 AppHeader：用 :root 上由 AppHeader 实时发布的 --app-header-h，
   未发布前 fallback 88px（design.md / R5.6）。 */
.admin-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: var(--app-header-h, 88px) clamp(20px, 3vw, 32px) clamp(20px, 3vw, 32px);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  color: var(--text);
}

/* 在 `/admin` 首页网格内嵌入时不重复 padding-top —— 由父容器统一让位。
   两种命名都支持：网格容器 `.admin-grid` 直接包裹，或单元 `<AdminPanelCell>`。 */
:where(.admin-grid) > .admin-panel,
:where(.admin-panel-cell) .admin-panel {
  padding-top: clamp(20px, 3vw, 32px);
}

.admin-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 14px;
}

.admin-panel__title {
  margin: 0;
  font-family: var(--font-display, var(--font-sans));
  font-size: 1.15rem;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
}

.admin-panel__badge {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  border: 1px solid transparent;
  white-space: nowrap;
}

.admin-panel__badge--degraded {
  color: #fde68a;
  border-color: rgba(245, 158, 11, 0.42);
  background: rgba(245, 158, 11, 0.12);
}

.admin-panel__body {
  flex: 1 1 auto;
  min-height: 120px;
  display: flex;
  flex-direction: column;
}

/* ============ Loading 骨架 ============ */
.admin-panel__skel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}

.admin-panel__skel-bar {
  height: 14px;
  width: 100%;
  border-radius: var(--radius-sm);
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(255, 255, 255, 0.10) 50%,
    rgba(255, 255, 255, 0.04) 100%
  );
  background-size: 200% 100%;
  animation: admin-panel-skel 1.2s ease-in-out infinite;
}
.admin-panel__skel-bar:nth-child(2) { width: 86%; }
.admin-panel__skel-bar:nth-child(3) { width: 64%; }

@keyframes admin-panel-skel {
  0%   { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

/* ============ Empty ============ */
.admin-panel__empty {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px 8px;
  color: var(--text-muted);
  font-size: 0.92rem;
}
.admin-panel__empty p { margin: 0; }

/* ============ Error ============ */
.admin-panel__error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 18px 20px;
  border: 1px solid rgba(239, 68, 68, 0.32);
  background: rgba(239, 68, 68, 0.06);
  border-radius: var(--radius-md);
  color: var(--text-soft);
}

.admin-panel__error-head {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fca5a5;
  font-size: 0.96rem;
}

.admin-panel__error-code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  font-size: 0.78rem;
  letter-spacing: 0.04em;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.15);
  color: #fecaca;
}

.admin-panel__error-msg {
  margin: 0;
  font-size: 0.88rem;
  color: var(--text-muted);
  line-height: 1.6;
  word-break: break-word;
}

.admin-panel__retry {
  align-self: flex-start;
  margin-top: 4px;
}

/* ============ v7：诊断卡（UPSTREAM_UNAVAILABLE 根因展示） ============ */
.admin-panel__error-diagnostic {
  width: 100%;
  margin-top: 4px;
  padding: 12px 14px;
  border: 1px dashed rgba(239, 68, 68, 0.36);
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.04);
}
.admin-panel__diag-head {
  margin: 0 0 8px;
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: #fecaca;
}
.admin-panel__diag-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.admin-panel__diag-list li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
  flex-wrap: wrap;
}
.admin-panel__diag-list code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  font-size: 0.78rem;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(239, 68, 68, 0.12);
  color: #fed7d7;
}
.admin-panel__diag-k {
  font-size: 0.74rem;
  color: var(--text-muted);
  min-width: 76px;
  letter-spacing: 0.04em;
}
.admin-panel__diag-detail {
  align-items: flex-start;
  word-break: break-word;
}
.admin-panel__diag-hint {
  margin: 8px 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
  line-height: 1.5;
}

/* ============ Degraded footer ============ */
.admin-panel__degraded-footer {
  border-top: 1px dashed rgba(245, 158, 11, 0.32);
  padding-top: 12px;
  color: var(--text-muted);
  font-size: 0.84rem;
}
.admin-panel__degraded-footer p {
  margin: 0 0 6px;
  color: #fde68a;
}
.admin-panel__degraded-list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.admin-panel__degraded-list li {
  list-style: disc;
}
</style>
