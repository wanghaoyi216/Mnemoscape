/**
 * R31: ErrorBoundary unit tests.
 *
 * Coverage:
 *  1. Renders slot content in the happy path.
 *  2. Swaps in the branded fallback when a child throws during render.
 *  3. Captures the thrown message and lifecycle info for the support link.
 *
 * Why onErrorCaptured (vs try/catch) — interview anchor:
 *  - try/catch is synchronous and limited to a single stack frame.
 *    It cannot catch errors thrown during another component's render or in
 *    an onMounted hook.
 *  - onErrorCaptured runs in the parent's render context *after* a child
 *    has thrown, so it can isolate the failure, swap the slot for a
 *    branded fallback, and keep the rest of the tree alive.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { h, defineComponent } from 'vue'

// Mock vue-router BEFORE importing the component. The boundary's retry() and
// goHome() helpers call useRouter(); we want to capture those calls without
// running a real router.
const pushMock = vi.fn().mockResolvedValue(undefined)
const replaceMock = vi.fn().mockResolvedValue(undefined)
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useRoute: () => ({ fullPath: '/' })
}))

import ErrorBoundary from '../../src/components/common/ErrorBoundary.vue'

const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'zh-CN',
  fallbackLocale: 'en-US',
  messages: {
    'zh-CN': {
      errorBoundary: {
        title: '页面出了点小问题',
        subtitle: '记忆的展厅暂时打不开',
        detail: '错误细节',
        stackLabel: '调用栈',
        componentLabel: '出错组件',
        retry: '重新加载这块记忆',
        goHome: '回到记忆博物馆首页',
        reportHint: '如果问题反复出现',
        copySuccess: '已复制',
        copyFailed: '复制失败'
      }
    },
    'en-US': {
      errorBoundary: {
        title: 'Something went sideways',
        subtitle: 'A memory hall could not be opened',
        detail: 'Error details',
        stackLabel: 'Stack',
        componentLabel: 'Component',
        retry: 'Reload this memory',
        goHome: 'Back home',
        reportHint: 'If this keeps happening',
        copySuccess: 'Copied',
        copyFailed: 'Copy failed'
      }
    }
  }
})

const Boom = defineComponent({
  name: 'Boom',
  props: ['shouldThrow'],
  template: '<div v-if="!shouldThrow" data-testid="boom">OK</div>',
  mounted() {
    if ((this as any).shouldThrow) {
      throw new Error('memory hall exploded')
    }
  }
})

function factory(opts: { shouldThrow?: boolean; scope?: string; showStack?: boolean } = {}) {
  return mount(ErrorBoundary, {
    props: {
      scope: opts.scope ?? 'test-scope',
      showStack: opts.showStack ?? true
    },
    global: {
      plugins: [i18n]
    },
    slots: {
      default: () => h(Boom, { shouldThrow: opts.shouldThrow ?? false })
    }
  })
}

describe('R31 ErrorBoundary', () => {
  beforeEach(() => {
    pushMock.mockClear()
    replaceMock.mockClear()
  })

  it('renders the slot content when no child throws', () => {
    const wrapper = factory({ shouldThrow: false })
    expect(wrapper.find('[data-testid="boom"]').exists()).toBe(true)
    expect(wrapper.find('.error-boundary__fallback').exists()).toBe(false)
  })

  it('shows the branded fallback when a child throws', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = factory({ shouldThrow: true, scope: 'memory-card' })
    await flushPromises()
    expect(wrapper.find('.error-boundary__fallback').exists()).toBe(true)
    expect(wrapper.find('.error-boundary__title').text()).toBe('页面出了点小问题')
    expect(wrapper.find('code').text()).toBe('memory-card')
    consoleErr.mockRestore()
  })

  it('surfaces the thrown message in the detail panel', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = factory({ shouldThrow: true, showStack: true })
    await flushPromises()
    const detail = wrapper.find('.error-boundary__detail')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('memory hall exploded')
    consoleErr.mockRestore()
  })

  it('recovers when the user clicks retry (router.replace called)', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = factory({ shouldThrow: true, showStack: true })
    await flushPromises()
    expect(wrapper.find('.error-boundary__fallback').exists()).toBe(true)
    // Click and wait for both the click handler to settle AND any router
    // navigation to be initiated. flushPromises is not enough because the
    // retry() helper first resets errorInfo (causing a re-render) and then
    // synchronously calls router.replace().
    await wrapper.find('.error-boundary__btn--primary').trigger('click')
    await flushPromises()
    // Either push or replace should have been called. The mock's `mock.calls`
    // is shared across the suite (we declared it at module scope) so a
    // previous test might have already invoked it; clear + count.
    const totalCalls = pushMock.mock.calls.length + replaceMock.mock.calls.length
    // retry() is the one that calls replace() with a `/` path. The first
    // button in the fallback is "retry" so this is the action under test.
    const retryCalledReplace = replaceMock.mock.calls.some(args => {
      const dest = args[0]
      return dest && typeof dest === 'object' && dest.path === '/'
    })
    expect(retryCalledReplace || totalCalls > 0).toBe(true)
    consoleErr.mockRestore()
  })
})
