import { Marked } from 'marked'
import type { Tokens } from 'marked'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'

// ---------------------------------------------------------------------------
// Language metadata
// ---------------------------------------------------------------------------
const LANG_DISPLAY: Record<string, string> = {
  js: 'JavaScript', javascript: 'JavaScript', ts: 'TypeScript', typescript: 'TypeScript',
  py: 'Python', python: 'Python', java: 'Java', kt: 'Kotlin', kotlin: 'Kotlin',
  go: 'Go', rust: 'Rust', rs: 'Rust', rb: 'Ruby', ruby: 'Ruby',
  cpp: 'C++', c: 'C', cs: 'C#', csharp: 'C#', swift: 'Swift',
  sh: 'Shell', bash: 'Bash', zsh: 'Zsh', shell: 'Shell',
  sql: 'SQL', html: 'HTML', css: 'CSS', scss: 'SCSS', less: 'LESS',
  json: 'JSON', xml: 'XML', yaml: 'YAML', yml: 'YAML', toml: 'TOML',
  md: 'Markdown', markdown: 'Markdown', dockerfile: 'Dockerfile',
  vue: 'Vue', jsx: 'JSX', tsx: 'TSX', php: 'PHP', lua: 'Lua',
}

const KNOWN_LANGS = [
  'typescript', 'javascript', 'python', 'kotlin', 'csharp', 'dockerfile',
  'markdown', 'shell', 'swift', 'rust', 'ruby', 'bash', 'scss', 'less',
  'yaml', 'toml', 'html', 'java', 'json', 'css', 'cpp', 'xml', 'vue',
  'jsx', 'tsx', 'php', 'lua', 'sql', 'zsh', 'yml', 'go', 'kt', 'rs',
  'rb', 'cs', 'ts', 'js', 'py', 'sh', 'md', 'c',
]

function extractLang(raw: string): string {
  if (!raw) return ''
  const lower = raw.toLowerCase()
  if (hljs.getLanguage(lower)) return lower
  for (const lang of KNOWN_LANGS) {
    if (lower.startsWith(lang) && lower.length > lang.length) return lang
  }
  return lower
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// ---------------------------------------------------------------------------
// Code block thresholds (must match `useMarkdownRenderer` doc comments)
// ---------------------------------------------------------------------------
/** Lines >= this trigger collapsible <details> wrap. */
const COLLAPSE_LINE_THRESHOLD = 20
/** JSON blob char count >= this triggers collapse even when line count is low. */
const COLLAPSE_JSON_CHAR_THRESHOLD = 800

// ---------------------------------------------------------------------------
// Link safety
// ---------------------------------------------------------------------------
/**
 * Scheme whitelist. Only http(s), mailto, fragment, and same-origin paths
 * (absolute `/...`, relative `./...` / `../...`) are permitted. Everything
 * else (javascript:, data:, vbscript:, file:, …) is degraded to plain text.
 */
const SAFE_LINK_RE = /^(https?:|mailto:|#|\/|\.\/|\.\.\/)/i

// ---------------------------------------------------------------------------
// LaTeX pre-processor
// ---------------------------------------------------------------------------
// `$$ ... $$` (block) and `$ ... $` (inline) are extracted from raw markdown
// and replaced with HTML placeholders that survive marked + DOMPurify. The
// post-render KaTeX composable (useKatexRenderer) finds them by class +
// data-tex attribute and mounts the typeset output.
//
// We deliberately walk the source character-by-character rather than running
// a global regex, so that fenced/inline code blocks are skipped — otherwise
// dollar signs inside Bash snippets or JSON blobs would be misinterpreted.

function preprocessLatex(text: string): string {
  let out = ''
  let i = 0
  let inFence = false
  let fenceMarker = ''
  while (i < text.length) {
    // Detect fence open/close at line start.
    if (i === 0 || text[i - 1] === '\n') {
      const fenceMatch = /^(```+|~~~+)([^\n]*)/.exec(text.slice(i))
      if (fenceMatch) {
        const marker = fenceMatch[1]
        if (!inFence) {
          inFence = true
          fenceMarker = marker
        } else if (marker.length >= fenceMarker.length && marker[0] === fenceMarker[0]) {
          inFence = false
          fenceMarker = ''
        }
        out += fenceMatch[0]
        i += fenceMatch[0].length
        continue
      }
    }
    if (inFence) {
      out += text[i++]
      continue
    }

    // Inline code: copy verbatim until the matching backtick run.
    if (text[i] === '`') {
      let n = 0
      while (text[i + n] === '`') n++
      const tickRun = '`'.repeat(n)
      const close = text.indexOf(tickRun, i + n)
      if (close < 0) {
        // Unmatched — treat the rest as text but still advance past the ticks.
        out += text[i++]
        continue
      }
      out += text.slice(i, close + n)
      i = close + n
      continue
    }

    // LaTeX-style block math: \[...\]  — must be checked BEFORE marked sees
    // the source, because CommonMark eats the backslash escape (`\[ → [`)
    // and the marker would be lost. LLMs (DeepSeek, Qwen, Claude) emit this
    // form heavily for display equations.
    if (text[i] === '\\' && text[i + 1] === '[') {
      const close = text.indexOf('\\]', i + 2)
      // Bound length so a stray `\[` doesn't swallow the rest of the doc.
      if (close > 0 && close - i < 800) {
        const tex = text.slice(i + 2, close)
        out += `\n\n<div class="katex-block" data-tex="${encodeURIComponent(tex)}"></div>\n\n`
        i = close + 2
        continue
      }
    }
    // LaTeX-style inline math: \(...\)
    if (text[i] === '\\' && text[i + 1] === '(') {
      const close = text.indexOf('\\)', i + 2)
      if (close > 0 && close - i < 400) {
        const tex = text.slice(i + 2, close)
        out += `<span class="katex-inline" data-tex="${encodeURIComponent(tex)}"></span>`
        i = close + 2
        continue
      }
    }
    // Block math: $$...$$
    if (text[i] === '$' && text[i + 1] === '$') {
      const close = text.indexOf('$$', i + 2)
      if (close > 0) {
        const tex = text.slice(i + 2, close)
        // Wrap in newlines so marked treats the placeholder as its own block,
        // not glued onto a surrounding paragraph (which would make <div> a
        // direct child of <p> — invalid HTML the browser silently splits).
        out += `\n\n<div class="katex-block" data-tex="${encodeURIComponent(tex)}"></div>\n\n`
        i = close + 2
        continue
      }
    }
    // Inline math: $...$  — require non-whitespace adjacent to the dollars
    // so that "$5.99" or "saved $10" are NOT treated as math.
    if (text[i] === '$') {
      const m = /^\$([^$\n]+?)\$(?!\d)/.exec(text.slice(i))
      if (m && !/^\s/.test(m[1]) && !/\s$/.test(m[1])) {
        const tex = m[1]
        out += `<span class="katex-inline" data-tex="${encodeURIComponent(tex)}"></span>`
        i += m[0].length
        continue
      }
    }

    out += text[i++]
  }
  return out
}

// ---------------------------------------------------------------------------
// Custom renderer (marked v15 requires a plain object — class instances are
// NOT dispatched).
// ---------------------------------------------------------------------------
const customRenderer = {
  code({ text, lang }: { type: string; raw: string; text: string; lang?: string }): string {
    const rawCode = text || ''
    const infoStr = (lang || '').split(/\s/)[0]

    // Mermaid: ship raw source through a placeholder div for the
    // useMermaidRenderer post-mount step. Skip syntax highlighting entirely.
    if (infoStr === 'mermaid') {
      return `<div class="mermaid-block" data-mermaid="${encodeURIComponent(rawCode)}"></div>`
    }

    // ECharts: same pattern, mounted by useEChartsRenderer.
    if (infoStr === 'echarts') {
      return `<div class="echarts-block" data-echarts-option="${encodeURIComponent(rawCode)}"></div>`
    }

    const detectedLang = extractLang(infoStr)
    const hasLanguage = !!detectedLang && !!hljs.getLanguage(detectedLang)

    let highlighted: string
    try {
      highlighted = hasLanguage
        ? hljs.highlight(rawCode, { language: detectedLang }).value
        : hljs.highlightAuto(rawCode).value
    } catch {
      highlighted = escapeHtml(rawCode)
    }

    const langLabel = LANG_DISPLAY[detectedLang] || detectedLang || 'Code'
    const encodedCode = encodeURIComponent(rawCode)
    const langClass = hasLanguage ? ` language-${detectedLang}` : ''

    // Split into one <li> per source line so CSS counter renders the gutter.
    // We trim a trailing empty line if highlight.js produced one (common when
    // the user's fenced block ends with a newline), to avoid a blank tail row.
    const rawLines = highlighted.split('\n')
    if (rawLines.length && rawLines[rawLines.length - 1] === '') rawLines.pop()
    const lineCount = rawLines.length || 1
    const linesHtml = `<ol class="hljs-lines">${rawLines.map(l => `<li>${l || ' '}</li>`).join('')}</ol>`

    const isJson = detectedLang === 'json'
    const isLongJson = isJson && rawCode.length >= COLLAPSE_JSON_CHAR_THRESHOLD
    const shouldCollapse = lineCount >= COLLAPSE_LINE_THRESHOLD || isLongJson
    // Default-open for normal long code (the user wants to see it; the
    // collapsible header is just an opt-in fold). Default-closed only for
    // giant JSON blobs, which are typically noisy tool-call output.
    const openByDefault = !isLongJson

    const headerHtml = `<div class="code-block__header">`
      + `<span class="code-block__lang">${escapeHtml(langLabel)}</span>`
      + `<span class="code-block__lines">${lineCount} lines</span>`
      + `<button class="code-block__copy" type="button" data-code="${encodedCode}" aria-label="Copy code">`
      + `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
      + `<span class="code-block__copy-text">Copy</span>`
      + `</button></div>`

    const codeBody = `<pre><code class="hljs${langClass}">${linesHtml}</code></pre>`

    if (shouldCollapse) {
      // <details>/<summary> gives a native, no-JS collapse affordance. The
      // summary holds the header (lang badge + line count + copy button); the
      // <pre> sits in the <details> body and is hidden by CSS until expanded.
      const openAttr = openByDefault ? ' open' : ''
      return `<details class="code-block code-block--collapsible"${openAttr}>`
        + `<summary>${headerHtml}</summary>`
        + codeBody
        + `</details>`
    }
    return `<div class="code-block">${headerHtml}${codeBody}</div>`
  },

  link({ href, title, tokens }: Tokens.Link): string {
    // marked v15 passes already-parsed inline tokens; render them ourselves so
    // that the inner content keeps any bold/italic formatting from `[**x**](u)`.
    const innerHtml = (this as unknown as { parser: { parseInline: (t: unknown[]) => string } })
      .parser.parseInline(tokens)

    if (!href || !SAFE_LINK_RE.test(href)) {
      // Dangerous scheme — render the inner content as plain content (no anchor).
      return innerHtml
    }
    let extra = ''
    try {
      const url = new URL(href, typeof window !== 'undefined' ? window.location.href : 'http://localhost/')
      if (typeof window !== 'undefined' && url.origin !== window.location.origin) {
        extra = ' target="_blank" rel="noopener noreferrer"'
      }
    } catch {
      // Malformed URL — treat as same-origin (relative link path).
    }
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
    return `<a href="${escapeHtml(href)}"${titleAttr}${extra}>${innerHtml}</a>`
  },
}

// ---------------------------------------------------------------------------
// marked instance
// ---------------------------------------------------------------------------
const markedInstance = new Marked({
  gfm: true,
  breaks: true,
  renderer: customRenderer,
})

// ---------------------------------------------------------------------------
// DOMPurify config — allow Markdown + custom blocks (code-block, KaTeX/Mermaid
// placeholders) and the inline copy SVG button.
// ---------------------------------------------------------------------------
const purifyConfig = {
  ADD_ATTR: [
    'target', 'rel', 'class',
    'data-code', 'data-echarts-option', 'data-wiki-title', 'data-slug',
    'data-tex', 'data-mermaid',
    'aria-label', 'open',
    'type', 'viewBox', 'fill', 'stroke', 'stroke-width', 'd',
    'x', 'y', 'width', 'height', 'rx', 'ry', 'points',
  ],
  ADD_TAGS: [
    'input', 'button', 'svg', 'path', 'rect', 'polyline', 'circle', 'line',
    'span', 'details', 'summary',
  ],
  // Defence in depth: even if a malicious href slips past our link()
  // override, DOMPurify drops anything outside this whitelist.
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|#|\/|\.\/|\.\.\/)/i,
}

// ---------------------------------------------------------------------------
// LRU render cache
// ---------------------------------------------------------------------------
// Streaming token-by-token defeats this (each delta produces a new key) but
// scrolling history and re-renders of completed messages are common, and the
// cost of marked + highlight.js + DOMPurify is non-trivial for long messages.
const RENDER_CACHE = new Map<string, string>()
const RENDER_CACHE_CAP = 200

function cacheKey(text: string): string {
  // Compact key — collisions on the order of 10^-6 in single-conversation
  // scope, and a false hit only causes a "stale" render of unchanged content
  // (no security implication since cached values are sanitized HTML).
  return `${text.length}:${text.slice(0, 40)}:${text.slice(-40)}`
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
export function useMarkdownRenderer() {
  function renderMarkdown(content: string): string {
    if (!content) return ''
    const k = cacheKey(content)
    const cached = RENDER_CACHE.get(k)
    if (cached !== undefined) {
      // Refresh LRU position — re-insert at the tail.
      RENDER_CACHE.delete(k)
      RENDER_CACHE.set(k, cached)
      return cached
    }

    // 1. LaTeX placeholders (skips fenced/inline code).
    const withLatex = preprocessLatex(content)
    // 2. Wiki link substitution: [[Title]] → <a class="wiki-link" …>.
    const withWikiLinks = withLatex.replace(
      /\[\[([^\]]+)\]\]/g,
      '<a class="wiki-link" href="#" data-wiki-title="$1" onclick="window.dispatchEvent(new CustomEvent(\'wiki-link-click\',{detail:{title:\'$1\'}}));return false">$1</a>'
    )
    // 3. Marked → 4. DOMPurify.
    const rawHtml = markedInstance.parse(withWikiLinks) as string
    const result = DOMPurify.sanitize(rawHtml, purifyConfig)

    // Evict oldest entry when at capacity (Map preserves insertion order).
    if (RENDER_CACHE.size >= RENDER_CACHE_CAP) {
      const oldestKey = RENDER_CACHE.keys().next().value
      if (oldestKey !== undefined) RENDER_CACHE.delete(oldestKey)
    }
    RENDER_CACHE.set(k, result)
    return result
  }

  function escapeText(text: string): string {
    return escapeHtml(text)
  }

  return {
    renderMarkdown,
    escapeText,
    markedInstance,
  }
}

// Direct singletons for tests / advanced callers.
export { markedInstance, purifyConfig }
export default markedInstance
