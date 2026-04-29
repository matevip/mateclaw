<template>
  <Teleport to="body">
    <Transition name="cfg-modal">
      <div v-if="open" class="cfg-modal-overlay" @click.self="emit('close')">
        <div class="cfg-modal" style="max-width:700px">

          <!-- Header -->
          <div class="cfg-modal__header">
            <div class="cfg-modal__title-group">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
              </svg>
              <span>{{ t('wiki.configPanel.searchPreview') }}</span>
              <span v-if="kbName" class="cfg-modal__kb">{{ kbName }}</span>
            </div>
            <div class="cfg-modal__actions">
              <button class="btn-cfg-close" @click="emit('close')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
          </div>

          <!-- Body -->
          <div class="sp-body">

            <!-- Input row -->
            <div class="sp-input-row">
              <input
                ref="inputEl"
                v-model="query"
                type="text"
                class="sp-input"
                :placeholder="t('wiki.configPanel.searchPreviewPlaceholder')"
                @keyup.enter="runSearch"
              />
              <button class="sp-btn" @click="runSearch" :disabled="searching || !query.trim()">
                <svg v-if="searching" class="sp-spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
                </svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
                {{ searching ? t('wiki.configPanel.searching') : t('wiki.configPanel.searchPreviewRun') }}
              </button>
            </div>

            <!-- Mode chips -->
            <div class="sp-modes">
              <button
                v-for="m in modes"
                :key="m.value"
                :class="['sp-mode-chip', { active: searchMode === m.value }]"
                @click="searchMode = m.value"
              >{{ m.label }}</button>
            </div>

            <!-- Results -->
            <div v-if="results.length > 0" class="sp-results">
              <div class="sp-results-header">
                <span>找到 {{ results.length }} 条结果</span>
                <button class="sp-clear" @click="results = []">清除</button>
              </div>
              <div v-for="(r, idx) in results" :key="r.slug" class="sp-item">
                <div class="sp-item__rank">{{ idx + 1 }}</div>
                <div class="sp-item__body">
                  <div class="sp-item__head">
                    <span class="sp-item__title">{{ r.title }}</span>
                    <span class="sp-item__slug">[[{{ r.slug }}]]</span>
                    <span v-if="r.score != null" class="sp-item__score">{{ (r.score * 100).toFixed(0) }}%</span>
                  </div>
                  <div v-if="r.snippet" class="sp-item__snippet">{{ r.snippet }}</div>
                  <div v-if="r.matchedBy?.length || r.reason" class="sp-item__meta">
                    <span v-if="r.matchedBy?.length" class="sp-item__matched">
                      {{ r.matchedBy.join(' · ') }}
                    </span>
                    <span v-if="r.reason" class="sp-item__reason">{{ r.reason }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- Empty state -->
            <div v-else-if="searched && !searching" class="sp-empty">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
                <line x1="8" y1="11" x2="14" y2="11"/>
              </svg>
              <p>无匹配结果</p>
            </div>

            <!-- Hint (before first search) -->
            <div v-else-if="!searched" class="sp-hint">
              输入问题或关键词，测试知识库的检索效果
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { wikiApi } from '@/api/index'

const { t } = useI18n()

const props = defineProps<{
  open: boolean
  kbId: number
  kbName?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

interface SearchResult {
  slug: string
  title: string
  snippet?: string
  matchedBy?: string[]
  reason?: string
  score: number
}

const modes = [
  { value: 'hybrid', label: '混合' },
  { value: 'semantic', label: '语义' },
  { value: 'keyword', label: '关键词' },
]

const query = ref('')
const searchMode = ref('hybrid')
const results = ref<SearchResult[]>([])
const searching = ref(false)
const searched = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)

watch(() => props.open, (v) => {
  if (v) {
    results.value = []
    searched.value = false
    nextTick(() => inputEl.value?.focus())
  }
})

async function runSearch() {
  if (!query.value.trim() || !props.kbId) return
  searching.value = true
  searched.value = true
  try {
    const res: any = await wikiApi.searchPreview(props.kbId, {
      query: query.value.trim(),
      mode: searchMode.value,
      topK: 8,
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
.sp-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sp-input-row { display: flex; gap: 8px; }
.sp-input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  font-size: 14px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  outline: none;
  transition: border-color 0.15s;
}
.sp-input:focus { border-color: var(--mc-primary); }

.sp-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 18px;
  border: none;
  border-radius: 10px;
  background: var(--mc-primary);
  color: white;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
  white-space: nowrap;
  flex-shrink: 0;
}
.sp-btn:hover { opacity: 0.88; }
.sp-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.sp-spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.sp-modes { display: flex; gap: 6px; }
.sp-mode-chip {
  padding: 4px 12px;
  border-radius: 99px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.sp-mode-chip.active { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); font-weight: 500; }
.sp-mode-chip:not(.active):hover { border-color: var(--mc-border); color: var(--mc-text-secondary); background: var(--mc-bg-sunken); }

.sp-results { display: flex; flex-direction: column; gap: 0; border: 1px solid var(--mc-border-light); border-radius: 12px; overflow: hidden; }
.sp-results-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  background: var(--mc-bg-sunken);
  font-size: 11px;
  color: var(--mc-text-tertiary);
  border-bottom: 1px solid var(--mc-border-light);
}
.sp-clear { background: none; border: none; cursor: pointer; font-size: 11px; color: var(--mc-text-tertiary); padding: 0; }
.sp-clear:hover { color: var(--mc-danger, #e53); }

.sp-item {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--mc-border-light);
  transition: background 0.1s;
}
.sp-item:last-child { border-bottom: none; }
.sp-item:hover { background: var(--mc-bg-muted); }

.sp-item__rank {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.sp-item__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.sp-item__head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.sp-item__title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.sp-item__slug { font-size: 11px; font-family: 'JetBrains Mono', monospace; color: var(--mc-primary); opacity: 0.8; }
.sp-item__score {
  margin-left: auto;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  padding: 1px 7px;
  border-radius: 99px;
  flex-shrink: 0;
}

.sp-item__snippet {
  font-size: 13px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
  word-break: break-word;
}

.sp-item__meta { display: flex; flex-wrap: wrap; gap: 6px; }
.sp-item__matched {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-sunken);
  padding: 1px 7px;
  border-radius: 6px;
}
.sp-item__reason { font-size: 11px; color: var(--mc-text-tertiary); font-style: italic; }

/* Empty / hint */
.sp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 0;
  color: var(--mc-text-tertiary);
}
.sp-empty p { font-size: 13px; margin: 0; }

.sp-hint {
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: var(--mc-text-tertiary);
  line-height: 1.6;
}
</style>
