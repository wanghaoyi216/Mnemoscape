<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useAvatarThree } from '../../composables/useAvatarThree'
import { parseAvatarProfile, type AvatarProfile } from '../../api/avatar'

const props = defineProps<{
  profile: AvatarProfile
  /** 容器高度（px），默认 360 */
  height?: number
}>()

const containerRef = ref<HTMLElement | null>(null)
const { init, loadAvatar } = useAvatarThree(containerRef)

onMounted(() => {
  init()
  if (props.profile) {
    const { traits, emotionTone } = parseAvatarProfile(props.profile)
    loadAvatar(traits, emotionTone)
  }
})

watch(
  () => props.profile,
  (profile) => {
    if (profile) {
      const { traits, emotionTone } = parseAvatarProfile(profile)
      loadAvatar(traits, emotionTone)
    }
  },
)
</script>

<template>
  <div class="avatar3d-wrapper">
    <div
      ref="containerRef"
      class="avatar3d-canvas"
      :style="{ height: `${height ?? 360}px` }"
    ></div>

    <!-- 角色信息覆盖层 -->
    <div class="avatar3d-overlay">
      <div class="avatar3d-title-badge">
        <span class="avatar3d-title">{{ profile.avatarTitle }}</span>
      </div>
    </div>

    <!-- 底部信息条 -->
    <div class="avatar3d-footer">
      <div class="avatar3d-tags">
        <span
          v-for="tag in (JSON.parse(profile.personalityTags || '[]') as string[])"
          :key="tag"
          class="avatar3d-tag"
        >{{ tag }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.avatar3d-wrapper {
  position: relative;
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid rgba(108, 99, 255, 0.25);
  background: radial-gradient(ellipse at center, rgba(10, 8, 30, 0.95) 0%, rgba(5, 7, 20, 1) 100%);
  box-shadow: 0 0 40px rgba(108, 99, 255, 0.15), inset 0 0 60px rgba(0, 0, 0, 0.5);
}

.avatar3d-canvas {
  width: 100%;
  display: block;
}

.avatar3d-overlay {
  position: absolute;
  top: 16px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  pointer-events: none;
}

.avatar3d-title-badge {
  background: rgba(10, 8, 30, 0.72);
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 999px;
  padding: 5px 16px;
  backdrop-filter: blur(8px);
}

.avatar3d-title {
  font-size: 0.82rem;
  color: var(--primary, #36d8b4);
  font-family: var(--font-display, sans-serif);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.avatar3d-footer {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 16px;
  background: linear-gradient(to top, rgba(5, 7, 20, 0.9) 0%, transparent 100%);
  pointer-events: none;
}

.avatar3d-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
}

.avatar3d-tag {
  font-size: 0.7rem;
  color: rgba(255, 255, 255, 0.65);
  background: rgba(108, 99, 255, 0.18);
  border: 1px solid rgba(108, 99, 255, 0.25);
  border-radius: 999px;
  padding: 2px 10px;
  letter-spacing: 0.02em;
}
</style>
