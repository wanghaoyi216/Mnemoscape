<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useResonanceStore } from '../stores/resonance'
import { useAuthStore } from '../stores/auth'
import { useWebSocket } from '../composables/useWebSocket'
import { useThreeScene } from '../composables/useThreeScene'
import { useBeaconLayer } from '../composables/useBeaconLayer'
import NoteComposer from '../components/resonance/NoteComposer.vue'
import NoteBubble from '../components/resonance/NoteBubble.vue'
import GhostIndicator from '../components/resonance/GhostIndicator.vue'
import BeaconCard from '../components/resonance/BeaconCard.vue'
import { images } from '../assets/media-catalog'
import type { MemoryNote, WebSocketMessage } from '../types'

const { t } = useI18n()
const heroBg = images.resonanceTwins.src

const route = useRoute()
const resonanceStore = useResonanceStore()
const auth = useAuthStore()
const containerRef = ref<HTMLElement | null>(null)
const { init, scene, camera } = useThreeScene(containerRef)
const { connected, connect, send, on } = useWebSocket()
const ghosts = ref<Map<string, { userId: string; position: number[]; lookingAt: { x: number; y: number; z: number } }>>(new Map())
const showComposer = ref(false)
// 信标种植位置 — 由地面 Raycaster 拾取得来，默认 (0,1.5,0) 仅作回退。
const composerPosition = ref({ x: 0, y: 1.5, z: 0 })
const composerScreen = ref<{ x: number; y: number } | null>(null)
const activeBeacon = ref<MemoryNote | null>(null)
const proximityHint = ref(false)
let joinTimer = 0
let proximityTimer = 0

const spaceId = route.params.id as string
const noteCount = computed(() => resonanceStore.notes.length)

// 信标图层 — 挂在 useThreeScene 的同一个 scene 上，dispose 自动跟随
const beaconLayer = useBeaconLayer({ scene, camera, containerRef, proximityRadius: 2.6 })
beaconLayer.onNearbyTrigger((note) => {
  activeBeacon.value = note
})

onMounted(async () => {
  await resonanceStore.fetchSpace(spaceId)
  await resonanceStore.fetchNotes(spaceId)

  init()
  connect(auth.token)

  on('GHOST_JOIN', (msg: WebSocketMessage) => {
    ghosts.value.set(msg.userId!, {
      userId: msg.userId!,
      position: msg.position || [0, 1.8, 0],
      lookingAt: msg.lookingAt || { x: 0, y: 0, z: -1 },
    })
  })

  on('GHOST_MOVE', (msg: WebSocketMessage) => {
    const ghost = ghosts.value.get(msg.userId!)
    if (ghost) {
      ghost.position = msg.position || ghost.position
      ghost.lookingAt = msg.lookingAt || ghost.lookingAt
    }
  })

  on('GHOST_LEFT', (msg: WebSocketMessage) => {
    ghosts.value.delete(msg.userId!)
  })

  on('NOTE_PLACED', () => {
    resonanceStore.fetchNotes(spaceId)
  })

  // 首次拿到 notes 后立刻同步信标层；之后通过 watch 持续 diff
  beaconLayer.syncBeacons(resonanceStore.notes)

  // 每 800ms 检测一次"靠近信标"，让 UI 提示"按 E 查看留言"
  proximityTimer = window.setInterval(() => {
    proximityHint.value = !!beaconLayer.findNearestBeacon()
  }, 800)

  joinTimer = window.setTimeout(() => {
    send({
      type: 'JOIN',
      resonanceId: spaceId,
      userId: auth.user?.username || 'anonymous',
      position: [0, 1.8, 0],
    })
  }, 500)
})

// store 里的 notes 一旦更新（首次拉取、其他用户广播过来），都增量同步到 3D 图层
watch(
  () => resonanceStore.notes,
  (next) => beaconLayer.syncBeacons(next),
  { deep: false },
)

onUnmounted(() => {
  if (joinTimer) {
    window.clearTimeout(joinTimer)
  }
  if (proximityTimer) {
    window.clearInterval(proximityTimer)
  }
})

function handlePlaceNote(content: string, mood: string) {
  send({
    type: 'PLACE_NOTE',
    resonanceId: spaceId,
    userId: auth.user?.username || 'anonymous',
    content,
    mood,
    position: [composerPosition.value.x, composerPosition.value.y, composerPosition.value.z],
  })
  showComposer.value = false
}

/**
 * 点击画布 = "在地面种一颗信标种子"。
 *   - 用 useBeaconLayer 的 raycaster 求出地面上的世界坐标
 *   - 弹出 composer 在屏幕坐标处
 *   - 提交后 handlePlaceNote 通过 WS 发出，NOTE_PLACED 回来再触发 syncBeacons
 */
function handleCanvasClick(ev: MouseEvent) {
  // 已展开全息卡 → 优先关掉，避免误触种植
  if (activeBeacon.value) {
    activeBeacon.value = null
    return
  }
  const world = beaconLayer.pickGroundPoint(ev)
  if (!world) return
  composerPosition.value = { x: world.x, y: 1.5, z: world.z }
  const rect = containerRef.value?.getBoundingClientRect()
  composerScreen.value = rect ? { x: ev.clientX - rect.left, y: ev.clientY - rect.top } : null
  showComposer.value = true
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      v-if="resonanceStore.currentSpace"
      class="hero-card hero-card--split resonance-space-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.82) 0%, rgba(8,10,14,0.42) 55%, rgba(8,10,14,0.88) 100%), url(${heroBg})` }"
    >
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('resonance.space.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ t('resonance.space.title_long') }}</h1>
        <p class="lead">{{ t('resonance.space.lead') }}</p>
      </div>

      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.space.metrics.status') }}</span>
          <strong class="metric-card__value">
            {{ connected ? t('resonance.space.metrics.connected') : t('resonance.space.metrics.reconnecting') }}
          </strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.space.metrics.notes') }}</span>
          <strong class="metric-card__value">{{ noteCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('resonance.space.metrics.similarity') }}</span>
          <strong class="metric-card__value">{{ Math.round((resonanceStore.currentSpace.similarityScore || 0) * 100) }}%</strong>
        </div>
      </div>
    </section>

    <section class="section-card stack" style="margin-top: 24px; position: relative;">
      <div class="space-hud">
        <div>
          <h2 class="section-title">{{ resonanceStore.currentSpace?.id }}</h2>
          <p class="subtitle">
            {{ t('resonance.space.hud.emotion') }}
            {{ Math.round((resonanceStore.currentSpace?.emotionSimilarity || 0) * 100) }}% ·
            {{ t('resonance.space.hud.scene') }}
            {{ Math.round((resonanceStore.currentSpace?.sceneSimilarity || 0) * 100) }}%
          </p>
        </div>
        <div class="space-hud__hint">
          <p class="help-text">{{ t('resonance.space.hud.plantTip') }}</p>
        </div>
      </div>

      <div ref="containerRef" class="canvas-container" @click="handleCanvasClick"></div>

      <!-- 屏幕上落点指示 — 点击的瞬间在该处显示一个小十字，给用户即时反馈 -->
      <div
        v-if="composerScreen && showComposer"
        class="beacon-drop-marker"
        :style="{ left: `${composerScreen.x}px`, top: `${composerScreen.y}px` }"
        aria-hidden="true"
      ></div>

      <GhostIndicator
        v-for="[id, ghost] in ghosts"
        :key="id"
        :userId="ghost.userId"
        :position="ghost.position"
      />

      <NoteBubble
        v-for="note in resonanceStore.notes"
        :key="note.id"
        :note="note"
      />

      <div class="space-status status-pill" :class="connected ? 'status-pill--success' : 'status-pill--warning'">
        {{ connected ? t('resonance.space.metrics.connected') : t('resonance.space.metrics.reconnecting') }}
      </div>

      <div class="note-count chip">
        {{ t('resonance.space.hud.notesInSpace', { count: noteCount }) }}
      </div>

      <!-- 靠近信标时的提示气泡，告诉用户按 E 键查看 -->
      <transition name="proximity">
        <div
          v-if="proximityHint && !activeBeacon && !showComposer"
          class="beacon-proximity"
          role="status"
        >
          <kbd class="beacon-proximity__key">E</kbd>
          {{ t('resonance.space.hud.pressEHint') }}
        </div>
      </transition>

      <BeaconCard
        v-if="activeBeacon"
        :note="activeBeacon"
        @close="activeBeacon = null"
      />

      <NoteComposer
        v-if="showComposer"
        @place="handlePlaceNote"
        @close="showComposer = false"
      />
    </section>
  </div>
</template>

<style scoped>
.resonance-space-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.space-hud__hint {
  text-align: right;
  max-width: 260px;
}
.space-hud__hint p {
  margin: 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}

/* 点击落点的十字标记 — 在用户填 composer 时帮助记忆"我刚才点在哪里" */
.beacon-drop-marker {
  position: absolute;
  width: 22px;
  height: 22px;
  margin-left: -11px;
  margin-top: -11px;
  pointer-events: none;
  z-index: 12;
}
.beacon-drop-marker::before,
.beacon-drop-marker::after {
  content: '';
  position: absolute;
  background: var(--gold, #f2b95c);
  box-shadow: 0 0 8px var(--gold, #f2b95c);
}
.beacon-drop-marker::before {
  left: 50%; top: 0; bottom: 0;
  width: 1.5px;
  margin-left: -0.75px;
}
.beacon-drop-marker::after {
  top: 50%; left: 0; right: 0;
  height: 1.5px;
  margin-top: -0.75px;
}

/* 「按 E 键查看」提示 */
.beacon-proximity {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px 8px 10px;
  border-radius: 999px;
  background: rgba(10, 14, 22, 0.7);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: var(--text);
  font-size: 0.86rem;
  z-index: 18;
  pointer-events: none;
  box-shadow: 0 16px 40px -20px rgba(0, 0, 0, 0.6);
}
.beacon-proximity__key {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(255,255,255,0.14), rgba(255,255,255,0.06));
  border: 1px solid rgba(255, 255, 255, 0.18);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  font-size: 0.78rem;
  color: var(--gold, #f2b95c);
  letter-spacing: 0.02em;
}

.proximity-enter-active,
.proximity-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}
.proximity-enter-from,
.proximity-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

.space-hud {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.canvas-container {
  position: relative;
  min-height: min(72vh, 760px);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background:
    linear-gradient(135deg, rgba(32, 199, 164, 0.1), rgba(240, 179, 91, 0.06)),
    rgba(8, 9, 8, 0.34);
  border: 1px solid var(--border);
}

.space-status {
  position: absolute;
  top: 24px;
  right: 24px;
  z-index: 8;
}

.note-count {
  position: absolute;
  top: 24px;
  left: 24px;
  z-index: 8;
}

@media (max-width: 768px) {
  .space-hud {
    flex-direction: column;
  }
}
</style>
