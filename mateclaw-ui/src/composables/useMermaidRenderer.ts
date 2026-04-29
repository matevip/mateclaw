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

  async function mountBlock(el: HTMLElement) {
    if (rendered.has(el) || mounting.has(el)) return
    mounting.add(el)
    const src = decodeURIComponent(el.getAttribute('data-mermaid') || '')
    if (!src.trim()) {
      mounting.delete(el)
      return
    }
    try {
      const theme = themeStore.isDark ? 'dark' : 'default'
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
      el.innerHTML = svg
      rendered.add(el)
      tracked.add(el)
    } catch (e) {
      // Streaming-aware: if the LLM is still emitting tokens the source may
      // legitimately be incomplete on each pass. Mark this element done so
      // we don't retry on every mutation; when streaming finishes Vue's v-html
      // produces a fresh element (different identity) and we'll try again.
      console.warn('[MermaidRenderer] failed to render — showing source:', e)
      el.classList.add('mermaid-error')
      el.textContent = src
      rendered.add(el)
    } finally {
      mounting.delete(el)
    }
  }

  function scanAndMount() {
    const container = containerRef.value
    if (!container) return
    const blocks = container.querySelectorAll<HTMLElement>(
      '.mermaid-block[data-mermaid]:not(.mermaid-error)',
    )
    blocks.forEach((el) => {
      if (!rendered.has(el) && !mounting.has(el) && !el.querySelector('svg')) {
        mountBlock(el)
      }
    })
  }

  function rebuildAll() {
    // Theme switch: clear rendered SVGs and re-mount with the new theme.
    tracked.forEach((el) => {
      el.innerHTML = ''
      rendered.delete(el)
    })
    tracked.clear()
    initializedTheme = null // force re-init with the new theme
    scanAndMount()
  }

  function attachObserver(container: HTMLElement) {
    observer?.disconnect()
    observer = new MutationObserver(() => {
      nextTick(() => scanAndMount())
    })
    observer.observe(container, { childList: true, subtree: true })
    scanAndMount()
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
    tracked.clear()
  }

  return { startObserving, dispose, scanAndMount }
}
