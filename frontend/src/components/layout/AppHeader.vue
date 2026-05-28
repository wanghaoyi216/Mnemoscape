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
const headerEl = ref<HTMLElement | null>(null)
let headerObserver: ResizeObserver | null = null

function handleScroll() {
  scrolled.value = window.scrollY > 8
}

/** 把实时 header 高度写到 :root --app-header-h，让需要让位的页面（atlas / scene 等）
 *  通过 var(--app-header-h, 88px) 拿到精确数值，避免在 1100px 以下 nav wrap 后被压住。 */
function publishHeaderHeight() {
  const el = headerEl.value
  if (!el) return
  const h = Math.round(el.getBoundingClientRect().height)
  if (h > 0) {
    document.documentElement.style.setProperty('--app-header-h', `${h}px`)
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
  window.addEventListener('resize', publishHeaderHeight)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('resize', publishHeaderHeight)
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
        <span class="brand__mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none">
            <path d="M4 18V6l8 6 8-6v12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <span class="brand__copy">
          <strong>{{ t('brand.name') }}</strong>
          <small>{{ t('brand.subtitle') }}</small>
        </span>
      </RouterLink>

      <nav class="app-nav" :aria-label="t('nav.memories')">
        <RouterLink to="/memories" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.memories') }}</span>
        </RouterLink>
        <RouterLink to="/memories/new" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.create') }}</span>
        </RouterLink>
        <RouterLink to="/memories/graph" class="app-nav__link">
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
        <RouterLink to="/memories/timeline" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 3v18" stroke="currentColor" stroke-width="1.6" />
            <circle cx="12" cy="7"  r="2" fill="currentColor" />
            <circle cx="12" cy="13" r="2" fill="currentColor" />
            <circle cx="12" cy="19" r="2" fill="currentColor" />
            <path d="M14 7h4M6 13h4M14 19h4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
          <span>{{ t('nav.timeline') }}</span>
        </RouterLink>
        <RouterLink to="/memories/atlas" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
            <path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18" stroke="currentColor" stroke-width="1.2" />
          </svg>
          <span>{{ t('nav.atlas') }}</span>
        </RouterLink>
        <RouterLink to="/resonance" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <circle cx="9" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
            <circle cx="15" cy="12" r="5" stroke="currentColor" stroke-width="1.8" />
          </svg>
          <span>{{ t('nav.resonance') }}</span>
        </RouterLink>
        <RouterLink to="/chat" class="app-nav__link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>{{ t('nav.chat') }}</span>
        </RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="app-nav__link app-nav__link--admin">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
            <path d="M12 3 4 6v6c0 4.5 3.4 8.4 8 9 4.6-.6 8-4.5 8-9V6l-8-3z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            <path d="m9 12 2.2 2.2L15 10.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>{{ t('admin.nav.entry') }}</span>
        </RouterLink>
      </nav>

      <div class="header-actions">
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
          <RouterLink to="/profile" class="user-chip">
            <span class="user-chip__avatar">{{ initials }}</span>
            <span class="user-chip__meta">
              <strong>{{ auth.user?.username || t('brand.name') }}</strong>
              <small>{{ t('profile.title') }}</small>
            </span>
          </RouterLink>
          <button class="button button--secondary" type="button" @click="handleLogout">
            <span>{{ t('nav.logout') }}</span>
          </button>
        </template>

        <template v-else>
          <RouterLink to="/login" class="button button--ghost">{{ t('login.submit') }}</RouterLink>
          <RouterLink to="/register" class="button button--primary">{{ t('register.submit') }}</RouterLink>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 40;
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
  min-height: 88px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 0;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 14px;
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
}

.brand__copy strong {
  font-family: var(--font-display);
  font-size: 1.1rem;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand__copy small {
  color: var(--text-muted);
  font-size: 0.74rem;
  letter-spacing: 0.02em;
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
  /* 当容器宽度紧张时允许内部水平滚动，避免链接被挤压触发文字断行。
     正常宽屏下 max-content 永远不超过容器，滚动条不出现。 */
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
}

.app-nav::-webkit-scrollbar {
  display: none;
}

.app-nav__link {
  /* flex-shrink:0 + nowrap 是核心修复 ——
     防止浏览器在文字内部断行（中文 nav 被切成"我的记/忆"的根因）。 */
  flex-shrink: 0;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--radius-full);
  color: var(--text-muted);
  font-size: 0.84rem;
  font-weight: 500;
  letter-spacing: 0.005em;
  transition: background-color 180ms ease, color 180ms ease;
}

.app-nav__link span {
  /* 双保险：让 span 内文字也不参与换行。 */
  white-space: nowrap;
}

.app-nav__link svg {
  flex-shrink: 0;
  opacity: 0.7;
  transition: opacity 180ms ease;
}

.app-nav__link:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.04);
}

.app-nav__link:hover svg {
  opacity: 1;
}

.app-nav__link.router-link-active {
  color: #052017;
  background: linear-gradient(135deg, var(--primary), #b6f077);
  box-shadow: 0 6px 18px rgba(54, 216, 180, 0.32);
}

.app-nav__link.router-link-active svg {
  opacity: 1;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.locale-switch {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 3px;
  background: rgba(14, 17, 22, 0.6);
}

.locale-switch__btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 5px 10px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.locale-switch__btn:hover {
  color: var(--text);
}

.locale-switch__btn--active {
  background: linear-gradient(135deg, var(--primary), #b6f077);
  color: #052017;
}



/* ---- Brand: 副标题在中屏就藏起来，把宽度让给 nav ---- */
@media (max-width: 1280px) {
  .brand__copy small {
    display: none;
  }
}

/* ---- 中屏断点提早到 1280px：八个 nav 项 + 中文 + 英文 admin 入口 ----
   都在中屏笔记本上撞到 1100px 的旧断点之前就挤不下，所以直接在 1280px
   把 nav wrap 到第二行，避免再走那段"凑合挤一行"的丑相。 */
@media (max-width: 1280px) {
  .app-header__inner {
    flex-wrap: wrap;
    padding-top: 14px;
    padding-bottom: 14px;
  }

  .app-nav {
    order: 3;
    width: 100%;
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .app-nav {
    width: 100%;
    justify-content: flex-start;
  }

  .header-actions {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
