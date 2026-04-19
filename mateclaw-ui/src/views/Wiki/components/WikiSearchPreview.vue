<template>
  <div class="search-preview">
    <h4 class="preview-title">{{ t('wiki.configPanel.searchPreview') }}</h4>
    <div class="preview-input-row">
      <input
        v-model="query"
        type="text"
        class="preview-input"
        :placeholder="t('wiki.configPanel.searchPreviewPlaceholder')"
        @keyup.enter="runSearch"
      />
      <button class="btn-secondary" @click="runSearch" :disabled="searching || !query.trim()">
        {{ searching ? '...' : t('wiki.configPanel.searchPreviewRun') }}
      </button>
    </div>

    <div v-if="results.length > 0" class="preview-results">
      <div v-for="r in results" :key="r.slug" class="preview-result-item">
        <div class="result-header">
          <span class="result-slug">[[{{ r.slug }}]]</span>
          <span class="result-title">{{ r.title }}</span>
        </div>
        <div v-if="r.snippet" class="result-snippet">"{{ r.snippet }}"</div>
        <div class="result-meta">
          <span v-if="r.matchedBy?.length" class="result-matched">
            {{ t('wiki.configPanel.searchPreviewRun') }}: {{ r.matchedBy.join(', ') }}
          </span>
          <span v-if="r.reason" class="result-reason">· {{ r.reason }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { wikiApi } from '@/api/index'

const { t } = useI18n()

const props = defineProps<{
  kbId: number
}>()

interface SearchResult {
  slug: string
  title: string
  snippet?: string
  matchedBy?: string[]
  reason?: string
  score: number
}

const query = ref('')
const results = ref<SearchResult[]>([])
const searching = ref(false)

async function runSearch() {
  if (!query.value.trim() || !props.kbId) return
  searching.value = true
  try {
    const res: any = await wikiApi.searchPreview(props.kbId, {
      query: query.value.trim(),
      mode: 'hybrid',
      topK: 5,
    })
    results.value = res.data || res || []
  } catch (e) {
    console.error('[SearchPreview] Failed:', e)
    results.value = []
  } finally {
    searching.value = false
  }
}
</script>

<style scoped>
.search-preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: var(--mc-bg-sunken);
  border-radius: 10px;
  border: 1px solid var(--mc-border-light);
}

.preview-title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }

.preview-input-row { display: flex; gap: 8px; }
.preview-input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 13px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  outline: none;
}
.preview-input:focus { border-color: var(--mc-primary); }

.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }

.preview-results {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

.preview-result-item {
  padding: 10px 12px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
}
.result-header { display: flex; gap: 8px; align-items: center; margin-bottom: 4px; }
.result-slug { font-size: 12px; font-family: 'JetBrains Mono', monospace; color: var(--mc-primary); }
.result-title { font-size: 13px; font-weight: 500; color: var(--mc-text-primary); }
.result-snippet { font-size: 12px; color: var(--mc-text-secondary); font-style: italic; line-height: 1.5; margin-bottom: 4px; max-height: 60px; overflow: hidden; }
.result-meta { display: flex; gap: 6px; font-size: 11px; color: var(--mc-text-tertiary); }
</style>
