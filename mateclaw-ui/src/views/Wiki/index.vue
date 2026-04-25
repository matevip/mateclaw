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
          <!-- Left sidebar: KB list + page list -->
          <div class="wiki-sidebar mc-surface-card">

            <!-- Knowledge bases -->
            <div class="sidebar-section">
              <h3 class="sidebar-label">{{ t('wiki.knowledgeBases') }}</h3>
              <div v-if="store.loading" class="empty-hint">{{ t('common.loading') }}</div>
              <div v-else-if="store.knowledgeBases.length === 0" class="empty-hint">{{ t('wiki.noKB') }}</div>
              <div v-else class="kb-list">
                <div
                  v-for="kb in store.knowledgeBases" :key="kb.id"
                  class="kb-item" :class="{ active: store.currentKB?.id === kb.id }"
                  @click="selectKB(kb.id)"
                >
                  <div class="kb-item-name">{{ kb.name }}</div>
                  <div class="kb-item-stats">
                    <span class="stat-chip">
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                      {{ kb.pageCount }}
                    </span>
                    <span class="stat-chip">
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                      {{ kb.rawCount }}
                    </span>
                    <span class="kb-status-dot" :class="kb.status" :title="t(`wiki.status.${kb.status}`)"></span>
                  </div>
                  <div v-if="kbStats[kb.id]?.failedJobCount > 0" class="kb-warn">
                    ⚠ {{ t('wiki.stats.failedJobs', { count: kbStats[kb.id].failedJobCount }) }}
                  </div>
                </div>
              </div>
            </div>

            <!-- Page list -->
            <div v-if="store.currentKB" class="sidebar-section sidebar-section--pages">
              <div class="sidebar-title-row">
                <h3 class="sidebar-label">
                  {{ t('wiki.pages') }}
                  <span class="count-badge">{{ store.pages.length }}</span>
                </h3>
                <div class="sidebar-title-actions">
                  <button
                    v-if="!batchMode"
                    class="icon-btn" :title="t('wiki.batchSelect')"
                    @click="batchMode = true"
                  >
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
                  </button>
                  <button v-else class="icon-btn icon-btn--active" @click="exitBatchMode">✕</button>
                </div>
              </div>

              <!-- Search -->
              <div class="search-wrap">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                <input
                  v-model="pageSearch"
                  type="text"
                  :placeholder="t('wiki.searchPages')"
                  class="search-input"
                />
                <button v-if="pageSearch" class="search-clear" @click="pageSearch = ''">✕</button>
              </div>

              <!-- Batch actions -->
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

              <!-- Raw material filter banner -->
              <div v-if="store.selectedRawId" class="filter-banner">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M22 3H2l8 9.46V19l4 2V12.46L22 3z"/></svg>
                {{ t('wiki.filteredByRaw') }}
                <button class="filter-clear-btn" @click="store.clearRawFilter(store.currentKB!.id)">✕</button>
              </div>

              <!-- Grouped page list -->
              <div class="page-list" v-if="!pageSearch" ref="pageListEl" @scroll="onPageListScroll">
                <div v-for="group in groupedPages" :key="group.type" class="page-group">
                  <button
                    class="group-header"
                    @click="toggleGroup(group.type)"
                  >
                    <svg
                      class="group-chevron"
                      :class="{ expanded: !collapsedGroups.has(group.type) }"
                      width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
                    ><polyline points="9 18 15 12 9 6"/></svg>
                    <span class="group-label">{{ formatGroupLabel(group.type) }}</span>
                    <span class="group-count">{{ group.pages.length }}</span>
                  </button>
                  <div v-if="!collapsedGroups.has(group.type)" class="group-items">
                    <div
                      v-for="page in paginatedGroupPages(group)" :key="page.slug"
                      class="page-item"
                      :class="{
                        active: !batchMode && store.currentPage?.slug === page.slug,
                        'page-item--system': page.pageType === 'system'
                      }"
                      @click="batchMode ? toggleSelect(page.slug) : openPage(page.slug)"
                    >
                      <!-- RFC-051 PR-8: batch checkbox is disabled for protected pages so a stray
                           batch-delete can't drag in overview/log or any locked page. -->
                      <input
                        v-if="batchMode"
                        type="checkbox"
                        :checked="selectedSlugs.includes(page.slug)"
                        :disabled="isProtectedPage(page)"
                        class="page-checkbox"
                        @click.stop="!isProtectedPage(page) && toggleSelect(page.slug)"
                      />
                      <div class="page-item-body">
                        <div class="page-item-title">{{ page.title }}</div>
                        <div class="page-item-meta">
                          <span v-if="page.lastUpdatedBy === 'manual'" class="edit-dot manual" title="Manual edit"></span>
                          <span v-if="page.pageType === 'system'" class="page-flag page-flag--system">{{ t('wiki.systemPageBadge') }}</span>
                          <span v-else-if="page.locked === 1" class="page-flag page-flag--locked">{{ t('wiki.lockedPageBadge') }}</span>
                          <span class="meta-text">v{{ page.version }}</span>
                        </div>
                      </div>
                    </div>
                    <!-- Load more within group (visible when more items exist) -->
                    <button
                      v-if="(groupPageLimit[group.type] || PAGE_STEP) < group.pages.length"
                      class="load-more-btn"
                      @click.stop="loadMoreGroup(group.type)"
                    >
                      {{ t('wiki.loadMore', { n: Math.min(PAGE_STEP, group.pages.length - (groupPageLimit[group.type] || PAGE_STEP)) }) }}
                    </button>
                  </div>
                </div>
                <div v-if="groupedPages.length === 0" class="empty-hint">{{ t('wiki.noPages') }}</div>
              </div>

              <!-- Flat search results -->
              <div class="page-list" v-else>
                <div
                  v-for="page in paginatedSearch" :key="page.slug"
                  class="page-item"
                  :class="{
                    active: !batchMode && store.currentPage?.slug === page.slug,
                    'page-item--system': page.pageType === 'system'
                  }"
                  @click="batchMode ? toggleSelect(page.slug) : openPage(page.slug)"
                >
                  <input
                    v-if="batchMode"
                    type="checkbox"
                    :checked="selectedSlugs.includes(page.slug)"
                    :disabled="isProtectedPage(page)"
                    class="page-checkbox"
                    @click.stop="!isProtectedPage(page) && toggleSelect(page.slug)"
                  />
                  <div class="page-item-body">
                    <div class="page-item-title">{{ page.title }}</div>
                    <div class="page-item-meta">
                      <span class="type-chip">{{ formatGroupLabel(page.pageType || 'other') }}</span>
                      <span v-if="page.pageType === 'system'" class="page-flag page-flag--system">{{ t('wiki.systemPageBadge') }}</span>
                      <span v-else-if="page.locked === 1" class="page-flag page-flag--locked">{{ t('wiki.lockedPageBadge') }}</span>
                      <span class="meta-text">v{{ page.version }}</span>
                    </div>
                  </div>
                </div>
                <div v-if="filteredPages.length > searchPageLimit" class="pagination-row">
                  <button class="load-more-btn" @click="searchPageLimit += PAGE_STEP">
                    {{ t('wiki.loadMore', { n: Math.min(PAGE_STEP, filteredPages.length - searchPageLimit) }) }}
                  </button>
                </div>
                <div v-if="filteredPages.length === 0" class="empty-hint">{{ t('wiki.noResults') }}</div>
              </div>
            </div>
          </div>

          <!-- Right: Content -->
          <div class="wiki-content mc-surface-card">
            <div v-if="!store.currentKB" class="empty-state">
              <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
              </svg>
              <p>{{ t('wiki.selectKB') }}</p>
            </div>

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

              <div v-if="activeTab === 'raw'" class="tab-content">
                <RawMaterialPanel />
              </div>

              <div v-if="activeTab === 'pages'" class="tab-content">
                <WikiPageViewer v-if="store.currentPage" />
                <div v-else class="empty-state">
                  <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
                  </svg>
                  <p>{{ t('wiki.selectPage') }}</p>
                </div>
              </div>

              <div v-if="activeTab === 'graph'" class="tab-content tab-content--graph">
                <WikiGraphView :pages="store.pages" @open-page="openPage" />
              </div>

              <div v-if="activeTab === 'config'" class="tab-content tab-content--config">
                <WikiConfig />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Create KB Modal -->
    <div v-if="showCreateKB" class="modal-overlay" @click.self="showCreateKB = false">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.createKB') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.kbName') }}</label>
          <input v-model="newKBName" type="text" class="form-input" :placeholder="t('wiki.kbNamePlaceholder')" autofocus />
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
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore, isProtectedPage } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import RawMaterialPanel from './components/RawMaterialPanel.vue'
import WikiPageViewer from './components/WikiPageViewer.vue'
import WikiConfig from './components/WikiConfig.vue'
import WikiGraphView from './components/WikiGraphView.vue'

const { t } = useI18n()
const store = useWikiStore()
const pageListEl = ref<HTMLElement | null>(null)

// KB health stats
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

watch(() => store.knowledgeBases.length, () => {
  if (store.knowledgeBases.length > 0) fetchKBStats()
})

const showCreateKB = ref(false)
const newKBName = ref('')
const newKBDesc = ref('')
const activeTab = ref('raw')
const pageSearch = ref('')

// Batch selection
const batchMode = ref(false)
const selectedSlugs = ref<string[]>([])

// Pagination constants
const PAGE_STEP = 20
const searchPageLimit = ref(PAGE_STEP)

// Per-group pagination limit map
const groupPageLimit = reactive<Record<string, number>>({})

// Which groups are collapsed
const collapsedGroups = reactive<Set<string>>(new Set())

function toggleGroup(type: string) {
  if (collapsedGroups.has(type)) collapsedGroups.delete(type)
  else collapsedGroups.add(type)
}

function loadMoreGroup(type: string) {
  groupPageLimit[type] = (groupPageLimit[type] || PAGE_STEP) + PAGE_STEP
}

function paginatedGroupPages(group: { type: string; pages: any[] }) {
  const limit = groupPageLimit[group.type] || PAGE_STEP
  return group.pages.slice(0, limit)
}

function formatGroupLabel(type: string): string {
  if (!type) return t('wiki.pageTypes.other')
  const key = `wiki.pageTypes.${type.toLowerCase()}`
  const translated = t(key)
  // If i18n key not found it returns the key itself; fall back to capitalised type
  return translated === key ? (type.charAt(0).toUpperCase() + type.slice(1)) : translated
}

// Type sort order
const TYPE_ORDER = ['concept', 'technology', 'process', 'person', 'organization', 'product', 'place', 'event', 'term', 'other']

const filteredPages = computed(() => {
  const q = pageSearch.value.toLowerCase()
  if (!q) return store.pages
  return store.pages.filter(
    (p) => p.title.toLowerCase().includes(q) || p.slug.toLowerCase().includes(q)
  )
})

const paginatedSearch = computed(() => filteredPages.value.slice(0, searchPageLimit.value))

const groupedPages = computed(() => {
  const map = new Map<string, typeof store.pages>()
  for (const page of store.pages) {
    const type = (page.pageType || 'other').toLowerCase()
    if (!map.has(type)) map.set(type, [])
    map.get(type)!.push(page)
  }
  // Sort groups by TYPE_ORDER
  return [...map.entries()]
    .sort(([a], [b]) => {
      const ia = TYPE_ORDER.indexOf(a) >= 0 ? TYPE_ORDER.indexOf(a) : 99
      const ib = TYPE_ORDER.indexOf(b) >= 0 ? TYPE_ORDER.indexOf(b) : 99
      return ia - ib
    })
    .map(([type, pages]) => ({ type, pages }))
})

// Reset pagination when KB changes
watch(() => store.currentKB?.id, () => {
  searchPageLimit.value = PAGE_STEP
  Object.keys(groupPageLimit).forEach(k => delete groupPageLimit[k])
  collapsedGroups.clear()
})

watch(() => pageSearch.value, () => {
  searchPageLimit.value = PAGE_STEP
})

const allSelected = computed(() =>
  filteredPages.value.length > 0 && selectedSlugs.value.length === filteredPages.value.length
)

function toggleSelect(slug: string) {
  const idx = selectedSlugs.value.indexOf(slug)
  if (idx >= 0) selectedSlugs.value.splice(idx, 1)
  else selectedSlugs.value.push(slug)
}

function toggleSelectAll() {
  if (allSelected.value) selectedSlugs.value = []
  else selectedSlugs.value = filteredPages.value.map(p => p.slug)
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
  { key: 'graph', label: t('wiki.graph.tab') },
  { key: 'config', label: t('wiki.config') },
])

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

// Infinite scroll: when the page-list container scrolls near the bottom,
// auto-load more items for the last non-fully-expanded group.
function onPageListScroll() {
  const el = pageListEl.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 60) {
    // Find the first group that still has hidden pages and load more
    for (const group of groupedPages.value) {
      const limit = groupPageLimit[group.type] || PAGE_STEP
      if (limit < group.pages.length) {
        loadMoreGroup(group.type)
        break
      }
    }
  }
}

onMounted(() => {
  store.fetchKnowledgeBases()
})
</script>

<style scoped>
.wiki-shell { background: transparent; height: 100%; min-height: 0; overflow: hidden; }
.wiki-frame { height: min(calc(100vh - 28px), 100%); min-height: 0; overflow: hidden; }
.wiki-inner { display: flex; flex-direction: column; height: 100%; min-height: 0; }

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 18px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); transition: opacity 0.15s; }
.btn-primary:hover { opacity: 0.9; }
.btn-primary:disabled { background: var(--mc-border); box-shadow: none; cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; transition: background 0.15s; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Layout */
.wiki-layout { display: flex; gap: 16px; flex: 1; min-height: 0; overflow: hidden; }

/* Sidebar */
.wiki-sidebar {
  width: 272px;
  min-width: 272px;
  overflow-y: auto;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.sidebar-section { display: flex; flex-direction: column; gap: 8px; }
.sidebar-section--pages { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 6px; overflow: hidden; }

.sidebar-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--mc-text-tertiary);
  padding: 0 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.count-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 16px;
  padding: 0 5px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  border-radius: 9999px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0;
}

/* Search */
.search-wrap {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-tertiary);
  transition: border-color 0.15s;
}
.search-wrap:focus-within { border-color: var(--mc-primary); }
.search-input { flex: 1; border: none; background: transparent; font-size: 12px; color: var(--mc-text-primary); outline: none; }
.search-input::placeholder { color: var(--mc-text-tertiary); }
.search-clear { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 11px; padding: 0; line-height: 1; }
.search-clear:hover { color: var(--mc-text-secondary); }

/* KB list */
.kb-list { display: flex; flex-direction: column; gap: 4px; }
.kb-item {
  padding: 10px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
  border: 1px solid transparent;
}
.kb-item:hover { background: var(--mc-bg-muted); }
.kb-item.active { background: var(--mc-primary-bg); border-color: rgba(217, 109, 70, 0.15); }
.kb-item-name { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 5px; }
.kb-item-stats { display: flex; align-items: center; gap: 6px; }
.stat-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.kb-status-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  margin-left: auto;
}
.kb-status-dot.active { background: var(--mc-success); }
.kb-status-dot.processing { background: var(--mc-primary); animation: pulse 1.4s ease-in-out infinite; }
.kb-status-dot.error { background: var(--mc-danger); }
.kb-warn { font-size: 11px; color: var(--mc-danger); margin-top: 3px; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* Page list and groups */
.sidebar-title-row { display: flex; justify-content: space-between; align-items: center; padding: 0 4px; }
.sidebar-title-actions { display: flex; gap: 4px; }
.icon-btn {
  width: 24px;
  height: 24px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  border-radius: 7px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  transition: all 0.15s;
}
.icon-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); }
.icon-btn--active { color: var(--mc-primary); border-color: var(--mc-primary); }

.page-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }

.page-group { display: flex; flex-direction: column; }
.group-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 6px;
  border: none;
  background: none;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.12s;
  width: 100%;
  text-align: left;
}
.group-header:hover { background: var(--mc-bg-muted); }
.group-chevron { color: var(--mc-text-tertiary); transition: transform 0.18s; flex-shrink: 0; }
.group-chevron.expanded { transform: rotate(90deg); }
.group-label { font-size: 11px; font-weight: 600; color: var(--mc-text-secondary); flex: 1; }
.group-count {
  font-size: 10px;
  padding: 1px 5px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  border-radius: 9999px;
  font-variant-numeric: tabular-nums;
}
.group-items { display: flex; flex-direction: column; gap: 1px; padding-left: 12px; }

.page-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.12s;
  border: 1px solid transparent;
}
.page-item:hover { background: var(--mc-bg-muted); }
.page-item.active { background: var(--mc-primary-bg); border-color: rgba(217, 109, 70, 0.12); }
.page-item-body { flex: 1; min-width: 0; }
.page-item-title { font-size: 12px; color: var(--mc-text-primary); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.page-item-meta { display: flex; align-items: center; gap: 4px; margin-top: 2px; }
.meta-text { font-size: 10px; color: var(--mc-text-tertiary); }
.type-chip { font-size: 10px; padding: 1px 5px; background: var(--mc-bg-sunken); border-radius: 4px; color: var(--mc-text-tertiary); }
.edit-dot { width: 5px; height: 5px; border-radius: 9999px; flex-shrink: 0; }
.edit-dot.manual { background: var(--mc-primary); }
.page-checkbox { flex-shrink: 0; cursor: pointer; }
.page-checkbox:disabled { cursor: not-allowed; opacity: 0.4; }

/* RFC-051 PR-8: protection flag chips inside the page-item meta line. */
.page-flag { font-size: 10px; padding: 1px 6px; border-radius: 99px; font-weight: 600; }
.page-flag--system { background: var(--mc-primary-bg); color: var(--mc-primary); border: 1px solid var(--mc-primary); }
.page-flag--locked { background: var(--mc-bg-elevated); color: var(--mc-text-secondary); border: 1px solid var(--mc-border-light); }

/* Subtle accent on the row itself so system pages read as "managed by the system". */
.page-item--system { border-left: 2px solid var(--mc-primary); padding-left: 6px; }

.load-more-btn {
  width: 100%;
  padding: 6px;
  border: 1px dashed var(--mc-border-light);
  background: none;
  border-radius: 8px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: all 0.15s;
  margin-top: 2px;
}
.load-more-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }

.pagination-row { padding: 4px 0; }

/* Batch */
.batch-bar { display: flex; justify-content: space-between; align-items: center; padding: 5px 8px; background: var(--mc-bg-muted); border-radius: 8px; }
.batch-check-all { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; }
.batch-check-all input { cursor: pointer; }
.batch-delete-btn { padding: 3px 10px; border: none; border-radius: 6px; font-size: 11px; font-weight: 600; cursor: pointer; background: rgba(245, 108, 108, 0.1); color: var(--el-color-danger, #f56c6c); }
.batch-delete-btn:hover:not(:disabled) { background: rgba(245, 108, 108, 0.2); }
.batch-delete-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.empty-hint { font-size: 13px; color: var(--mc-text-tertiary); padding: 12px 4px; }

.filter-banner {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  background: var(--mc-primary-bg);
  border: 1px solid rgba(217,109,70,0.2);
  border-radius: 8px;
  font-size: 11px;
  color: var(--mc-primary);
  font-weight: 500;
}
.filter-clear-btn {
  margin-left: auto;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-primary);
  font-size: 11px;
  padding: 0 2px;
  opacity: 0.7;
  line-height: 1;
}
.filter-clear-btn:hover { opacity: 1; }

/* Content area */
.wiki-content { flex: 1; overflow: hidden; min-width: 0; padding: 16px; display: flex; flex-direction: column; min-height: 0; }
.wiki-content-body { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.content-tabs { display: inline-flex; gap: 4px; padding: 4px; background: var(--mc-bg-muted); border-radius: 14px; margin-bottom: 14px; border: 1px solid var(--mc-border-light); align-self: flex-start; }
.tab-btn { padding: 7px 14px; border: none; background: none; cursor: pointer; font-size: 13px; color: var(--mc-text-secondary); border-radius: 10px; transition: all 0.15s; font-weight: 500; }
.tab-btn:hover { color: var(--mc-text-primary); }
.tab-btn.active { color: var(--mc-primary); background: var(--mc-bg-elevated); box-shadow: 0 1px 4px rgba(0,0,0,0.08); font-weight: 600; }
.tab-content { flex: 1; min-height: 0; overflow-y: auto; padding-right: 2px; }
.tab-content--config { overflow: hidden; padding-right: 0; }
.tab-content--graph { overflow: hidden; padding: 0; }

.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 200px; color: var(--mc-text-tertiary); text-align: center; }
.empty-state p { font-size: 14px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 18px; width: 100%; max-width: 520px; padding: 24px; box-shadow: 0 24px 64px rgba(0,0,0,0.18); }
.modal-title { font-size: 17px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 18px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 600; margin-bottom: 6px; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.04em; }
.form-input { width: 100%; padding: 9px 12px; border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; background: var(--mc-bg-muted); color: var(--mc-text-primary); outline: none; font-family: inherit; box-sizing: border-box; transition: border-color 0.15s; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }

@media (max-width: 980px) {
  .wiki-frame { height: 100%; min-height: calc(100vh - 28px); }
  .wiki-layout { flex-direction: column; overflow: visible; }
  .wiki-sidebar { width: 100%; min-width: 0; max-height: 320px; }
  .wiki-content { overflow: visible; }
  .tab-content { overflow: visible; }
  .tab-content--config { overflow: visible; }
}
</style>
