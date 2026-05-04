/**
 * Structured view over an agent's free-text systemPrompt.
 *
 * Templates and the editor split the prompt into four H2 sections so the UI
 * can show "who is this employee" as a tagline on the agent card and edit
 * each part separately. The on-disk format stays a single `systemPrompt`
 * string — these helpers parse it on read and serialize it on save, so the
 * backend schema is untouched and prompts authored before this feature
 * still load (their full text falls into `extra`).
 *
 * Section markers are fixed English strings — they are an internal protocol,
 * never shown to the user. The content inside each section can be in any
 * language.
 */

export interface AgentPromptProfile {
  role: string
  goal: string
  backstory: string
  extra: string
}

const SECTION_KEYS = ['role', 'goal', 'backstory', 'extra'] as const
type SectionKey = (typeof SECTION_KEYS)[number]

const SECTION_HEADINGS: Record<SectionKey, string> = {
  role: 'Role',
  goal: 'Goal',
  backstory: 'Backstory',
  extra: 'Additional Instructions',
}

const HEADING_TO_KEY: Record<string, SectionKey> = {
  role: 'role',
  goal: 'goal',
  backstory: 'backstory',
  'additional instructions': 'extra',
}

const SECTION_HEADING_REGEX = /^##\s+(.+?)\s*$/

export function emptyProfile(): AgentPromptProfile {
  return { role: '', goal: '', backstory: '', extra: '' }
}

export function isStructuredPrompt(systemPrompt: string | null | undefined): boolean {
  if (!systemPrompt) return false
  const lines = systemPrompt.split(/\r?\n/)
  for (const line of lines) {
    const m = line.match(SECTION_HEADING_REGEX)
    if (m && HEADING_TO_KEY[m[1].trim().toLowerCase()]) return true
  }
  return false
}

/**
 * Parse a systemPrompt into role/goal/backstory/extra. The parse contract is
 * lossless: every byte of the original prompt ends up in one of the four
 * fields, so a parse → serialize round-trip never silently drops content.
 *
 * - If no recognized section markers exist, the whole prompt becomes `extra`.
 * - If recognized markers exist:
 *   - Lines before the first heading (preamble) prepend to `extra`.
 *   - Unknown `## Heading` blocks (e.g. `## Notes`, `## Examples`) keep
 *     their heading line and content and append to `extra` verbatim.
 *   - Multiple headings of the same kind concatenate (last writer wins on
 *     intent, but content is preserved).
 */
export function parsePrompt(systemPrompt: string | null | undefined): AgentPromptProfile {
  const profile = emptyProfile()
  if (!systemPrompt) return profile

  if (!isStructuredPrompt(systemPrompt)) {
    profile.extra = systemPrompt.trim()
    return profile
  }

  const lines = systemPrompt.split(/\r?\n/)
  const buffers: Record<SectionKey, string[]> = {
    role: [],
    goal: [],
    backstory: [],
    extra: [],
  }
  const preamble: string[] = []
  // Unknown sections are captured as { heading, body } pairs so we can
  // re-emit them verbatim (including their `## Heading` line) into `extra`.
  const unknownSections: { heading: string; body: string[] }[] = []

  type Bucket = { kind: 'preamble' } | { kind: 'known'; key: SectionKey } | { kind: 'unknown'; index: number }
  let bucket: Bucket = { kind: 'preamble' }

  for (const line of lines) {
    const m = line.match(SECTION_HEADING_REGEX)
    if (m) {
      const headingText = m[1].trim()
      const key = HEADING_TO_KEY[headingText.toLowerCase()]
      if (key) {
        bucket = { kind: 'known', key }
        continue
      }
      // Unknown heading — start a new captured block, keep the original
      // heading text so we can round-trip it back.
      const idx = unknownSections.length
      unknownSections.push({ heading: line, body: [] })
      bucket = { kind: 'unknown', index: idx }
      continue
    }
    if (bucket.kind === 'preamble') preamble.push(line)
    else if (bucket.kind === 'known') buffers[bucket.key].push(line)
    else unknownSections[bucket.index].body.push(line)
  }

  // Compose `extra` lossless: preamble first, then the recognized "extra"
  // section's content, then any unknown sections (keeping their headings).
  const extraParts: string[] = []
  const trimmedPreamble = preamble.join('\n').trim()
  if (trimmedPreamble) extraParts.push(trimmedPreamble)
  const trimmedExtra = buffers.extra.join('\n').trim()
  if (trimmedExtra) extraParts.push(trimmedExtra)
  for (const sec of unknownSections) {
    const body = sec.body.join('\n').trimEnd()
    extraParts.push(body ? `${sec.heading}\n${body}` : sec.heading)
  }

  profile.role = buffers.role.join('\n').trim()
  profile.goal = buffers.goal.join('\n').trim()
  profile.backstory = buffers.backstory.join('\n').trim()
  profile.extra = extraParts.join('\n\n').trim()
  return profile
}

/**
 * Serialize a profile back into a systemPrompt string. Empty sections are
 * omitted so the stored prompt stays compact.
 */
export function serializePrompt(profile: AgentPromptProfile): string {
  const parts: string[] = []
  for (const key of SECTION_KEYS) {
    const value = (profile[key] || '').trim()
    if (!value) continue
    parts.push(`## ${SECTION_HEADINGS[key]}\n${value}`)
  }
  return parts.join('\n\n')
}

/**
 * Maximum visible characters for the card tagline. CJK characters count as
 * one — the rendered string stays roughly the same width as 14 Chinese
 * characters or about 28 Latin characters.
 */
export const TAGLINE_MAX_LENGTH = 28
export const TAGLINE_CJK_BUDGET = 14

function visualLength(text: string): number {
  let len = 0
  for (const ch of text) {
    const code = ch.codePointAt(0) || 0
    // CJK Unified Ideographs / full-width punctuation count as 2 cells, ASCII as 1.
    if (
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3000 && code <= 0x30ff) ||
      (code >= 0xff00 && code <= 0xffef)
    ) {
      len += 2
    } else {
      len += 1
    }
  }
  return len
}

function truncateVisual(text: string, maxCells: number): string {
  let used = 0
  let out = ''
  for (const ch of text) {
    const code = ch.codePointAt(0) || 0
    const cells =
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3000 && code <= 0x30ff) ||
      (code >= 0xff00 && code <= 0xffef)
        ? 2
        : 1
    if (used + cells > maxCells) return out + '…'
    out += ch
    used += cells
  }
  return out
}

/**
 * Build the one-line `{role} · {goal}` tagline shown on the agent card.
 * Falls back gracefully:
 *   - role + goal     → "数据分析师 · 把数据变成洞察"
 *   - role only       → "数据分析师"
 *   - goal only       → goal (truncated)
 *   - neither + extra → first non-empty line of extra (truncated)
 *   - nothing at all  → empty string (caller decides fallback)
 */
export function deriveTagline(
  profile: AgentPromptProfile,
  fallbackDescription?: string | null,
): string {
  const role = (profile.role || '').trim().split(/\r?\n/)[0]?.trim() || ''
  const goal = (profile.goal || '').trim().split(/\r?\n/)[0]?.trim() || ''

  let tagline = ''
  if (role && goal) {
    tagline = `${role} · ${goal}`
  } else if (role) {
    tagline = role
  } else if (goal) {
    tagline = goal
  } else if ((profile.extra || '').trim()) {
    tagline = profile.extra.trim().split(/\r?\n/)[0]?.trim() || ''
  } else if (fallbackDescription && fallbackDescription.trim()) {
    tagline = fallbackDescription.trim().split(/\r?\n/)[0]?.trim() || ''
  }

  // Cap visual width so long goals can't push the card layout around.
  const cap = TAGLINE_MAX_LENGTH * 2 // visualLength counts CJK as 2
  return visualLength(tagline) > cap ? truncateVisual(tagline, cap) : tagline
}

/**
 * Used by the Goal field's live preview to colour the hint when the user
 * crosses the recommended length. Returns visual width in CJK-equivalent
 * cells so the threshold compares apples to apples regardless of script.
 */
export function taglineVisualWidth(text: string): number {
  return Math.ceil(visualLength(text) / 2)
}
