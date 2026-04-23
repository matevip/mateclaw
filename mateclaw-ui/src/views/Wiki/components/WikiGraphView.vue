<template>
  <div class="graph-view">
    <!-- Toolbar -->
    <div class="graph-toolbar">
      <div class="graph-stats">
        <span class="stat-item">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="10" stroke-width="1.5"/></svg>
          {{ nodes.length }} {{ t('wiki.graph.nodes') }}
        </span>
        <span class="stat-item">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/></svg>
          {{ edges.length }} {{ t('wiki.graph.edges') }}
        </span>
        <span v-if="orphanCount > 0" class="stat-item stat-warn">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          {{ orphanCount }} {{ t('wiki.graph.orphans') }}
        </span>
      </div>
      <div class="graph-controls">
        <label class="filter-label">
          <input v-model="showOrphans" type="checkbox" />
          {{ t('wiki.graph.showOrphans') }}
        </label>
        <label class="filter-label">
          <input v-model="selectedType" type="checkbox" value="" @change="typeFilter = ''" />
        </label>
        <select v-model="typeFilter" class="type-select">
          <option value="">{{ t('wiki.graph.allTypes') }}</option>
          <option v-for="type in availableTypes" :key="type" :value="type">
            {{ t(`wiki.pageTypes.${type}`, type) }}
          </option>
        </select>
        <button class="btn-icon-sm" :title="t('wiki.graph.resetView')" @click="resetChart">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
        </button>
      </div>
    </div>

    <!-- Chart container -->
    <div ref="chartEl" class="graph-canvas" />

    <!-- Hover tooltip / selected node panel -->
    <div v-if="selectedNode" class="node-panel">
      <div class="node-panel-header">
        <span class="node-type-badge" :style="{ background: typeColor(selectedNode.pageType) }">
          {{ t(`wiki.pageTypes.${selectedNode.pageType || 'other'}`, selectedNode.pageType || 'other') }}
        </span>
        <button class="node-panel-close" @click="selectedNode = null">✕</button>
      </div>
      <div class="node-panel-title">{{ selectedNode.title }}</div>
      <div class="node-panel-summary">{{ selectedNode.summary }}</div>
      <div class="node-panel-links" v-if="selectedNodeLinks.length > 0">
        <div class="links-label">{{ t('wiki.graph.linksTo') }} ({{ selectedNodeLinks.length }})</div>
        <div class="links-list">
          <button
            v-for="link in selectedNodeLinks.slice(0, 8)" :key="link.slug"
            class="link-chip"
            @click="emit('open-page', link.slug)"
          >{{ link.title }}</button>
          <span v-if="selectedNodeLinks.length > 8" class="link-more">+{{ selectedNodeLinks.length - 8 }}</span>
        </div>
      </div>
      <button class="btn-open-page" @click="emit('open-page', selectedNode.slug)">
        {{ t('wiki.graph.openPage') }} →
      </button>
    </div>

    <!-- Empty state -->
    <div v-if="nodes.length === 0" class="graph-empty">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
        <circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3"/>
        <line x1="5" y1="5" x2="19" y2="19" stroke-width="0.5"/>
      </svg>
      <p>{{ t('wiki.graph.empty') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { WikiPage } from '@/stores/useWikiStore'

echarts.use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

const { t } = useI18n()
const props = defineProps<{ pages: WikiPage[] }>()
const emit = defineEmits<{ (e: 'open-page', slug: string): void }>()

const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const showOrphans = ref(true)
const typeFilter = ref('')
const selectedNode = ref<WikiPage | null>(null)
const selectedType = ref(false)

// Type → color map
const TYPE_COLORS: Record<string, string> = {
  concept: '#D96E46',
  person: '#5B8DEF',
  place: '#4CAF82',
  event: '#F59E0B',
  technology: '#8B5CF6',
  organization: '#EC4899',
  product: '#14B8A6',
  term: '#6B7280',
  process: '#F97316',
  other: '#9CA3AF',
}

function typeColor(type: string | null | undefined): string {
  return TYPE_COLORS[(type || 'other').toLowerCase()] || TYPE_COLORS.other
}

// Parse outgoing links JSON string → slug[]
function parseLinks(outgoingLinks: string | null | undefined): string[] {
  if (!outgoingLinks) return []
  try {
    const arr = JSON.parse(outgoingLinks)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

const slugToPage = computed(() => {
  const map = new Map<string, WikiPage>()
  for (const p of props.pages) map.set(p.slug, p)
  return map
})

// Pages filtered by type
const filteredPages = computed(() => {
  let ps = props.pages
  if (typeFilter.value) ps = ps.filter(p => (p.pageType || 'other').toLowerCase() === typeFilter.value)
  return ps
})

// Build edges from outgoing links
const edges = computed(() => {
  const result: { source: string; target: string }[] = []
  const slugSet = new Set(filteredPages.value.map(p => p.slug))
  for (const page of filteredPages.value) {
    for (const link of parseLinks(page.outgoingLinks)) {
      if (slugSet.has(link) && link !== page.slug) {
        result.push({ source: page.slug, target: link })
      }
    }
  }
  return result
})

// Compute in-degree for each node
const inDegree = computed(() => {
  const map = new Map<string, number>()
  for (const e of edges.value) {
    map.set(e.target, (map.get(e.target) || 0) + 1)
  }
  return map
})

const orphanCount = computed(() =>
  filteredPages.value.filter(p => (inDegree.value.get(p.slug) || 0) === 0 && parseLinks(p.outgoingLinks).length === 0).length
)

const nodes = computed(() => {
  let ps = filteredPages.value
  if (!showOrphans.value) {
    ps = ps.filter(p =>
      (inDegree.value.get(p.slug) || 0) > 0 || parseLinks(p.outgoingLinks).length > 0
    )
  }
  return ps
})

const availableTypes = computed(() => {
  const types = new Set(props.pages.map(p => (p.pageType || 'other').toLowerCase()))
  return [...types].sort()
})

const selectedNodeLinks = computed(() => {
  if (!selectedNode.value) return []
  return parseLinks(selectedNode.value.outgoingLinks)
    .map(slug => slugToPage.value.get(slug))
    .filter(Boolean) as WikiPage[]
})

function buildOption() {
  const nodeList = nodes.value.map(p => {
    const deg = (inDegree.value.get(p.slug) || 0) + parseLinks(p.outgoingLinks).length
    const size = Math.max(10, Math.min(40, 10 + deg * 3))
    return {
      id: p.slug,
      name: p.title,
      symbolSize: size,
      itemStyle: { color: typeColor(p.pageType) },
      label: { show: size > 18, fontSize: 10, color: 'var(--mc-text-secondary)' },
      _page: p,
    }
  })

  const edgeList = edges.value
    .filter(e => nodes.value.some(n => n.slug === e.source) && nodes.value.some(n => n.slug === e.target))
    .map(e => ({
      source: e.source,
      target: e.target,
      lineStyle: { color: 'rgba(150,150,150,0.3)', width: 1 },
    }))

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType !== 'node') return ''
        const p = params.data._page as WikiPage
        return `<div style="max-width:220px"><strong>${p.title}</strong><br/><small style="color:#999">${t(`wiki.pageTypes.${p.pageType || 'other'}`, p.pageType || 'other')}</small><br/><span style="font-size:11px">${(p.summary || '').substring(0, 80)}${(p.summary || '').length > 80 ? '…' : ''}</span></div>`
      },
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodeList,
      links: edgeList,
      roam: true,
      force: {
        repulsion: 200,
        gravity: 0.08,
        edgeLength: [60, 150],
        friction: 0.6,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 2 },
      },
      lineStyle: { color: 'rgba(150,150,150,0.3)', curveness: 0.1 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 6,
    }],
  }
}

function renderChart() {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value, undefined, { renderer: 'canvas' })
    chart.on('click', (params: any) => {
      if (params.dataType === 'node' && params.data._page) {
        selectedNode.value = params.data._page
      }
    })
  }
  chart.setOption(buildOption(), { notMerge: true })
}

function resetChart() {
  selectedNode.value = null
  if (chart) chart.setOption(buildOption(), { notMerge: true })
}

const resizeObserver = new ResizeObserver(() => {
  chart?.resize()
})

onMounted(async () => {
  await nextTick()
  renderChart()
  if (chartEl.value) resizeObserver.observe(chartEl.value)
})

onBeforeUnmount(() => {
  resizeObserver.disconnect()
  chart?.dispose()
  chart = null
})

watch([nodes, edges], async () => {
  await nextTick()
  renderChart()
})

watch(() => props.pages.length, async () => {
  await nextTick()
  renderChart()
})
</script>

<style scoped>
.graph-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  position: relative;
}

.graph-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  gap: 12px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.graph-stats { display: flex; align-items: center; gap: 12px; }
.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.stat-warn { color: var(--mc-danger, #f56c6c); }

.graph-controls { display: flex; align-items: center; gap: 8px; }
.filter-label { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; }
.type-select {
  padding: 3px 8px;
  font-size: 11px;
  border: 1px solid var(--mc-border-light);
  border-radius: 7px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  cursor: pointer;
  outline: none;
}
.btn-icon-sm {
  width: 26px;
  height: 26px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  border-radius: 7px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.btn-icon-sm:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); }

.graph-canvas {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.node-panel {
  position: absolute;
  right: 12px;
  top: 52px;
  width: 240px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 14px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 10;
}
.node-panel-header { display: flex; align-items: center; justify-content: space-between; }
.node-type-badge {
  font-size: 10px;
  font-weight: 600;
  color: white;
  padding: 2px 8px;
  border-radius: 99px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.node-panel-close { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 12px; }
.node-panel-close:hover { color: var(--mc-text-primary); }
.node-panel-title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.node-panel-summary { font-size: 12px; color: var(--mc-text-secondary); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.links-label { font-size: 10px; font-weight: 600; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.06em; }
.links-list { display: flex; flex-wrap: wrap; gap: 4px; }
.link-chip {
  padding: 2px 8px;
  font-size: 11px;
  border: 1px solid var(--mc-border-light);
  border-radius: 99px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.12s;
}
.link-chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.link-more { font-size: 11px; color: var(--mc-text-tertiary); padding: 2px 4px; }
.btn-open-page {
  padding: 6px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-muted);
  color: var(--mc-primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
}
.btn-open-page:hover { background: var(--mc-primary-bg); border-color: var(--mc-primary); }

.graph-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--mc-text-tertiary);
  pointer-events: none;
}
.graph-empty p { font-size: 14px; }
</style>
