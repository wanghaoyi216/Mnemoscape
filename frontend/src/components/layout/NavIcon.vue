<script setup lang="ts">
/** 顶部导航共用图标 — 台式分组菜单与移动抽屉共用，保持描边/尺寸统一。 */
export type NavIconName =
  | 'memories'
  | 'create'
  | 'graph'
  | 'timeline'
  | 'atlas'
  | 'resonance'
  | 'chat'
  | 'admin'

defineProps<{ name: NavIconName }>()
</script>

<template>
  <svg class="nav-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
    <template v-if="name === 'memories'">
      <path d="M4 6h16M4 12h16M4 18h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
    </template>
    <template v-else-if="name === 'create'">
      <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
    </template>
    <template v-else-if="name === 'graph'">
      <circle cx="12" cy="12" r="2" fill="currentColor" />
      <circle cx="5" cy="6" r="1.4" fill="currentColor" />
      <circle cx="19" cy="7" r="1.2" fill="currentColor" />
      <circle cx="6" cy="18" r="1.2" fill="currentColor" />
      <circle cx="18" cy="17" r="1.4" fill="currentColor" />
      <path d="M12 12L5 6M12 12l7-5M12 12l-6 6M12 12l6 5" stroke="currentColor" stroke-width="1" opacity="0.55" />
    </template>
    <template v-else-if="name === 'timeline'">
      <path d="M12 3v18" stroke="currentColor" stroke-width="1.6" />
      <circle cx="12" cy="7" r="2" fill="currentColor" />
      <circle cx="12" cy="13" r="2" fill="currentColor" />
      <circle cx="12" cy="19" r="2" fill="currentColor" />
      <path d="M14 7h4M6 13h4M14 19h4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
    </template>
    <template v-else-if="name === 'atlas'">
      <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
      <path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18" stroke="currentColor" stroke-width="1.2" />
    </template>
    <template v-else-if="name === 'resonance'">
      <circle cx="9" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
      <circle cx="15" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
    </template>
    <template v-else-if="name === 'chat'">
      <path
        d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </template>
    <template v-else>
      <rect x="3" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <rect x="14" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <rect x="14" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <rect x="3" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
    </template>
  </svg>
</template>

<style scoped>
.nav-icon {
  flex-shrink: 0;
  opacity: 0.78;
  transition: opacity 180ms ease;
}
</style>
