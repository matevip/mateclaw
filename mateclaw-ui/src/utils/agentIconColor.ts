/**
 * Per-role accent color for digital-employee icons.
 *
 * The pixelarticons SVGs render with `fill="currentColor"`, so wrapping
 * the icon in an element with a `color: ...` style tints the glyph
 * without touching the SVG itself. We only use this in agent contexts
 * (cards, picker, chat header) so skills / tools keep their default
 * neutral color.
 *
 * The palette is tuned to MateClaw's warm/earthy brand — every entry sits
 * around 45-55% lightness with mid saturation, so colors stay distinct
 * but live in the same room as the rust-orange primary, instead of
 * competing with it.
 */

const BRAND_FALLBACK = 'var(--mc-primary)'

/** icon name (without `pi:` prefix) → CSS color */
const ICON_COLOR_MAP: Record<string, string> = {
  // Engineering / inspection — rosewood, sits next to the brand rust
  'bug': 'hsl(8, 62%, 50%)',
  'search': 'hsl(8, 62%, 50%)',

  // Research / writing — sage green, calm, "library" feel
  'book-open': 'hsl(155, 32%, 42%)',
  'notes': 'hsl(285, 30%, 50%)',
  'article': 'hsl(155, 32%, 42%)',

  // Data / analytics — dusk blue
  'chart-bar-big': 'hsl(212, 45%, 48%)',
  'chart': 'hsl(212, 45%, 48%)',
  'analytics': 'hsl(212, 45%, 48%)',

  // Customer / support — terracotta, warm and approachable
  'headphone': 'hsl(20, 68%, 50%)',
  'message': 'hsl(20, 68%, 50%)',
  'message-text': 'hsl(20, 68%, 50%)',

  // General / friendly assistants — warm amber
  'robot-face-happy': 'hsl(38, 72%, 50%)',
  'robot-face': 'hsl(38, 72%, 50%)',
  'robot': 'hsl(38, 72%, 50%)',

  // System / infrastructure — slate teal
  'cpu': 'hsl(195, 28%, 42%)',
  'cloud': 'hsl(195, 28%, 42%)',

  // Planning / task — indigo
  'clipboard-note': 'hsl(232, 38%, 52%)',
  'clipboard': 'hsl(232, 38%, 52%)',
  'list-box': 'hsl(232, 38%, 52%)',
  'checkbox-on': 'hsl(232, 38%, 52%)',
}

/**
 * Return the accent color for a stored icon string. Returns the brand
 * primary as a CSS variable for emoji / URL / unknown icons so callers
 * can apply the color unconditionally.
 */
export function agentIconColor(iconValue: string | null | undefined): string {
  if (!iconValue) return BRAND_FALLBACK
  const v = iconValue.trim()
  if (!v.startsWith('pi:')) return BRAND_FALLBACK
  const name = v.slice(3)
  return ICON_COLOR_MAP[name] || BRAND_FALLBACK
}
