import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import MemoryBuilderView from '../src/views/MemoryBuilderView.vue'
import i18n from '../src/i18n'

const memoryStore = vi.hoisted(() => ({ create: vi.fn() }))

vi.mock('../src/stores/memory', () => ({
  useMemoryStore: () => memoryStore,
}))

describe('memory creation flow', () => {
  let wrapper: VueWrapper
  let router: Router

  beforeEach(async () => {
    memoryStore.create.mockReset()
    memoryStore.create.mockResolvedValue({ id: 'created-memory' })
    i18n.global.locale.value = 'zh-CN'
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/memories/new')
    await router.isReady()
    wrapper = mount(MemoryBuilderView, {
      global: {
        plugins: [router, i18n],
        stubs: { LocationPicker: true, CoverPickerModal: true },
      },
    })
  })

  afterEach(() => {
    wrapper.unmount()
  })

  async function fillMemory(title = '归途', description = '那晚温暖的风吹过窗边，听见远处的音乐。') {
    await wrapper.get('input[maxlength="200"]').setValue(title)
    await wrapper.get('textarea').setValue(description)
  }

  it('does not send an empty or whitespace-only title even when the form is submitted directly', async () => {
    await fillMemory('   ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(memoryStore.create).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/memories/new')
  })

  it('blocks descriptions below the existing minimum without clearing the draft', async () => {
    await fillMemory('归途', '太短')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(memoryStore.create).not.toHaveBeenCalled()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('太短')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('preserves the create API payload and navigates to the existing detail route', async () => {
    await fillMemory()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(memoryStore.create).toHaveBeenCalledTimes(1)
    expect(memoryStore.create).toHaveBeenCalledWith(expect.objectContaining({
      title: '归途',
      description: '那晚温暖的风吹过窗边，听见远处的音乐。',
      privacyLevel: 'PRIVATE',
    }))
    expect(router.currentRoute.value.path).toBe('/memories/created-memory')
  })

  it('keeps submission locked and ignores repeat events until creation settles', async () => {
    let finishCreation!: (memory: { id: string }) => void
    memoryStore.create.mockReturnValue(new Promise((resolve) => { finishCreation = resolve }))
    await fillMemory()
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(memoryStore.create).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(router.currentRoute.value.path).toBe('/memories/new')

    finishCreation({ id: 'created-once' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/memories/created-once')
  })

  it('keeps entered text and permits retry after a service failure', async () => {
    memoryStore.create.mockRejectedValueOnce({ response: { data: { message: '暂时无法保存，请重试。' } } })
    await fillMemory()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法保存，请重试。')
    expect((wrapper.get('input[maxlength="200"]').element as HTMLInputElement).value).toBe('归途')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('温暖的风')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(memoryStore.create).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.path).toBe('/memories/created-memory')
  })

  it('does not navigate to an invalid detail route when the store returns no record', async () => {
    memoryStore.create.mockResolvedValueOnce(undefined)
    await fillMemory()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/memories/new')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
