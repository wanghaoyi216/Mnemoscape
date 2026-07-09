<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import { setLocale, SUPPORTED_LOCALES, type Locale } from '../../i18n'

const auth = useAuthStore()
const router = useRouter()
const { t, locale } = useI18n()

const initials = computed(() => auth.user?.username?.charAt(0).toUpperCase() || 'M')

const scrolled = ref(false)
const isDrawerOpen = ref(false)
const headerEl = ref<HTMLElement | null>(null)
const themeSwitchEl = ref<HTMLElement | null>(null)
let headerObserver: ResizeObserver | null = null

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
const currentTheme = ref(localStorage.getItem('mnemoscape-theme') || 'theme-mint')

const themes = [
  { id: 'theme-mint', name: '薄荷深空', color: '#36d8b4' },
  { id: 'theme-pink', name: '粉黛星河', color: '#ff6eb4' },
  { id: 'theme-gold', name: '暖阳沙漏', color: '#f2b95c' },
  { id: 'theme-blue', name: '极光深海', color: '#4fc3f7' }
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
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  handleScroll()
  publishHeaderHeight()
  if (typeof ResizeObserver !== 'undefined' && headerEl.value) {
    headerObserver = new ResizeObserver(() => publishHeaderHeight())
    headerObserver.observe(headerEl.value)
  }
  window.addEventListener('click', handleGlobalClick)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('click', handleGlobalClick)
  if (headerObserver) {
    headerObserver.disconnect()
    headerObserver = null
  }
})

function handleLogout() {
  auth.logout()
  router.push('/login')
}

function switchLocale(l: Locale) {
  setLocale(l)
}
</script>

<template>
  <header ref="headerEl" class="app-header ai-glow-edge" :class="{ 'app-header--scrolled': scrolled }">
    <div class="page-shell--wide app-header__inner">
      <RouterLink to="/" class="brand" :aria-label="t('brand.name')">
        <span class="brand__mark" aria-hidden="true" style="overflow: hidden; display: flex; align-items: center; justify-content: center;">
          <img src="/favicon.ico?v=2" alt="Logo" style="width: 100%; height: 100%; object-fit: cover;" />
        </span>
        <span class="brand__copy">
          <strong>{{ t('brand.name') }}</strong>
        </span>
      </RouterLink>

      <nav class="app-nav" :aria-label="t('nav.memories')">
        <RouterLink to="/memories" class="app-nav__link" :title="t('nav.memories')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.memories') }}</span>
        </RouterLink>
        <RouterLink to="/memories/new" class="app-nav__link" :title="t('nav.create')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.create') }}</span>
        </RouterLink>
        <RouterLink to="/memories/graph" class="app-nav__link" :title="t('nav.graph')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="2" fill="currentColor" />
            <circle cx="5"  cy="6"  r="1.4" fill="currentColor" />
            <circle cx="19" cy="7"  r="1.2" fill="currentColor" />
            <circle cx="6"  cy="18" r="1.2" fill="currentColor" />
            <circle cx="18" cy="17" r="1.4" fill="currentColor" />
            <path d="M12 12L5 6M12 12l7-5M12 12l-6 6M12 12l6 5" stroke="currentColor" stroke-width="1" opacity="0.55" />
          </svg>
          <span>{{ t('nav.graph') }}</span>
        </RouterLink>
        <RouterLink to="/memories/timeline" class="app-nav__link" :title="t('nav.timeline')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 3v18" stroke="currentColor" stroke-width="1.6" />
            <circle cx="12" cy="7"  r="2" fill="currentColor" />
            <circle cx="12" cy="13" r="2" fill="currentColor" />
            <circle cx="12" cy="19" r="2" fill="currentColor" />
            <path d="M14 7h4M6 13h4M14 19h4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.timeline') }}</span>
        </RouterLink>
        <RouterLink to="/memories/atlas" class="app-nav__link" :title="t('nav.atlas')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
            <path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18" stroke="currentColor" stroke-width="1.2" />
          </svg>
          <span>{{ t('nav.atlas') }}</span>
        </RouterLink>
        <RouterLink to="/resonance" class="app-nav__link" :title="t('nav.resonance')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="9" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
            <circle cx="15" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
          </svg>
          <span>{{ t('nav.resonance') }}</span>
        </RouterLink>
        <RouterLink to="/chat" class="app-nav__link" :title="t('nav.chat')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>{{ t('nav.chat') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="app-nav__link app-nav__link--admin" :title="t('admin.nav.entry')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="7" height="7" rx="1" />
            <rect x="14" y="3" width="7" height="7" rx="1" />
            <rect x="14" y="14" width="7" height="7" rx="1" />
            <rect x="3" y="14" width="7" height="7" rx="1" />
          </svg>
          <span>{{ t('admin.nav.entry') }}</span>
        </RouterLink>
      </nav>

      <!-- Hamburger Button for responsive drawer -->
      <button class="hamburger-btn" @click="isDrawerOpen = true" :aria-label="t('nav.menu') || 'Open menu'">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="3" y1="12" x2="21" y2="12"></line>
          <line x1="3" y1="6" x2="21" y2="6"></line>
          <line x1="3" y1="18" x2="21" y2="18"></line>
        </svg>
      </button>

      <div class="header-actions">
        <!-- 系统多主题色控制器 -->
        <div ref="themeSwitchEl" class="theme-switch-container">
          <button
            type="button"
            class="theme-switch-trigger"
            :title="t('nav.theme') || '切换系统主题'"
            @click="themeMenuOpen = !themeMenuOpen"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8"/>
              <path d="M12 3a9 9 0 0 1 0 18V3z" fill="currentColor"/>
            </svg>
          </button>
          <transition name="theme-menu-fade">
            <div v-if="themeMenuOpen" class="theme-menu-dropdown">
              <button
                v-for="themeItem in themes"
                :key="themeItem.id"
                type="button"
                class="theme-menu-item"
                :class="{ 'theme-menu-item--active': currentTheme === themeItem.id }"
                @click="changeTheme(themeItem.id)"
              >
                <span class="theme-color-preview" :style="{ background: themeItem.color, color: themeItem.color }"></span>
                <span>{{ themeItem.name }}</span>
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
            :title="t(`locale.${l}`)"
            @click="switchLocale(l)"
          >
            {{ l === 'zh-CN' ? '中' : 'EN' }}
          </button>
        </div>

        <template v-if="auth.isLoggedIn">
          <RouterLink to="/profile" class="user-chip" :title="auth.user?.username || ''">
            <span class="user-chip__avatar">{{ initials }}</span>
            <span class="user-chip__meta">
              <strong>{{ auth.user?.username || t('brand.name') }}</strong>
              <small>{{ t('profile.title') }}</small>
            </span>
          </RouterLink>
          <button class="button button--secondary" type="button" :title="t('nav.logout')" @click="handleLogout" style="display: inline-flex; align-items: center; gap: 6px;">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>
            </svg>
            <span>{{ t('nav.logout') }}</span>
          </button>
        </template>

        <template v-else>
          <RouterLink to="/login" class="button button--ghost" style="display: inline-flex; align-items: center; gap: 6px;">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3"/>
            </svg>
            <span>{{ t('login.submit') }}</span>
          </RouterLink>
          <RouterLink to="/register" class="button button--primary" style="display: inline-flex; align-items: center; gap: 6px;">
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
      <div v-if="isDrawerOpen" class="drawer-backdrop" @click="isDrawerOpen = false" />
    </Transition>
    <Transition name="drawer-slide">
      <aside v-if="isDrawerOpen" class="drawer-sidebar" role="dialog" aria-modal="true">
        <div class="drawer-sidebar__header">
          <span class="drawer-sidebar__logo">{{ t('brand.name') }}</span>
          <button class="drawer-sidebar__close" @click="isDrawerOpen = false" aria-label="Close menu">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
        
        <nav class="drawer-sidebar__nav">
          <RouterLink to="/memories" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M4 6h16M4 12h16M4 18h10" />
            </svg>
            <span>{{ t('nav.memories') }}</span>
          </RouterLink>
          <RouterLink to="/memories/new" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            <span>{{ t('nav.create') }}</span>
          </RouterLink>
          <RouterLink to="/memories/graph" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <circle cx="12" cy="12" r="2" fill="currentColor" />
              <circle cx="5"  cy="6"  r="1.4" fill="currentColor" />
              <circle cx="19" cy="7"  r="1.2" fill="currentColor" />
              <circle cx="6"  cy="18" r="1.2" fill="currentColor" />
              <circle cx="18" cy="17" r="1.4" fill="currentColor" />
              <path d="M12 12L5 6M12 12l7-5M12 12l-6 6M12 12l6 5" />
            </svg>
            <span>{{ t('nav.graph') }}</span>
          </RouterLink>
          <RouterLink to="/memories/timeline" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 3v18" />
              <circle cx="12" cy="7"  r="2" fill="currentColor" />
              <circle cx="12" cy="13" r="2" fill="currentColor" />
              <circle cx="12" cy="19" r="2" fill="currentColor" />
              <path d="M14 7h4M6 13h4M14 19h4" />
            </svg>
            <span>{{ t('nav.timeline') }}</span>
          </RouterLink>
          <RouterLink to="/memories/atlas" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18" />
            </svg>
            <span>{{ t('nav.atlas') }}</span>
          </RouterLink>
          <RouterLink to="/resonance" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <circle cx="9" cy="12" r="5" />
              <circle cx="15" cy="12" r="5" />
            </svg>
            <span>{{ t('nav.resonance') }}</span>
          </RouterLink>
          <RouterLink to="/chat" class="drawer-sidebar__link" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            <span>{{ t('nav.chat') }}</span>
          </RouterLink>
          <RouterLink v-if="auth.isAdmin" to="/admin" class="drawer-sidebar__link drawer-sidebar__link--admin" @click="isDrawerOpen = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 3 4 6v6c0 4.5 3.4 8.4 8 9 4.6-.6 8-4.5 8-9V6l-8-3z" />
              <path d="m9 12 2.2 2.2L15 10.5" />
            </svg>
            <span>{{ t('admin.nav.entry') }}</span>
          </RouterLink>
        </nav>
      </aside>
    </Transition>
  </Teleport>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  border-bottom: 1px solid transparent;
  background: rgba(8, 10, 14, 0.0);
  backdrop-filter: blur(0);
  transition: background-color 240ms ease, border-color 240ms ease, backdrop-filter 240ms ease;
}

.app-header--scrolled {
  background: rgba(8, 10, 14, 0.74);
  border-bottom-color: var(--border);
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
}

.app-header__inner {
  margin: 0 auto;
  min-height: 84px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* v11：缩短 gap 让 nav 在中屏上有更多展示空间；不再 flex-wrap，
     避免 nav 被挤到第二行后内容被遮挡。 */
  gap: 10px;
  padding: 10px 0;
  /* 让 brand / nav / actions 三块都不能撑爆容器 — flex 容器整体 min-width: 0
     允许子项各自缩 / 内部滚动。 */
  min-width: 0;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  /* v11：brand 不参与 flex 增长，但允许收缩（中屏 brand__copy 会先隐藏 small） */
  flex: 0 1 auto;
  min-width: 0;
  transition: opacity 200ms ease;
}

.brand:hover {
  opacity: 0.88;
}

.brand__mark {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--primary) 0%, var(--gold) 100%);
  color: #052017;
  box-shadow: 0 14px 28px rgba(54, 216, 180, 0.28),
              0 1px 0 rgba(255, 255, 255, 0.22) inset;
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
  font-size: 1.2rem;
  font-weight: 800;
  letter-spacing: -0.01em;
  background-image: linear-gradient(
    135deg,
    var(--primary) 0%, #7ee7c7 45%, var(--gold) 100%
  );
  background-size: 200% 200%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  animation: auroraShift 22s linear infinite;
  /* v14：单行兜底，配合 .brand__copy 的 white-space:nowrap */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  display: block;
}

.app-nav {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: rgba(14, 17, 22, 0.6);
  backdrop-filter: blur(12px);
  /* v15：让 nav 在 flex 容器里"霸占剩余空间但不溢出" — flex: 1 1 auto + min-width: 0
     是关键：parent .app-header__inner 已是 min-width:0，nav 也设 0 才能让里面的
     link/text 在容器不够时收窄。 */
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
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
  padding: 8px 12px;
  border-radius: var(--radius-full);
  /* v12：nav 用纯色，明确高对比；v13 略收一点，0.98rem 配 750 重，
     在中屏 1280-1440 区间能把 8 个完整 link 全展开。 */
  color: rgba(245, 248, 252, 0.92);
  font-size: 0.92rem;
  font-weight: 750;
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
  color: #052017;
  -webkit-text-fill-color: #052017;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  box-shadow: 0 6px 18px rgba(54, 216, 180, 0.32);
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
  background: rgba(14, 17, 22, 0.6);
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
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
  -webkit-text-fill-color: #052017;
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

/* 1600px 以下：把 brand 文字藏掉，腾出空间 */
@media (max-width: 1600px) {
  .brand__copy {
    display: none;
  }
}

/* 1480px 以下：主导航栏折叠为 Hamburger Menu 按钮 */
@media (max-width: 1480px) {
  .app-nav {
    display: none !important;
  }
  .hamburger-btn {
    display: flex;
  }
}

/* Hamburger 按钮 */
.hamburger-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: rgba(14, 17, 22, 0.45);
  color: var(--text-soft);
  cursor: pointer;
  transition: all 180ms ease;
  flex-shrink: 0;
}

.hamburger-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.2);
  color: var(--primary);
}

/* 响应式滑出式侧边栏 Drawer */
.drawer-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  z-index: var(--z-drawer-backdrop);
}

.drawer-sidebar {
  position: fixed;
  top: 0;
  right: 0;
  width: 290px;
  max-width: 85vw;
  height: 100vh;
  background: rgba(10, 12, 16, 0.95);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-left: 1px solid var(--border);
  box-shadow: -10px 0 30px rgba(0, 0, 0, 0.65);
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 28px;
  z-index: var(--z-drawer);
  box-sizing: border-box;
}

.drawer-sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  padding-bottom: 16px;
}

.drawer-sidebar__logo {
  font-family: var(--font-display);
  font-size: 1.22rem;
  font-weight: 800;
  letter-spacing: -0.01em;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.drawer-sidebar__close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--text-soft);
  cursor: pointer;
  transition: all 180ms ease;
}

.drawer-sidebar__close:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.2);
}

.drawer-sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-grow: 1;
  overflow-y: auto;
}

.drawer-sidebar__link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  color: rgba(245, 248, 252, 0.82);
  font-size: 0.95rem;
  font-weight: 700;
  text-decoration: none;
  transition: all 180ms ease;
  background-image: none;
  -webkit-text-fill-color: currentColor;
}

.drawer-sidebar__link:hover {
  background: rgba(255, 255, 255, 0.04);
  color: #fff;
}

.drawer-sidebar__link.router-link-active {
  color: #052017;
  -webkit-text-fill-color: #052017;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  box-shadow: 0 4px 12px rgba(54, 216, 180, 0.28);
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
  transition: transform 250ms cubic-bezier(0.16, 1, 0.3, 1);
}
.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(100%);
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
  .locale-switch {
    /* 极窄屏：locale 切换隐藏 — 用户主要交互是导航和登出 */
    display: none;
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
.theme-menu-fade-enter-active,
.theme-menu-fade-leave-active {
  transition: opacity 160ms var(--ease-out-quart), transform 160ms var(--ease-out-quart);
}
.theme-menu-fade-enter-from,
.theme-menu-fade-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.96);
}
</style>
