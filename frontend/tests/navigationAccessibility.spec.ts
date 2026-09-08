import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import AppHeader from '../src/components/layout/AppHeader.vue'
import i18n from '../src/i18n'

vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    isLoggedIn: true,
    isAdmin: false,
    user: { username: 'Memory Keeper' },
    logout: vi.fn(),
  }),
}))

describe('responsive navigation accessibility', () => {
  let wrapper: VueWrapper
  let router: Router
  let appRoot: HTMLDivElement
  let mediaQuery: EventTarget & { matches: boolean; media: string }

  beforeEach(async () => {
    localStorage.clear()
    i18n.global.locale.value = 'zh-CN'
    mediaQuery = Object.assign(new EventTarget(), {
      matches: true,
      media: '(max-width: 1260px)',
    })
    vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
    appRoot = document.createElement('div')
    appRoot.id = 'app'
    appRoot.inert = false
    document.body.append(appRoot)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/memories')
    await router.isReady()
    wrapper = mount(AppHeader, {
      attachTo: appRoot,
      global: { plugins: [router, i18n] },
    })
  })

  afterEach(() => {
    wrapper.unmount()
    appRoot.remove()
    document.documentElement.className = ''
    vi.unstubAllGlobals()
  })

  async function openDrawer() {
    await wrapper.get('.hamburger-btn').trigger('click')
    await nextTick()
    return document.querySelector<HTMLElement>('#mobile-navigation')!
  }

  function pressTab(shiftKey = false) {
    const event = new KeyboardEvent('keydown', {
      key: 'Tab',
      shiftKey,
      bubbles: true,
      cancelable: true,
    })
    document.activeElement?.dispatchEvent(event)
    return event
  }

  it('opens a labelled modal, focuses its close button, and isolates the background', async () => {
    const trigger = wrapper.get('.hamburger-btn')
    expect(trigger.attributes('aria-expanded')).toBe('false')

    const drawer = await openDrawer()
    expect(drawer.getAttribute('role')).toBe('dialog')
    expect(drawer.getAttribute('aria-modal')).toBe('true')
    expect(drawer.getAttribute('aria-label')).toBeTruthy()
    expect(trigger.attributes('aria-controls')).toBe(drawer.id)
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(drawer.querySelector('.drawer-sidebar__close'))
    expect(appRoot.inert).toBe(true)
    expect(document.documentElement.classList.contains('nav-drawer-open')).toBe(true)
  })

  it('wraps keyboard focus in both directions within the drawer', async () => {
    const drawer = await openDrawer()
    const controls = drawer.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex="0"]')
    const firstControl = controls[0]!
    const lastControl = controls[controls.length - 1]!

    firstControl.focus()
    expect(pressTab(true).defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(lastControl)

    expect(pressTab().defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(firstControl)
  })

  it('restores trigger focus, background access, and scrolling on Escape', async () => {
    await openDrawer()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
    await nextTick()

    expect(document.querySelector('#mobile-navigation')).toBeNull()
    expect(document.activeElement).toBe(wrapper.get('.hamburger-btn').element)
    expect(appRoot.inert).toBe(false)
    expect(document.documentElement.classList.contains('nav-drawer-open')).toBe(false)
  })

  it('closes after navigation without changing destination routes', async () => {
    const drawer = await openDrawer()
    const createLink = drawer.querySelector<HTMLAnchorElement>('a[href="/memories/new"]')!
    expect(createLink).not.toBeNull()
    createLink.click()
    await router.isReady()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/memories/new'))
    await nextTick()

    expect(document.querySelector('#mobile-navigation')).toBeNull()
    expect(appRoot.inert).toBe(false)
    expect(document.documentElement.classList.contains('nav-drawer-open')).toBe(false)
  })

  it('closes and returns focus to desktop navigation when the viewport expands', async () => {
    await openDrawer()
    mediaQuery.matches = false
    const changeEvent = Object.assign(new Event('change'), { matches: false })
    mediaQuery.dispatchEvent(changeEvent)
    await nextTick()

    expect(document.querySelector('#mobile-navigation')).toBeNull()
    expect(document.activeElement).toBe(wrapper.get('.app-nav__group-btn').element)
    expect(appRoot.inert).toBe(false)
  })

  it('restores pre-existing background state and removes listeners when unmounted', async () => {
    appRoot.inert = true
    await openDrawer()
    wrapper.unmount()

    expect(appRoot.inert).toBe(true)
    expect(document.documentElement.classList.contains('nav-drawer-open')).toBe(false)
    expect(document.querySelector('#mobile-navigation')).toBeNull()
  })

  it('exposes the active theme and language to assistive technology', async () => {
    await wrapper.get('.theme-switch-trigger').trigger('click')
    const selectedTheme = wrapper.get('.theme-menu-item[aria-pressed="true"]')
    expect(selectedTheme.text()).toBeTruthy()
    expect(wrapper.get('.locale-switch__btn[aria-pressed="true"]').text()).toBe('中')

    await wrapper.findAll('.locale-switch__btn')[1]!.trigger('click')
    expect(i18n.global.locale.value).toBe('en-US')
    expect(wrapper.get('.locale-switch__btn[aria-pressed="true"]').text()).toBe('EN')
    expect(localStorage.getItem('mnemoscape:locale')).toBe('en-US')
  })
})
