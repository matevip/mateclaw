import { useI18n } from 'vue-i18n'
import type { Skill } from '@/types/index'

/**
 * RFC-042 §2.2 — locale-aware skill name resolver.
 *
 * Returns {@code nameZh} for zh-* locales, {@code nameEn} for en-* locales,
 * falling back to {@code name} (the slug) when the locale-specific column
 * is null. The slug stays the only stable identifier — these are display
 * labels only.
 */
export function useSkillName() {
  const { locale } = useI18n()

  function resolveSkillName(skill: Pick<Skill, 'name' | 'nameZh' | 'nameEn'>): string {
    const loc = String(locale.value || '').toLowerCase()
    if (loc.startsWith('zh') && skill.nameZh && skill.nameZh.trim()) return skill.nameZh
    if (loc.startsWith('en') && skill.nameEn && skill.nameEn.trim()) return skill.nameEn
    return skill.name
  }

  /** True when the resolved display name differs from the underlying slug. */
  function hasI18nName(skill: Pick<Skill, 'name' | 'nameZh' | 'nameEn'>): boolean {
    return resolveSkillName(skill) !== skill.name
  }

  return { resolveSkillName, hasI18nName }
}
