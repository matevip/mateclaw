import { type Ref, watch, nextTick } from 'vue'

// Lazy-load KaTeX to keep initial bundle small (~280 KB saved).
type KatexLib = typeof import('katex').default
let katexModule: KatexLib | null = null
async function getKatex(): Promise<KatexLib> {
  if (!katexModule) {
    katexModule = (await import('katex')).default
    // Side-effect import for the stylesheet — Vite tree-shakes this when
    // unused at build time, so users without LaTeX in any visible message
    // never download katex.min.css.
    await import('katex/dist/katex.min.css')
  }
  return katexModule
}

/**
 * Composable that observes a container for `.katex-inline` / `.katex-block`
 * placeholder elements (emitted by `useMarkdownRenderer.preprocessLatex`)
 * and replaces them with KaTeX-typeset HTML.
 *
 * Mirrors the structure of `useEChartsRenderer` so behaviour is predictable:
 * MutationObserver picks up new placeholders as messages stream in, and
 * mounted elements are tracked via WeakMap to avoid double-renders.
 */
export function useKatexRenderer(containerRef: Ref<HTMLElement | null>) {
  // Track elements we've already rendered so re-scans are cheap.
  const rendered = new WeakSet<HTMLElement>()
  const mounting = new Set<HTMLElement>()
  let observer: MutationObserver | null = null

  async function mountElement(el: HTMLElement) {
    if (rendered.has(el) || mounting.has(el)) return
    mounting.add(el)
    const tex = decodeURIComponent(el.getAttribute('data-tex') || '')
    if (!tex) {
      mounting.delete(el)
      return
    }
    try {
      const katex = await getKatex()
      const isBlock = el.classList.contains('katex-block')
      katex.render(tex, el, {
        throwOnError: false,   // failed input renders as red TeX, never throws
        displayMode: isBlock,
        output: 'html',
        trust: false,           // don't trust input (it's user/LLM content)
        strict: 'ignore',       // tolerate non-standard commands
      })
      rendered.add(el)
    } catch (e) {
      console.error('[KatexRenderer] render error:', e)
      // Fall back to the raw TeX so the user can still read it.
      el.textContent = tex
      el.classList.add('katex-error')
    } finally {
      mounting.delete(el)
    }
  }

  function scanAndMount() {
    const container = containerRef.value
    if (!container) return
    const blocks = container.querySelectorAll<HTMLElement>(
      '.katex-inline[data-tex]:not(.katex-error), .katex-block[data-tex]:not(.katex-error)',
    )
    blocks.forEach((el) => {
      if (!rendered.has(el) && !mounting.has(el) && !el.querySelector('.katex')) {
        mountElement(el)
      }
    })
  }

  function attachObserver(container: HTMLElement) {
    observer?.disconnect()
    observer = new MutationObserver(() => {
      // Defer to nextTick so all of Vue's batched DOM updates settle first.
      nextTick(() => scanAndMount())
    })
    observer.observe(container, { childList: true, subtree: true })
    scanAndMount()
  }

  function startObserving() {
    const container = containerRef.value
    if (container) attachObserver(container)
  }

  // If the ref is null at composable-call time (e.g. v-if container), wait
  // for it to materialise.
  const stopContainerWatch = watch(
    () => containerRef.value,
    (newContainer) => {
      if (newContainer && !observer) attachObserver(newContainer)
    },
    { immediate: false },
  )

  function dispose() {
    stopContainerWatch()
    observer?.disconnect()
    observer = null
  }

  return { startObserving, dispose, scanAndMount }
}
