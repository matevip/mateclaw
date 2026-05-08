import { type Ref, watch, nextTick } from 'vue'
import { useThemeStore } from '@/stores/useThemeStore'

// Lazy-load Mermaid (~600 KB minified) — only fetched when a chat message
// actually contains a ```mermaid block.
type MermaidLib = typeof import('mermaid').default
let mermaidModule: MermaidLib | null = null
let initializedTheme: 'dark' | 'default' | null = null

async function getMermaid(theme: 'dark' | 'default'): Promise<MermaidLib> {
  if (!mermaidModule) {
    mermaidModule = (await import('mermaid')).default
  }
  if (initializedTheme !== theme) {
    // securityLevel:'strict' disables click handlers and inline JS — important
    // because Mermaid sources come from arbitrary LLM output. Coupled with
    // our existing DOMPurify pass it provides defence in depth.
    mermaidModule.initialize({
      startOnLoad: false,
      theme,
      securityLevel: 'strict',
      flowchart: { useMaxWidth: true, htmlLabels: true },
      themeVariables: theme === 'dark'
        ? { darkMode: true, background: '#1e293b' }
        : {},
    })
    initializedTheme = theme
  }
  return mermaidModule
}

let renderCounter = 0

// Module-level cache of rendered SVGs, keyed by raw mermaid source. This is
// the anti-flicker fix for STABLE content (history, theme toggles, scroll):
// streaming markdown updates use Vue's `v-html`, which destroys and recreates
// the entire subtree on every token — including stable `.mermaid-block`
// placeholders whose source hasn't changed. Element identity is therefore
// useless for dedup; the only stable key is the source string itself. On
// every MutationObserver tick we sync-paint from this cache before the
// browser repaints, so the user never sees an empty box for an already
// rendered diagram.
//
// For ACTIVELY STREAMING content the source itself changes every token, so
// the cache always misses. The streaming flicker is fixed separately by
// (a) skipping the async render path while the host message still has a
// `.with-cursor` ancestor, and (b) debouncing the async render so it only
// fires after content has been stable for STABLE_RENDER_DEBOUNCE_MS.
type SvgCacheEntry = { html: string; theme: 'dark' | 'default' }
const SVG_CACHE = new Map<string, SvgCacheEntry>()
const SVG_CACHE_CAP = 64
const STABLE_RENDER_DEBOUNCE_MS = 350

function cacheGet(src: string, theme: 'dark' | 'default'): string | null {
  const entry = SVG_CACHE.get(src)
  if (!entry || entry.theme !== theme) return null
  // Refresh LRU position.
  SVG_CACHE.delete(src)
  SVG_CACHE.set(src, entry)
  return entry.html
}

function cacheSet(src: string, theme: 'dark' | 'default', html: string): void {
  if (SVG_CACHE.size >= SVG_CACHE_CAP) {
    const oldest = SVG_CACHE.keys().next().value
    if (oldest !== undefined) SVG_CACHE.delete(oldest)
  }
  SVG_CACHE.set(src, { html, theme })
}

function isInsideStreaming(el: HTMLElement): boolean {
  // `.with-cursor` is set on the assistant `.msg-content` while the message
  // is generating (see MessageBubble.vue:141). When it's there, every token
  // produces a fresh v-html update — rendering now would just flicker.
  return !!el.closest('.msg-content.with-cursor')
}

/**
 * Composable that observes a container for `.mermaid-block[data-mermaid]`
 * placeholders (emitted by `useMarkdownRenderer.code()` for ```mermaid fenced
 * blocks) and replaces them with rendered SVG diagrams.
 *
 * Mirrors `useEChartsRenderer` / `useKatexRenderer` so the three post-render
 * augmentations behave identically: lazy-loaded module, MutationObserver for
 * streaming inserts, theme reactivity via re-render on dark-mode toggle.
 */
export function useMermaidRenderer(containerRef: Ref<HTMLElement | null>) {
  const themeStore = useThemeStore()
  const rendered = new WeakSet<HTMLElement>()
  const mounting = new Set<HTMLElement>()
  const tracked = new Set<HTMLElement>()
  let observer: MutationObserver | null = null
  let asyncTimer: ReturnType<typeof setTimeout> | null = null

  function getBody(el: HTMLElement): HTMLElement {
    // Renderers built before the header/body split fall back to the wrapper
    // itself so cached chat history (rendered HTML in DB) still mounts.
    return (el.querySelector<HTMLElement>('.mermaid-block__body')) || el
  }

  function paintLoadingPlaceholder(el: HTMLElement) {
    const body = getBody(el)
    if (body.dataset.mcLoading === '1') return
    // Use a stable inline-svg loader so the body has a non-empty paint that
    // doesn't change between mutations — kills the visible "shake".
    body.innerHTML = '<div class="mermaid-block__loader" aria-hidden="true">'
      + '<span class="mermaid-block__loader-dot"></span>'
      + '<span class="mermaid-block__loader-dot"></span>'
      + '<span class="mermaid-block__loader-dot"></span>'
      + '</div>'
    body.dataset.mcLoading = '1'
  }

  function tryMountFromCache(el: HTMLElement, src: string, theme: 'dark' | 'default'): boolean {
    const cached = cacheGet(src, theme)
    if (!cached) return false
    const body = getBody(el)
    body.innerHTML = cached
    delete body.dataset.mcLoading
    el.classList.remove('mermaid-error')
    el.classList.add('mermaid-ready')
    rendered.add(el)
    tracked.add(el)
    return true
  }

  async function mountBlock(el: HTMLElement) {
    if (rendered.has(el) || mounting.has(el)) return
    const src = decodeURIComponent(el.getAttribute('data-mermaid') || '')
    if (!src.trim()) return

    const theme: 'dark' | 'default' = themeStore.isDark ? 'dark' : 'default'

    mounting.add(el)
    try {
      const mermaid = await getMermaid(theme)

      // Pre-parse so we can fall back gracefully on bad input. Without this,
      // mermaid 11.x's render() emits its own bomb-icon "Syntax error" SVG
      // INSTEAD of throwing — which (1) looks ugly and (2) wouldn't trigger
      // our catch block, so the same broken source would re-render on every
      // streaming token mutation, flooding the console.
      const parsed = await mermaid.parse(src, { suppressErrors: true })
      if (!parsed) {
        throw new Error('mermaid parse failed')
      }
      const id = `mc-mermaid-${++renderCounter}`
      const { svg } = await mermaid.render(id, src)
      cacheSet(src, theme, svg)
      // Re-check the element is still in the DOM — during streaming it may
      // have been detached by a fresh v-html update before our async render
      // resolved. The cache write above is what matters; future re-creations
      // of this source will hit the sync fast path.
      if (el.isConnected) {
        const body = getBody(el)
        body.innerHTML = svg
        delete body.dataset.mcLoading
        el.classList.add('mermaid-ready')
        rendered.add(el)
        tracked.add(el)
      }
    } catch (e) {
      // Source either incomplete (still streaming) or genuinely broken.
      // Don't mark this element rendered — leave the loading placeholder so
      // the next stable scan can retry. We only show the source-as-text
      // fallback if the parent message has finished generating, otherwise
      // the user briefly sees raw mermaid syntax.
      if (!isInsideStreaming(el)) {
        console.warn('[MermaidRenderer] failed to render — showing source:', e)
        el.classList.add('mermaid-error')
        getBody(el).textContent = src
        delete getBody(el).dataset.mcLoading
        rendered.add(el)
      } else {
        paintLoadingPlaceholder(el)
      }
    } finally {
      mounting.delete(el)
    }
  }

  /**
   * Synchronous pass: paint cache hits and loading placeholders. Runs on
   * every MutationObserver tick — must stay cheap and side-effect-free
   * besides DOM writes for the blocks it inspects.
   */
  function syncPaint() {
    const container = containerRef.value
    if (!container) return
    const blocks = container.querySelectorAll<HTMLElement>(
      '.mermaid-block[data-mermaid]:not(.mermaid-error)',
    )
    const theme: 'dark' | 'default' = themeStore.isDark ? 'dark' : 'default'
    blocks.forEach((el) => {
      if (rendered.has(el) || mounting.has(el)) {
        return
      }
      const body = getBody(el)
      if (body.querySelector('svg')) {
        rendered.add(el)
        tracked.add(el)
        return
      }
      const src = decodeURIComponent(el.getAttribute('data-mermaid') || '')
      if (!src.trim()) return
      if (tryMountFromCache(el, src, theme)) return
      // No cache: paint a stable loading placeholder so the empty box doesn't
      // flicker between paints. This is what makes streaming feel calm —
      // every v-html re-creation lands here and immediately writes the same
      // loader markup, so the user sees a steady box, not a strobing one.
      paintLoadingPlaceholder(el)
    })
  }

  /**
   * Slow async pass: kick off mermaid renders for blocks that haven't been
   * cached yet. Skipped while the host message is still streaming — running
   * mermaid.render on a half-finished source wastes CPU AND visibly flashes
   * partial diagrams as elements get destroyed mid-render.
   */
  function asyncRenderPass() {
    const container = containerRef.value
    if (!container) return
    const blocks = container.querySelectorAll<HTMLElement>(
      '.mermaid-block[data-mermaid]:not(.mermaid-error):not(.mermaid-ready)',
    )
    blocks.forEach((el) => {
      if (rendered.has(el) || mounting.has(el)) return
      const body = getBody(el)
      if (body.querySelector('svg')) {
        rendered.add(el)
        tracked.add(el)
        return
      }
      if (isInsideStreaming(el)) return
      mountBlock(el)
    })
  }

  function scheduleAsyncPass() {
    if (asyncTimer) clearTimeout(asyncTimer)
    asyncTimer = setTimeout(() => {
      asyncTimer = null
      asyncRenderPass()
    }, STABLE_RENDER_DEBOUNCE_MS)
  }

  function rebuildAll() {
    // Theme switch: clear rendered SVGs and re-mount with the new theme.
    // Don't drop the cache map itself — entries are theme-tagged and the next
    // render will overwrite the stale one.
    tracked.forEach((el) => {
      const body = getBody(el)
      body.innerHTML = ''
      delete body.dataset.mcLoading
      el.classList.remove('mermaid-ready')
      rendered.delete(el)
    })
    tracked.clear()
    initializedTheme = null // force re-init with the new theme
    syncPaint()
    scheduleAsyncPass()
  }

  function attachObserver(container: HTMLElement) {
    observer?.disconnect()
    observer = new MutationObserver(() => {
      nextTick(() => {
        // Sync paint runs every tick — gives stable content cache-hit speed
        // and gives streaming content a stable loader (not an empty box).
        syncPaint()
        // Async render is debounced so streaming tokens don't pile up
        // concurrent mermaid.render() calls; kicks in ~350 ms after the last
        // mutation, which is well after token cadence and well before a user
        // would notice.
        scheduleAsyncPass()
      })
    })
    observer.observe(container, {
      childList: true,
      subtree: true,
      // Watch class changes so removing `.with-cursor` (stream end) triggers
      // a re-scan even when no childList mutation accompanies it.
      attributes: true,
      attributeFilter: ['class'],
    })
    syncPaint()
    scheduleAsyncPass()
  }

  function startObserving() {
    const container = containerRef.value
    if (container) attachObserver(container)
  }

  const stopContainerWatch = watch(
    () => containerRef.value,
    (newContainer) => {
      if (newContainer && !observer) attachObserver(newContainer)
    },
    { immediate: false },
  )

  // Re-render on theme change so diagrams pick up the new colour palette.
  const stopThemeWatch = watch(
    () => themeStore.isDark,
    () => rebuildAll(),
  )

  function dispose() {
    stopContainerWatch()
    stopThemeWatch()
    observer?.disconnect()
    observer = null
    if (asyncTimer) clearTimeout(asyncTimer)
    asyncTimer = null
    tracked.clear()
  }

  return { startObserving, dispose, scanAndMount: () => { syncPaint(); scheduleAsyncPass() } }
}

/**
 * Re-render mermaid source with `htmlLabels: false` so the resulting SVG is
 * self-contained (uses `<text>` nodes instead of `<foreignObject><div>`).
 * The on-screen render keeps htmlLabels:true for nicer typography in the
 * chat UI; but foreignObject HTML labels (a) depend on the host page's CSS
 * variables, which are gone when the SVG is opened standalone, (b) aren't
 * rendered at all by macOS Quick Look / Preview, and (c) occasionally trip
 * XMLSerializer cross-namespace bugs that drop the inner xhtml xmlns. The
 * net effect of all three is the "blank downloaded SVG" the user reports.
 */
async function renderSvgForExport(src: string, theme: 'dark' | 'default'): Promise<string | null> {
  try {
    const mermaid = await getMermaid(theme)
    const exportSrc = /^\s*%%\{\s*init\s*:/i.test(src)
      ? src
      : `%%{init: {"flowchart": {"htmlLabels": false}}}%%\n${src}`
    const id = `mc-mermaid-export-${++renderCounter}`
    const { svg } = await mermaid.render(id, exportSrc)
    return svg
  } catch (e) {
    console.warn('[MermaidRenderer] export re-render failed; falling back to live SVG', e)
    return null
  }
}

function ensureExportShape(svgEl: SVGSVGElement, fallbackBox: DOMRect | null) {
  if (!svgEl.getAttribute('xmlns')) {
    svgEl.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  }
  if (!svgEl.getAttribute('xmlns:xlink')) {
    svgEl.setAttribute('xmlns:xlink', 'http://www.w3.org/1999/xlink')
  }
  // Prefer viewBox (intrinsic to the diagram) over the live bounding rect,
  // which depends on the chat layout and can be 0 if the message is in a
  // collapsed panel.
  let width = 0
  let height = 0
  const vb = (svgEl.getAttribute('viewBox') || '').split(/\s+/).map(Number)
  if (vb.length === 4 && vb[2] > 0 && vb[3] > 0) {
    width = Math.round(vb[2])
    height = Math.round(vb[3])
  }
  if ((!width || !height) && fallbackBox) {
    width = Math.round(fallbackBox.width)
    height = Math.round(fallbackBox.height)
  }
  if (!width || !height) {
    width = 800
    height = 600
  }
  svgEl.setAttribute('width', String(width))
  svgEl.setAttribute('height', String(height))
  // Strip mermaid's `style="max-width: 100%"` — fine inside a sized parent,
  // collapses to 0 px in standalone viewers.
  svgEl.style.removeProperty('max-width')
  if (svgEl.getAttribute('style') === '') {
    svgEl.removeAttribute('style')
  }
}

/**
 * Click handler for `.mermaid-block__download` buttons. Synchronously claims
 * the click (returning true so the caller can early-return) and kicks off
 * the actual export work async — the export re-renders mermaid with
 * htmlLabels:false to produce a portable SVG, hence the async path.
 *
 * Hosted in this module so callers (ChatConsole, AgentContext) only wire one
 * line — matching the existing `handleCodeCopy` pattern.
 */
export function handleMermaidDownload(e: MouseEvent): boolean {
  const btn = (e.target as HTMLElement | null)?.closest('.mermaid-block__download') as HTMLElement | null
  if (!btn) return false
  e.preventDefault()
  e.stopPropagation()
  void doMermaidDownload(btn)
  return true
}

async function doMermaidDownload(btn: HTMLElement) {
  const block = btn.closest('.mermaid-block') as HTMLElement | null
  if (!block) return
  const liveSvg = block.querySelector('svg') as SVGSVGElement | null
  const textEl = btn.querySelector('.mermaid-block__download-text') as HTMLElement | null
  if (!liveSvg) {
    flashButton(btn, textEl, 'Wait…')
    return
  }
  const originalText = textEl?.textContent || 'SVG'
  if (textEl) textEl.textContent = '...'
  btn.setAttribute('disabled', 'true')
  try {
    const src = decodeURIComponent(block.getAttribute('data-mermaid') || '')
    const theme: 'dark' | 'default' = initializedTheme
      || (document.documentElement.classList.contains('dark') ? 'dark' : 'default')

    let exportSvg: SVGSVGElement | null = null
    if (src) {
      const svgString = await renderSvgForExport(src, theme)
      if (svgString) {
        const tmp = document.createElement('div')
        tmp.innerHTML = svgString
        exportSvg = tmp.querySelector('svg') as SVGSVGElement | null
      }
    }
    // Fallback: clone the live SVG. Will still work for diagrams without
    // foreignObject (sequence/class/state diagrams), and degrades gracefully
    // for flowcharts (the file at least has the structure).
    if (!exportSvg) {
      exportSvg = liveSvg.cloneNode(true) as SVGSVGElement
    }

    let liveBox: DOMRect | null = null
    try { liveBox = liveSvg.getBoundingClientRect() } catch { /* ignore */ }
    ensureExportShape(exportSvg, liveBox)

    const xml = new XMLSerializer().serializeToString(exportSvg)
    const blob = new Blob([`<?xml version="1.0" encoding="UTF-8"?>\n${xml}`], {
      type: 'image/svg+xml;charset=utf-8',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `mermaid-${Date.now()}.svg`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } finally {
    btn.removeAttribute('disabled')
    if (textEl) textEl.textContent = originalText
  }
}

function flashButton(btn: HTMLElement, textEl: Element | null, text: string) {
  const original = textEl?.textContent || ''
  if (textEl) textEl.textContent = text
  btn.classList.add('is-flash')
  setTimeout(() => {
    btn.classList.remove('is-flash')
    if (textEl && original) textEl.textContent = original
  }, 1200)
}
