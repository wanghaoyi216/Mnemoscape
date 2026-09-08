<script setup lang="ts">
/**
 * 管理端通用图标按钮。
 *
 * 设计动机（避坑原则 4）：废弃操作按钮里的原生 Emoji（🗑 🔓 🌐 📦 💬 …）
 * 与字符箭头（↑↓✓✗）。Emoji 在 Windows 字体栈下经常渲染成空方块，导致用户
 * 以为"按钮丢了"；字符箭头在不同字号下基线偏移、对齐不齐。本组件统一提供
 * 矢量 SVG 图标，强制 width/height + currentColor + flex-shrink:0，跨主题
 * （mint/pink/gold/blue）保证形状与颜色一致可见。
 *
 * 用法：
 *   <AdminIconBtn icon="trash"      danger title="删除"  @click="onDelete(row)" />
 *   <AdminIconBtn icon="check"      title="通过"        @click="onAccept(row)" />
 *   <AdminIconBtn icon="arrow-down" :title="...?"       @click="onChangeRole(row)" />
 *
 * ICONS 用 path d 字符串数组（不是整个 SVG 片段）——每段渲染成一个 <path>，
 * 避免 v-html 注入风险，也避免正则拆 HTML 的脆弱写法。
 */
type IconName =
  | 'lock' | 'unlock'
  | 'globe' | 'lock-private'
  | 'trash'
  | 'check' | 'x'
  | 'arrow-up' | 'arrow-down'
  | 'archive' | 'message'
  | 'verify' | 'unverify'

const ICONS: Record<IconName, string[]> = {
  lock: ['M3 11V7a5 5 0 0 1 9.9-1', 'M3 11h18v11H3z'],
  unlock: ['M7 11V7a5 5 0 0 1 10 0v4', 'M3 11h18v11H3z'],
  globe: ['M2 12h20', 'M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z', 'M12 2a10 10 0 0 0 0 20', 'M12 2a10 10 0 0 1 0 20'],
  'lock-private': ['M7 11V7a5 5 0 0 1 10 0v4', 'M3 11h18v11H3z'],
  trash: ['M3 6h18', 'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6', 'M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2', 'M10 11v6', 'M14 11v6'],
  check: ['M20 6 9 17 4 12'],
  x: ['M18 6 6 18', 'M6 6 18 18'],
  'arrow-up': ['M12 19V5', 'M5 12l7-7 7 7'],
  'arrow-down': ['M12 5v14', 'M19 12l-7 7-7-7'],
  archive: ['M3 4h18v4H3z', 'M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8', 'M10 12h4'],
  message: ['M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'],
  verify: ['M9 12l2 2 4-4', 'M12 3 4 6v6c0 4.5 3.4 8.4 8 9 4.6-.6 8-4.5 8-9V6l-8-3z'],
  unverify: ['M12 3 4 6v6c0 4.5 3.4 8.4 8 9 4.6-.6 8-4.5 8-9V6l-8-3z', 'M8 16 16 8'],
}

defineProps<{
  icon: IconName
  title?: string
  danger?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{ click: [e: MouseEvent] }>()
</script>

<template>
  <button
    class="admin-icon-btn"
    :class="{ 'admin-icon-btn--danger': danger }"
    type="button"
    :title="title"
    :aria-label="title"
    :disabled="disabled"
    @click="emit('click', $event)"
  >
    <svg
      viewBox="0 0 24 24"
      width="15"
      height="15"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path v-for="d in ICONS[icon]" :key="d" :d="d" />
    </svg>
  </button>
</template>

<style scoped>
.admin-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-left: 4px;
  flex-shrink: 0;
  transition: background 140ms ease, border-color 140ms ease, color 140ms ease;
}
.admin-icon-btn svg { flex-shrink: 0; }
.admin-icon-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.admin-icon-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.08); }
.admin-icon-btn--danger {
  color: #fca5a5;
  border-color: rgba(239, 68, 68, 0.32);
}
.admin-icon-btn--danger:hover:not(:disabled) {
  background: rgba(239, 68, 68, 0.15);
  border-color: rgba(239, 68, 68, 0.4);
  color: #fca5a5;
}
</style>
