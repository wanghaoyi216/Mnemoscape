<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AppHeader from './components/layout/AppHeader.vue'
import AiMascotDock from './components/ai/AiMascotDock.vue'
import CustomerSupportWidget from './components/support/CustomerSupportWidget.vue'
import ToastContainer from './components/common/ToastContainer.vue'
import WeatherFxOverlay from './components/layout/WeatherFxOverlay.vue'
import AmbientFilmStrip from './components/layout/AmbientFilmStrip.vue'
import { useHealthCheck } from './composables/useHealthCheck'
import { useDynamicMedia } from './composables/useDynamicMedia'
import { useAuthStore } from './stores/auth'

const { t, locale } = useI18n()
const { isOffline, setOffline } = useHealthCheck()
const auth = useAuthStore()
const year = new Date().getFullYear()
const route = useRoute()

// 启动即拉一次 asset-service 的资源清单（本地 + MinIO），
// 让登录页 / 列表 / AI / 图谱拿到的不是空数组
const dynamicMedia = useDynamicMedia()

onMounted(() => {
  if (auth.isLoggedIn && !auth.user) {
    void auth.fetchProfile()
  }
  // 登录页已使用本地编号素材；登录后再拉动态资源，避免后端未启动时首屏出现无关请求。
  if (auth.isLoggedIn) void dynamicMedia.refresh()

  // 读取并应用用户上次保存的主题色
  const savedTheme = localStorage.getItem('mnemoscape-theme') || 'theme-mint'
  document.documentElement.classList.add(savedTheme)
})
</script>

<template>
  <div class="app-shell">
    <div class="app-shell__ambient" aria-hidden="true"></div>
    <div class="app-shell__grain" aria-hidden="true"></div>
    <div class="app-shell__aura" aria-hidden="true"></div>
    <div class="app-shell__frame ai-glow-border ai-glow-border--static ai-glow-border--intense" aria-hidden="true"></div>
    <transition name="banner">
      <div v-if="isOffline" class="infra-offline-banner" role="alert">
        <div class="infra-offline-banner__content">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" class="infra-offline-banner__icon">
            <path d="M12 9v4m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span v-if="locale === 'zh-CN'">
            后端请求失败：可能是本机 api-gateway 未启动，或本机微服务未加载工位机环境变量，或工位机 Docker 中间件未运行。请按顺序自检：①
            <code>Test-NetConnection 100.66.166.46 -Port 8848</code> 验证 Tailscale；② 工位机执行
            <code>docker compose -f scripts/remote/docker-compose.remote.yml up -d</code>；③ 本机在同一 PowerShell 中先
            <code>. .\scripts\remote\Use-LocalDev-WorkpcInfra.ps1</code> 再启动 Spring Boot 服务。
          </span>
          <span v-else>
            Backend request failed. Likely causes: local api-gateway not running, local Spring Boot services missing workstation env vars, or workstation Docker infra not up. Check in order: ①
            <code>Test-NetConnection 100.66.166.46 -Port 8848</code> for Tailscale; ② on workstation run
            <code>docker compose -f scripts/remote/docker-compose.remote.yml up -d</code>; ③ on laptop, in the same PowerShell window, dot-source
            <code>.\scripts\remote\Use-LocalDev-WorkpcInfra.ps1</code> before starting Spring Boot services.
          </span>
          <button class="infra-offline-banner__btn" @click="setOffline(false)">✕</button>
        </div>
      </div>
    </transition>
    <AppHeader />
    <main class="app-shell__main">
      <router-view v-slot="{ Component, route }">
        <transition name="page" mode="out-in">
          <component :is="Component" :key="route.fullPath" />
        </transition>
      </router-view>
    </main>
    <AmbientFilmStrip v-if="auth.isLoggedIn" />
    <AiMascotDock v-if="auth.isLoggedIn" />
    <CustomerSupportWidget v-if="auth.isLoggedIn" />
    <ToastContainer />
    <!-- 16+ 环境特效层（登录后启用，避免与登录页 WebGL 背景冲突） -->
    <WeatherFxOverlay v-if="auth.isLoggedIn" />
    <footer v-if="route.name !== 'MemoryAtlas'" class="app-footer" role="contentinfo">
      <div class="page-shell app-footer__inner">
        <div class="app-footer__brand">
          <span class="app-footer__mark" aria-hidden="true">M</span>
          <span class="app-footer__label">
            <strong>{{ t('brand.name') }}</strong>
            <small>{{ t('brand.subtitle') }} · {{ t('brand.version') }}</small>
          </span>
        </div>
        <p class="app-footer__legal">
          {{ t('brand.footer') }} · © {{ year }} {{ t('brand.name') }}
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.page-enter-active,
.page-leave-active {
  transition: opacity 240ms cubic-bezier(0.165, 0.84, 0.44, 1),
              transform 240ms cubic-bezier(0.165, 0.84, 0.44, 1);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.app-footer {
  border-top: 1px solid var(--border);
  margin-top: 80px;
  background: rgba(8, 10, 14, 0.7);
  backdrop-filter: blur(20px);
}

.app-footer__inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  padding: 28px 0;
  flex-wrap: wrap;
}

.app-footer__brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.app-footer__mark {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-xs);
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--primary), var(--gold));
  color: #052017;
  font-weight: 800;
  font-size: 0.92rem;
}

.app-footer__label {
  display: grid;
  gap: 1px;
}

.app-footer__label strong {
  font-size: 0.92rem;
  letter-spacing: 0.01em;
}

.app-footer__label small {
  font-size: 0.74rem;
  color: var(--text-muted);
  font-family: var(--font-mono);
}

.app-footer__legal {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-muted);
  letter-spacing: 0.01em;
}

@media (max-width: 768px) {
  .app-footer__inner {
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
  }
}

/* Infrastructure Offline Banner */
.infra-offline-banner {
  background: linear-gradient(90deg, rgba(239, 111, 122, 0.95), rgba(242, 185, 92, 0.95));
  color: #050608;
  font-weight: 500;
  font-size: 0.88rem;
  padding: 10px 24px;
  position: sticky;
  top: 0;
  z-index: 9999;
  box-shadow: 0 4px 20px rgba(239, 111, 122, 0.25);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.15);
}

.infra-offline-banner__content {
  max-width: var(--page-width-wide);
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.infra-offline-banner__icon {
  flex-shrink: 0;
}

.infra-offline-banner__btn {
  margin-left: auto;
  background: transparent;
  color: #050608;
  border: none;
  font-size: 1.1rem;
  font-weight: 700;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0.8;
  transition: opacity 0.2s ease;
}

.infra-offline-banner__btn:hover {
  opacity: 1;
}

/* Banner animations */
.banner-enter-active,
.banner-leave-active {
  transition: all 300ms cubic-bezier(0.165, 0.84, 0.44, 1);
}

.banner-enter-from,
.banner-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
