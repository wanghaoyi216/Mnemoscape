<script setup lang="ts">
/**
 * R31: SkeletonBox — a single shimmer placeholder.
 *
 * Why a separate component instead of inline <div class="skeleton"/>?
 *   - Centralizes the gradient animation. One keyframe, one duration.
 *   - Accepts `width/height/radius` props so callers do not have to remember
 *     the `--skeleton-*` CSS variable names.
 *   - `aria-hidden="true"` by default — screen readers skip it.
 *
 * Skeleton vs Spinner (interview note):
 *   - Spinner: "I don't know how long this will take" → 0–5s, indeterminate.
 *   - Skeleton: "The page structure is already decided; the data is in flight"
 *     → reduces cognitive load because the user sees the *shape* of what
 *     they're about to read, not a blank void. Nielsen Norman: skeletons
 *     feel ~30% faster than spinners for content-heavy UIs.
 */
import { computed } from 'vue'

interface Props {
  width?: string | number
  height?: string | number
  radius?: string | number
  /** Renders a circle (avatar) when true. */
  circle?: boolean
  /** Adds a slight gap below the box. */
  block?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '1em',
  radius: '8px',
  circle: false,
  block: false,
})

const style = computed(() => {
  const w = typeof props.width === 'number' ? `${props.width}px` : props.width
  const h = typeof props.height === 'number' ? `${props.height}px` : props.height
  const r = props.circle ? '50%' : typeof props.radius === 'number' ? `${props.radius}px` : props.radius
  return { width: w, height: h, borderRadius: r }
})
</script>

<template>
  <span
    class="skeleton-box"
    :class="{ 'skeleton-box--block': block }"
    :style="style"
    aria-hidden="true"
  />
</template>

<style scoped>
.skeleton-box {
  --skeleton-base: rgba(255, 255, 255, 0.06);
  --skeleton-hi:   rgba(255, 255, 255, 0.18);
  display: inline-block;
  vertical-align: middle;
  background: linear-gradient(
    90deg,
    var(--skeleton-base) 0%,
    var(--skeleton-hi) 50%,
    var(--skeleton-base) 100%
  );
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
  /* Box-sizing: prevents the box from "growing" inside flex parents. */
  box-sizing: border-box;
  /* Avoid layout shift between SSR/hydration — Skeleton height matches the
     real content's line-height. */
  line-height: 1;
}

.skeleton-box--block {
  display: block;
}

/* Reduced motion: kill the sweep, keep the static placeholder. */
@media (prefers-reduced-motion: reduce) {
  .skeleton-box {
    animation: none;
    background: var(--skeleton-base);
  }
}

@keyframes skeleton-shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
