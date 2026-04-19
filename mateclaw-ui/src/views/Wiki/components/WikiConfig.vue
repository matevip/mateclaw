<template>
  <div class="wiki-config">
    <div class="config-header">
      <h3 class="config-title">{{ t('wiki.configTitle') }}</h3>
      <p class="config-desc">{{ t('wiki.configDesc') }}</p>
    </div>

    <!-- Embedding model binding -->
    <div class="embedding-config">
      <label class="embedding-label">
        Embedding Model
        <span class="embedding-hint">Semantic search model for this KB; leave empty for system default</span>
      </label>
      <div class="embedding-row">
        <select v-model="embeddingModelId" class="embedding-select" :disabled="savingEmbedding">
          <option value="">Follow system default</option>
          <option v-for="m in embeddingOptions" :key="m.id" :value="String(m.id)">
            {{ m.name }} ({{ m.modelName }})
          </option>
        </select>
        <button class="btn-secondary" @click="saveEmbeddingBinding" :disabled="savingEmbedding">
          {{ savingEmbedding ? t('wiki.saving') : t('common.save') }}
        </button>
      </div>
    </div>

    <!-- RFC-033: Step model strategy -->
    <details class="config-section">
      <summary class="section-toggle">{{ t('wiki.configPanel.modelStrategy') }}</summary>
      <div class="step-models-grid">
        <div v-for="step in stepKeys" :key="step" class="step-model-row">
          <label class="step-label">{{ t(`wiki.configPanel.stepModel.${step}`) }}</label>
          <select v-model="stepModels[step]" class="step-select">
            <option value="">Global default</option>
            <option v-for="m in chatModelOptions" :key="m.id" :value="String(m.id)">
              {{ m.name }}
            </option>
          </select>
        </div>
      </div>
      <div class="fallback-section">
        <label class="step-label">{{ t('wiki.configPanel.fallbackModels') }}</label>
        <div class="fallback-list">
          <span v-for="(fId, idx) in fallbackModelIds" :key="idx" class="fallback-tag">
            {{ chatModelOptions.find(m => String(m.id) === String(fId))?.name || fId }}
            <button class="fallback-remove" @click="fallbackModelIds.splice(idx, 1)">×</button>
          </span>
          <select class="fallback-add-select" @change="addFallback($event)">
            <option value="">+ Add</option>
            <option v-for="m in chatModelOptions" :key="m.id" :value="String(m.id)">
              {{ m.name }}
            </option>
          </select>
        </div>
      </div>
      <button class="btn-secondary btn-sm" @click="saveStepModels" :disabled="savingStepModels">
        {{ savingStepModels ? t('wiki.saving') : t('common.save') }}
      </button>
    </details>

    <!-- Config editor -->
    <textarea
      v-model="configContent"
      class="config-editor"
      rows="20"
      :placeholder="t('wiki.configPlaceholder')"
    ></textarea>

    <div class="config-actions">
      <button class="btn-secondary" @click="loadConfig">{{ t('common.reset') }}</button>
      <button class="btn-primary" @click="saveConfig" :disabled="saving">
        {{ saving ? t('wiki.saving') : t('common.save') }}
      </button>
    </div>

    <!-- RFC-033: Search preview -->
    <WikiSearchPreview v-if="store.currentKB" :kb-id="store.currentKB.id" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'
import { wikiApi, modelApi } from '@/api/index'
import WikiSearchPreview from './WikiSearchPreview.vue'

const { t } = useI18n()
const store = useWikiStore()

const configContent = ref('')
const saving = ref(false)

// Embedding binding
interface ModelOption { id: string | number; name: string; modelName: string }
const embeddingModelId = ref<string>('')
const embeddingOptions = ref<ModelOption[]>([])
const savingEmbedding = ref(false)

// Step model strategy
const stepKeys = ['route', 'create_page', 'merge_page', 'enrich', 'summary']
const stepModels = reactive<Record<string, string>>({})
const fallbackModelIds = ref<string[]>([])
const chatModelOptions = ref<ModelOption[]>([])
const savingStepModels = ref(false)

async function loadEmbeddingOptions() {
  try {
    const res = await modelApi.listByType('embedding')
    embeddingOptions.value = ((res.data as any[]) || []).filter(m => m.enabled !== false)
  } catch (e) {
    console.error('[WikiConfig] Failed to load embedding options', e)
  }
}

async function loadChatModelOptions() {
  try {
    const res = await modelApi.listByType('chat')
    chatModelOptions.value = ((res.data as any[]) || []).filter(m => m.enabled !== false)
  } catch (e) {
    console.error('[WikiConfig] Failed to load chat model options', e)
  }
}

function loadEmbeddingBinding() {
  const kb: any = store.currentKB
  embeddingModelId.value = kb?.embeddingModelId ? String(kb.embeddingModelId) : ''
}

async function saveEmbeddingBinding() {
  if (!store.currentKB) return
  savingEmbedding.value = true
  try {
    await wikiApi.updateKB(store.currentKB.id, {
      embeddingModelId: embeddingModelId.value === '' ? null : embeddingModelId.value,
    })
    const kb: any = store.currentKB
    kb.embeddingModelId = embeddingModelId.value === '' ? null : Number(embeddingModelId.value)
  } catch (e) {
    console.error('[WikiConfig] Failed to save embedding binding', e)
  } finally {
    savingEmbedding.value = false
  }
}

function loadStepModels() {
  // Parse from KB configContent if it contains stepModels
  stepKeys.forEach(k => stepModels[k] = '')
  fallbackModelIds.value = []

  if (!store.currentKB) return
  try {
    const cfg = store.currentKB.configContent ? JSON.parse(store.currentKB.configContent) : null
    if (cfg?.stepModels) {
      for (const key of stepKeys) {
        const fullKey = `heavy_ingest.${key}`
        if (cfg.stepModels[fullKey]) stepModels[key] = String(cfg.stepModels[fullKey])
      }
    }
    if (cfg?.fallbackModelIds) {
      fallbackModelIds.value = cfg.fallbackModelIds.map(String)
    }
  } catch { /* config might not be JSON */ }
}

async function saveStepModels() {
  if (!store.currentKB) return
  savingStepModels.value = true
  try {
    // Build stepModels map
    const stepMap: Record<string, number> = {}
    for (const key of stepKeys) {
      if (stepModels[key]) {
        stepMap[`heavy_ingest.${key}`] = Number(stepModels[key])
      }
    }
    // Merge into existing config
    let existingConfig: any = {}
    try {
      if (store.currentKB.configContent) {
        existingConfig = JSON.parse(store.currentKB.configContent)
      }
    } catch { /* not JSON, will overwrite */ }

    existingConfig.stepModels = Object.keys(stepMap).length > 0 ? stepMap : undefined
    existingConfig.fallbackModelIds = fallbackModelIds.value.length > 0
      ? fallbackModelIds.value.map(Number)
      : undefined

    await wikiApi.updateConfig(store.currentKB.id, JSON.stringify(existingConfig, null, 2))
  } catch (e) {
    console.error('[WikiConfig] Failed to save step models', e)
  } finally {
    savingStepModels.value = false
  }
}

function addFallback(event: Event) {
  const select = event.target as HTMLSelectElement
  const val = select.value
  if (val && !fallbackModelIds.value.includes(val)) {
    fallbackModelIds.value.push(val)
  }
  select.value = ''
}

async function loadConfig() {
  if (!store.currentKB) return
  try {
    const res: any = await wikiApi.getConfig(store.currentKB.id)
    configContent.value = res.data?.content || ''
  } catch (e) {
    console.error('Failed to load config', e)
  }
}

async function saveConfig() {
  if (!store.currentKB) return
  saving.value = true
  try {
    await wikiApi.updateConfig(store.currentKB.id, configContent.value)
  } catch (e) {
    console.error('Failed to save config', e)
  } finally {
    saving.value = false
  }
}

watch(() => store.currentKB, () => {
  loadConfig()
  loadEmbeddingBinding()
  loadStepModels()
}, { immediate: true })

loadEmbeddingOptions()
loadChatModelOptions()
</script>

<style scoped>
.wiki-config {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 0;
}

.config-header { padding-bottom: 10px; border-bottom: 1px solid var(--mc-border-light); }
.config-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 6px; letter-spacing: -0.02em; }
.config-desc { font-size: 13px; color: var(--mc-text-tertiary); margin: 0; line-height: 1.6; }

/* Embedding binding */
.embedding-config { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; background: var(--mc-bg-sunken); border-radius: 10px; border: 1px solid var(--mc-border-light); }
.embedding-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); display: flex; align-items: baseline; gap: 8px; }
.embedding-hint { font-size: 11px; font-weight: 400; color: var(--mc-text-tertiary); }
.embedding-row { display: flex; gap: 8px; align-items: center; }
.embedding-select { flex: 1; padding: 7px 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); font-size: 13px; outline: none; }
.embedding-select:focus { border-color: var(--mc-primary); }
.embedding-select:disabled { opacity: 0.6; cursor: not-allowed; }

/* Step model strategy */
.config-section { padding: 12px 14px; background: var(--mc-bg-sunken); border-radius: 10px; border: 1px solid var(--mc-border-light); }
.section-toggle { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); cursor: pointer; padding: 4px 0; }
.step-models-grid { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.step-model-row { display: flex; align-items: center; gap: 12px; }
.step-label { font-size: 12px; color: var(--mc-text-secondary); min-width: 100px; }
.step-select { flex: 1; padding: 6px 10px; border: 1px solid var(--mc-border); border-radius: 6px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); font-size: 12px; outline: none; }

.fallback-section { margin-top: 12px; }
.fallback-list { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 6px; }
.fallback-tag { display: flex; align-items: center; gap: 4px; padding: 3px 8px; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 6px; font-size: 11px; }
.fallback-remove { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 14px; padding: 0 2px; }
.fallback-remove:hover { color: var(--mc-danger); }
.fallback-add-select { padding: 4px 8px; border: 1px dashed var(--mc-border); border-radius: 6px; background: transparent; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; }

/* Editor */
.config-editor { width: 100%; flex: 1; min-height: 0; padding: 16px; border: 1px solid var(--mc-border); border-radius: 14px; font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace; font-size: 13px; line-height: 1.7; resize: none; overflow: auto; background: var(--mc-bg-elevated); color: var(--mc-text-primary); outline: none; }
.config-editor:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

.config-actions { display: flex; justify-content: flex-end; gap: 10px; flex-shrink: 0; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-sm { padding: 6px 12px; font-size: 12px; }

@media (max-width: 768px) {
  .config-editor { min-height: 42vh; flex: none; resize: vertical; }
  .config-actions { flex-direction: column-reverse; }
  .config-actions .btn-primary, .config-actions .btn-secondary { width: 100%; justify-content: center; }
  .step-model-row { flex-direction: column; align-items: flex-start; }
  .step-label { min-width: 0; }
}
</style>
