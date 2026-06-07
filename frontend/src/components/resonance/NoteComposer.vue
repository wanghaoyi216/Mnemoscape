<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

const emit = defineEmits<{ place: [content: string, mood: string]; close: [] }>()
const { t } = useI18n()

const content = ref('')
const mood = ref('warm')

// 5 个 mood 与后端 ResonanceNote 约定一致；显示文案走 resonance.beacon.moods 字典
const moods = ['warm', 'melancholic', 'joyful', 'contemplative', 'grateful'] as const
</script>

<template>
  <div class="note-composer-overlay" role="dialog" aria-modal="true" aria-labelledby="note-composer-title">
    <form class="note-composer stack" @submit.prevent="emit('place', content, mood)">
      <div class="stack">
        <h3 id="note-composer-title" class="section-title">{{ t('resonance.noteComposer.title') }}</h3>
        <p class="help-text" style="margin: 0;">{{ t('resonance.noteComposer.subtitle') }}</p>
      </div>

      <textarea
        v-model="content"
        class="textarea"
        :placeholder="t('resonance.noteComposer.placeholder')"
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
          {{ t(`resonance.beacon.moods.${m}`, m) }}
        </button>
      </div>

      <div class="hero-row" style="margin: 0;">
        <button class="button button--primary" type="submit" :disabled="!content.trim()">
          {{ t('resonance.noteComposer.submit') }}
        </button>
        <button class="button button--ghost" type="button" @click="emit('close')">
          {{ t('resonance.noteComposer.cancel') }}
        </button>
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
