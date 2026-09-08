import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

const STORAGE_KEY = 'mnemoscape:locale'
const SUPPORTED = ['zh-CN', 'en-US'] as const
export type Locale = (typeof SUPPORTED)[number]

function detectInitialLocale(): Locale {
  const stored = (typeof window !== 'undefined') ? window.localStorage.getItem(STORAGE_KEY) : null
  if (stored && (SUPPORTED as readonly string[]).includes(stored)) {
    return stored as Locale
  }
  // 主理人要求默认中文展示，但保留切换英文的能力 — 不照搬 navigator.language。
  return 'zh-CN'
}

const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: detectInitialLocale(),
  fallbackLocale: 'en-US',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
  // 避免在控制台疯狂打缺 key 警告 — 已 fallback 到英文。
  missingWarn: false,
  fallbackWarn: false,
})

export function setLocale(locale: Locale) {
  i18n.global.locale.value = locale
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, locale)
    document.documentElement.lang = locale
  }
}

if (typeof document !== 'undefined') {
  document.documentElement.lang = i18n.global.locale.value
}

export const SUPPORTED_LOCALES = SUPPORTED
export default i18n
