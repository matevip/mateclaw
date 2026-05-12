<template>
  <div class="transformations-panel">
    <header class="panel-header">
      <div class="header-text">
        <h3 class="panel-title">{{ t('wiki.transformations.title') }}</h3>
        <p class="panel-desc">{{ t('wiki.transformations.desc') }}</p>
      </div>
      <div class="header-actions">
        <button class="btn-primary" :disabled="!store.currentKB" @click="openCreate">
          + {{ t('wiki.transformations.createBtn') }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="state-row">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="error" class="state-row state-row--error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="btn-secondary" @click="loadAll">{{ t('common.retry', 'Retry') }}</button>
    </div>

    <div v-else-if="templates.length === 0" class="state-row state-row--empty">
      <span>{{ t('wiki.transformations.empty') }}</span>
    </div>

    <div v-else class="templates-list">
      <article v-for="tpl in templates" :key="tpl.id" class="template-card">
        <div class="template-head">
          <div class="template-title">
            <span class="template-name">{{ tpl.title }}</span>
            <span class="template-handle">{{ tpl.name }}</span>
          </div>
          <div class="template-flags">
            <span class="flag" :class="{ 'flag--on': tpl.applyDefault }">
              {{ tpl.applyDefault ? t('wiki.transformations.applyDefaultOn') : t('wiki.transformations.applyDefaultOff') }}
            </span>
            <span v-if="tpl.outputTarget === 'page'" class="flag flag--on">
              {{ t('wiki.transformations.outputTargetPageBadge') }}
            </span>
            <span class="flag" :class="{ 'flag--muted': tpl.enabled === false }">
              {{ tpl.enabled === false ? t('wiki.transformations.disabled') : t('wiki.transformations.enabled') }}
            </span>
            <span class="flag flag--scope">
              {{ tpl.kbId ? t('wiki.transformations.scopeKb') : t('wiki.transformations.scopeWorkspace') }}
            </span>
          </div>
        </div>
        <p v-if="tpl.description" class="template-desc">{{ tpl.description }}</p>

        <div class="template-actions">
          <select
            class="raw-select"
            v-model="selectedRawByTemplate[tpl.id]"
          >
            <option :value="null" disabled>{{ t('wiki.transformations.pickRaw') }}</option>
            <option v-for="r in completedRaws" :key="r.id" :value="r.id">
              {{ r.title || `raw#${r.id}` }}
            </option>
          </select>
          <button
            class="btn-primary"
            :disabled="!selectedRawByTemplate[tpl.id] || runningTemplateId === tpl.id"
            @click="onApply(tpl)"
          >
            <span v-if="runningTemplateId === tpl.id">{{ t('wiki.transformations.running') }}</span>
            <span v-else>{{ t('wiki.transformations.runApply') }}</span>
          </button>
          <button class="btn-secondary" @click="openEdit(tpl)">{{ t('wiki.transformations.editBtn') }}</button>
          <button class="btn-secondary btn-danger" @click="onDelete(tpl)">{{ t('wiki.transformations.deleteBtn') }}</button>
        </div>

        <div v-if="runsByTemplate[tpl.id]?.length" class="runs-list">
          <div class="runs-title">{{ t('wiki.transformations.runs') }}</div>
          <details v-for="run in runsByTemplate[tpl.id]" :key="run.id" class="run-item">
            <summary>
              <span class="run-status" :class="`run-status--${run.status}`">{{ run.status }}</span>
              <span class="run-meta">
                {{ rawTitleFor(run.rawId) }}
                · {{ formatTimestamp(run.completedAt || run.startedAt || run.createTime) }}
                · {{ formatDuration(run.durationMs) }}
              </span>
              <span v-if="run.outputPageId" class="run-saved-badge">
                {{ t('wiki.transformations.savedAsPage') }} #{{ run.outputPageId }}
              </span>
            </summary>
            <template v-if="run.status === 'completed'">
              <div class="run-actions">
                <button
                  v-if="!run.outputPageId"
                  class="btn-secondary"
                  :disabled="savingRunId === run.id"
                  @click="onSaveRunAsPage(tpl, run)"
                >
                  {{ savingRunId === run.id
                      ? t('wiki.transformations.saving')
                      : t('wiki.transformations.saveAsPageBtn') }}
                </button>
                <button
                  v-else
                  class="btn-secondary"
                  @click="onOpenSavedPage(run)"
                >
                  {{ t('wiki.transformations.openPage') }}
                </button>
              </div>
              <div class="run-output">{{ run.output }}</div>
            </template>
            <div v-else-if="run.status === 'failed'" class="run-error">{{ run.error }}</div>
            <div v-else class="run-output run-output--muted">{{ t('wiki.transformations.running') }}</div>
          </details>
        </div>
      </article>
    </div>

    <!-- Create / edit modal -->
    <div v-if="editorOpen" class="modal-overlay" @click.self="closeEditor">
      <div class="modal">
        <div class="modal-head">
          <h3>{{ editing ? t('wiki.transformations.editBtn') : t('wiki.transformations.createBtn') }}</h3>
          <button class="modal-close" @click="closeEditor">×</button>
        </div>

        <div class="modal-body">
          <label class="field">
            <span class="field-label">{{ t('wiki.transformations.name') }}</span>
            <input
              v-model="form.name"
              :placeholder="t('wiki.transformations.namePlaceholder')"
              :disabled="!!editing"
              class="field-input"
            />
            <span class="field-hint">{{ t('wiki.transformations.nameHelp') }}</span>
          </label>

          <label class="field">
            <span class="field-label">{{ t('wiki.transformations.title2') }}</span>
            <input
              v-model="form.title"
              :placeholder="t('wiki.transformations.titlePlaceholder')"
              class="field-input"
            />
          </label>

          <label class="field">
            <span class="field-label">{{ t('wiki.transformations.description') }}</span>
            <input
              v-model="form.description"
              :placeholder="t('wiki.transformations.descriptionPlaceholder')"
              class="field-input"
            />
          </label>

          <label class="field">
            <span class="field-label">{{ t('wiki.transformations.prompt') }}</span>
            <textarea
              v-model="form.promptTemplate"
              class="field-textarea"
              rows="10"
            ></textarea>
            <span class="field-hint">{{ t('wiki.transformations.promptHelp') }}</span>
          </label>

          <div class="field-row">
            <label class="check">
              <input type="checkbox" v-model="form.applyDefault" />
              <span>{{ t('wiki.transformations.applyDefault') }}</span>
            </label>
            <label class="check">
              <input type="checkbox" v-model="form.enabled" />
              <span>{{ t('wiki.transformations.enabled') }}</span>
            </label>
          </div>

          <fieldset class="field field--group">
            <legend class="field-label">{{ t('wiki.transformations.outputTargetLabel') }}</legend>
            <label class="radio-row">
              <input type="radio" value="none" v-model="form.outputTarget" />
              <span>{{ t('wiki.transformations.outputTargetNone') }}</span>
            </label>
            <label class="radio-row">
              <input type="radio" value="page" v-model="form.outputTarget" />
              <span>{{ t('wiki.transformations.outputTargetPage') }}</span>
            </label>
          </fieldset>
        </div>

        <div class="modal-actions">
          <button class="btn-secondary" @click="closeEditor">{{ t('wiki.transformations.cancelBtn') }}</button>
          <button class="btn-primary" :disabled="saving" @click="onSave">
            {{ saving ? t('common.loading') : t('wiki.transformations.saveBtn') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElIcon, ElMessage } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { wikiApi } from '@/api/index'
import { useWikiStore, type WikiRawMaterial } from '@/stores/useWikiStore'

interface WikiTransformation {
  id: number
  kbId: number | null
  workspaceId: number
  name: string
  title: string
  description: string | null
  promptTemplate: string
  applyDefault: boolean
  enabled: boolean
  modelId: number | null
  outputTarget: 'none' | 'page' | null
}

interface WikiTransformationRun {
  id: number
  transformationId: number
  kbId: number
  rawId: number | null
  pageId: number | null
  inputKind: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  output: string | null
  error: string | null
  durationMs: number | null
  startedAt: string | null
  completedAt: string | null
  createTime: string
  triggeredBy: string
  outputPageId: number | null
}

const { t } = useI18n()
const store = useWikiStore()

const templates = ref<WikiTransformation[]>([])
const runsByTemplate = ref<Record<number, WikiTransformationRun[]>>({})
const selectedRawByTemplate = ref<Record<number, number | null>>({})
const runningTemplateId = ref<number | null>(null)

const loading = ref(false)
const error = ref('')

const editorOpen = ref(false)
const editing = ref<WikiTransformation | null>(null)
const saving = ref(false)
const savingRunId = ref<number | null>(null)
const form = reactive<{
  name: string
  title: string
  description: string
  promptTemplate: string
  applyDefault: boolean
  enabled: boolean
  outputTarget: 'none' | 'page'
}>({
  name: '',
  title: '',
  description: '',
  promptTemplate: '',
  applyDefault: false,
  enabled: true,
  outputTarget: 'none',
})

const completedRaws = computed<WikiRawMaterial[]>(() =>
  store.rawMaterials.filter(
    (r) => r.processingStatus === 'completed' || r.processingStatus === 'partial'
  )
)

function rawTitleFor(rawId: number | null): string {
  if (rawId == null) return '—'
  const r = store.rawMaterials.find((x) => x.id === rawId)
  return r?.title || `raw#${rawId}`
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

async function loadAll() {
  if (!store.currentKB) return
  loading.value = true
  error.value = ''
  try {
    const resp: any = await wikiApi.listTransformations(store.currentKB.id)
    templates.value = resp?.data ?? []
    selectedRawByTemplate.value = Object.fromEntries(
      templates.value.map((t) => [t.id, selectedRawByTemplate.value[t.id] ?? null])
    )
    await Promise.all(templates.value.map((tpl) => loadRunsFor(tpl.id)))
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  } finally {
    loading.value = false
  }
}

async function loadRunsFor(templateId: number) {
  try {
    const resp: any = await wikiApi.listTransformationRuns({ transformationId: templateId, limit: 10 })
    runsByTemplate.value[templateId] = resp?.data ?? []
  } catch {
    runsByTemplate.value[templateId] = []
  }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.title = ''
  form.description = ''
  form.promptTemplate = 'Summarize the source below as 3 key bullet points and 1 sentence of overall takeaway.\n\nSource:\n{input_text}'
  form.applyDefault = false
  form.enabled = true
  form.outputTarget = 'none'
  editorOpen.value = true
}

function openEdit(tpl: WikiTransformation) {
  editing.value = tpl
  form.name = tpl.name
  form.title = tpl.title
  form.description = tpl.description || ''
  form.promptTemplate = tpl.promptTemplate
  form.applyDefault = tpl.applyDefault
  form.enabled = tpl.enabled !== false
  form.outputTarget = tpl.outputTarget === 'page' ? 'page' : 'none'
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
  editing.value = null
}

async function onSave() {
  if (!store.currentKB) return
  if (!form.name.trim() || !form.title.trim() || !form.promptTemplate.trim()) {
    ElMessage.warning(t('common.required', 'All fields are required'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await wikiApi.updateTransformation(editing.value.id, {
        title: form.title,
        description: form.description,
        promptTemplate: form.promptTemplate,
        applyDefault: form.applyDefault,
        enabled: form.enabled,
        outputTarget: form.outputTarget,
      })
    } else {
      await wikiApi.createTransformation({
        kbId: store.currentKB.id,
        name: form.name.trim(),
        title: form.title.trim(),
        description: form.description,
        promptTemplate: form.promptTemplate,
        applyDefault: form.applyDefault,
        enabled: form.enabled,
        outputTarget: form.outputTarget,
      })
    }
    closeEditor()
    await loadAll()
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e))
  } finally {
    saving.value = false
  }
}

async function onDelete(tpl: WikiTransformation) {
  if (!confirm(t('wiki.transformations.deleteConfirm'))) return
  try {
    await wikiApi.deleteTransformation(tpl.id)
    await loadAll()
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e))
  }
}

async function onApply(tpl: WikiTransformation) {
  const rawId = selectedRawByTemplate.value[tpl.id]
  if (!rawId) return
  runningTemplateId.value = tpl.id
  try {
    await wikiApi.applyTransformation(tpl.id, rawId, true)
    await loadRunsFor(tpl.id)
  } catch (e: any) {
    ElMessage.error(e?.message ?? t('wiki.transformations.runFailed'))
  } finally {
    runningTemplateId.value = null
  }
}

async function onSaveRunAsPage(tpl: WikiTransformation, run: WikiTransformationRun) {
  if (run.status !== 'completed' || !run.output) return
  savingRunId.value = run.id
  try {
    const resp: any = await wikiApi.saveTransformationRunAsPage(run.id)
    const payload = resp?.data ?? {}
    if (payload.pageId) {
      run.outputPageId = payload.pageId
    }
    ElMessage.success(t('wiki.transformations.saveAsPageDone'))
    // Refresh the page list in the wiki store so the new page is visible in
    // the sidebar / search results without a manual reload.
    if (store.currentKB) {
      await store.fetchPages(store.currentKB.id)
    }
    await loadRunsFor(tpl.id)
  } catch (e: any) {
    ElMessage.error(e?.message ?? t('wiki.transformations.saveAsPageFailed'))
  } finally {
    savingRunId.value = null
  }
}

async function onOpenSavedPage(run: WikiTransformationRun) {
  if (!run.outputPageId || !store.currentKB) return
  try {
    // Resolve slug from the run's outputPageId by checking the local store
    // first; if not loaded yet, fetch pages.
    if (store.pages.length === 0) {
      await store.fetchPages(store.currentKB.id)
    }
    const page = store.pages.find((p) => p.id === run.outputPageId)
    if (page) {
      await store.loadPage(store.currentKB.id, page.slug)
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e))
  }
}

watch(() => store.currentKB?.id, async (newId, oldId) => {
  if (newId === oldId) return
  templates.value = []
  runsByTemplate.value = {}
  selectedRawByTemplate.value = {}
  if (store.currentKB) {
    // Raw materials are loaded by the parent workspace, but if this tab is
    // opened before the raw panel mounts, force a refresh so the picker has
    // something to show.
    if (store.rawMaterials.length === 0) {
      try { await store.fetchRawMaterials(store.currentKB.id) } catch {}
    }
    await loadAll()
  }
})

onMounted(async () => {
  if (store.currentKB && store.rawMaterials.length === 0) {
    try { await store.fetchRawMaterials(store.currentKB.id) } catch {}
  }
  await loadAll()
})
</script>

<style scoped>
.transformations-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.header-text { flex: 1; min-width: 240px; }
.header-actions { display: flex; gap: 8px; }
.panel-title { font-size: 18px; font-weight: 700; margin: 0 0 4px; color: var(--mc-text-primary); }
.panel-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0; line-height: 1.5; }

.btn-primary, .btn-secondary {
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover:not(:disabled) { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border-color: var(--mc-border); }
.btn-secondary:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.btn-danger:hover { color: var(--el-color-danger); border-color: var(--el-color-danger-light-5); }

.state-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px;
  background: var(--mc-bg-muted);
  border-radius: 10px;
  color: var(--mc-text-secondary);
  font-size: 14px;
}
.state-row--error { color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.state-row--empty { justify-content: center; }

.templates-list { display: flex; flex-direction: column; gap: 12px; }
.template-card {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.template-head { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.template-title { display: flex; flex-direction: column; gap: 2px; }
.template-name { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); }
.template-handle { font-size: 12px; color: var(--mc-text-tertiary); font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); }
.template-flags { display: flex; gap: 6px; flex-wrap: wrap; align-items: flex-start; }
.flag {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  border: 1px solid var(--mc-border-light);
  white-space: nowrap;
}
.flag--on { color: var(--mc-primary); border-color: var(--mc-primary); }
.flag--muted { color: var(--mc-text-tertiary); }
.flag--scope { background: transparent; }

.template-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0; line-height: 1.5; }

.template-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.raw-select {
  padding: 7px 10px;
  border-radius: 8px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-size: 13px;
  min-width: 220px;
}

.runs-list { display: flex; flex-direction: column; gap: 6px; padding-top: 8px; border-top: 1px dashed var(--mc-border-light); }
.runs-title { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--mc-text-tertiary); }
.run-item summary {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--mc-text-secondary);
  padding: 4px 0;
}
.run-status {
  font-size: 10px;
  text-transform: uppercase;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.run-status--completed { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.run-status--failed { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.run-status--running, .run-status--pending { background: var(--mc-bg-muted); color: var(--mc-text-secondary); }
.run-meta { color: var(--mc-text-tertiary); }
.run-output {
  margin-top: 6px;
  padding: 10px 12px;
  background: var(--mc-bg-muted);
  border-radius: 8px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace);
  max-height: 360px;
  overflow-y: auto;
}
.run-output--muted { color: var(--mc-text-tertiary); font-style: italic; }
.run-actions { display: flex; gap: 8px; padding: 8px 0 6px; }
.run-saved-badge {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--mc-primary);
  color: white;
  margin-left: auto;
  white-space: nowrap;
}
.run-error {
  margin-top: 6px;
  padding: 10px 12px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  border-radius: 8px;
  font-size: 12px;
  white-space: pre-wrap;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.modal {
  width: min(640px, 90vw);
  background: var(--mc-bg-elevated);
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  max-height: 90vh;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
}
.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-head h3 { margin: 0; font-size: 15px; }
.modal-close {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  line-height: 1;
}
.modal-body { padding: 16px 18px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--mc-border-light);
}

.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { font-size: 12px; color: var(--mc-text-secondary); font-weight: 500; }
.field-input, .field-textarea {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-family: inherit;
  width: 100%;
  box-sizing: border-box;
}
.field-textarea { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); resize: vertical; }
.field-hint { font-size: 11px; color: var(--mc-text-tertiary); }
.field-row { display: flex; gap: 16px; }
.check { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--mc-text-primary); cursor: pointer; }
.field--group {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field--group legend { padding: 0 4px; font-size: 12px; color: var(--mc-text-secondary); font-weight: 500; }
.radio-row { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--mc-text-primary); cursor: pointer; }
</style>
