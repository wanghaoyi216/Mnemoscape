/**
 * Vitest tests for the {@code <AdminPanel>} state machine
 * (admin-dashboard task 10.12, validates Requirements 5.5 / 5.6 / 18.2).
 *
 * Coverage:
 *   - Each of the 5 states (idle/loading/empty/error/ready) renders the
 *     correct DOM region and hides the others.
 *   - degraded=true renders the badge + footer regardless of state.
 *   - Error state retry button calls onRetry when clicked.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import AdminPanel from '../../src/components/admin/AdminPanel.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': {
      admin: {
        common: {
          empty: '暂无数据',
          error: '加载失败',
          retry: '重试',
          degradedBadge: '数据降级',
          degradedHint: '部分上游服务暂不可用',
          loading: '数据加载中',
        },
        activeUsers: {
          title: '活跃用户',
        },
      },
    },
  },
})

function mountPanel(props: Record<string, unknown>, slots: Record<string, string> = {}) {
  return mount(AdminPanel, {
    props: { title: 'admin.activeUsers.title', ...props },
    slots,
    global: { plugins: [i18n] },
  })
}

describe('<AdminPanel> state machine (R5.5 / R5.6 / R18.2)', () => {
  it('renders the title from the i18n key', () => {
    const wrapper = mountPanel({ state: 'idle' })
    expect(wrapper.find('.admin-panel__title').text()).toBe('活跃用户')
  })

  it('idle state renders neither skeleton nor empty/error slots', () => {
    const wrapper = mountPanel({ state: 'idle' })
    expect(wrapper.find('.admin-panel__skel').exists()).toBe(false)
    expect(wrapper.find('.admin-panel__empty').exists()).toBe(false)
    expect(wrapper.find('.admin-panel__error').exists()).toBe(false)
  })

  it('loading state shows the skeleton with three bars', () => {
    const wrapper = mountPanel({ state: 'loading' })
    const skel = wrapper.find('.admin-panel__skel')
    expect(skel.exists()).toBe(true)
    expect(skel.findAll('.admin-panel__skel-bar')).toHaveLength(3)
  })

  it('empty state renders the empty placeholder text', () => {
    const wrapper = mountPanel({ state: 'empty' })
    expect(wrapper.find('.admin-panel__empty').exists()).toBe(true)
    expect(wrapper.find('.admin-panel__empty p').text()).toBe('暂无数据')
  })

  it('error state shows the error code, message, and a retry button', () => {
    const onRetry = vi.fn()
    const wrapper = mountPanel({
      state: 'error',
      error: { code: 'UPSTREAM_UNAVAILABLE', message: '上游服务暂不可达' },
      onRetry,
    })
    const errorBlock = wrapper.find('.admin-panel__error')
    expect(errorBlock.exists()).toBe(true)
    expect(errorBlock.find('.admin-panel__error-code').text()).toBe('UPSTREAM_UNAVAILABLE')
    expect(errorBlock.find('.admin-panel__error-msg').text()).toBe('上游服务暂不可达')
    expect(errorBlock.find('.admin-panel__retry').exists()).toBe(true)
  })

  it('clicking the retry button invokes onRetry', async () => {
    const onRetry = vi.fn()
    const wrapper = mountPanel({
      state: 'error',
      error: { code: 'UPSTREAM_UNAVAILABLE', message: 'down' },
      onRetry,
    })
    await wrapper.find('.admin-panel__retry').trigger('click')
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('ready state renders the default slot', () => {
    const wrapper = mountPanel({ state: 'ready' }, {
      default: '<div class="my-content">live data here</div>',
    })
    expect(wrapper.find('.my-content').exists()).toBe(true)
    expect(wrapper.find('.my-content').text()).toBe('live data here')
    // No skeleton / empty / error rendered.
    expect(wrapper.find('.admin-panel__skel').exists()).toBe(false)
    expect(wrapper.find('.admin-panel__empty').exists()).toBe(false)
    expect(wrapper.find('.admin-panel__error').exists()).toBe(false)
  })

  it('degraded=true renders the badge in the header', () => {
    const wrapper = mountPanel({ state: 'ready', degraded: true })
    const badge = wrapper.find('.admin-panel__badge--degraded')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('数据降级')
  })

  it('degraded=true with reasons renders the footer list', () => {
    const wrapper = mountPanel({
      state: 'ready',
      degraded: true,
      degradedReasons: ['auth-service username lookup failed', 'cache layer down'],
    })
    const footer = wrapper.find('.admin-panel__degraded-footer')
    expect(footer.exists()).toBe(true)
    const items = footer.findAll('.admin-panel__degraded-list li')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toBe('auth-service username lookup failed')
    expect(items[1].text()).toBe('cache layer down')
  })

  it('degraded=false does NOT render the badge or footer', () => {
    const wrapper = mountPanel({ state: 'ready', degraded: false })
    expect(wrapper.find('.admin-panel__badge--degraded').exists()).toBe(false)
    expect(wrapper.find('.admin-panel__degraded-footer').exists()).toBe(false)
  })

  it('degraded badge is shown alongside any state (e.g. degraded + error)', () => {
    const wrapper = mountPanel({
      state: 'error',
      error: { code: 'UPSTREAM_UNAVAILABLE', message: 'down' },
      degraded: true,
    })
    expect(wrapper.find('.admin-panel__badge--degraded').exists()).toBe(true)
    expect(wrapper.find('.admin-panel__error').exists()).toBe(true)
  })

  it('data-state attribute reflects the panel state', () => {
    for (const s of ['idle', 'loading', 'empty', 'error', 'ready'] as const) {
      const wrapper = mountPanel({
        state: s,
        ...(s === 'error'
          ? { error: { code: 'X', message: 'y' } }
          : {}),
      })
      expect(wrapper.attributes('data-state')).toBe(s)
    }
  })
})
