<script setup lang="ts">
/**
 * 全局 Toast 渲染容器。挂在 App.vue 根部一次即可；订阅 useToastStore.toasts，
 * 按 tone 显示不同色调，自动消失由 store push 时的 setTimeout 触发。
 */
import { useI18n } from 'vue-i18n'
import { useToastStore } from '../../stores/toast'

const { t } = useI18n()
const store = useToastStore()
</script>

<template>
  <div class="toast-stack" role="status" aria-live="polite">
    <transition-group name="toast">
      <div
        v-for="msg in store.toasts"
        :key="msg.id"
        class="toast"
        :class="`toast--${msg.tone}`"
      >
        <span class="toast__text">{{ t(msg.key, (msg.params || {}) as any) }}</span>
        <button class="toast__close" type="button" aria-label="dismiss" @click="store.dismiss(msg.id)">×</button>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-stack {
  position: fixed;
  top: 90px;
  right: 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  z-index: 9000;
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.94);
  border: 1px solid var(--border);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
  font-size: 0.86rem;
  color: var(--text);
  min-width: 240px;
  max-width: 420px;
}

.toast--info { border-left: 3px solid #60a5fa; }
.toast--success { border-left: 3px solid #34d399; }
.toast--warning {
  border-left: 3px solid #fbbf24;
  color: #fde68a;
}
.toast--error {
  border-left: 3px solid #f87171;
  color: #fecaca;
}

.toast__text {
  flex: 1;
  word-break: break-word;
}

.toast__close {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 1.1rem;
  font-weight: 500;
  cursor: pointer;
  line-height: 1;
}
.toast__close:hover {
  color: var(--text);
}

.toast-enter-active, .toast-leave-active {
  transition: all 240ms cubic-bezier(0.165, 0.84, 0.44, 1);
}
.toast-enter-from, .toast-leave-to {
  transform: translateX(120%);
  opacity: 0;
}
</style>
