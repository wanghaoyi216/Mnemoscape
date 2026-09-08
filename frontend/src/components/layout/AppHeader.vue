<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import { setLocale, SUPPORTED_LOCALES, type Locale } from '../../i18n'
import NavIcon, { type NavIconName } from './NavIcon.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t, locale } = useI18n()

/* 导航分组（只重组展示，不动任何路由）：记忆 3 项 / 探索 2 项 / 社交 2 项。 */
interface NavLinkItem { to: string; icon: NavIconName; labelKey: string }
interface NavGroup { id: string; labelKey: string; links: NavLinkItem[] }

const NAV_GROUPS: NavGroup[] = [
  {
    id: 'memory',
    labelKey: 'nav.groups.memory',
    links: [
      { to: '/memories', icon: 'memories', labelKey: 'nav.memories' },
      { to: '/memories/new', icon: 'create', labelKey: 'nav.create' },
      { to: '/memories/timeline', icon: 'timeline', labelKey: 'nav.timeline' },
    ],
  },
  {
    id: 'explore',
    labelKey: 'nav.groups.explore',
    links: [
      { to: '/memories/graph', icon: 'graph', labelKey: 'nav.graph' },
      { to: '/memories/atlas', icon: 'atlas', labelKey: 'nav.atlas' },
    ],
  },
  {
    id: 'social',
    labelKey: 'nav.groups.social',
    links: [
      { to: '/resonance', icon: 'resonance', labelKey: 'nav.resonance' },
      { to: '/chat', icon: 'chat', labelKey: 'nav.chat' },
    ],
  },
]

const openGroupId = ref<string | null>(null)
const navEl = ref<HTMLElement | null>(null)

function isLinkActive(to: string) {
  return route.path === to || route.path.startsWith(`${to}/`)
}

function isGroupActive(group: NavGroup) {
  return group.links.some((link) => isLinkActive(link.to))
}

function toggleGroup(id: string) {
  openGroupId.value = openGroupId.value === id ? null : id
  themeMenuOpen.value = false
}

const initials = computed(() => auth.user?.username?.charAt(0).toUpperCase() || 'M')

const scrolled = ref(false)
const isDrawerOpen = ref(false)
const headerEl = ref<HTMLElement | null>(null)
const themeSwitchEl = ref<HTMLElement | null>(null)
const drawerEl = ref<HTMLElement | null>(null)
const drawerTriggerEl = ref<HTMLButtonElement | null>(null)
let headerObserver: ResizeObserver | null = null
let drawerMediaQuery: MediaQueryList | null = null
let drawerBackground: HTMLElement | null = null
let drawerBackgroundWasInert = false

function handleScroll() {
  scrolled.value = window.scrollY > 8
}

/** 把实时 header 高度写到 :root --app-header-h，让需要让位的页面（atlas / scene 等）
 *  通过 var(--app-header-h, 88px) 拿到精确数值，避免在 1100px 以下 nav wrap 后被压住。 */
function publishHeaderHeight() {
  if (typeof document === 'undefined') return
  const el = headerEl.value
  if (!el) return
  const h = Math.round(el.getBoundingClientRect().height)
  if (h > 0) {
    document.documentElement.style.setProperty('--app-header-h', `${h}px`)
  }
}

const themeMenuOpen = ref(false)
const currentTheme = ref(localStorage.getItem('mnemoscape-theme') || 'theme-museum')

const themes = [
  { id: 'theme-museum', nameKey: 'theme.museum', color: '#d8b4fe' },
  { id: 'theme-mint', nameKey: 'theme.mint', color: '#36d8b4' },
  { id: 'theme-pink', nameKey: 'theme.pink', color: '#ff6eb4' },
  { id: 'theme-gold', nameKey: 'theme.gold', color: '#f2b95c' },
  { id: 'theme-blue', nameKey: 'theme.blue', color: '#4fc3f7' }
]

function changeTheme(themeId: string) {
  currentTheme.value = themeId
  localStorage.setItem('mnemoscape-theme', themeId)
  
  const htmlEl = document.documentElement
  htmlEl.classList.forEach(cls => {
    if (cls.startsWith('theme-')) {
      htmlEl.classList.remove(cls)
    }
  })
  htmlEl.classList.add(themeId)
  themeMenuOpen.value = false
}

function handleGlobalClick(e: MouseEvent) {
  if (themeSwitchEl.value && !themeSwitchEl.value.contains(e.target as Node)) {
    themeMenuOpen.value = false
  }
  if (navEl.value && !navEl.value.contains(e.target as Node)) {
    openGroupId.value = null
  }
}

function restoreDrawerBackground() {
  if (drawerBackground) drawerBackground.inert = drawerBackgroundWasInert
  drawerBackground = null
}

function handleDrawerBreakpoint(event: MediaQueryListEvent) {
  if (!event.matches) isDrawerOpen.value = false
}

function handleDrawerFocus(event: FocusEvent) {
  if (isDrawerOpen.value && drawerEl.value && !drawerEl.value.contains(event.target as Node)) {
    drawerEl.value.querySelector<HTMLButtonElement>('.drawer-sidebar__close')?.focus()
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    if (isDrawerOpen.value) event.preventDefault()
    isDrawerOpen.value = false
    themeMenuOpen.value = false
    openGroupId.value = null
    return
  }
  if (event.key !== 'Tab' || !isDrawerOpen.value || !drawerEl.value) return

  const focusableElements = Array.from(drawerEl.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), [tabindex="0"]',
  ))
  const firstElement = focusableElements[0]
  const lastElement = focusableElements[focusableElements.length - 1]
  const focusOutside = !drawerEl.value.contains(document.activeElement)
  if (!firstElement || !lastElement) {
    event.preventDefault()
    drawerEl.value.focus()
  } else if (event.shiftKey && (document.activeElement === firstElement || focusOutside)) {
    event.preventDefault()
    lastElement.focus()
  } else if (!event.shiftKey && (document.activeElement === lastElement || focusOutside)) {
    event.preventDefault()
    firstElement.focus()
  }
}

watch(() => route.fullPath, () => {
  isDrawerOpen.value = false
  themeMenuOpen.value = false
  openGroupId.value = null
})

watch(isDrawerOpen, (open) => {
  document.documentElement.classList.toggle('nav-drawer-open', open)
  if (open) {
    themeMenuOpen.value = false
    openGroupId.value = null
    drawerEl.value?.querySelector<HTMLButtonElement>('.drawer-sidebar__close')?.focus({ preventScroll: true })
    drawerBackground = document.getElementById('app')
    if (drawerBackground) {
      drawerBackgroundWasInert = drawerBackground.inert
      drawerBackground.inert = true
    }
  } else {
    restoreDrawerBackground()
    const returnTarget = drawerMediaQuery?.matches === false
      ? navEl.value?.querySelector<HTMLButtonElement>('button')
      : drawerTriggerEl.value
    returnTarget?.focus({ preventScroll: true })
  }
}, { flush: 'post' })

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  handleScroll()
  publishHeaderHeight()
  if (typeof ResizeObserver !== 'undefined' && headerEl.value) {
    headerObserver = new ResizeObserver(() => publishHeaderHeight())
    headerObserver.observe(headerEl.value)
  }
  window.addEventListener('click', handleGlobalClick)
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('focusin', handleDrawerFocus)
  drawerMediaQuery = window.matchMedia('(max-width: 1260px)')
  drawerMediaQuery.addEventListener('change', handleDrawerBreakpoint)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('click', handleGlobalClick)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('focusin', handleDrawerFocus)
  drawerMediaQuery?.removeEventListener('change', handleDrawerBreakpoint)
  document.documentElement.classList.remove('nav-drawer-open')
  restoreDrawerBackground()
  if (headerObserver) {
    headerObserver.disconnect()
    headerObserver = null
  }
})

function handleLogout() {
  isDrawerOpen.value = false
  auth.logout()
  router.push('/login')
}

function switchLocale(l: Locale) {
  setLocale(l)
}
</script>

<template>
  <header ref="headerEl" class="app-header" :class="{ 'app-header--scrolled': scrolled }">
    <div class="page-shell--wide app-header__inner">
      <RouterLink to="/" class="brand" :aria-label="t('brand.name')">
        <span class="brand__mark" aria-hidden="true">
          <img src="/favicon.ico?v=2" alt="" />
        </span>
        <span class="brand__copy">
          <strong>{{ t('brand.name') }}</strong>
          <small>{{ t('brand.tagline') }}</small>
        </span>
      </RouterLink>

      <nav ref="navEl" class="app-nav" :aria-label="t('nav.memories')">
        <div v-for="group in NAV_GROUPS" :key="group.id" class="app-nav__group">
          <button
            type="button"
            class="app-nav__group-btn"
            :class="{ 'app-nav__group-btn--active': isGroupActive(group) }"
            :aria-expanded="openGroupId === group.id"
            aria-haspopup="true"
            @click="toggleGroup(group.id)"
          >
            <span>{{ t(group.labelKey) }}</span>
            <svg class="app-nav__chevron" viewBox="0 0 24 24" width="12" height="12" fill="none" aria-hidden="true">
              <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <transition name="theme-menu-fade">
            <div v-if="openGroupId === group.id" class="app-nav__menu" role="menu">
              <RouterLink
                v-for="link in group.links"
                :key="link.to"
                :to="link.to"
                class="app-nav__menu-item"
                :title="t(link.labelKey)"
                role="menuitem"
              >
                <NavIcon :name="link.icon" />
                <span>{{ t(link.labelKey) }}</span>
              </RouterLink>
            </div>
          </transition>
        </div>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="app-nav__link app-nav__link--admin" :title="t('admin.nav.entry')">
          <NavIcon name="admin" />
          <span>{{ t('admin.nav.entry') }}</span>
        </RouterLink>
      </nav>

      <!-- Hamburger Button for responsive drawer -->
      <button
        ref="drawerTriggerEl"
        type="button"
        class="hamburger-btn"
        :class="{ 'hamburger-btn--open': isDrawerOpen }"
        @click="isDrawerOpen = !isDrawerOpen"
        :aria-label="isDrawerOpen ? t('nav.closeMenu') : t('nav.menu')"
        :aria-expanded="isDrawerOpen"
        aria-controls="mobile-navigation"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line class="hamburger-bar hamburger-bar--top" x1="4" y1="7" x2="20" y2="7"></line>
          <line class="hamburger-bar hamburger-bar--mid" x1="4" y1="12" x2="20" y2="12"></line>
          <line class="hamburger-bar hamburger-bar--bot" x1="4" y1="17" x2="20" y2="17"></line>
        </svg>
      </button>

      <div class="header-actions">
        <!-- 系统多主题色控制器 -->
        <div ref="themeSwitchEl" class="theme-switch-container">
          <button
            type="button"
            class="theme-switch-trigger"
            :title="t('nav.theme')"
            :aria-label="t('nav.theme')"
            :aria-expanded="themeMenuOpen"
            aria-controls="theme-menu"
            @click="themeMenuOpen = !themeMenuOpen"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8"/>
              <path d="M12 3a9 9 0 0 1 0 18V3z" fill="currentColor"/>
            </svg>
          </button>
          <transition name="theme-menu-fade">
            <div v-if="themeMenuOpen" id="theme-menu" class="theme-menu-dropdown">
              <button
                v-for="themeItem in themes"
                :key="themeItem.id"
                type="button"
                class="theme-menu-item"
                :class="{ 'theme-menu-item--active': currentTheme === themeItem.id }"
                :aria-pressed="currentTheme === themeItem.id"
                @click="changeTheme(themeItem.id)"
              >
                <span class="theme-color-preview" :style="{ background: themeItem.color, color: themeItem.color }"></span>
                <span>{{ t(themeItem.nameKey) }}</span>
              </button>
            </div>
          </transition>
        </div>

        <div class="locale-switch" role="group" :aria-label="t('nav.locale')">
          <button
            v-for="l in SUPPORTED_LOCALES"
            :key="l"
            type="button"
            class="locale-switch__btn"
            :class="{ 'locale-switch__btn--active': locale === l }"
            :aria-pressed="locale === l"
            :title="t(`locale.${l}`)"
            @click="switchLocale(l)"
          >
            {{ l === 'zh-CN' ? '中' : 'EN' }}
          </button>
        </div>

        <template v-if="auth.isLoggedIn">
          <RouterLink to="/profile" class="user-chip" :title="auth.user?.username || ''" :aria-label="t('profile.title')">
            <span class="user-chip__avatar">{{ initials }}</span>
            <span class="user-chip__meta">
              <strong>{{ auth.user?.username || t('brand.name') }}</strong>
              <small>{{ t('profile.title') }}</small>
            </span>
          </RouterLink>
          <button class="button button--secondary" type="button" :title="t('nav.logout')" :aria-label="t('nav.logout')" @click="handleLogout" style="display: inline-flex; align-items: center; gap: 6px;">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>
            </svg>
            <span>{{ t('nav.logout') }}</span>
          </button>
        </template>

        <template v-else>
          <RouterLink to="/login" class="button button--ghost" :aria-label="t('login.submit')" style="display: inline-flex; align-items: center; gap: 6px;">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3"/>
            </svg>
            <span>{{ t('login.submit') }}</span>
          </RouterLink>
          <RouterLink to="/register" class="button button--primary" :aria-label="t('register.submit')" style="display: inline-flex; align-items: center; gap: 6px;">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2m7.5-13a4 4 0 1 0 0-8 4 4 0 0 0 0 8m12 1v6m-3-3h6"/>
            </svg>
            <span>{{ t('register.submit') }}</span>
          </RouterLink>
        </template>
      </div>
    </div>
  </header>

  <!-- Mobile/Responsive Drawer Navigation -->
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="isDrawerOpen" class="drawer-backdrop" aria-hidden="true" @click="isDrawerOpen = false" />
    </Transition>
    <Transition name="drawer-slide">
      <aside v-if="isDrawerOpen" id="mobile-navigation" ref="drawerEl" class="drawer-sidebar" role="dialog" aria-modal="true" :aria-label="t('nav.menu')" tabindex="-1">
        <div class="drawer-sidebar__header">
          <span class="drawer-sidebar__logo">{{ t('brand.name') }}</span>
          <button type="button" class="drawer-sidebar__close" @click="isDrawerOpen = false" :aria-label="t('nav.closeMenu')">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <line x1="6" y1="6" x2="18" y2="18"></line>
              <line x1="18" y1="6" x2="6" y2="18"></line>
            </svg>
          </button>
        </div>
        
        <nav class="drawer-sidebar__nav">
          <div v-for="group in NAV_GROUPS" :key="group.id" class="drawer-sidebar__group">
            <p class="drawer-sidebar__group-caption">{{ t(group.labelKey) }}</p>
            <RouterLink
              v-for="link in group.links"
              :key="link.to"
              :to="link.to"
              class="drawer-sidebar__link"
              @click="isDrawerOpen = false"
            >
              <NavIcon :name="link.icon" />
              <span>{{ t(link.labelKey) }}</span>
            </RouterLink>
          </div>
          <div v-if="auth.isAdmin" class="drawer-sidebar__group">
            <RouterLink to="/admin" class="drawer-sidebar__link drawer-sidebar__link--admin" @click="isDrawerOpen = false">
              <NavIcon name="admin" />
              <span>{{ t('admin.nav.entry') }}</span>
            </RouterLink>
          </div>
        </nav>

        <div class="drawer-sidebar__foot">
          <div class="locale-switch" role="group" :aria-label="t('nav.locale')">
            <button
              v-for="localeOption in SUPPORTED_LOCALES"
              :key="localeOption"
              type="button"
              class="locale-switch__btn"
              :class="{ 'locale-switch__btn--active': locale === localeOption }"
              :aria-pressed="locale === localeOption"
              @click="switchLocale(localeOption)"
            >{{ t(`locale.${localeOption}`) }}</button>
          </div>
          <p class="drawer-sidebar__hint">{{ t('nav.drawerHint') }}</p>
          <button
            v-if="auth.isLoggedIn"
            type="button"
            class="drawer-sidebar__signout"
            @click="handleLogout"
          >
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
            </svg>
            <span>{{ t('nav.logout') }}</span>
          </button>
        </div>
      </aside>
    </Transition>
  </Teleport>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  border-bottom: 1px solid rgba(231, 224, 255, 0.08);
  background: rgba(7, 6, 17, 0.66);
  backdrop-filter: blur(18px) saturate(145%);
  -webkit-backdrop-filter: blur(18px) saturate(145%);
  transition: background-color 240ms ease, border-color 240ms ease, box-shadow 240ms ease;
}

.app-header--scrolled {
  background: rgba(7, 6, 17, 0.9);
  border-bottom-color: rgba(216, 180, 254, 0.15);
  box-shadow: 0 12px 36px rgba(3, 2, 10, 0.24);
}

.app-header__inner {
  margin: 0 auto;
  min-height: 76px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* v11：缩短 gap 让 nav 在中屏上有更多展示空间；不再 flex-wrap，
     避免 nav 被挤到第二行后内容被遮挡。 */
  gap: 18px;
  padding: 9px 0;
  /* 让 brand / nav / actions 三块都不能撑爆容器 — flex 容器整体 min-width: 0
     允许子项各自缩 / 内部滚动。 */
  min-width: 0;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  /* v11：brand 不参与 flex 增长，但允许收缩（中屏 brand__copy 会先隐藏 small） */
  flex: 0 1 auto;
  min-width: 0;
  transition: opacity 200ms ease;
}

.brand:hover {
  opacity: 0.88;
}

.brand__mark {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--primary) 0%, var(--gold) 100%);
  color: #171022;
  overflow: hidden;
  box-shadow: 0 12px 28px color-mix(in srgb, var(--primary) 24%, transparent),
              0 1px 0 rgba(255, 255, 255, 0.22) inset;
}

.brand__mark img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.brand__copy {
  display: grid;
  gap: 1px;
  /* v14：brand 强制单行 + 省略号 — 修复英文 "Mnemoscape" 在 1200-1440px
     窗口下被 flex 容器挤压成 "Mnemoscap / e" 换行的 bug。 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.brand__copy strong {
  font-family: var(--font-display);
  /* v13：brand 主标题；副标题已删除（template 里不再渲染）。 */
  font-size: 1.06rem;
  font-weight: 800;
  letter-spacing: -0.01em;
  background-image: none;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  color: var(--text);
  animation: none;
  /* v14：单行兜底，配合 .brand__copy 的 white-space:nowrap */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  display: block;
}

.brand__copy small {
  color: var(--text-muted);
  font-size: 0.66rem;
  font-weight: 650;
  letter-spacing: 0.16em;
  line-height: 1.15;
}

.app-nav {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: rgba(20, 17, 40, 0.62);
  backdrop-filter: blur(12px);
  /* v15：让 nav 在 flex 容器里"霸占剩余空间但不溢出" — flex: 1 1 auto + min-width: 0
     是关键：parent .app-header__inner 已是 min-width:0，nav 也设 0 才能让里面的
     link/text 在容器不够时收窄。 */
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  /* 分组下拉菜单需要弹出层不被裁掉 — 桌面端只有 3 个分组胶囊，
     不再需要横向滚动兜底；1260px 以下整个 nav 收入抽屉。 */
  overflow: visible;
  flex-shrink: 1;
  /* 隐藏滚动条 */
  -ms-overflow-style: none;  /* IE and Edge */
  scrollbar-width: none;  /* Firefox */
}
.app-nav::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

/* v14：横向滚动兜底 — 当所有 link 加起来 + icon + padding 仍超过容器时
   允许水平滚动而不是把 "Chat & Discovery" 截断成 "Chat & Disc"。 */
.app-nav::after {
  content: "";
  flex: 0 0 0;
}
[hidden],
[aria-hidden="true"].app-nav { display: none; }
.app-nav__link,
.app-nav__link span,
.app-nav__link svg { box-sizing: border-box; }

.app-nav__link {
  /* flex-shrink:0 + nowrap 是核心修复：防止文字被切成"我的记/忆"。 */
  flex-shrink: 0;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--radius-full);
  /* v12：nav 用纯色，明确高对比；v13 略收一点，0.98rem 配 750 重，
     在中屏 1280-1440 区间能把 8 个完整 link 全展开。 */
  color: rgba(235, 230, 247, 0.82);
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.005em;
  transition: background-color 180ms ease, color 180ms ease, padding 180ms ease;

  /* 重置全局 a:not(.button) 的"渐变文字"效果 — 否则 active pill 文字会消失 */
  background-image: none;
  background-size: auto;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  animation: none;

  /* 覆盖 style.css 里的 overflow: hidden，确保文字即使溢出也能显示，不被 ellipsis 截断 */
  overflow: visible;
  text-overflow: clip;
}

.app-nav__link span {
  white-space: nowrap;
  -webkit-text-fill-color: currentColor;
  background-image: none;
  animation: none;
}

.app-nav__link svg {
  flex-shrink: 0;
  opacity: 0.78;
  transition: opacity 180ms ease;
}

.app-nav__link:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.app-nav__link:hover svg {
  opacity: 1;
}

.app-nav__link.router-link-active,
.app-nav__link.router-link-exact-active {
  color: #171022;
  -webkit-text-fill-color: #171022;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  box-shadow: 0 6px 20px color-mix(in srgb, var(--primary) 26%, transparent);
}

.app-nav__link.router-link-active svg,
.app-nav__link.router-link-exact-active svg {
  opacity: 1;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: nowrap;
  flex-shrink: 0;
  justify-content: flex-end;
}

.locale-switch {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 3px;
  background: rgba(20, 17, 40, 0.62);
  flex-shrink: 0;
}

.locale-switch__btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.92rem;
  font-weight: 800;
  letter-spacing: 0.04em;
  padding: 5px 10px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
  background-image: none;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  animation: none;
}

.locale-switch__btn:hover {
  color: var(--text);
}

.locale-switch__btn--active {
  background: linear-gradient(135deg, var(--primary), var(--gold));
  color: #171022;
  -webkit-text-fill-color: #171022;
}

/* header 内的 button 比全站基础规格紧凑些 */
.header-actions :deep(.button),
.header-actions :deep(.button--primary),
.header-actions :deep(.button--secondary),
.header-actions :deep(.button--ghost),
.header-actions :deep(.button--danger) {
  min-height: 36px;
  padding: 0 14px;
  font-size: 0.9rem;
}

/* user-chip 紧凑布局，匹配 nav pill 高度 */
.header-actions :deep(.user-chip) {
  padding: 3px 12px 3px 3px;
  gap: 8px;
  flex-shrink: 0;
}
.header-actions :deep(.user-chip__avatar) {
  width: 30px;
  height: 30px;
  font-size: 0.86rem;
}
.header-actions :deep(.user-chip__meta) {
  /* v13：在窄屏整组隐藏（媒体查询里覆盖），不再显示孤零零的小字 */
  display: grid;
}
.header-actions :deep(.user-chip__meta strong) {
  font-size: 0.92rem;
  line-height: 1.15;
}
.header-actions :deep(.user-chip__meta small) {
  font-size: 0.7rem;
  line-height: 1.15;
}

/* ============== 响应式渐进收缩（v14 重写） ==============
   设计逻辑：
   1. >= 1600px：全展开 — brand 文字 + 8 个完整 nav + 完整 user-chip + 按钮
   2. 1440-1600px：brand 文字（限宽省略号）+ nav 文字（限宽 8ch 省略号）+ 完整 user-chip
   3. 1280-1440px：brand 仅图标 + nav icon-only
   4. 960-1280px：brand 仅图标 + nav icon-only + user-chip 紧凑
   5.  720-960px：brand 仅图标 + nav icon-only + user-chip 仅头像 + 按钮仅图标
   6.  < 720px：最小布局，locale-switch 也藏起来
   ============================================================== */

/* 中型桌面先收起副标，保留品牌识别。 */
@media (max-width: 1380px) {
  .brand__copy small { display: none; }
}

/* 宽度不足时整体收入抽屉，避免横向截断。 */
@media (max-width: 1260px) {
  .app-nav {
    display: none !important;
  }
  .hamburger-btn {
    display: flex !important;
  }
}

/* Hamburger 按钮 — 体积小、有动效、左对齐而非右置 */
.hamburger-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  border: 1px solid rgba(216, 180, 254, 0.18);
  background: rgba(231, 224, 255, 0.04);
  color: var(--text-soft);
  cursor: pointer;
  transition: transform 160ms ease, background-color 180ms ease,
              border-color 180ms ease, color 180ms ease, box-shadow 200ms ease;
  flex-shrink: 0;
}

.hamburger-btn:hover {
  background: rgba(216, 180, 254, 0.12);
  border-color: rgba(216, 180, 254, 0.45);
  color: #fff;
  box-shadow: 0 6px 16px rgba(118, 86, 196, 0.22);
}

.hamburger-btn:active {
  transform: scale(0.92);
}

.hamburger-btn:focus-visible {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(216, 180, 254, 0.28);
}

.hamburger-btn[aria-expanded="true"] {
  background: linear-gradient(135deg, rgba(216, 180, 254, 0.32), rgba(242, 185, 92, 0.22));
  border-color: rgba(216, 180, 254, 0.55);
  color: #fff;
}

.hamburger-bar {
  transform-origin: 12px 12px;
  transition: transform 220ms cubic-bezier(0.16, 1, 0.3, 1),
              opacity 160ms ease;
}
.hamburger-btn--open .hamburger-bar--top {
  transform: translateY(5px) rotate(45deg);
}
.hamburger-btn--open .hamburger-bar--mid {
  opacity: 0;
  transform: scaleX(0.2);
}
.hamburger-btn--open .hamburger-bar--bot {
  transform: translateY(-5px) rotate(-45deg);
}

/* 响应式滑出式侧边栏 Drawer */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(7, 6, 17, 0.55);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  z-index: var(--z-drawer-backdrop);
}

.drawer-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  width: 296px;
  max-width: 84vw;
  height: 100vh;
  height: 100dvh;
  background: linear-gradient(180deg, rgba(14, 11, 30, 0.97), rgba(8, 6, 22, 0.97));
  backdrop-filter: blur(22px) saturate(150%);
  -webkit-backdrop-filter: blur(22px) saturate(150%);
  border-right: 1px solid rgba(216, 180, 254, 0.16);
  box-shadow: 18px 0 42px rgba(3, 2, 10, 0.55);
  padding: max(22px, env(safe-area-inset-top)) 18px max(22px, env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  gap: 24px;
  z-index: var(--z-drawer);
  box-sizing: border-box;
}

.drawer-sidebar__header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(216, 180, 254, 0.12);
  padding: 4px 4px 14px 4px;
}

.drawer-sidebar__logo {
  font-family: var(--font-display);
  font-size: 1.18rem;
  font-weight: 800;
  letter-spacing: -0.01em;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.drawer-sidebar__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: 8px;
  border: 1px solid rgba(216, 180, 254, 0.18);
  background: rgba(231, 224, 255, 0.04);
  color: var(--text-soft);
  cursor: pointer;
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
}

.drawer-sidebar__close:hover {
  background: rgba(216, 180, 254, 0.12);
  border-color: rgba(216, 180, 254, 0.45);
  color: #fff;
}

.drawer-sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex-grow: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px;
}

.drawer-sidebar__link {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 9px 12px;
  border-radius: 10px;
  color: rgba(245, 248, 252, 0.78);
  font-size: 0.94rem;
  font-weight: 600;
  text-decoration: none;
  transition: background-color 160ms ease, color 160ms ease,
              transform 160ms ease;
  background-image: none;
  -webkit-text-fill-color: currentColor;
  border: 1px solid transparent;
}

.drawer-sidebar__link:hover {
  background: rgba(216, 180, 254, 0.08);
  color: #fff;
  border-color: rgba(216, 180, 254, 0.16);
  transform: translateX(2px);
}

.drawer-sidebar__link.router-link-active {
  color: #1a1230;
  -webkit-text-fill-color: #1a1230;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  box-shadow: 0 6px 18px color-mix(in srgb, var(--primary) 28%, transparent);
}

.drawer-sidebar__foot {
  border-top: 1px solid rgba(216, 180, 254, 0.1);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 10px;
}

.drawer-sidebar__hint {
  margin: 0;
  font-size: 0.74rem;
  letter-spacing: 0.02em;
  color: var(--text-muted);
  text-align: center;
}

.drawer-sidebar__signout {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 44px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 109, 142, 0.25);
  background: rgba(255, 109, 142, 0.06);
  color: rgba(255, 200, 215, 0.88);
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease, border-color 160ms ease;
}

.drawer-sidebar__signout:hover {
  background: rgba(255, 109, 142, 0.16);
  border-color: rgba(255, 109, 142, 0.5);
  color: #fff;
}

:global(html.nav-drawer-open),
:global(html.nav-drawer-open body) {
  overflow: hidden;
  overscroll-behavior: none;
}

/* Transitions */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 200ms ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 260ms cubic-bezier(0.16, 1, 0.3, 1),
              opacity 220ms ease;
}
.drawer-slide-enter-active {
  box-shadow: 18px 0 48px rgba(118, 86, 196, 0.18);
}
.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(-100%);
  opacity: 0.6;
}

/* 窄屏 960px：user-chip meta 隐藏，按钮文字隐藏 */
@media (max-width: 960px) {
  .header-actions :deep(.user-chip__meta) {
    display: none;
  }
  .header-actions :deep(.user-chip) {
    padding: 3px;
  }
  .header-actions :deep(.button) span,
  .header-actions :deep(.button--secondary) span {
    display: none;
  }
  .header-actions :deep(.button),
  .header-actions :deep(.button--secondary) {
    padding: 0 10px;
    min-width: 36px;
  }
}

@media (max-width: 720px) {
  .app-header__inner {
    gap: 8px;
    padding: 8px 0;
  }
  .app-nav {
    padding: 3px;
    gap: 0;
  }
  .app-nav__link {
    padding: 8px 9px;
  }
  .app-nav__link svg {
    width: 18px;
    height: 18px;
  }
  .header-actions {
    gap: 6px;
  }
  .header-actions .locale-switch {
    /* 极窄屏：locale 切换隐藏 — 用户主要交互是导航和登出 */
    display: none;
  }
  .brand__copy {
    display: none;
  }
  .theme-switch-trigger,
  .locale-switch__btn,
  .header-actions :deep(.button),
  .header-actions :deep(.user-chip) {
    min-width: 44px;
    min-height: 44px;
  }
}

/* ============== 主题色控制下拉面板 ============== */
.theme-switch-container {
  position: relative;
  display: flex;
  align-items: center;
}
.theme-switch-trigger {
  background: transparent;
  border: none;
  color: var(--text-soft);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out-quart);
  flex-shrink: 0;
}
.theme-switch-trigger:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--primary);
}
.theme-menu-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  background: rgba(14, 18, 26, 0.85);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.5);
  min-width: 140px;
  z-index: var(--z-theme-switch);
}
.theme-menu-item {
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: var(--radius-xs);
  width: 100%;
  text-align: left;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out-quart);
  color: var(--text-soft);
  font-size: 0.84rem;
  font-weight: 500;
}
.theme-menu-item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text);
}
.theme-menu-item--active {
  background: rgba(255, 255, 255, 0.08);
  color: var(--primary);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.04);
}
.theme-color-preview {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 0 0 8px currentColor;
}
/* ============== 导航分组下拉（桌面端溢出收纳） ============== */
.app-nav__group {
  position: relative;
  flex-shrink: 0;
}

.app-nav__group-btn {
  appearance: none;
  border: none;
  background: transparent;
  font: inherit;
  cursor: pointer;
  flex-shrink: 0;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: var(--radius-full);
  color: rgba(235, 230, 247, 0.82);
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.005em;
  transition: background-color 180ms ease, color 180ms ease;
  background-image: none;
  -webkit-text-fill-color: currentColor;
}

.app-nav__group-btn:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.app-nav__group-btn--active {
  color: #171022;
  -webkit-text-fill-color: #171022;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  box-shadow: 0 6px 20px color-mix(in srgb, var(--primary) 26%, transparent);
}

.app-nav__chevron {
  flex-shrink: 0;
  opacity: 0.7;
  transition: transform 180ms ease, opacity 180ms ease;
}

.app-nav__group-btn[aria-expanded="true"] .app-nav__chevron {
  transform: rotate(180deg);
  opacity: 1;
}

.app-nav__menu {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  min-width: 178px;
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: rgba(14, 13, 30, 0.94);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: var(--radius-md);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.5);
  z-index: var(--z-theme-switch);
}

.app-nav__menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--radius-xs);
  color: rgba(235, 230, 247, 0.85);
  font-size: 0.84rem;
  font-weight: 700;
  white-space: nowrap;
  transition: background-color 160ms ease, color 160ms ease;
  background-image: none;
  -webkit-text-fill-color: currentColor;
}

.app-nav__menu-item span {
  background-image: none;
  -webkit-text-fill-color: currentColor;
}

.app-nav__menu-item:hover {
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
}

.app-nav__menu-item:hover .nav-icon {
  opacity: 1;
}

.app-nav__menu-item.router-link-active {
  color: #171022;
  -webkit-text-fill-color: #171022;
  background: linear-gradient(135deg, var(--primary), var(--gold));
}

.app-nav__menu-item.router-link-active .nav-icon {
  opacity: 1;
}

/* ============== 抽屉分组标题 ============== */
.drawer-sidebar__group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.drawer-sidebar__group + .drawer-sidebar__group {
  margin-top: 10px;
  padding-top: 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.drawer-sidebar__group-caption {
  margin: 0 0 2px;
  padding: 0 14px;
  font-size: 0.7rem;
  font-weight: 800;
  letter-spacing: 0.22em;
  color: var(--text-muted);
}

.drawer-sidebar__link .nav-icon {
  width: 18px;
  height: 18px;
}

.drawer-sidebar__link:hover .nav-icon,
.drawer-sidebar__link.router-link-active .nav-icon {
  opacity: 1;
}

.theme-menu-fade-enter-active,
.theme-menu-fade-leave-active {
  transition: opacity 160ms var(--ease-out-quart), transform 160ms var(--ease-out-quart);
}
.theme-menu-fade-enter-from,
.theme-menu-fade-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.96);
}

/* Quiet museum navigation: a catalogue rail, not a floating command centre. */
.app-header {
  border-bottom-color: rgba(244, 232, 220, 0.1);
  background: rgba(14, 11, 13, 0.94);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.app-header--scrolled {
  background: rgba(14, 11, 13, 0.98);
  border-bottom-color: rgba(214, 164, 143, 0.18);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.22);
}

.brand__mark {
  border-radius: 10px;
  background: var(--surface-strong);
  border: 1px solid var(--border-strong);
  box-shadow: none;
}

.app-nav {
  border-radius: 10px;
  padding: 3px;
  background: rgba(35, 27, 32, 0.92);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.app-nav__link {
  border-radius: 7px;
  color: var(--text-muted);
  font-weight: 500;
}

.app-nav__link:hover {
  color: var(--text);
  background: rgba(244, 232, 220, 0.07);
}

.app-nav__link.router-link-active,
.app-nav__link.router-link-exact-active {
  color: #2c211d;
  -webkit-text-fill-color: #2c211d;
  background: var(--gold);
  box-shadow: inset 0 0 0 1px rgba(255, 250, 240, 0.2);
}

.locale-switch {
  border-radius: 8px;
  padding: 2px;
  background: var(--surface-strong);
}

.locale-switch__btn {
  border-radius: 6px;
  font-weight: 500;
}

.locale-switch__btn--active {
  background: var(--gold);
  color: #2c211d;
  -webkit-text-fill-color: #2c211d;
}

.hamburger-btn {
  border-color: var(--border-strong);
  background: var(--surface-strong);
  box-shadow: none;
}

.hamburger-btn:hover,
.hamburger-btn[aria-expanded="true"] {
  background: var(--surface-elevated);
  border-color: var(--border-accent);
  color: var(--text);
  box-shadow: none;
}

.drawer-backdrop {
  background: rgba(7, 5, 7, 0.68);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}

.drawer-sidebar {
  background: var(--surface-strong);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  border-right-color: var(--border-strong);
  box-shadow: 18px 0 36px rgba(0, 0, 0, 0.32);
}

.drawer-sidebar__logo {
  background: none;
  -webkit-background-clip: initial;
  background-clip: initial;
  -webkit-text-fill-color: currentColor;
  color: var(--text);
}

.drawer-sidebar__link.router-link-active {
  color: #2c211d;
  -webkit-text-fill-color: #2c211d;
  background: var(--gold);
  box-shadow: none;
}
</style>
