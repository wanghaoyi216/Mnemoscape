<script setup lang="ts">
defineProps<{ userId: string; position: number[] }>()
</script>

<template>
  <div class="ghost-indicator" aria-hidden="true">
    <div
      class="ghost-dot"
      :style="{
        left: ((position[0] + 5) / 10 * 100) + '%',
        top: ((5 - position[2]) / 10 * 100) + '%',
      }"
    >
      <div class="pulse"></div>
      <span class="label">{{ userId }}</span>
    </div>
  </div>
</template>

<style scoped>
.ghost-indicator {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 5;
}

.ghost-dot {
  position: absolute;
  transform: translate(-50%, -50%);
  display: grid;
  justify-items: center;
  gap: 4px;
}

.pulse {
  width: 14px;
  height: 14px;
  border-radius: var(--radius-sm);
  background: rgba(32, 199, 164, 0.82);
  box-shadow: 0 0 18px rgba(32, 199, 164, 0.44);
  animation: pulse 2s infinite;
}

.label {
  display: inline-flex;
  align-items: center;
  padding: 5px 9px;
  border-radius: var(--radius-sm);
  background: rgba(8, 9, 8, 0.7);
  border: 1px solid var(--border);
  font-size: 0.7rem;
  color: var(--text-soft);
}

@keyframes pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.8;
  }

  50% {
    transform: scale(1.35);
    opacity: 1;
  }
}
</style>
