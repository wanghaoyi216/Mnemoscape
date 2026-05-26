<script setup lang="ts">
/**
 * E 键触发的「全息展开」卡片。
 * 视觉：薄玻璃 + 金色/薄荷边缘扫描线 + 自顶部铺开的入场动画，
 * 模拟全息投影从信标位置升起的感觉。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { images } from '../../assets/media-catalog'
import type { MemoryNote } from '../../types'

const props = defineProps<{ note: MemoryNote }>()
const emit = defineEmits<{ close: [] }>()
const { t, locale } = useI18n()

const beaconArt = images.memoryBeacon.src

const moodColor = computed(() => {
  switch (props.note.mood) {
    case 'warm':          return '#f2b95c'
    case 'joyful':        return '#ffd273'
    case 'melancholic':   return '#5f7bd8'
    case 'contemplative': return '#846edc'
    case 'grateful':      return '#36d8b4'
    default:              return '#36d8b4'
  }
})

const createdAt = computed(() => {
  if (!props.note.createdAt) return ''
  try {
    return new Intl.DateTimeFormat(locale.value, {
      month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit',
    }).format(new Date(props.note.createdAt))
  } catch {
    return props.note.createdAt
  }
})
</script>

<template>
  <transition name="hologram">
    <div class="beacon-hologram" role="dialog" aria-modal="true" aria-labelledby="beacon-hologram-title">
      <div class="beacon-hologram__inner" :style="{ '--mood-color': moodColor }">
        <header class="beacon-hologram__head">
          <img class="beacon-hologram__art" :src="beaconArt" alt="" aria-hidden="true" />
          <div>
            <p class="eyebrow">{{ t('resonance.beacon.eyebrow') }}</p>
            <h3 id="beacon-hologram-title" class="section-title">{{ t('resonance.beacon.title') }}</h3>
            <p class="help-text">{{ t('resonance.beacon.from', { author: note.authorId || t('resonance.beacon.anonymous') }) }}</p>
          </div>
          <button
            class="beacon-hologram__close"
            type="button"
            :aria-label="t('common.cancel')"
            @click="emit('close')"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
        </header>

        <p class="beacon-hologram__content">{{ note.content }}</p>

        <footer class="beacon-hologram__meta">
          <span class="chip beacon-hologram__mood">
            <i class="beacon-hologram__dot"></i>
            {{ t(`resonance.beacon.moods.${note.mood}`, note.mood) }}
          </span>
          <span class="help-text">{{ createdAt }}</span>
        </footer>

        <!-- 扫描线 — 装饰层 -->
        <div class="beacon-hologram__scanline" aria-hidden="true"></div>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.beacon-hologram {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: end center;
  padding-bottom: 36px;
  z-index: 25;
  pointer-events: none; /* 让画布事件穿透，仅卡片本身接收点击 */
}

.beacon-hologram__inner {
  pointer-events: auto;
  position: relative;
  width: min(460px, calc(100vw - 40px));
  padding: 22px 24px 20px;
  border-radius: var(--radius-md);
  background: rgba(10, 14, 22, 0.78);
  backdrop-filter: blur(18px) saturate(160%);
  -webkit-backdrop-filter: blur(18px) saturate(160%);
  border: 1px solid color-mix(in srgb, var(--mood-color) 36%, rgba(255, 255, 255, 0.08));
  box-shadow:
    0 32px 60px -28px rgba(0, 0, 0, 0.7),
    0 0 24px -10px color-mix(in srgb, var(--mood-color) 60%, transparent);
  overflow: hidden;
  display: grid;
  gap: 14px;
}

.beacon-hologram__inner::before {
  /* 顶部金色细线，模拟"全息投影源" */
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--mood-color), transparent);
  filter: blur(0.5px);
  opacity: 0.85;
}

.beacon-hologram__head {
  display: grid;
  grid-template-columns: 48px 1fr auto;
  gap: 12px;
  align-items: center;
}

.beacon-hologram__art {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  border: 1px solid color-mix(in srgb, var(--mood-color) 50%, rgba(255, 255, 255, 0.1));
  box-shadow: 0 0 14px -4px color-mix(in srgb, var(--mood-color) 65%, transparent);
}

.beacon-hologram__close {
  width: 32px;
  height: 32px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-soft);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 160ms ease, border-color 160ms ease;
}
.beacon-hologram__close:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.22);
}

.beacon-hologram__content {
  margin: 0;
  color: var(--text);
  line-height: 1.7;
  font-size: 0.98rem;
  white-space: pre-wrap;
}

.beacon-hologram__meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px dashed rgba(255, 255, 255, 0.08);
}

.beacon-hologram__mood {
  border-color: color-mix(in srgb, var(--mood-color) 40%, rgba(255, 255, 255, 0.1));
  color: var(--mood-color);
}

.beacon-hologram__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--mood-color);
  box-shadow: 0 0 8px var(--mood-color);
  display: inline-block;
  margin-right: 6px;
}

.beacon-hologram__scanline {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    repeating-linear-gradient(
      to bottom,
      rgba(255, 255, 255, 0.025) 0px,
      rgba(255, 255, 255, 0.025) 1px,
      transparent 1px,
      transparent 3px
    );
  mix-blend-mode: overlay;
  animation: scanline-drift 4.2s linear infinite;
}

@keyframes scanline-drift {
  from { background-position: 0 0; }
  to   { background-position: 0 -200px; }
}

.hologram-enter-active,
.hologram-leave-active {
  transition: opacity 260ms ease, transform 260ms ease;
}
.hologram-enter-from,
.hologram-leave-to {
  opacity: 0;
  transform: translateY(28px) scaleY(0.92);
}

@media (prefers-reduced-motion: reduce) {
  .beacon-hologram__scanline {
    animation: none;
  }
}
</style>
