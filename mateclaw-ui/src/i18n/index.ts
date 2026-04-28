import { createI18n } from 'vue-i18n'
import { ref } from 'vue'
import { settingsApi } from '@/api'

export type AppLocale = 'zh-CN' | 'en-US'

const STORAGE_KEY = 'mateclaw_locale'
const DEFAULT_LOCALE: AppLocale = 'zh-CN'

export const currentLocale = ref<AppLocale>(DEFAULT_LOCALE)

export const i18n = createI18n({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: {} as Record<AppLocale, any>,
})

const loadedLocales = new Set<AppLocale>()

// Each locale dictionary is ~78KB. Splitting them into their own chunks keeps
// the entry bundle ~150KB lighter — only the active locale is fetched on cold
// start, the other one only when the user switches language.
async function loadLocaleMessages(locale: AppLocale) {
  if (loadedLocales.has(locale)) return
  const messages = locale === 'zh-CN'
    ? (await import('./locales/zh-CN')).default
    : (await import('./locales/en-US')).default
  i18n.global.setLocaleMessage(locale, messages)
  loadedLocales.add(locale)
}

function normalizeLocale(locale?: string | null): AppLocale {
  if (locale === 'en' || locale === 'en-US') {
    return 'en-US'
  }
  return 'zh-CN'
}

export async function applyLocale(locale?: string | null) {
  const normalized = normalizeLocale(locale)
  // Must finish loading messages before flipping currentLocale, otherwise the
  // first render after a switch would show the i18n keys verbatim.
  await loadLocaleMessages(normalized)
  currentLocale.value = normalized
  i18n.global.locale.value = normalized
  localStorage.setItem(STORAGE_KEY, normalized)
  return normalized
}

export async function initializeLocale() {
  try {
    const res: any = await settingsApi.getLanguage()
    return await applyLocale(res.data)
  } catch {
    return await applyLocale(localStorage.getItem(STORAGE_KEY))
  }
}
