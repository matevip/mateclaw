<template>
  <div class="page-viewer" v-if="store.currentPage">
    <!-- Header -->
    <div class="page-viewer-header">
      <div class="page-viewer-copy">
        <div class="page-viewer-kicker">
          <span class="kicker-dot" :class="store.currentPage.lastUpdatedBy === 'manual' ? 'manual' : 'ai'"></span>
          {{ store.currentPage.lastUpdatedBy === 'ai' ? t('wiki.generatedByAi') : t('wiki.editedManually') }}
        </div>
        <h2 class="page-viewer-title">{{ store.currentPage.title }}</h2>
        <div class="page-viewer-meta">
          <span class="meta-badge">v{{ store.currentPage.version }}</span>
          <span class="meta-slug">{{ store.currentPage.slug }}</span>
        </div>
      </div>
      <div class="page-viewer-actions">
        <button class="btn-secondary btn-sm" @click="editing = !editing">
          {{ editing ? t('common.cancel') : t('common.edit') }}
        </button>
        <button v-if="editing" class="btn-primary btn-sm" @click="saveEdit">
          {{ t('common.save') }}
        </button>
        <button v-if="!editing" class="btn-secondary btn-sm btn-delete" @click="handleDelete">
          {{ t('common.delete') }}
        </button>
      </div>
    </div>

    <!-- Summary Card -->
    <div v-if="store.currentPage.summary && !editing" class="page-summary">
      <div class="summary-label">Summary</div>
      <p class="summary-text">{{ store.currentPage.summary }}</p>
    </div>

    <!-- Content -->
    <article v-if="!editing" class="page-content markdown-body" v-html="renderedContent"></article>
    <textarea v-else v-model="editContent" class="page-editor" rows="30"></textarea>

    <!-- Backlinks -->
    <div v-if="backlinks.length > 0" class="backlinks-section">
      <h4 class="backlinks-title">{{ t('wiki.backlinks') }} ({{ backlinks.length }})</h4>
      <div class="backlinks-list">
        <span
          v-for="bl in backlinks" :key="bl.slug"
          class="backlink-tag"
          @click="openPage(bl.slug)"
        >
          {{ bl.title }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore, type WikiPage } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { t } = useI18n()
const store = useWikiStore()
const { renderMarkdown } = useMarkdownRenderer()

const editing = ref(false)
const editContent = ref('')
const backlinks = ref<WikiPage[]>([])

const renderedContent = computed(() => {
  if (!store.currentPage?.content) return ''
  // Pre-process wiki links [[title]] before markdown rendering
  const content = store.currentPage.content.replace(/\[\[([^\]]+)\]\]/g, (_match, title) => {
    const slug = title.trim().toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff\s-]/g, '').replace(/\s+/g, '-')
    return `<a class="wiki-link" data-slug="${slug}" onclick="return false">${title}</a>`
  })
  return renderMarkdown(content)
})

watch(() => store.currentPage, async (page) => {
  if (page && store.currentKB) {
    editing.value = false
    editContent.value = page.content || ''
    // Fetch backlinks
    try {
      const res: any = await wikiApi.getBacklinks(store.currentKB.id, page.slug)
      backlinks.value = res.data || []
    } catch {
      backlinks.value = []
    }
  }
}, { immediate: true })

async function saveEdit() {
  if (!store.currentKB || !store.currentPage) return
  await wikiApi.updatePage(store.currentKB.id, store.currentPage.slug, editContent.value)
  await store.loadPage(store.currentKB.id, store.currentPage.slug)
  editing.value = false
}

async function handleDelete() {
  if (!store.currentKB || !store.currentPage) return
  const confirmed = confirm(t('wiki.confirmDelete', { title: store.currentPage.title }))
  if (!confirmed) return
  try {
    await wikiApi.deletePage(store.currentKB.id, store.currentPage.slug)
    store.currentPage = null
    await store.fetchPages(store.currentKB.id)
  } catch (e: any) {
    alert(e?.message || 'Delete failed')
  }
}

async function openPage(slug: string) {
  if (!store.currentKB) return
  await store.loadPage(store.currentKB.id, slug)
}

// Handle wiki link clicks via event delegation
onMounted(() => {
  document.addEventListener('click', (e) => {
    const target = e.target as HTMLElement
    if (target.classList.contains('wiki-link')) {
      const slug = target.dataset.slug
      if (slug) openPage(slug)
    }
  })
})
</script>

<style scoped>
/* Buttons */
.page-viewer {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-primary.btn-sm { padding: 6px 14px; font-size: 13px; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary.btn-sm { padding: 6px 14px; font-size: 13px; }
.btn-secondary.btn-delete { color: var(--el-color-danger, #f56c6c); }
.btn-secondary.btn-delete:hover { background: var(--el-color-danger-light-9, #fef0f0); border-color: var(--el-color-danger-light-5, #fab6b6); }

/* Header */
.page-viewer-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; padding-bottom: 16px; border-bottom: 1px solid var(--mc-border-light); }
.page-viewer-copy { min-width: 0; }
.page-viewer-kicker { font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--mc-text-secondary); margin-bottom: 8px; display: flex; align-items: center; gap: 6px; }
.kicker-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.kicker-dot.ai { background: var(--el-color-primary, #409eff); }
.kicker-dot.manual { background: var(--el-color-success, #67c23a); }
.page-viewer-title { font-size: clamp(24px, 3vw, 32px); line-height: 1.1; letter-spacing: -0.03em; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.page-viewer-meta { font-size: 12px; color: var(--mc-text-secondary); display: flex; gap: 10px; margin-top: 10px; align-items: center; }
.meta-badge { padding: 2px 8px; background: var(--mc-bg-sunken); border-radius: 6px; font-weight: 600; font-size: 11px; }
.meta-slug { font-family: 'JetBrains Mono', monospace; font-size: 11px; opacity: 0.7; }
.page-viewer-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* Summary */
.page-summary { padding: 16px 20px; background: var(--mc-bg-muted); border-radius: 12px; border-left: 3px solid var(--mc-primary); }
.summary-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: var(--mc-text-secondary); margin-bottom: 6px; }
.summary-text { font-size: 14px; color: var(--mc-text-primary); line-height: 1.7; margin: 0; }

/* Content */
.page-content { font-size: 15px; line-height: 1.8; color: var(--mc-text-primary); padding: 2px 2px 0; }
.page-content :deep(p) { margin: 0 0 1.05em; }
.page-content :deep(h1) { font-size: 26px; font-weight: 700; margin: 28px 0 14px; color: var(--mc-text-primary); letter-spacing: -0.03em; }
.page-content :deep(h2) { font-size: 21px; font-weight: 700; margin: 24px 0 10px; color: var(--mc-text-primary); letter-spacing: -0.025em; }
.page-content :deep(h3) { font-size: 18px; font-weight: 700; margin: 20px 0 8px; color: var(--mc-text-primary); }
.page-content :deep(li) { margin-left: 24px; list-style: disc; }
.page-content :deep(code) { background: var(--mc-bg-sunken); padding: 2px 6px; border-radius: 6px; font-size: 0.85em; }
.page-content :deep(pre code) { background: none; padding: 0; }
.page-content :deep(blockquote) { border-left: 3px solid var(--mc-primary); padding: 8px 16px; margin: 12px 0; color: var(--mc-text-secondary); background: var(--mc-bg-muted); border-radius: 0 10px 10px 0; }
.page-content :deep(table) { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 14px; }
.page-content :deep(th), .page-content :deep(td) { padding: 8px 12px; border: 1px solid var(--mc-border-light); text-align: left; }
.page-content :deep(th) { background: var(--mc-bg-sunken); font-weight: 600; }
.page-content :deep(hr) { border: none; border-top: 1px solid var(--mc-border-light); margin: 20px 0; }
.page-content :deep(img) { max-width: 100%; border-radius: 10px; }
.page-content :deep(.wiki-link) { color: var(--mc-primary); text-decoration: none; cursor: pointer; border-bottom: 1px dashed var(--mc-primary); }
.page-content :deep(.wiki-link:hover) { text-decoration: underline; }

/* Editor */
.page-editor { width: 100%; min-height: 60vh; padding: 16px; border: 1px solid var(--mc-border); border-radius: 14px; font-family: 'JetBrains Mono', monospace; font-size: 14px; line-height: 1.65; resize: vertical; background: var(--mc-bg-elevated); color: var(--mc-text-primary); outline: none; }
.page-editor:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

/* Backlinks */
.backlinks-section { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--mc-border); }
.backlinks-title { font-size: 12px; font-weight: 600; text-transform: uppercase; color: var(--mc-text-secondary); margin-bottom: 8px; letter-spacing: 0.05em; }
.backlinks-list { display: flex; flex-wrap: wrap; gap: 6px; }
.backlink-tag { padding: 5px 10px; background: var(--mc-bg-sunken); border-radius: 9999px; font-size: 12px; cursor: pointer; color: var(--mc-primary); transition: background 0.15s, transform 0.15s; }
.backlink-tag:hover { background: var(--mc-primary-bg); }

@media (max-width: 768px) {
  .page-viewer-header {
    flex-direction: column;
  }

  .page-viewer-actions {
    width: 100%;
  }

  .btn-primary.btn-sm,
  .btn-secondary.btn-sm {
    flex: 1;
    justify-content: center;
  }

  .page-viewer-title {
    font-size: 24px;
  }

  .page-editor {
    min-height: 46vh;
  }
}
</style>
