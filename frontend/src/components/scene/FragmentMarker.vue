<script setup lang="ts">
import { ref } from 'vue'
import type { MemoryFragment } from '../../types'

const props = defineProps<{ fragment: MemoryFragment }>()
const emit = defineEmits<{ discover: [id: string] }>()
const glowing = ref(false)

function toggle() {
  glowing.value = true
  emit('discover', props.fragment.id)
  window.setTimeout(() => {
    glowing.value = false
  }, 1800)
}
</script>

<template>
  <button
    type="button"
    class="fragment-marker"
    :class="{ glow: glowing, discovered: fragment.isDiscovered }"
    @click="toggle"
  >
    <span class="marker-dot"></span>
    <span v-if="glowing" class="marker-content">
      <strong>{{ fragment.fragmentType }}</strong>
      <span>{{ fragment.content }}</span>
    </span>
  </button>
</template>

<style scoped>
.fragment-marker {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.marker-dot {
  width: 16px;
  height: 16px;
  border-radius: var(--radius-sm);
  border: 2px solid #20c7a4;
  background: rgba(32, 199, 164, 0.28);
  box-shadow: 0 0 12px rgba(32, 199, 164, 0.35);
  transition: transform 180ms ease, background-color 180ms ease, box-shadow 180ms ease;
}

.fragment-marker:hover .marker-dot,
.fragment-marker.glow .marker-dot {
  transform: scale(1.25);
  background: rgba(108, 198, 255, 0.82);
  box-shadow: 0 0 20px rgba(108, 198, 255, 0.48);
}

.fragment-marker.discovered .marker-dot {
  border-color: rgba(52, 211, 153, 0.9);
  background: rgba(52, 211, 153, 0.24);
}

.marker-content {
  display: grid;
  gap: 4px;
  max-width: 240px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: rgba(8, 9, 8, 0.9);
  box-shadow: var(--shadow-md);
  color: var(--text-soft);
  text-align: left;
}

.marker-content strong {
  font-size: 0.75rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #a7f3e2;
}

.marker-content span {
  font-size: 0.84rem;
  line-height: 1.5;
}
</style>
