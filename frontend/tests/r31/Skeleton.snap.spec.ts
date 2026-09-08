/**
 * R31: SkeletonBox + SkeletonCard snapshot tests.
 *
 * The point of a skeleton is *visual* — it is a placeholder that mirrors the
 * shape of the real content. Snapshots guard against accidental layout
 * regressions: if someone removes a "block" prop, the row height collapses
 * and the snapshot breaks. That is the desired failure mode.
 *
 * Skeleton vs Spinner (interview anchor):
 *  - Spinner signals "duration unknown, content not ready" — good for short,
 *    indeterminate actions (button press, form submit).
 *  - Skeleton mirrors the *shape* of the upcoming content — eye already knows
 *    where to land, so perceived wait time drops by ~30% (NN/g). Best for
 *    content-heavy pages like a memory list or a chat thread.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import SkeletonBox from '../../src/components/common/SkeletonBox.vue'
import SkeletonCard from '../../src/components/common/SkeletonCard.vue'

const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'zh-CN',
  fallbackLocale: 'en-US',
  messages: {
    'zh-CN': {
      skeleton: {
        box: '加载中…',
        memoryCard: {
          title: '记忆标题占位',
          meta: '时间 · 地点',
          excerpt: '记忆摘要占位',
          tag: '标签'
        },
        chapterList: { heading: '章节标题', item: '章节列表项', timestamp: '时间戳' },
        chatMessage: { user: '正在输入消息…', ai: '星空使者正在思考…', timestamp: '刚刚' }
      }
    },
    'en-US': {
      skeleton: {
        box: 'Loading…',
        memoryCard: { title: 'Title', meta: 'time · location', excerpt: 'Excerpt', tag: 'tag' },
        chapterList: { heading: 'Heading', item: 'Item', timestamp: 'Timestamp' },
        chatMessage: { user: 'Typing…', ai: 'AI thinking…', timestamp: 'Just now' }
      }
    }
  }
})

function mountBox(props: Record<string, unknown> = {}) {
  return mount(SkeletonBox, {
    props,
    global: { plugins: [i18n] }
  })
}

function mountCard(props: Record<string, unknown> = {}) {
  return mount(SkeletonCard, {
    props,
    global: { plugins: [i18n] }
  })
}

describe('R31 SkeletonBox', () => {
  it('applies width / height / radius as inline styles', () => {
    const wrapper = mountBox({ width: 200, height: 40, radius: 12 })
    const el = wrapper.find('.skeleton-box').element as HTMLElement
    expect(el.style.width).toBe('200px')
    expect(el.style.height).toBe('40px')
    expect(el.style.borderRadius).toBe('12px')
  })

  it('forces border-radius 50% when circle is set', () => {
    const wrapper = mountBox({ width: 32, height: 32, circle: true })
    const el = wrapper.find('.skeleton-box').element as HTMLElement
    expect(el.style.borderRadius).toBe('50%')
  })

  it('is aria-hidden so screen readers skip it', () => {
    const wrapper = mountBox()
    expect(wrapper.find('.skeleton-box').attributes('aria-hidden')).toBe('true')
  })

  it('matches the baseline snapshot', () => {
    const wrapper = mountBox({ width: 120, height: 18 })
    expect(wrapper.html()).toMatchSnapshot()
  })
})

describe('R31 SkeletonCard', () => {
  it('renders the memory variant with cover + body + tags', () => {
    const wrapper = mountCard({ variant: 'memory', rows: 3 })
    expect(wrapper.find('.skeleton-card--memory').exists()).toBe(true)
    expect(wrapper.find('.skeleton-card__cover').exists()).toBe(true)
    expect(wrapper.findAll('.skeleton-card__tags > .skeleton-box').length).toBe(3)
  })

  it('renders the chapter variant with a vertical rail', () => {
    const wrapper = mountCard({ variant: 'chapter' })
    expect(wrapper.find('.skeleton-card--chapter').exists()).toBe(true)
    expect(wrapper.find('.skeleton-card__chapter-rail').exists()).toBe(true)
  })

  it('renders the chat variant and tags the bubble by speaker', () => {
    const wrapper = mountCard({ variant: 'chat', speaker: 'ai' })
    expect(wrapper.find('.skeleton-card--chat').exists()).toBe(true)
    expect(wrapper.find('.skeleton-card__chat-bubble--ai').exists()).toBe(true)
  })

  it('honors the `rows` prop for the memory excerpt', () => {
    const wrapper = mountCard({ variant: 'memory', rows: 5 })
    // body has 1 title + 1 meta + N excerpt + 1 tag row → 5 boxes in body
    const body = wrapper.find('.skeleton-card__body')
    expect(body.findAll('.skeleton-box').length).toBeGreaterThanOrEqual(5)
  })

  it('matches the baseline snapshot for each variant', () => {
    expect(mountCard({ variant: 'memory' }).html()).toMatchSnapshot('memory')
    expect(mountCard({ variant: 'chapter' }).html()).toMatchSnapshot('chapter')
    expect(mountCard({ variant: 'chat', speaker: 'user' }).html()).toMatchSnapshot('chat-user')
    expect(mountCard({ variant: 'chat', speaker: 'ai' }).html()).toMatchSnapshot('chat-ai')
  })
})
