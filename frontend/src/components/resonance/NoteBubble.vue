<script setup lang="ts">
import type { MemoryNote } from '../../types'

const props = defineProps<{ note: MemoryNote }>()

let position = { x: 0, y: 1.5, z: 0 }
try {
  position = JSON.parse(props.note.position3d)
} catch {
  position = { x: 0, y: 1.5, z: 0 }
}

const moodColors: Record<string, string> = {
  warm: '#f0b35b',
  melancholic: '#6cc6ff',
  joyful: '#c8f169',
  contemplative: '#f3a0b5',
  grateful: '#48d597',
}

const bg = moodColors[props.note.mood] || '#20c7a4'
</script>

<template>
  <div
    class="note-bubble"
    :style="{
      left: ((position.x + 5) / 10 * 100) + '%',
      top: ((5 - position.z) / 10 * 100) + '%',
    }"
  >
    <div class="bubble" :style="{ borderColor: bg }">
      <p>{{ note.content }}</p>
      <span class="mood-tag" :style="{ color: bg }">{{ note.mood }}</span>
    </div>
  </div>
</template>

<style scoped>
.note-bubble {
  position: absolute;
  z-index: 6;
  transform: translate(-50%, -50%);
  max-width: 220px;
}

.bubble {
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  border: 1px solid;
  background: rgba(8, 9, 8, 0.82);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(12px);
}

.bubble p {
  margin: 0 0 6px;
  color: var(--text-soft);
  line-height: 1.55;
  font-size: 0.84rem;
}

.mood-tag {
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
}
</style>
