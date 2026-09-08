<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { featureIllustrations } from '../../assets/media-catalog'

const { t } = useI18n()

const portalItems = [
  {
    id: 'create',
    to: '/memories/new',
    art: featureIllustrations[0],
    layout: 'lead',
    kickerKey: 'museum.portal.create.kicker',
    titleKey: 'museum.portal.create.title',
    descKey: 'museum.portal.create.desc',
  },
  {
    id: 'graph',
    to: '/memories/graph',
    art: featureIllustrations[8],
    layout: 'compact',
    kickerKey: 'museum.portal.graph.kicker',
    titleKey: 'museum.portal.graph.title',
    descKey: 'museum.portal.graph.desc',
  },
  {
    id: 'timeline',
    to: '/memories/timeline',
    art: featureIllustrations[4],
    layout: 'compact',
    kickerKey: 'museum.portal.timeline.kicker',
    titleKey: 'museum.portal.timeline.title',
    descKey: 'museum.portal.timeline.desc',
  },
  {
    id: 'resonance',
    to: '/resonance',
    art: featureIllustrations[6],
    layout: 'wide',
    kickerKey: 'museum.portal.resonance.kicker',
    titleKey: 'museum.portal.resonance.title',
    descKey: 'museum.portal.resonance.desc',
  },
] as const
</script>

<template>
  <section class="museum-portal" aria-labelledby="museum-portal-title">
    <header class="museum-portal__header">
      <div class="museum-portal__heading">
        <p class="museum-portal__eyebrow">
          <span aria-hidden="true"></span>
          {{ t('museum.portal.eyebrow') }}
        </p>
        <h2 id="museum-portal-title" class="museum-portal__title">
          {{ t('museum.portal.title') }}
        </h2>
      </div>

      <p class="museum-portal__subtitle">
        {{ t('museum.portal.subtitle') }}
      </p>
    </header>

    <ul class="museum-portal__grid">
      <li
        v-for="item in portalItems"
        :key="item.id"
        class="museum-portal__cell"
        :class="`museum-portal__cell--${item.layout}`"
      >
        <RouterLink
          :to="item.to"
          class="portal-card"
          :class="[`portal-card--${item.layout}`, `portal-card--${item.id}`]"
          :aria-labelledby="`portal-${item.id}-title`"
          :aria-describedby="`portal-${item.id}-description`"
        >
          <figure class="portal-card__media" aria-hidden="true">
            <img
              :src="item.art.thumb ?? item.art.src"
              :alt="t(item.titleKey)"
              width="420"
              height="420"
              loading="lazy"
              decoding="async"
            />
            <span class="portal-card__wash"></span>
          </figure>

          <span class="portal-card__art-number">
            {{ t('museum.portal.artNumber', { number: item.art.number }) }}
          </span>

          <div class="portal-card__content">
            <p class="portal-card__kicker">{{ t(item.kickerKey) }}</p>
            <h3 :id="`portal-${item.id}-title`" class="portal-card__title">
              {{ t(item.titleKey) }}
            </h3>
            <p :id="`portal-${item.id}-description`" class="portal-card__description">
              {{ t(item.descKey) }}
            </p>
            <span class="portal-card__open">
              {{ t('museum.portal.open') }}
              <svg aria-hidden="true" viewBox="0 0 20 20" fill="none">
                <path d="M4 10h11M11 6l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
          </div>
        </RouterLink>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.museum-portal {
  width: 100%;
  margin-top: clamp(4.5rem, 9vw, 8.5rem);
  color: var(--text, #f8f5ff);
  isolation: isolate;
}

.museum-portal__header {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(17rem, 0.7fr);
  gap: clamp(2rem, 6vw, 6.5rem);
  align-items: end;
  margin-bottom: clamp(1.75rem, 4vw, 3rem);
}

.museum-portal__heading {
  min-width: 0;
}

.museum-portal__eyebrow {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin: 0 0 0.9rem;
  color: var(--gold, #f2b95c);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.16em;
  line-height: 1.4;
  text-transform: uppercase;
}

.museum-portal__eyebrow span {
  width: 2rem;
  height: 1px;
  flex: 0 0 auto;
  background: currentColor;
  opacity: 0.72;
}

.museum-portal__title {
  max-width: 13ch;
  margin: 0;
  font-family: var(--font-display, serif);
  font-size: clamp(2.25rem, 5.4vw, 4.8rem);
  font-weight: 500;
  letter-spacing: -0.045em;
  line-height: 0.98;
  text-wrap: balance;
}

.museum-portal__subtitle {
  max-width: 35rem;
  margin: 0 0 0.3rem;
  color: var(--text-soft, #d8d1e8);
  font-size: clamp(0.94rem, 1.25vw, 1.08rem);
  line-height: 1.8;
  text-wrap: pretty;
}

.museum-portal__grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(15.5rem, 1fr)) minmax(19rem, 0.82fr);
  gap: clamp(0.85rem, 1.7vw, 1.35rem);
  min-height: 55rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.museum-portal__cell {
  min-width: 0;
  min-height: 0;
}

.museum-portal__cell--lead {
  grid-column: 1 / span 7;
  grid-row: 1 / span 2;
}

.museum-portal__cell--compact {
  grid-column: 8 / -1;
}

.museum-portal__cell--wide {
  grid-column: 1 / -1;
}

.portal-card {
  --card-accent: var(--primary, #d8b4fe);
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--card-accent) 15%, rgba(255, 255, 255, 0.1));
  border-radius: clamp(1rem, 2vw, 1.5rem);
  background: #0d0b18;
  box-shadow: 0 26px 70px -46px rgba(0, 0, 0, 0.92);
  color: inherit;
  text-decoration: none;
  transition:
    transform 320ms var(--ease-out-expo, cubic-bezier(0.16, 1, 0.3, 1)),
    border-color 240ms ease,
    box-shadow 320ms ease;
}

.portal-card::after {
  position: absolute;
  z-index: 3;
  inset: 0;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: inherit;
  pointer-events: none;
  content: '';
}

.portal-card--graph {
  --card-accent: #d9a6dc;
}

.portal-card--timeline {
  --card-accent: #a9bee9;
}

.portal-card--resonance {
  --card-accent: var(--gold, #f2b95c);
}

.portal-card__media,
.portal-card__wash {
  position: absolute;
  inset: 0;
}

.portal-card__media {
  z-index: 0;
  margin: 0;
  overflow: hidden;
  background: #151126;
}

.portal-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition:
    transform 700ms var(--ease-out-expo, cubic-bezier(0.16, 1, 0.3, 1)),
    filter 400ms ease;
}

.portal-card__wash {
  background:
    linear-gradient(180deg, rgba(7, 6, 15, 0.04) 28%, rgba(7, 6, 15, 0.68) 67%, rgba(7, 6, 15, 0.98) 100%),
    linear-gradient(90deg, rgba(7, 6, 15, 0.26), transparent 58%);
}

.portal-card__art-number {
  position: absolute;
  z-index: 2;
  top: clamp(1rem, 2vw, 1.4rem);
  right: clamp(1rem, 2vw, 1.4rem);
  padding: 0.45rem 0.68rem;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  background: rgba(10, 8, 20, 0.44);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: rgba(255, 255, 255, 0.78);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.66rem;
  letter-spacing: 0.09em;
  line-height: 1;
}

.portal-card__content {
  position: absolute;
  z-index: 2;
  right: 0;
  bottom: 0;
  left: 0;
  padding: clamp(1.35rem, 3vw, 2.35rem);
}

.portal-card__kicker {
  margin: 0 0 0.65rem;
  color: var(--card-accent);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  line-height: 1.4;
  text-transform: uppercase;
}

.portal-card__title {
  margin: 0;
  font-family: var(--font-display, serif);
  font-size: clamp(1.55rem, 2.4vw, 2.45rem);
  font-weight: 560;
  letter-spacing: -0.035em;
  line-height: 1.08;
  text-wrap: balance;
}

.portal-card__description {
  max-width: 38rem;
  margin: 0.8rem 0 0;
  color: rgba(242, 238, 250, 0.76);
  font-size: 0.91rem;
  line-height: 1.65;
  text-wrap: pretty;
}

.portal-card__open {
  display: inline-flex;
  gap: 0.55rem;
  align-items: center;
  margin-top: 1.35rem;
  color: rgba(255, 255, 255, 0.92);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.71rem;
  font-weight: 700;
  letter-spacing: 0.1em;
  line-height: 1;
  text-transform: uppercase;
}

.portal-card__open svg {
  width: 1.15rem;
  height: 1.15rem;
  transition: transform 240ms ease;
}

.portal-card--lead .portal-card__content {
  padding: clamp(1.65rem, 3.6vw, 3.1rem);
}

.portal-card--lead .portal-card__title {
  max-width: 11ch;
  font-size: clamp(2rem, 3.7vw, 3.65rem);
}

.portal-card--lead .portal-card__description {
  max-width: 31rem;
}

.portal-card--compact .portal-card__content {
  padding: clamp(1.15rem, 2vw, 1.65rem);
}

.portal-card--compact .portal-card__title {
  font-size: clamp(1.35rem, 2vw, 1.9rem);
}

.portal-card--compact .portal-card__description {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 0.55rem;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.portal-card--compact .portal-card__open {
  margin-top: 0.9rem;
}

.portal-card--wide {
  background: #12101d;
}

.portal-card--wide .portal-card__media {
  right: 0;
  left: auto;
  width: 65%;
  -webkit-mask-image: linear-gradient(90deg, transparent 0%, #000 30%, #000 100%);
  mask-image: linear-gradient(90deg, transparent 0%, #000 30%, #000 100%);
}

.portal-card--wide .portal-card__media img {
  object-position: center 48%;
}

.portal-card--wide .portal-card__wash {
  background: linear-gradient(90deg, rgba(9, 8, 16, 0.5), rgba(9, 8, 16, 0.06) 66%);
}

.portal-card--wide .portal-card__content {
  top: 0;
  right: auto;
  width: min(55%, 42rem);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
}

.portal-card--wide .portal-card__description {
  max-width: 34rem;
}

.portal-card:focus-visible {
  outline: 2px solid var(--card-accent);
  outline-offset: 4px;
  box-shadow: 0 0 0 1px rgba(6, 5, 15, 0.92), 0 24px 72px -34px rgba(0, 0, 0, 0.95);
}

@media (hover: hover) and (pointer: fine) {
  .portal-card:hover {
    transform: translateY(-4px);
    border-color: color-mix(in srgb, var(--card-accent) 42%, rgba(255, 255, 255, 0.15));
    box-shadow: 0 34px 82px -44px rgba(0, 0, 0, 0.96);
  }

  .portal-card:hover .portal-card__media img {
    transform: scale(1.035);
    filter: saturate(1.04) contrast(1.02);
  }

  .portal-card:hover .portal-card__open svg {
    transform: translateX(0.25rem);
  }
}

@media (max-width: 900px) {
  .museum-portal__header {
    grid-template-columns: 1fr;
    gap: 1.2rem;
  }

  .museum-portal__subtitle {
    max-width: 42rem;
  }

  .museum-portal__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: minmax(27rem, auto) minmax(20rem, auto) minmax(21rem, auto);
    min-height: 0;
  }

  .museum-portal__cell--lead,
  .museum-portal__cell--wide {
    grid-column: 1 / -1;
    grid-row: auto;
  }

  .museum-portal__cell--compact {
    grid-column: auto;
  }

  .portal-card--compact .portal-card__description {
    -webkit-line-clamp: 3;
  }

  .portal-card--wide .portal-card__content {
    width: min(62%, 34rem);
  }
}

@media (max-width: 620px) {
  .museum-portal__header {
    margin-bottom: 1.5rem;
  }

  .museum-portal__title {
    font-size: clamp(2.15rem, 12vw, 3.35rem);
  }

  .museum-portal__grid {
    display: grid;
    grid-template-columns: 1fr;
    grid-template-rows: minmax(25rem, auto) repeat(3, minmax(21rem, auto));
  }

  .museum-portal__cell,
  .museum-portal__cell--lead,
  .museum-portal__cell--compact,
  .museum-portal__cell--wide {
    grid-column: 1;
    grid-row: auto;
  }

  .portal-card--wide .portal-card__media {
    inset: 0;
    width: auto;
    -webkit-mask-image: none;
    mask-image: none;
  }

  .portal-card--wide .portal-card__wash {
    background:
      linear-gradient(180deg, rgba(7, 6, 15, 0.03) 22%, rgba(7, 6, 15, 0.75) 66%, rgba(7, 6, 15, 0.98) 100%),
      linear-gradient(90deg, rgba(7, 6, 15, 0.22), transparent 60%);
  }

  .portal-card--wide .portal-card__content {
    top: auto;
    right: 0;
    width: auto;
    display: block;
  }

  .portal-card__description {
    font-size: 0.87rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .portal-card,
  .portal-card__media img,
  .portal-card__open svg {
    transition: none;
  }
}
</style>
