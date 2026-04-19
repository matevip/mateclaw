<template>
  <div class="mc-page-shell wiki-shell">
    <div class="mc-page-frame wiki-frame">
      <div class="mc-page-inner wiki-inner">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('wiki.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('nav.wiki') }}</h1>
            <p class="mc-page-desc">{{ t('wiki.desc') }}</p>
          </div>
          <button class="btn-primary page-cta" @click="showCreateKB = true">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('wiki.createKB') }}
          </button>
        </div>

        <div class="wiki-layout">
      <!-- Left: Knowledge Base List -->
      <div class="wiki-sidebar mc-surface-card">
        <div class="sidebar-section">
          <h3 class="sidebar-title">{{ t('wiki.knowledgeBases') }}</h3>
          <div v-if="store.loading" class="text-center py-4 text-gray-400">{{ t('common.loading') }}</div>
          <div v-else-if="store.knowledgeBases.length === 0" class="text-center py-4 text-gray-400">
            {{ t('wiki.noKB') }}
          </div>
          <div v-else class="kb-list">
            <div
              v-for="kb in store.knowledgeBases" :key="kb.id"
              class="kb-item" :class="{ active: store.currentKB?.id === kb.id }"
              @click="selectKB(kb.id)"
            >
              <div class="kb-item-name">{{ kb.name }}</div>
              <div class="kb-item-meta">
                <span>{{ t('wiki.pageCount', { count: kb.pageCount }) }}</span>
                <span>{{ t('wiki.sourceCount', { count: kb.rawCount }) }}</span>
              </div>
              <!-- RFC-033: KB health stats -->
              <div v-if="kbStats[kb.id]" class="kb-health">
                <span v-if="kbStats[kb.id].enrichedPageCount != null" class="health-enriched">
                  ✦ {{ t('wiki.stats.enrichedRatio', { enriched: kbStats[kb.id].enrichedPageCount, total: kbStats[kb.id].pageCount }) }}
                </span>
                <span v-if="kbStats[kb.id].failedJobCount > 0" class="health-failed">
                  ⚠ {{ t('wiki.stats.failedJobs', { count: kbStats[kb.id].failedJobCount }) }}
                </span>
              </div>
              <span class="kb-status" :class="kb.status">{{ t(`wiki.status.${kb.status}`) }}</span>
            </div>
          </div>
        </div>

        <!-- Pages List when KB selected -->
        <div v-if="store.currentKB" class="sidebar-section sidebar-section--pages">
          <div class="sidebar-title-row">
            <h3 class="sidebar-title">
              {{ t('wiki.pages') }}
              <span class="text-xs text-gray-400">({{ store.pages.length }})</span>
            </h3>
            <button v-if="!batchMode" class="batch-toggle" @click="batchMode = true" :title="t('wiki.batchSelect')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
            </button>
            <button v-else class="batch-toggle active" @click="exitBatchMode">
              {{ t('common.cancel') }}
            </button>
          </div>
          <input
            v-model="pageSearch"
            type="text"
            :placeholder="t('wiki.searchPages')"
            class="sidebar-search"
          />

          <!-- Batch actions bar -->
          <div v-if="batchMode" class="batch-bar">
            <label class="batch-check-all">
              <input type="checkbox" :checked="allSelected" @change="toggleSelectAll" />
              <span>{{ t('wiki.selectAll') }}</span>
            </label>
            <button
              class="batch-delete-btn"
              :disabled="selectedSlugs.length === 0"
              @click="handleBatchDelete"
            >
              {{ t('common.delete') }} ({{ selectedSlugs.length }})
            </button>
          </div>

          <div class="page-list">
            <div
              v-for="page in filteredPages" :key="page.slug"
              class="page-item" :class="{ active: !batchMode && store.currentPage?.slug === page.slug }"
              @click="batchMode ? toggleSelect(page.slug) : openPage(page.slug)"
            >
              <input
                v-if="batchMode"
                type="checkbox"
                :checked="selectedSlugs.includes(page.slug)"
                class="page-checkbox"
                @click.stop="toggleSelect(page.slug)"
              />
              <div class="page-item-body">
                <div class="page-item-title">{{ page.title }}</div>
                <div class="page-item-meta">v{{ page.version }} &middot; {{ page.lastUpdatedBy }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Right: Content Area -->
      <div class="wiki-content mc-surface-card">
        <!-- No KB selected -->
        <div v-if="!store.currentKB" class="empty-state">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" class="mx-auto mb-4 text-gray-400">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
          </svg>
          <p>{{ t('wiki.selectKB') }}</p>
        </div>

        <!-- Tab navigation -->
        <div v-else class="wiki-content-body">
          <div class="content-tabs">
            <button
              v-for="tab in tabs" :key="tab.key"
              class="tab-btn" :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </div>

          <!-- Raw Materials Tab -->
          <div v-if="activeTab === 'raw'" class="tab-content">
            <RawMaterialPanel />
          </div>

          <!-- Wiki Pages Tab -->
          <div v-if="activeTab === 'pages'" class="tab-content">
            <WikiPageViewer v-if="store.currentPage" />
            <div v-else class="empty-state">
              <p>{{ t('wiki.selectPage') }}</p>
            </div>
          </div>

          <!-- Config Tab -->
          <div v-if="activeTab === 'config'" class="tab-content tab-content--config">
            <WikiConfig />
          </div>
        </div>
      </div>
    </div>
      </div>
    </div>

    <!-- Create KB Modal -->
    <div v-if="showCreateKB" class="modal-overlay">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.createKB') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.kbName') }}</label>
          <input v-model="newKBName" type="text" class="form-input" :placeholder="t('wiki.kbNamePlaceholder')" />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.kbDescription') }}</label>
          <textarea v-model="newKBDesc" class="form-input" rows="3" :placeholder="t('wiki.kbDescPlaceholder')"></textarea>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showCreateKB = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="handleCreateKB" :disabled="!newKBName.trim()">{{ t('common.create') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import RawMaterialPanel from './components/RawMaterialPanel.vue'
import WikiPageViewer from './components/WikiPageViewer.vue'
import WikiConfig from './components/WikiConfig.vue'

const { t } = useI18n()
const store = useWikiStore()

// RFC-033: KB health stats
interface KBStats {
  pageCount: number
  enrichedPageCount: number
  rawCount: number
  failedJobCount: number
  runningJobCount: number
}
const kbStats = reactive<Record<number, KBStats>>({})

async function fetchKBStats() {
  for (const kb of store.knowledgeBases) {
    try {
      const res: any = await wikiApi.getKBStats(kb.id)
      kbStats[kb.id] = res.data || res
    } catch { /* ignore */ }
  }
}

watch(() => store.knowledgeBases.length, () => { if (store.knowledgeBases.length > 0) fetchKBStats() })

const showCreateKB = ref(false)
const newKBName = ref('')
const newKBDesc = ref('')
const activeTab = ref('raw')
const pageSearch = ref('')

// Batch selection
const batchMode = ref(false)
const selectedSlugs = ref<string[]>([])

const allSelected = computed(() =>
  filteredPages.value.length > 0 && selectedSlugs.value.length === filteredPages.value.length
)

function toggleSelect(slug: string) {
  const idx = selectedSlugs.value.indexOf(slug)
  if (idx >= 0) selectedSlugs.value.splice(idx, 1)
  else selectedSlugs.value.push(slug)
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedSlugs.value = []
  } else {
    selectedSlugs.value = filteredPages.value.map(p => p.slug)
  }
}

function exitBatchMode() {
  batchMode.value = false
  selectedSlugs.value = []
}

async function handleBatchDelete() {
  if (selectedSlugs.value.length === 0 || !store.currentKB) return
  const confirmed = confirm(t('wiki.confirmBatchDelete', { count: selectedSlugs.value.length }))
  if (!confirmed) return
  try {
    await wikiApi.batchDeletePages(store.currentKB.id, selectedSlugs.value)
    exitBatchMode()
    await store.fetchPages(store.currentKB.id)
  } catch (e: any) {
    alert(e?.message || 'Batch delete failed')
  }
}

const tabs = computed(() => [
  { key: 'raw', label: t('wiki.rawMaterials') },
  { key: 'pages', label: t('wiki.pages') },
  { key: 'config', label: t('wiki.config') },
])

const filteredPages = computed(() => {
  const q = pageSearch.value.toLowerCase()
  if (!q) return store.pages
  return store.pages.filter(
    (p) => p.title.toLowerCase().includes(q) || p.slug.toLowerCase().includes(q)
  )
})

async function selectKB(id: number) {
  await store.selectKB(id)
  activeTab.value = 'raw'
}

async function openPage(slug: string) {
  if (!store.currentKB) return
  await store.loadPage(store.currentKB.id, slug)
  activeTab.value = 'pages'
}

async function handleCreateKB() {
  await store.createKB({ name: newKBName.value, description: newKBDesc.value })
  showCreateKB.value = false
  newKBName.value = ''
  newKBDesc.value = ''
}

onMounted(() => {
  store.fetchKnowledgeBases()
})
</script>

<style scoped>
.wiki-shell {
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.wiki-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.wiki-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Layout */
.wiki-layout { display: flex; gap: 16px; flex: 1; min-height: 0; overflow: hidden; }

.wiki-sidebar { width: 300px; min-width: 300px; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 14px; min-height: 0; }

.sidebar-section { display: flex; flex-direction: column; gap: 8px; }

.sidebar-title { font-size: 11px; font-weight: 700; text-transform: uppercase; color: var(--mc-text-tertiary); letter-spacing: 0.1em; }

.sidebar-search { width: 100%; padding: 9px 12px; border: 1px solid var(--mc-border); border-radius: 12px; font-size: 13px; background: var(--mc-bg-muted); color: var(--mc-text-primary); outline: none; }
.sidebar-search:focus { border-color: var(--mc-primary); }

.kb-list, .page-list { display: flex; flex-direction: column; gap: 4px; }

.kb-item, .page-item { padding: 10px 12px; border-radius: 16px; cursor: pointer; transition: background 0.15s, transform 0.15s; position: relative; border: 1px solid transparent; }
.kb-item:hover, .page-item:hover { background: var(--mc-bg-muted); transform: translateY(-1px); }
.kb-item.active, .page-item.active { background: var(--mc-primary-bg); border-color: rgba(217, 109, 70, 0.12); }

.kb-item-name { font-size: 14px; font-weight: 700; color: var(--mc-text-primary); margin-bottom: 4px; }
.kb-item-meta, .page-item-meta { font-size: 12px; color: var(--mc-text-secondary); display: flex; gap: 8px; }
.page-item-title { font-size: 13px; color: var(--mc-text-primary); font-weight: 600; }
.page-item-body { flex: 1; min-width: 0; }

/* Batch mode */
.sidebar-title-row { display: flex; justify-content: space-between; align-items: center; }
.batch-toggle { padding: 4px 8px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-elevated); color: var(--mc-text-secondary); cursor: pointer; font-size: 11px; display: flex; align-items: center; gap: 4px; }
.batch-toggle:hover { background: var(--mc-bg-sunken); }
.batch-toggle.active { color: var(--mc-primary); border-color: var(--mc-primary); }
.batch-bar { display: flex; justify-content: space-between; align-items: center; padding: 6px 8px; background: var(--mc-bg-muted); border-radius: 10px; }
.batch-check-all { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer; }
.batch-check-all input { cursor: pointer; }
.batch-delete-btn { padding: 4px 12px; border: none; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; background: var(--el-color-danger-light-9, #fef0f0); color: var(--el-color-danger, #f56c6c); }
.batch-delete-btn:hover:not(:disabled) { background: var(--el-color-danger-light-7, #fab6b6); }
.batch-delete-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-checkbox { flex-shrink: 0; cursor: pointer; margin-right: 8px; }
.page-item { display: flex; align-items: center; }

.kb-health { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
.health-enriched { font-size: 11px; color: #22c55e; }
.health-failed { font-size: 11px; color: var(--mc-danger); }

.kb-status { position: absolute; right: 8px; top: 8px; font-size: 10px; padding: 2px 6px; border-radius: 9999px; text-transform: uppercase; font-weight: 500; }
.kb-status.active { background: rgba(90, 138, 90, 0.15); color: var(--mc-success); }
.kb-status.processing { background: var(--mc-primary-bg); color: var(--mc-primary); }
.kb-status.error { background: var(--mc-danger-bg); color: var(--mc-danger); }

/* Content area */
.wiki-content { flex: 1; overflow: hidden; min-width: 0; padding: 16px; display: flex; flex-direction: column; min-height: 0; }

.wiki-content-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.content-tabs { display: inline-flex; gap: 4px; padding: 4px; background: var(--mc-bg-muted); border-radius: 16px; margin-bottom: 14px; border: 1px solid var(--mc-border-light); align-self: flex-start; }
.tab-btn { padding: 9px 14px; border: none; background: none; cursor: pointer; font-size: 13px; color: var(--mc-text-secondary); border-radius: 12px; transition: all 0.15s; font-weight: 600; }
.tab-btn:hover { color: var(--mc-text-primary); }
.tab-btn.active { color: var(--mc-primary); background: var(--mc-bg-elevated); box-shadow: var(--mc-shadow-soft); }

.tab-content { flex: 1; min-height: 0; overflow-y: auto; padding-right: 2px; }

.tab-content--config {
  overflow: hidden;
  padding-right: 0;
}

.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 240px; color: var(--mc-text-tertiary); text-align: center; padding: 24px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 520px; padding: 24px; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-title { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 16px; }

/* Form */
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: var(--mc-text-secondary); }
.form-input { width: 100%; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); outline: none; font-family: inherit; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }

@media (max-width: 980px) {
  .wiki-frame {
    height: 100%;
    min-height: calc(100vh - 28px);
  }

  .wiki-layout {
    flex-direction: column;
    overflow: visible;
  }

  .wiki-sidebar {
    width: 100%;
    min-width: 0;
    max-height: 280px;
  }

  .wiki-content {
    overflow: visible;
  }

  .tab-content {
    overflow: visible;
  }

  .tab-content--config {
    overflow: visible;
  }
}
</style>
