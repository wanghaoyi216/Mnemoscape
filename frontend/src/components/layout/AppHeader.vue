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
          <RouterLink to="/profile" class="user-chip" :title="auth.user?.username || ''">
            <span class="user-chip__avatar">{{ initials }}</span>
            <span class="user-chip__meta">
              <strong>{{ auth.user?.username || t('brand.name') }}</strong>
              <small>{{ t('profile.title') }}</small>
            </span>
          </RouterLink>
          <button class="button button--secondary" type="button" :title="t('nav.logout')" @click="handleLogout">
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
  min-height: 84px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* v11：缩短 gap 让 nav 在中屏上有更多展示空间；不再 flex-wrap，
     避免 nav 被挤到第二行后内容被遮挡。 */
  gap: 14px;
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
}

.brand__copy strong {
  font-family: var(--font-display);
  /* v13：brand 主标题；副标题已删除（template 里不再渲染）。 */
  font-size: 1.32rem;
  font-weight: 800;
  letter-spacing: -0.01em;
  background-image: linear-gradient(
    100deg,
    #ff5f6d 0%, #ffb86c 16%, #f7e96b 32%, #58e36a 48%,
    #4ecde6 64%, #5b8def 80%, #b06bff 96%, #ff5f6d 100%
  );
  background-size: 220% 220%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  animation: auroraShift 6s linear infinite;
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
  /* v13：nav 占用剩余空间，但绝不溢出滚动 — overflow 设为 hidden 让多余内容
     被截断而不是出现"管"那种半字，配合 media query 在窄屏直接切到 icon-only 模式。 */
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  flex-shrink: 1;
}

.app-nav__link {
  /* flex-shrink:0 + nowrap 是核心修复：防止文字被切成"我的记/忆"。 */
  flex-shrink: 0;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 9px 14px;
  border-radius: var(--radius-full);
  /* v12：nav 用纯色，明确高对比；v13 略收一点，0.98rem 配 750 重，
     在中屏 1280-1440 区间能把 8 个完整 link 全展开。 */
  color: rgba(245, 248, 252, 0.92);
  font-size: 0.98rem;
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

/* ============== 响应式渐进收缩（v13 重写：避免半字截断 / 孤零头像） ==============
   设计逻辑：
   1. >= 1440px：全展开 — brand 文字 + 8 个完整 nav + 完整 user-chip + 按钮
   2. 1200-1440px：brand 仅图标 + 8 个完整 nav + 完整 user-chip + 按钮
   3.  960-1200px：brand 仅图标 + nav 折叠为 icon-only（每个 link 文字隐藏，
      仅显示 SVG icon + tooltip）+ 完整 user-chip + 按钮
   4.  720-960px：brand 仅图标 + nav icon-only + user-chip 仅头像（meta 隐藏）
      + 按钮文字隐藏（保留底色）
   5.  < 720px：brand 仅图标（mark）+ nav icon-only 收紧 + 头像 + 退出按钮
================================================================== */

/* 中大屏：brand 副标题（已 template 删除）+ 主标题正常 */
@media (max-width: 1440px) {
  .brand__copy {
    display: none;
  }
}

/* 中屏：nav 折叠为 icon-only，避免文字被半截切（当前问题"管理面板"→"管"） */
@media (max-width: 1200px) {
  .app-nav__link {
    padding: 9px 11px;
    gap: 0;
  }
  .app-nav__link span {
    /* 完全隐藏文字 — 用 display: none 而非 width: 0 才不会留 padding */
    display: none;
  }
  .app-nav__link svg {
    width: 20px;
    height: 20px;
    opacity: 0.92;
  }
  .app-nav__link.router-link-active svg,
  .app-nav__link.router-link-exact-active svg {
    opacity: 1;
  }
}

/* 窄屏：user-chip meta 隐藏（只剩头像），按钮文字隐藏（只剩 icon） */
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
</style>
