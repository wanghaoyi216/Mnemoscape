<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useMemoryStore } from '../stores/memory'
import { images, videos } from '../assets/media-catalog'
import { useDynamicMedia } from '../composables/useDynamicMedia'
import LocationPicker from '../components/common/LocationPicker.vue'

const heroBg = images.memoryFoundry.src
const reconstructingVideo = videos.ebbingHourglass.src

// v1.1：把 MinIO / 本地静态资源池的图也并入封面候选，让用户存进 MinIO 的素材
// 自动出现在"选择封面"里，不再闲置。
const dynamicMedia = useDynamicMedia()
const allCoverOptions = computed(() => {
  // 内置 media-catalog（首选 — 永远存在）
  const builtIn = Object.entries(images).map(([k, v]) => ({
    key: `local:${k}`,
    src: v.src,
    thumb: v.thumb || v.src,
    origin: v.origin,
    badge: '内置',
  }))
  // MinIO + 本地 photo 池
  const dyn = [
    ...dynamicMedia.state.photos,
    ...dynamicMedia.state.gifs,
  ].slice(0, 40).map((a) => ({
    key: `dyn:${a.name}`,
    src: a.url,
    thumb: a.url,
    origin: a.name.replace(/\.[^.]+$/, ''),
    badge: a.url.includes('X-Amz-Signature') ? 'MinIO' : '本地',
  }))
  return [...builtIn, ...dyn]
})

const router = useRouter()
const store = useMemoryStore()
const { t, locale } = useI18n()
const title = ref('')
const description = ref('')
const selectedCoverUrl = ref('')
const memoryYear = ref<number | undefined>()
const memoryDate = ref('')
const memorySeason = ref('')
const memoryTimeOfDay = ref('')
const memoryLocation = ref('')
const privacyLevel = ref('PRIVATE')
const error = ref('')
const loading = ref(false)

// 地点输入：交给 LocationPicker（国家/省/市 + 浏览器定位 + Nominatim 反查）。
// 这里不再保留写死的 chip 列表 — 由 LocationPicker.vue 内部管理。

const descriptionLength = computed(() => description.value.trim().length)
const descriptionStatus = computed(() => {
  const len = descriptionLength.value
  if (len < 5) return 'too-short'
  if (len < 150) return 'short'
  if (len <= 300) return 'recommended'
  if (len <= 500) return 'long'
  return 'exceeded'
})
const descriptionProgress = computed(() => Math.min(100, (descriptionLength.value / 500) * 100))

const sensoryCoverage = computed(() => {
  const text = description.value.toLowerCase()
  return [
    { key: 'visual', label: t('memory.builder.senses.visual'), active: /light|color|shape|room|street|sky|window|shadow|看|光|颜色|房间|街|天空|影/.test(text) },
    { key: 'audio', label: t('memory.builder.senses.audio'), active: /sound|voice|music|rain|wind|noise|听|声音|音乐|雨|风|喊/.test(text) },
    { key: 'scent', label: t('memory.builder.senses.scent'), active: /smell|scent|coffee|flower|soil|香|味道|咖啡|花|泥土|气味/.test(text) },
    { key: 'touch', label: t('memory.builder.senses.touch'), active: /warm|cold|rough|soft|hand|skin|温|冷|触|手|柔软|粗糙/.test(text) },
    { key: 'emotion', label: t('memory.builder.senses.emotion'), active: /happy|sad|afraid|miss|nostalgia|joy|fear|想念|怀念|开心|难过|害怕|安心/.test(text) },
  ]
})
const coverageScore = computed(() => sensoryCoverage.value.filter((item) => item.active).length)
const canSubmit = computed(() => (
  title.value.trim().length > 0
  && descriptionLength.value >= 5
  && !loading.value
))

async function handleSubmit() {
  error.value = ''
  if (descriptionLength.value < 5) {
    error.value = t('memory.builder.error.tooShort')
    return
  }

  loading.value = true
  try {
    const memory = await store.create({
      title: title.value,
      description: description.value,
      memoryYear: memoryYear.value ? Number(memoryYear.value) : undefined,
      memoryDate: memoryDate.value || undefined,
      memorySeason: memorySeason.value || undefined,
      memoryTimeOfDay: memoryTimeOfDay.value || undefined,
      memoryLocation: memoryLocation.value || undefined,
      privacyLevel: privacyLevel.value,
      sceneDataUrl: selectedCoverUrl.value || undefined,
    })
    router.push(`/memories/${memory.id}`)
  } catch (e: any) {
    error.value = e.response?.data?.message || t('memory.builder.error.fallback')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell page-shell--wide">
    <!-- 提交期间的沉浸式遮罩 — 沙漏倒流视频替代干瘪的 Loading 圈 -->
    <transition name="reconstruct">
      <div v-if="loading" class="reconstruct-veil" role="status" aria-live="polite">
        <video class="reconstruct-veil__video" autoplay muted loop playsinline preload="auto">
          <source :src="reconstructingVideo" type="video/mp4" />
        </video>
        <div class="reconstruct-veil__mask"></div>
        <div class="reconstruct-veil__copy">
          <p class="eyebrow">{{ t('memory.builder.reconstructing.eyebrow') }}</p>
          <h2 class="display-title text-gradient">{{ t('memory.builder.reconstructing.title') }}</h2>
          <p class="lead">{{ t('memory.builder.reconstructing.lead') }}</p>
          <div class="reconstruct-veil__dots" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
        </div>
      </div>
    </transition>

    <div class="builder-grid">
      <section
        class="hero-card builder-hero"
        :style="{ backgroundImage: `linear-gradient(135deg, rgba(8,10,14,0.86) 0%, rgba(8,10,14,0.55) 50%, rgba(8,10,14,0.92) 100%), url(${heroBg})` }"
      >
        <p class="eyebrow reveal">{{ t('memory.builder.eyebrow') }}</p>
        <h1 class="display-title text-gradient reveal reveal-delay-1" v-html="t('memory.builder.title')"></h1>
        <p class="lead reveal reveal-delay-2">{{ t('memory.builder.lead') }}</p>

        <div class="builder-hero__metric-grid reveal reveal-delay-3">
          <div class="metric-card">
            <span class="metric-card__label">{{ t('memory.builder.metrics.min') }}</span>
            <strong class="metric-card__value">{{ t('memory.builder.metrics.minValue') }}</strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('memory.builder.metrics.coverage') }}</span>
            <strong class="metric-card__value">{{ coverageScore }} / 5</strong>
          </div>
          <div class="metric-card">
            <span class="metric-card__label">{{ t('memory.builder.metrics.engine') }}</span>
            <strong class="metric-card__value">{{ t('memory.builder.metrics.engineValue') }}</strong>
          </div>
        </div>

        <div class="sensory-strip reveal reveal-delay-4" :aria-label="t('memory.builder.metrics.coverage')">
          <span
            v-for="item in sensoryCoverage"
            :key="item.label"
            class="sensory-pill"
            :class="{ 'sensory-pill--active': item.active }"
          >
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.5" />
            </svg>
            {{ item.label }}
          </span>
        </div>

        <div class="builder-hero__hint reveal reveal-delay-4">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
            <path d="M12 8v4m0 4h.01" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          <span>{{ t('memory.builder.hint') }}</span>
        </div>
      </section>

      <form class="builder-card stack" @submit.prevent="handleSubmit">
        <div class="stack">
          <div>
            <p class="eyebrow">{{ t('memory.builder.card.eyebrow') }}</p>
            <h2 class="section-title">{{ t('memory.builder.card.title') }}</h2>
            <p class="subtitle">{{ t('memory.builder.card.subtitle') }}</p>
          </div>

          <transition name="alert">
            <div v-if="error" class="status-pill status-pill--danger" role="alert">{{ error }}</div>
          </transition>
        </div>

        <div class="form-grid">
          <label class="field" style="grid-column: 1 / -1;">
            <span class="field__label">{{ t('memory.builder.fields.title') }}</span>
            <input v-model="title" class="input" :placeholder="t('memory.builder.fields.titlePlaceholder')" required maxlength="200" />
          </label>

          <label class="field" style="grid-column: 1 / -1;">
            <span class="field__label">{{ t('memory.builder.fields.description') }}</span>
            <textarea
              v-model="description"
              class="textarea"
              :placeholder="t('memory.builder.fields.descriptionPlaceholder')"
              rows="7"
              required
            ></textarea>
            <div class="builder-description-meter">
              <div class="fade-bar" style="flex: 1;">
                <div
                  class="fade-bar__fill"
                  :class="`fade-bar__fill--${descriptionStatus}`"
                  :style="{ width: `${descriptionProgress}%` }"
                ></div>
              </div>
              <span :class="['builder-description-counter', `builder-description-counter--${descriptionStatus}`]">
                {{ descriptionLength }} / 500
              </span>
            </div>
            <div class="builder-description-tip">
              <span v-if="descriptionStatus === 'too-short'" class="status-pill status-pill--danger" style="padding: 2px 8px; font-size: 0.72rem;">
                {{ t('memory.builder.error.tooShort') }}
              </span>
              <span v-else-if="descriptionStatus === 'short'" class="status-pill status-pill--warning" style="padding: 2px 8px; font-size: 0.72rem;">
                {{ t('memory.builder.hints.writeMore') }}
              </span>
              <span v-else-if="descriptionStatus === 'recommended'" class="status-pill status-pill--success" style="padding: 2px 8px; font-size: 0.72rem;">
                {{ t('memory.builder.hints.recommended') }}
              </span>
              <span v-else-if="descriptionStatus === 'long'" class="status-pill status-pill--accent" style="padding: 2px 8px; font-size: 0.72rem;">
                {{ t('memory.builder.hints.sufficient') }}
              </span>
              <span v-else-if="descriptionStatus === 'exceeded'" class="status-pill status-pill--warning" style="padding: 2px 8px; font-size: 0.72rem;">
                {{ t('memory.builder.hints.exceeded') }}
              </span>
            </div>
          </label>

          <!-- 记忆视觉共鸣封面选择器 -->
          <div class="field" style="grid-column: 1 / -1; margin-bottom: 8px;">
            <span class="field__label">{{ locale === 'zh-CN' ? '选择时空记忆封面艺术' : 'Select Memory Space Art Cover' }}</span>
            <p class="subtitle" style="font-size:0.78rem; line-height:1.4; margin-top:2px; margin-bottom:12px;">
              {{ locale === 'zh-CN' ? '为这片记忆赋予一张时空视觉艺术封面，它将呈现在记忆列表、记忆详情与时光长河中。' : 'Give this memory a cinematic space artwork cover, shown on your lists and timeline.' }}
            </p>
            <div class="cover-art-gallery">
              <button
                v-for="opt in allCoverOptions"
                :key="opt.key"
                type="button"
                :class="['cover-art-item', selectedCoverUrl === opt.src ? 'active' : '']"
                @click="selectedCoverUrl = opt.src"
                :title="opt.origin"
              >
                <img :src="opt.thumb" :alt="opt.origin" />
                <span class="cover-art-item__label">{{ opt.origin }}</span>
                <span class="cover-art-item__badge">{{ opt.badge }}</span>
                <span class="cover-art-item__active-badge" v-if="selectedCoverUrl === opt.src">✓</span>
              </button>
            </div>
          </div>

          <label class="field">
            <span class="field__label">{{ t('memory.builder.fields.year') }}</span>
            <input v-model="memoryYear" class="input" type="number" placeholder="2010" min="1900" max="2100" />
          </label>

          <label class="field">
            <span class="field__label">{{ t('memory.builder.fields.date') }}</span>
            <input v-model="memoryDate" class="input" type="date" />
          </label>

          <label class="field">
            <span class="field__label">{{ t('memory.builder.fields.season') }}</span>
            <select v-model="memorySeason" class="select">
              <option value="">{{ t('memory.builder.seasons.placeholder') }}</option>
              <option value="SPRING">{{ t('memory.builder.seasons.SPRING') }}</option>
              <option value="SUMMER">{{ t('memory.builder.seasons.SUMMER') }}</option>
              <option value="AUTUMN">{{ t('memory.builder.seasons.AUTUMN') }}</option>
              <option value="WINTER">{{ t('memory.builder.seasons.WINTER') }}</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">{{ t('memory.builder.fields.timeOfDay') }}</span>
            <select v-model="memoryTimeOfDay" class="select">
              <option value="">{{ t('memory.builder.times.placeholder') }}</option>
              <option value="MORNING">{{ t('memory.builder.times.MORNING') }}</option>
              <option value="NOON">{{ t('memory.builder.times.NOON') }}</option>
              <option value="AFTERNOON">{{ t('memory.builder.times.AFTERNOON') }}</option>
              <option value="EVENING">{{ t('memory.builder.times.EVENING') }}</option>
              <option value="NIGHT">{{ t('memory.builder.times.NIGHT') }}</option>
            </select>
          </label>

          <label class="field" style="grid-column: 1 / -1;">
            <span class="field__label">{{ t('memory.builder.fields.location') }}</span>
            <LocationPicker v-model="memoryLocation" />
            <p class="location-helper">
              {{ locale === 'zh-CN'
                ? '💡 选择国家/省/市后可继续填写街道；点击「📍 使用当前位置」让浏览器自动定位（需要授予权限）。系统会按这条信息在时空地图上标点。'
                : '💡 Pick country/state/city, then optionally add a street. Tap "📍 Use current location" to fill via browser GPS (needs permission). The atlas will pin this memory using the saved string.' }}
            </p>
          </label>

          <label class="field">
            <span class="field__label">{{ t('memory.builder.fields.privacy') }}</span>
            <select v-model="privacyLevel" class="select">
              <option value="PRIVATE">{{ t('memory.builder.privacy.PRIVATE') }}</option>
              <option value="FRIENDS">{{ t('memory.builder.privacy.FRIENDS') }}</option>
              <option value="PUBLIC">{{ t('memory.builder.privacy.PUBLIC') }}</option>
            </select>
          </label>
        </div>

        <div class="builder-actions">
          <button type="submit" class="button button--primary" :disabled="!canSubmit">
            <span v-if="loading" class="auth-spinner" aria-hidden="true"></span>
            <span>{{ loading ? t('memory.builder.submitting') : t('memory.builder.submit') }}</span>
            <svg v-if="!loading" viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
              <path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <span class="help-text">
            {{ coverageScore >= 3
              ? t('memory.builder.ready')
              : t('memory.builder.needMore', { count: 3 - coverageScore }) }}
          </span>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.builder-hero {
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

/* 沉浸式重建遮罩 */
.reconstruct-veil {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  overflow: hidden;
  isolation: isolate;
}
.reconstruct-veil__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: -2;
  opacity: 0.78;
}
.reconstruct-veil__mask {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    radial-gradient(circle at center, rgba(8,10,14,0.25) 0%, rgba(8,10,14,0.72) 100%),
    linear-gradient(180deg, rgba(8,10,14,0.4), rgba(8,10,14,0.86));
}
.reconstruct-veil__copy {
  text-align: center;
  max-width: 580px;
  padding: 32px;
  display: grid;
  gap: 18px;
  justify-items: center;
}
.reconstruct-veil__dots {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
.reconstruct-veil__dots span {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--gold, #f2b95c);
  box-shadow: 0 0 10px rgba(242, 185, 92, 0.7);
  animation: reconstruct-dot 1.4s ease-in-out infinite;
}
.reconstruct-veil__dots span:nth-child(2) { animation-delay: 0.18s; }
.reconstruct-veil__dots span:nth-child(3) { animation-delay: 0.36s; }
@keyframes reconstruct-dot {
  0%, 80%, 100% { transform: scale(0.55); opacity: 0.35; }
  40%           { transform: scale(1.1);  opacity: 1; }
}
.reconstruct-enter-active,
.reconstruct-leave-active {
  transition: opacity 360ms ease;
}
.reconstruct-enter-from,
.reconstruct-leave-to {
  opacity: 0;
}

.builder-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 32px;
  margin-top: 24px;
}

.builder-hero {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.builder-hero__metric-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

/* ============== 记忆封面画库 ============== */
.cover-art-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  gap: 12px;
  max-height: 250px;
  overflow-y: auto;
  padding: 8px 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: rgba(8, 10, 14, 0.4);
}

.cover-art-gallery::-webkit-scrollbar {
  width: 5px;
}
.cover-art-gallery::-webkit-scrollbar-track {
  background: rgba(255,255,255,0.01);
}
.cover-art-gallery::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.1);
  border-radius: 99px;
}

.cover-art-item {
  position: relative;
  background: rgba(14, 17, 22, 0.6);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  padding: 0;
  transition: all 0.24s cubic-bezier(0.165, 0.84, 0.44, 1);
  text-align: center;
}

.cover-art-item:hover {
  transform: translateY(-2px);
  border-color: var(--border-accent);
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.15);
}

.cover-art-item.active {
  border-color: var(--primary);
  box-shadow: 0 0 14px rgba(54, 216, 180, 0.35), inset 0 0 0 1px var(--primary);
}

.cover-art-item img {
  width: 100%;
  height: 65px;
  object-fit: cover;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.cover-art-item__label {
  font-size: 0.68rem;
  color: var(--text-muted);
  padding: 6px 4px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.2s ease;
}

.cover-art-item.active .cover-art-item__label {
  color: var(--primary);
  font-weight: 600;
}

.cover-art-item__active-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  background: var(--primary);
  color: #052017;
  font-size: 0.62rem;
  font-weight: 800;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(54, 216, 180, 0.4);
}

.cover-art-item__badge {
  position: absolute;
  bottom: 22px;
  left: 4px;
  padding: 1px 5px;
  font-size: 0.6rem;
  font-weight: 600;
  color: #052017;
  background: linear-gradient(135deg, #fde68a, #f59e0b);
  border-radius: 4px;
  letter-spacing: 0.04em;
  box-shadow: 0 1px 3px rgba(0,0,0,0.18);
}
.cover-art-item.active .cover-art-item__badge {
  background: linear-gradient(135deg, #34d399, #06b6d4);
  color: #052017;
}

/* v6：地点推荐 chip */
.location-helper {
  margin: 6px 0 8px;
  font-size: 0.74rem;
  color: var(--text-muted);
  line-height: 1.4;
}
.location-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.location-chip {
  font-size: 0.74rem;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: rgba(14, 17, 22, 0.55);
  color: var(--text-soft);
  cursor: pointer;
  transition: all 180ms ease;
}
.location-chip:hover {
  border-color: var(--border-accent);
  background: rgba(54, 216, 180, 0.1);
  color: var(--text);
}
.location-chip--active {
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
  border-color: var(--primary);
  font-weight: 600;
  box-shadow: 0 0 12px rgba(54, 216, 180, 0.45);
}

.sensory-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.sensory-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: rgba(14, 17, 22, 0.5);
  color: var(--text-muted);
  font-size: 0.84rem;
  font-weight: 500;
  transition: all 240ms var(--ease-out-quart);
}

.sensory-pill svg {
  opacity: 0.5;
  transition: opacity 240ms ease;
}

.sensory-pill--active {
  color: var(--primary);
  border-color: var(--border-accent);
  background: rgba(54, 216, 180, 0.08);
  box-shadow: 0 0 0 1px var(--border-accent);
}

.sensory-pill--active svg {
  opacity: 1;
}

.builder-hero__hint {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: var(--radius-md);
  background: rgba(108, 198, 255, 0.06);
  border: 1px solid rgba(108, 198, 255, 0.18);
  color: var(--text-soft);
  font-size: 0.86rem;
  line-height: 1.6;
}

.builder-hero__hint svg {
  color: var(--accent);
  flex-shrink: 0;
  margin-top: 1px;
}

.builder-description-meter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.builder-description-counter {
  font-size: 0.82rem;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  transition: color 240ms ease;
}

.builder-description-counter--too-short { color: var(--danger, #f87171); }
.builder-description-counter--short { color: var(--warning, #facc15); }
.builder-description-counter--recommended { color: var(--primary, #36d8b4); font-weight: 600; }
.builder-description-counter--long { color: var(--accent, #6cc6ff); }
.builder-description-counter--exceeded { color: var(--gold, #f2b95c); }

.fade-bar__fill--too-short { background: var(--danger, #f87171); }
.fade-bar__fill--short { background: var(--warning, #facc15); }
.fade-bar__fill--recommended { background: var(--primary, #36d8b4); box-shadow: 0 0 8px var(--primary-glow); }
.fade-bar__fill--long { background: var(--accent, #6cc6ff); }
.fade-bar__fill--exceeded { background: var(--gold, #f2b95c); }

.builder-description-tip {
  margin-top: 6px;
  display: flex;
  justify-content: flex-start;
  gap: 8px;
}

.builder-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--border);
  margin-top: 8px;
}

.auth-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(5, 32, 23, 0.32);
  border-top-color: #052017;
  border-radius: 50%;
  animation: auth-spin 0.7s linear infinite;
}

@keyframes auth-spin {
  to { transform: rotate(360deg); }
}

.alert-enter-active,
.alert-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.alert-enter-from,
.alert-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 1100px) {
  .builder-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .builder-hero__metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
