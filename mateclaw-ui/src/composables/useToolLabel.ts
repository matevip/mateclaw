import { useI18n } from 'vue-i18n'

/**
 * Title-case a raw camelCase / snake_case / kebab-case tool name.
 * Last-resort fallback for unknown tools; the i18n table is authoritative
 * for built-ins (see toolLabels in locale files).
 *
 *   wiki_search_pages → "Wiki Search Pages"
 *   delegateToAgent   → "Delegate To Agent"
 *   browser-use       → "Browser Use"
 */
export function humanizeToolName(raw: string): string {
  if (!raw) return ''
  return raw
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

type TFn = (key: string) => string
type TeFn = (key: string) => boolean

/**
 * Pure resolver — callable outside Vue setup (pass t/te from a useI18n() instance).
 * Resolution: toolLabels.<rawName> in active locale → humanizeToolName(rawName).
 */
export function resolveToolLabel(raw: string, t: TFn, te: TeFn): string {
  if (!raw) return ''
  const key = `toolLabels.${raw}`
  return te(key) ? t(key) : humanizeToolName(raw)
}

/**
 * Composable — must be called inside a Vue setup context.
 */
export function useToolLabel() {
  const { t, te } = useI18n()
  return {
    getToolLabel: (raw: string) => resolveToolLabel(raw, t, te),
  }
}
