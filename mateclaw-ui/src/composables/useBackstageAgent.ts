/**
 * Shared display helpers for the Backstage page and its child components.
 * Pure formatting and classification — no API calls, no shared state.
 *
 * The parent (Backstage.vue) and the focus panel both render agent cards
 * with avatars, status rings, human sentences, and elapsed-time strings;
 * keeping these in one place avoids drift between the two surfaces when
 * a status string or ring rule changes.
 */
import { useI18n } from 'vue-i18n'
import type { BackstageRunCard, BackstageSubagentCard } from '@/api'

type AnyRun = BackstageRunCard | BackstageSubagentCard

export function useBackstageAgent() {
  const { t } = useI18n()

  function avatarLetter(run: AnyRun): string {
    const name = run.agentName || (run as any).username || (run as any).subagentId || '?'
    return name.charAt(0).toUpperCase()
  }

  /**
   * Soft, low-saturation gradient seeded from agent name. Even when an icon
   * is present we use this as the avatar surface — Apple's wallpaper-behind-
   * icon trick, not a billboard.
   */
  function avatarBgStyle(run: AnyRun) {
    const seed = run.agentName || (run as any).conversationId || (run as any).subagentId || 'x'
    let h = 0
    for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0
    const hue = Math.abs(h) % 360
    return {
      background: `linear-gradient(140deg, hsl(${hue}, 55%, 92%), hsl(${(hue + 30) % 360}, 50%, 86%))`,
      color: `hsl(${hue}, 45%, 28%)`,
    }
  }

  /**
   * Status ring classification — four moods:
   *   - thinking: alive but no first token yet (and still young) — spinner arc
   *   - stuck: explicit stuckReason — slow, saturated breathing
   *   - orphan: nobody listening — faint, almost-still
   *   - healthy: streaming or settled — gentle 3s breath
   *
   * Subagent cards lack stuck/orphan/firstToken flags, so they fall through
   * to healthy — which is what you'd want for a helper that's still around.
   */
  function ringClass(run: AnyRun): Record<string, boolean> {
    const r = run as BackstageRunCard
    if (r.stuckReason) return { 'ring-stuck': true }
    if (r.orphan) return { 'ring-orphan': true }
    if (r.firstTokenReceived === false && r.ageMs < 30_000) return { 'ring-thinking': true }
    return { 'ring-healthy': true }
  }

  function dotTitle(run: AnyRun): string {
    const r = run as BackstageRunCard
    if (r.stuckReason) return t('backstage.dotTitle.stuck')
    if (r.orphan) return t('backstage.dotTitle.orphan')
    return t('backstage.dotTitle.healthy')
  }

  function humanSentence(run: BackstageRunCard): string {
    if (run.stuckReason === 'tool_silent') return t('backstage.saying.toolSilent', { tool: run.runningToolName || t('backstage.aTool') })
    if (run.stuckReason === 'idle_silent') return t('backstage.saying.idleSilent')
    if (run.stuckReason === 'hard_cap') return t('backstage.saying.hardCap')
    if (run.currentPhase === 'awaiting_approval') return t('backstage.saying.awaitingApproval')
    if (run.runningToolName) return t('backstage.saying.usingTool', { tool: run.runningToolName })
    if (run.currentPhase === 'executing_tool') return t('backstage.saying.usingSomething')
    if (run.currentPhase === 'summarizing') return t('backstage.saying.wrappingUp')
    if (run.currentPhase === 'planning') return t('backstage.saying.planning')
    if (!run.firstTokenReceived) return t('backstage.saying.thinking')
    return t('backstage.saying.replying')
  }

  function formatAge(ms: number): string {
    if (ms < 1500) return t('backstage.time.justNow')
    const sec = Math.floor(ms / 1000)
    if (sec < 60) return t('backstage.time.seconds', { n: sec })
    const min = Math.floor(sec / 60)
    if (min < 60) return t('backstage.time.minutes', { n: min, s: sec % 60 })
    const hr = Math.floor(min / 60)
    return t('backstage.time.hours', { n: hr, m: min % 60 })
  }

  function stuckCallout(run: BackstageRunCard): string {
    if (run.stuckReason === 'tool_silent') return t('backstage.callout.toolSilent', { tool: run.runningToolName || t('backstage.aTool'), time: formatAge(run.msSinceLastEvent) })
    if (run.stuckReason === 'idle_silent') return t('backstage.callout.idleSilent', { time: formatAge(run.msSinceLastEvent) })
    return t('backstage.callout.hardCap', { time: formatAge(run.ageMs) })
  }

  return {
    avatarLetter,
    avatarBgStyle,
    ringClass,
    dotTitle,
    humanSentence,
    stuckCallout,
    formatAge,
  }
}
