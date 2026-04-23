import { Marked } from 'marked'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'

// 语言映射
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

// marked v15 requires a plain object renderer — class instances extending Renderer are NOT dispatched
const customRenderer = {
  code({ text, lang }: { type: string; raw: string; text: string; lang?: string }): string {
    const rawCode = text || ''
    const infoStr = (lang || '').split(/\s/)[0]

    // ECharts chart block: render as a placeholder div
    if (infoStr === 'echarts') {
      const encodedOption = encodeURIComponent(rawCode)
      return `<div class="echarts-block" data-echarts-option="${encodedOption}"></div>`
    }

    const detectedLang = extractLang(infoStr)
    const hasLanguage = detectedLang && hljs.getLanguage(detectedLang)

    let highlighted: string
    try {
      if (hasLanguage) {
        highlighted = hljs.highlight(rawCode, { language: detectedLang }).value
      } else {
        highlighted = hljs.highlightAuto(rawCode).value
      }
    } catch {
      highlighted = escapeHtml(rawCode)
    }

    const langLabel = LANG_DISPLAY[detectedLang] || detectedLang || 'Code'
    const encodedCode = encodeURIComponent(rawCode)
    const langClass = hasLanguage ? ` language-${detectedLang}` : ''

    return `<div class="code-block">`
      + `<div class="code-block__header">`
      + `<span class="code-block__lang">${escapeHtml(langLabel)}</span>`
      + `<button class="code-block__copy" type="button" data-code="${encodedCode}">`
      + `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
      + `<span class="code-block__copy-text">Copy</span>`
      + `</button></div>`
      + `<pre><code class="hljs${langClass}">${highlighted}</code></pre>`
      + `</div>`
  },
}

// 创建 marked 实例
const markedInstance = new Marked({
  gfm: true,
  breaks: true,
  renderer: customRenderer,
})

// 配置 DOMPurify — 允许 Markdown + 代码块复制按钮的标签和属性
const purifyConfig = {
  ADD_ATTR: ['target', 'rel', 'class', 'data-code', 'data-echarts-option', 'data-wiki-title', 'data-slug', 'type', 'viewBox', 'fill', 'stroke', 'stroke-width', 'd', 'x', 'y', 'width', 'height', 'rx', 'ry', 'points'],
  ADD_TAGS: ['input', 'button', 'svg', 'path', 'rect', 'polyline', 'circle', 'line', 'span'],
}

export function useMarkdownRenderer() {
  function renderMarkdown(content: string): string {
    if (!content) return ''
    // 将 [[Wiki Link]] 转换为可点击的 Wiki 引用链接
    const withWikiLinks = content.replace(
      /\[\[([^\]]+)\]\]/g,
      '<a class="wiki-link" href="#" data-wiki-title="$1" onclick="window.dispatchEvent(new CustomEvent(\'wiki-link-click\',{detail:{title:\'$1\'}}));return false">$1</a>'
    )
    const rawHtml = markedInstance.parse(withWikiLinks) as string
    return DOMPurify.sanitize(rawHtml, purifyConfig)
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

// 导出单例供直接使用
export { markedInstance, purifyConfig }
export default markedInstance
