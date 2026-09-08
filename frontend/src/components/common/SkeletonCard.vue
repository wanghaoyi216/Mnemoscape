<script setup lang="ts">
/**
 * R31: SkeletonCard — three preset layouts matching the museum's hottest
 * loading states.
 *
 *   variant="memory"   → 3D memory card on the list / detail
 *   variant="chapter"  → chapter / timeline row
 *   variant="chat"     → chat bubble (user or AI)
 *
 * Skeletons are *content-shape mirrors*. They are not decorative — every
 * block corresponds to a real field that will be filled in once the data
 * arrives. That is the whole point: the eye already knows where to land.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SkeletonBox from './SkeletonBox.vue'

type Variant = 'memory' | 'chapter' | 'chat'
type ChatSpeaker = 'user' | 'ai'

interface Props {
  variant?: Variant
  /** Only meaningful for variant="chat". */
  speaker?: ChatSpeaker
  /** Show three rows (default) or one. */
  rows?: number
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'memory',
  speaker: 'user',
  rows: 3,
})

const { t } = useI18n()

const rowArray = computed(() => Array.from({ length: props.rows }, (_, i) => i))
</script>

<template>
  <div
    class="skeleton-card"
    :class="`skeleton-card--${variant}`"
    role="status"
    :aria-label="t('skeleton.box')"
  >
    <!-- ============ Memory card ============ -->
    <template v-if="variant === 'memory'">
      <div class="skeleton-card__cover">
        <SkeletonBox width="100%" height="100%" radius="14px" block />
      </div>
      <div class="skeleton-card__body">
        <SkeletonBox width="60%" height="1.15rem" />
        <SkeletonBox width="40%" height="0.85rem" />
        <SkeletonBox
          v-for="i in rowArray"
          :key="i"
          :width="i === rowArray.length - 1 ? '72%' : '100%'"
          height="0.8rem"
          block
        />
        <div class="skeleton-card__tags">
          <SkeletonBox v-for="i in 3" :key="i" width="46px" height="22px" radius="999px" />
        </div>
      </div>
    </template>

    <!-- ============ Chapter / timeline row ============ -->
    <template v-else-if="variant === 'chapter'">
      <div class="skeleton-card__chapter-rail" aria-hidden="true">
        <SkeletonBox width="6px" height="100%" radius="999px" block />
      </div>
      <div class="skeleton-card__chapter-body">
        <SkeletonBox width="35%" height="1.05rem" block />
        <SkeletonBox width="80%" height="0.8rem" block />
        <SkeletonBox width="55%" height="0.7rem" />
      </div>
      <div class="skeleton-card__chapter-time">
        <SkeletonBox width="64px" height="0.75rem" />
      </div>
    </template>

    <!-- ============ Chat message ============ -->
    <template v-else>
      <SkeletonBox width="34px" height="34px" radius="50%" />
      <div
        class="skeleton-card__chat-bubble"
        :class="`skeleton-card__chat-bubble--${speaker}`"
      >
        <SkeletonBox v-if="speaker === 'ai'" width="60px" height="0.7rem" />
        <SkeletonBox width="100%" height="0.85rem" block />
        <SkeletonBox width="84%" height="0.85rem" block />
        <SkeletonBox width="62%" height="0.85rem" />
        <span class="skeleton-card__chat-ts">{{ t('skeleton.chatMessage.timestamp') }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.skeleton-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  border-radius: var(--radius-md, 14px);
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.04);
}

/* ----- memory ----- */
.skeleton-card--memory {
  grid-template-columns: 168px 1fr;
  align-items: stretch;
}
.skeleton-card__cover {
  aspect-ratio: 4 / 3;
  border-radius: 14px;
  overflow: hidden;
}
.skeleton-card__body {
  display: grid;
  gap: 8px;
  align-content: start;
}
.skeleton-card__tags {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}
@media (max-width: 640px) {
  .skeleton-card--memory {
    grid-template-columns: 1fr;
  }
}

/* ----- chapter ----- */
.skeleton-card--chapter {
  grid-template-columns: 6px 1fr auto;
  align-items: stretch;
  padding: 14px 18px;
}
.skeleton-card__chapter-rail {
  display: flex;
  align-items: stretch;
  padding: 4px 0;
}
.skeleton-card__chapter-body {
  display: grid;
  gap: 6px;
}
.skeleton-card__chapter-time {
  display: flex;
  align-items: center;
}

/* ----- chat ----- */
.skeleton-card--chat {
  grid-template-columns: 34px 1fr;
  align-items: flex-start;
  padding: 10px 14px;
  background: transparent;
  border: none;
}
.skeleton-card__chat-bubble {
  display: grid;
  gap: 6px;
  padding: 10px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.05);
  max-width: 70%;
  position: relative;
}
.skeleton-card__chat-bubble--user {
  background: linear-gradient(135deg, rgba(54, 216, 180, 0.18), rgba(182, 240, 119, 0.12));
  border-color: rgba(54, 216, 180, 0.28);
}
.skeleton-card__chat-bubble--ai {
  background: rgba(255, 255, 255, 0.04);
}
.skeleton-card__chat-ts {
  font-size: 0.7rem;
  color: rgba(245, 248, 252, 0.4);
  font-family: var(--font-mono, monospace);
  margin-top: 2px;
}
</style>
