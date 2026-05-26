<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ place: [content: string, mood: string]; close: [] }>()
const content = ref('')
const mood = ref('warm')

const moods = ['warm', 'melancholic', 'joyful', 'contemplative', 'grateful']
</script>

<template>
  <div class="note-composer-overlay" role="dialog" aria-modal="true" aria-labelledby="note-composer-title">
    <form class="note-composer stack" @submit.prevent="emit('place', content, mood)">
      <div class="stack">
        <h3 id="note-composer-title" class="section-title">Leave a note</h3>
        <p class="help-text" style="margin: 0;">Attach a brief thought to the current resonance space.</p>
      </div>

      <textarea
        v-model="content"
        class="textarea"
        placeholder="What do you feel in this space?"
        rows="4"
      ></textarea>

      <div class="chip-grid">
        <button
          v-for="m in moods"
          :key="m"
          type="button"
          class="button button--secondary"
          :class="{ 'button--primary': mood === m }"
          @click="mood = m"
        >
          {{ m }}
        </button>
      </div>

      <div class="hero-row" style="margin: 0;">
        <button class="button button--primary" type="submit" :disabled="!content.trim()">Place note</button>
        <button class="button button--ghost" type="button" @click="emit('close')">Cancel</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.note-composer-overlay {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  z-index: 20;
  background: rgba(2, 6, 23, 0.54);
  backdrop-filter: blur(10px);
}

.note-composer {
  width: min(420px, calc(100vw - 32px));
  padding: 24px;
}
</style>
