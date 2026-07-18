<script setup lang="ts">
/**
 * 时光轴页（Sprint 3.3 v1）。
 *
 * 当前版本：把 memoryYear / memoryDate（"硬时间"）渲为一条垂直长河，
 * 左右两侧交替排卡，时间点旁边附一个"羽化色块"指示时间精度：
 *   - 有 memoryDate → 细色块（具体到一天）
 *   - 仅 memoryYear → 跨整年的宽色块（年份精度）
 *   - 两者都没 → 落到 createdAt 上，色块半透明，且打"系统时间"标记
 *
 * 下一版当 ai-service 推出"语义时间区间推断"接口（如"那年夏天" → 2018-06-01 ~ 2018-08-31）
 * 后，把推断结果替换硬时间，色块的宽度与 alpha 直接由置信度驱动。
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { images, videos } from '../assets/media-catalog'
import type { MemoryItem } from '../types'

const store = useMemoryStore()
const { t, locale } = useI18n()
const heroBg = images.timeGeometry.src
const heroVideo = videos.nebulaTide.src
const heroPoster = videos.nebulaTide.poster
const auroraVideo = videos.nasaAuroraSteve.src
const auroraPoster = videos.nasaAuroraSteve.poster

interface TimelineNode {
  memory: MemoryItem
  /** 用于排序的 anchor 时间（毫秒） */
  anchorMs: number
  /** 排序后的索引 */
  index: number
  /** 显示的人类可读时间 */
  displayDate: string
  /** 精度："day" | "year" | "system" — 决定羽化色块宽度与 alpha */
  precision: 'day' | 'year' | 'system'
  /** 卡片落在轴的左侧还是右侧 */
  side: 'left' | 'right'
}

/**
 * 抓取每段记忆的 anchor 时间：
 *   memoryDate > memoryYear (取 7 月 1 日近似年中点) > createdAt > now
 */
function anchorOf(m: MemoryItem): { ms: number; precision: TimelineNode['precision']; display: string } {
  if (m.memoryDate) {
    const d = new Date(m.memoryDate)
    if (!isNaN(d.getTime())) {
      return { ms: d.getTime(), precision: 'day', display: m.memoryDate }
    }
  }
  if (m.memoryYear) {
    const d = new Date(m.memoryYear, 6, 1)
    const isZh = locale.value === 'zh-CN'
    let displayStr = isZh ? `${m.memoryYear}年` : `${m.memoryYear}`
    
    if (m.memorySeason) {
      const seasonsZh: Record<string, string> = { SPRING: '春', SUMMER: '夏', AUTUMN: '秋', WINTER: '冬' }
      const seasonsEn: Record<string, string> = { SPRING: 'Spring', SUMMER: 'Summer', AUTUMN: 'Autumn', WINTER: 'Winter' }
      displayStr += isZh 
        ? ` ${seasonsZh[m.memorySeason.toUpperCase()] || m.memorySeason}`
        : ` ${seasonsEn[m.memorySeason.toUpperCase()] || m.memorySeason}`
    }
    
    if (m.memoryTimeOfDay) {
      const timesZh: Record<string, string> = { MORNING: '清晨', NOON: '正午', AFTERNOON: '下午', EVENING: '傍晚', NIGHT: '夜晚' }
      const timesEn: Record<string, string> = { MORNING: 'Morning', NOON: 'Noon', AFTERNOON: 'Afternoon', EVENING: 'Evening', NIGHT: 'Night' }
      displayStr += isZh
        ? ` ${timesZh[m.memoryTimeOfDay.toUpperCase()] || m.memoryTimeOfDay}`
        : ` ${timesEn[m.memoryTimeOfDay.toUpperCase()] || m.memoryTimeOfDay}`
    }
    
    return { ms: d.getTime(), precision: 'year', display: displayStr }
  }
  
  // 如果完全未录入事件时间，回退显示“未记录时间”而非混淆的系统时间
  const d = m.createdAt ? new Date(m.createdAt) : new Date()
  const fallbackText = locale.value === 'zh-CN' ? '未记录时间' : 'Unrecorded Time'
  return { ms: d.getTime(), precision: 'system', display: fallbackText }
}

const nodes = computed<TimelineNode[]>(() => {
  const raw = store.memories.map((m) => {
    const a = anchorOf(m)
    return { m, ms: a.ms, precision: a.precision, display: a.display }
  })
  // 按事件时间从旧到新：最早的记忆在顶端，最近的记忆沉到底部（时光「长河」
  // 自上游流向下游的直觉）。
  raw.sort((a, b) => a.ms - b.ms)
  return raw.map((row, i) => ({
    memory: row.m,
    anchorMs: row.ms,
    index: i,
    displayDate: row.precision === 'day'
      ? new Intl.DateTimeFormat(locale.value, {
          year: 'numeric', month: 'short', day: 'numeric',
        }).format(new Date(row.ms))
      : row.display,
    precision: row.precision,
    side: i % 2 === 0 ? 'left' : 'right',
  }))
})

const memoryCount = computed(() => store.memories.length)

/** 把精度映射到时间色块的宽度（rem） */
function bandWidth(p: TimelineNode['precision']): string {
  switch (p) {
    case 'day':    return '28px'
    case 'year':   return '120px'
    case 'system': return '64px'
  }
}
function bandAlpha(p: TimelineNode['precision']): number {
  switch (p) {
    case 'day':    return 0.85
    case 'year':   return 0.55
    case 'system': return 0.32
  }
}

onMounted(async () => {
  if (store.memories.length === 0) {
    try { await store.fetchList(0, 100) } catch { /* 静默 */ }
  }
})
</script>

<template>
  <div class="page-shell page-shell--wide">
    <section
      class="hero-card hero-card--split timeline-hero"
      :style="{ backgroundImage: `linear-gradient(120deg, rgba(8,10,14,0.82) 0%, rgba(8,10,14,0.42) 55%, rgba(8,10,14,0.92) 100%), url(${heroBg})` }"
    >
      <video class="timeline-hero__video" :src="heroVideo" :poster="heroPoster" autoplay muted loop playsinline preload="metadata" aria-hidden="true"></video>
      <span class="timeline-hero__scan" aria-hidden="true"></span>
      <div class="stack stack--lg">
        <p class="eyebrow">{{ t('memory.timeline.eyebrow') }}</p>
        <h1 class="display-title text-gradient">{{ t('memory.timeline.title') }}</h1>
        <p class="lead">{{ t('memory.timeline.lead') }}</p>
      </div>

      <div class="metric-grid">
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.timeline.metrics.total') }}</span>
          <strong class="metric-card__value">{{ memoryCount }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.timeline.metrics.precision') }}</span>
          <strong class="metric-card__value">{{ t('memory.timeline.metrics.precisionValue') }}</strong>
        </div>
        <div class="metric-card">
          <span class="metric-card__label">{{ t('memory.timeline.metrics.layout') }}</span>
          <strong class="metric-card__value">{{ t('memory.timeline.metrics.layoutValue') }}</strong>
        </div>
      </div>
    </section>

    <section class="section-card timeline-shell" style="margin-top: 24px;">
      <video class="timeline-shell__aurora" :src="auroraVideo" :poster="auroraPoster" autoplay muted loop playsinline preload="metadata" aria-hidden="true"></video>
      <header class="page-shell__header" style="margin-bottom: 12px;">
        <div>
          <h2 class="section-title">{{ t('memory.timeline.shell.title') }}</h2>
          <p class="subtitle">{{ t('memory.timeline.shell.hint') }}</p>
        </div>
        <div class="timeline-legend" aria-hidden="true">
          <span><i class="legend-band" :style="{ width: bandWidth('day'),    opacity: bandAlpha('day') }"></i>{{ t('memory.timeline.legend.day') }}</span>
          <span><i class="legend-band" :style="{ width: bandWidth('year'),   opacity: bandAlpha('year') }"></i>{{ t('memory.timeline.legend.year') }}</span>
          <span><i class="legend-band" :style="{ width: bandWidth('system'), opacity: bandAlpha('system') }"></i>{{ t('memory.timeline.legend.system') }}</span>
        </div>
      </header>

      <div v-if="store.loadingList" class="empty-state">
        <h3 class="empty-state__title">{{ t('memory.timeline.loading') }}</h3>
      </div>

      <div v-else-if="nodes.length === 0" class="empty-state">
        <h3 class="empty-state__title">{{ t('memory.list.empty') }}</h3>
        <RouterLink to="/memories/new" class="button button--primary">{{ t('memory.list.createButton') }}</RouterLink>
      </div>

      <ol v-else class="timeline" aria-label="Memory timeline">
        <li
          v-for="node in nodes"
          :key="node.memory.id"
          class="timeline-row"
          :class="`timeline-row--${node.side}`"
        >
          <!-- 时间标签 + 羽化色块 -->
          <div class="timeline-row__time">
            <span class="timeline-row__date">{{ node.displayDate }}</span>
            <span
              class="timeline-row__band"
              :class="`timeline-row__band--${node.precision}`"
              :style="{ width: bandWidth(node.precision), opacity: bandAlpha(node.precision) }"
              :title="t(`memory.timeline.legend.${node.precision}`)"
            ></span>
          </div>

          <!-- 中央轴节点 -->
          <span class="timeline-row__node" :class="{ 'timeline-row__node--locked': node.memory.isLocked }" aria-hidden="true">
            <span class="timeline-row__pulse"></span>
          </span>

          <!-- 卡片 -->
          <RouterLink 
            :to="`/memories/${node.memory.id}`" 
            class="timeline-row__card"
            :style="node.memory.sceneDataUrl && (node.memory.sceneDataUrl.startsWith('http') || node.memory.sceneDataUrl.startsWith('/')) ? { backgroundImage: `linear-gradient(180deg, rgba(14,17,22,0.2) 0%, rgba(14,17,22,0.88) 100%), url(${node.memory.sceneDataUrl})`, backgroundSize: 'cover', backgroundPosition: 'center', backgroundRepeat: 'no-repeat' } : {}"
          >
            <article>
              <header class="timeline-card__head">
                <span class="chip">{{ t(`memory.list.privacy.${node.memory.privacyLevel}`, node.memory.privacyLevel) }}</span>
                <span v-if="node.memory.isLocked" class="status-pill status-pill--warning">{{ t('memory.detail.locked') }}</span>
              </header>
              <h3 class="timeline-card__title">{{ node.memory.title }}</h3>
              <p class="timeline-card__desc">{{ node.memory.description }}</p>
              <footer class="timeline-card__foot">
                <span v-if="node.memory.memoryLocation" class="chip">{{ node.memory.memoryLocation }}</span>
                <span v-if="node.memory.memorySeason" class="chip">{{ t(`memory.builder.seasons.${node.memory.memorySeason}`, node.memory.memorySeason) }}</span>
                <span class="timeline-card__fade">
                  {{ t('memory.detail.drift') }} {{ Math.round((node.memory.fadeLevel || 0) * 100) }}%
                </span>
              </footer>
            </article>
          </RouterLink>
        </li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.timeline-hero {
  position: relative;
  min-height: 360px;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
  isolation: isolate;
}

.timeline-hero > :not(.timeline-hero__video):not(.timeline-hero__scan) {
  position: relative;
  z-index: 1;
}

.timeline-hero__video {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.3;
  filter: saturate(1.22) contrast(1.08) brightness(0.72);
  pointer-events: none;
}

.timeline-hero__scan {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  opacity: 0.36;
  background:
    repeating-linear-gradient(180deg, rgba(255,255,255,0.055) 0 1px, transparent 1px 7px),
    linear-gradient(105deg, rgba(54, 216, 180, 0.14), transparent 42%, rgba(242, 185, 92, 0.12));
  mix-blend-mode: screen;
}

.timeline-shell {
  position: relative;
  overflow: hidden;
  padding: 24px;
  isolation: isolate;
}

.timeline-shell > :not(.timeline-shell__aurora) {
  position: relative;
  z-index: 1;
}

.timeline-shell__aurora {
  position: absolute;
  inset: auto 0 0 auto;
  z-index: 0;
  width: min(620px, 70vw);
  height: min(340px, 38vw);
  object-fit: cover;
  opacity: 0.12;
  filter: blur(0.4px) saturate(1.35);
  mask-image: radial-gradient(circle at 72% 64%, #000 0%, rgba(0,0,0,0.72) 42%, transparent 76%);
  pointer-events: none;
}

.timeline-legend {
  display: flex;
  gap: 14px;
  align-items: center;
  font-size: 0.78rem;
  color: var(--text-muted);
  flex-wrap: wrap;
}
.timeline-legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend-band {
  display: inline-block;
  height: 6px;
  border-radius: 3px;
  background: linear-gradient(90deg, rgba(54, 216, 180, 0), #36d8b4 50%, rgba(54, 216, 180, 0));
  filter: blur(0.4px);
}

/* 主轴 */
.timeline {
  position: relative;
  margin: 28px 0 0;
  padding: 0;
  list-style: none;
}
.timeline::before {
  /* 中央时间长河 */
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 2px;
  margin-left: -1px;
  background: linear-gradient(180deg,
    rgba(242, 185, 92, 0) 0%,
    rgba(242, 185, 92, 0.35) 10%,
    rgba(54, 216, 180, 0.42) 50%,
    rgba(132, 110, 220, 0.35) 90%,
    rgba(132, 110, 220, 0) 100%);
  box-shadow: 0 0 16px -4px rgba(54, 216, 180, 0.35);
}

.timeline-row {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 48px 1fr;
  align-items: center;
  gap: 16px;
  margin: 28px 0;
}

.timeline-row__time {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-soft);
  font-family: var(--font-display, serif);
  font-size: 0.96rem;
  letter-spacing: 0.02em;
}

.timeline-row__band {
  height: 7px;
  border-radius: 4px;
  background: linear-gradient(90deg,
    rgba(242, 185, 92, 0) 0%,
    rgba(242, 185, 92, 0.85) 30%,
    rgba(54, 216, 180, 0.85) 70%,
    rgba(54, 216, 180, 0) 100%);
  filter: blur(0.6px);
  box-shadow: 0 0 14px -3px rgba(242, 185, 92, 0.6);
}
/* 「年份」精度的色块更长更柔；「系统时间」更窄更暗 */
.timeline-row__band--year   { background: linear-gradient(90deg, rgba(132,110,220,0), rgba(132,110,220,0.7) 40%, rgba(132,110,220,0.7) 60%, rgba(132,110,220,0)); }
.timeline-row__band--system { background: linear-gradient(90deg, rgba(255,255,255,0), rgba(255,255,255,0.32) 50%, rgba(255,255,255,0)); }

/* 中央节点 */
.timeline-row__node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--gold, #f2b95c);
  box-shadow: 0 0 14px rgba(242, 185, 92, 0.65);
  justify-self: center;
  position: relative;
  z-index: 1;
}
.timeline-row__node--locked {
  background: #ef6f7a;
  box-shadow: 0 0 14px rgba(239, 111, 122, 0.65);
}
.timeline-row__pulse {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  color: var(--gold, #f2b95c);
  opacity: 0.7;
  animation: timeline-pulse 2.6s ease-out infinite;
}
@keyframes timeline-pulse {
  0%   { transform: scale(0.6); opacity: 0.6; }
  100% { transform: scale(1.8); opacity: 0; }
}

/* 卡片 */
.timeline-row__card {
  display: block;
  padding: 18px 20px;
  border-radius: var(--radius-md);
  background: rgba(14, 17, 22, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.08);
  transition: border-color 200ms ease, transform 200ms ease, background-color 200ms ease;
}
.timeline-row__card:hover {
  border-color: var(--border-accent);
  transform: translateY(-3px);
  background: rgba(14, 17, 22, 0.7);
}

.timeline-card__head {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 8px;
}
.timeline-card__title {
  margin: 0 0 8px;
  font-family: var(--font-display, serif);
  font-size: 1.16rem;
  letter-spacing: -0.01em;
}
.timeline-card__desc {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.92rem;
  line-height: 1.65;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.timeline-card__foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
}
.timeline-card__fade {
  margin-left: auto;
  font-size: 0.72rem;
  color: var(--text-muted);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

/* 左右交替：left = 时间在右侧靠近轴、卡片在左；right 反过来 */
.timeline-row--left .timeline-row__time {
  order: 2;
  justify-content: flex-start;
}
.timeline-row--left .timeline-row__card {
  order: 1;
  grid-column: 1;
  text-align: right;
}
.timeline-row--left .timeline-card__head,
.timeline-row--left .timeline-card__foot {
  justify-content: flex-end;
}

.timeline-row--right .timeline-row__time {
  order: 1;
  justify-content: flex-end;
  text-align: right;
}
.timeline-row--right .timeline-row__card {
  order: 2;
  grid-column: 3;
}

/* 窄屏：折叠为单列 */
@media (max-width: 768px) {
  .timeline::before { left: 16px; }
  .timeline-row {
    grid-template-columns: 16px 1fr;
    gap: 12px;
    margin: 22px 0;
  }
  .timeline-row__node { justify-self: start; }
  .timeline-row__time {
    order: 1;
    grid-column: 2;
    justify-content: flex-start !important;
    margin-bottom: 6px;
  }
  .timeline-row__card {
    order: 2;
    grid-column: 2 !important;
    text-align: left !important;
  }
  .timeline-card__head,
  .timeline-card__foot {
    justify-content: flex-start !important;
  }
}
</style>
