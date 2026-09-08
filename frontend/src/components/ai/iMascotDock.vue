<script setup lang="ts">
/**
 * Compatibility alias / wrapper for AiMascotDock.vue
 * Supports both named component resolution conventions (iMascotDock / AiMascotDock).
 */
import AiMascotDock from './AiMascotDock.vue'
</script>

<template>
  <AiMascotDock v-bind="$attrs" />
</template>
