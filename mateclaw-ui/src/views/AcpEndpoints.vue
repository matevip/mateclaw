<template>
  <div class="page-container">
    <div class="page-shell">
      <div class="page-header">
        <div class="page-lead">
          <div class="page-kicker">{{ t('acp.kicker') }}</div>
          <h1 class="page-title">{{ t('acp.title') }}</h1>
          <p class="page-desc">{{ t('acp.desc') }}</p>
        </div>
        <div class="header-actions">
          <button class="btn-primary" @click="openCreateModal">
            + {{ t('acp.addEndpoint') }}
          </button>
        </div>
      </div>

      <!-- Table -->
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ t('acp.columns.name') }}</th>
              <th>{{ t('acp.columns.command') }}</th>
              <th class="th-center">{{ t('acp.columns.status') }}</th>
              <th class="th-center">{{ t('acp.columns.enabled') }}</th>
              <th>{{ t('acp.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ep in endpoints" :key="ep.id" class="data-row">
              <td>
                <div class="endpoint-info">
                  <div class="endpoint-name">
                    {{ ep.displayName || ep.name }}
                    <span v-if="ep.builtin" class="builtin-badge">{{ t('acp.builtin') }}</span>
                  </div>
                  <div class="endpoint-slug">{{ ep.name }}</div>
                  <div v-if="ep.description" class="endpoint-desc">{{ ep.description }}</div>
                </div>
              </td>
              <td class="cell-cmd">
                <code>{{ ep.command }} {{ argsPreview(ep) }}</code>
              </td>
              <td class="th-center">
                <span class="status-badge" :class="`status-${(ep.lastStatus || 'unknown').toLowerCase()}`">
                  {{ ep.lastStatus || t('acp.statusUnknown') }}
                </span>
                <div v-if="ep.lastError" class="status-error" :title="ep.lastError">
                  {{ ep.lastError.slice(0, 80) }}
                </div>
              </td>
              <td class="th-center">
                <label class="toggle-switch">
                  <input type="checkbox" :checked="ep.enabled" @change="toggle(ep)" />
                  <span class="toggle-slider"></span>
                </label>
              </td>
              <td>
                <div class="row-actions">
                  <button class="btn-link" :disabled="testingId === ep.id" @click="testEndpoint(ep)">
                    {{ testingId === ep.id ? t('acp.testing') : t('acp.test') }}
                  </button>
                  <button class="btn-link" @click="openEditModal(ep)">{{ t('common.edit') }}</button>
                  <button v-if="!ep.builtin" class="btn-link danger" @click="removeEndpoint(ep)">{{ t('common.delete') }}</button>
                </div>
              </td>
            </tr>
            <tr v-if="endpoints.length === 0">
              <td colspan="5" class="empty-row">
                <div class="empty-state">
                  <span class="empty-icon">🔌</span>
                  <p>{{ t('acp.empty') }}</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Test result panel -->
      <div v-if="lastTestResult" class="test-result mc-surface-card">
        <div class="test-result-head">
          <span :class="`status-badge status-${(lastTestResult.status || '').toLowerCase()}`">
            {{ lastTestResult.status }}
          </span>
          <span class="test-result-name">{{ lastTestResult.name }}</span>
          <span v-if="lastTestResult.elapsedMs != null" class="test-result-elapsed">
            · {{ lastTestResult.elapsedMs }}ms
          </span>
          <button class="btn-link" @click="lastTestResult = null">×</button>
        </div>
        <pre class="test-result-pre">{{ JSON.stringify(lastTestResult, null, 2) }}</pre>
      </div>
    </div>

    <!-- Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editing ? t('acp.modal.editTitle') : t('acp.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" placeholder="my-acp-agent" :disabled="!!editing" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.displayName') }}</label>
              <input v-model="form.displayName" class="form-input" placeholder="My ACP Agent" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('acp.fields.description') }}</label>
              <input v-model="form.description" class="form-input" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.command') }} *</label>
              <input v-model="form.command" class="form-input mono" placeholder="npx" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.toolParseMode') }}</label>
              <select v-model="form.toolParseMode" class="form-input">
                <option value="call_title">call_title</option>
                <option value="call_detail">call_detail</option>
                <option value="update_detail">update_detail</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('acp.fields.args') }}</label>
              <input v-model="form.argsJson" class="form-input mono" placeholder='["-y","@zed-industries/codex-acp"]' />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('acp.fields.env') }}</label>
              <textarea v-model="form.envJson" class="form-input form-textarea mono" rows="2" placeholder='{"OPENAI_API_KEY":"..."}'></textarea>
            </div>
            <div class="form-group full-width">
              <label class="toggle-inline">
                <input type="checkbox" v-model="form.enabled" />
                {{ t('acp.fields.enabled') }}
              </label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" :disabled="!canSave" @click="saveEndpoint">{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { acpApi } from '@/api/index'

interface AcpEndpoint {
  id: number
  name: string
  displayName?: string
  description?: string
  command: string
  argsJson?: string
  envJson?: string
  toolParseMode?: string
  builtin?: boolean
  trusted?: boolean
  enabled?: boolean
  lastStatus?: string
  lastError?: string
  lastTestedAt?: string
  stdioBufferLimitBytes?: number
}

const { t } = useI18n()
const endpoints = ref<AcpEndpoint[]>([])
const showModal = ref(false)
const editing = ref<AcpEndpoint | null>(null)
const testingId = ref<number | null>(null)
const lastTestResult = ref<any>(null)

const defaultForm = (): any => ({
  name: '',
  displayName: '',
  description: '',
  command: '',
  argsJson: '[]',
  envJson: '{}',
  toolParseMode: 'call_title',
  enabled: false,
})
const form = reactive<any>(defaultForm())

const canSave = computed(() => !!form.name && !!form.command)

onMounted(loadEndpoints)

async function loadEndpoints() {
  try {
    const res: any = await acpApi.list()
    endpoints.value = res?.data || []
  } catch (e: any) {
    endpoints.value = []
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('acp.loadFailed'))
  }
}

function argsPreview(ep: AcpEndpoint): string {
  if (!ep.argsJson) return ''
  try {
    const parsed = JSON.parse(ep.argsJson)
    if (Array.isArray(parsed)) return parsed.join(' ')
  } catch { /* fall through */ }
  return ep.argsJson
}

function openCreateModal() {
  editing.value = null
  Object.assign(form, defaultForm())
  showModal.value = true
}

function openEditModal(ep: AcpEndpoint) {
  editing.value = ep
  Object.assign(form, defaultForm(), {
    name: ep.name,
    displayName: ep.displayName || '',
    description: ep.description || '',
    command: ep.command,
    argsJson: ep.argsJson || '[]',
    envJson: ep.envJson || '{}',
    toolParseMode: ep.toolParseMode || 'call_title',
    enabled: !!ep.enabled,
  })
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editing.value = null
}

async function saveEndpoint() {
  // Sanity-check args/env are valid JSON before sending; the server
  // will tolerate empty strings, but we'd rather fail fast in UI.
  try {
    if (form.argsJson) JSON.parse(form.argsJson)
    if (form.envJson) JSON.parse(form.envJson)
  } catch (e: any) {
    ElMessage.error(t('acp.invalidJson') + ': ' + (e?.message || 'parse error'))
    return
  }
  try {
    if (editing.value) {
      await acpApi.update(editing.value.id, form)
    } else {
      await acpApi.create(form)
    }
    closeModal()
    await loadEndpoints()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('acp.saveFailed'))
  }
}

async function removeEndpoint(ep: AcpEndpoint) {
  try {
    await ElMessageBox.confirm(t('acp.deleteConfirm', { name: ep.name }),
        t('acp.deleteTitle'), { type: 'warning' })
  } catch { return }
  try {
    await acpApi.delete(ep.id)
    await loadEndpoints()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('acp.deleteFailed'))
  }
}

async function toggle(ep: AcpEndpoint) {
  try {
    await acpApi.toggle(ep.id, !ep.enabled)
    await loadEndpoints()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('acp.toggleFailed'))
  }
}

async function testEndpoint(ep: AcpEndpoint) {
  testingId.value = ep.id
  lastTestResult.value = null
  try {
    const res: any = await acpApi.test(ep.id)
    lastTestResult.value = res?.data || null
    await loadEndpoints()
  } catch (e: any) {
    lastTestResult.value = {
      name: ep.name,
      status: 'ERROR',
      error: typeof e === 'string' ? e : e?.message || 'unknown',
    }
  } finally {
    testingId.value = null
  }
}
</script>

<style scoped>
.page-container { padding: 0; height: 100%; min-height: 0; overflow: auto; }
.page-shell { display: flex; flex-direction: column; gap: 14px; padding: 22px; min-height: 100%; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.page-kicker { font-size: 11px; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.08em; font-weight: 700; }
.page-title { font-size: 22px; font-weight: 700; color: var(--mc-text-primary); margin: 4px 0; }
.page-desc { color: var(--mc-text-secondary); margin: 0; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.btn-primary { padding: 8px 14px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 13px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-link { background: none; border: none; color: var(--mc-primary); cursor: pointer; padding: 4px 8px; font-size: 12px; font-weight: 500; }
.btn-link:hover { color: var(--mc-primary-hover); }
.btn-link.danger { color: var(--mc-danger); }
.btn-link:disabled { color: var(--mc-text-tertiary); cursor: not-allowed; }

.table-wrap { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border-light); border-radius: 14px; overflow: hidden; }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { padding: 12px 14px; text-align: left; font-size: 11px; font-weight: 700; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.06em; background: var(--mc-bg-muted); border-bottom: 1px solid var(--mc-border); }
.data-table td { padding: 12px 14px; font-size: 13px; color: var(--mc-text-primary); border-bottom: 1px solid var(--mc-border-light); vertical-align: top; }
.data-row:hover { background: var(--mc-bg-muted); }
.th-center { text-align: center; }
.endpoint-info { display: flex; flex-direction: column; gap: 2px; }
.endpoint-name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.builtin-badge { padding: 1px 6px; background: rgba(34, 197, 94, 0.12); color: #16a34a; border-radius: 999px; font-size: 10px; font-weight: 700; text-transform: uppercase; }
.endpoint-slug { font-size: 11px; color: var(--mc-text-tertiary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.endpoint-desc { font-size: 12px; color: var(--mc-text-secondary); margin-top: 4px; }
.cell-cmd code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; background: var(--mc-bg-sunken); padding: 2px 6px; border-radius: 4px; color: var(--mc-text-primary); display: inline-block; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }

.status-badge { padding: 2px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; }
.status-ok { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.status-error { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }

td .status-error { background: none; color: var(--mc-text-tertiary); font-size: 11px; padding: 4px 0 0; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.row-actions { display: flex; gap: 4px; }

.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }

.empty-row { padding: 40px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--mc-text-tertiary); }
.empty-icon { font-size: 32px; }

.test-result { padding: 14px; }
.test-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.test-result-name { font-weight: 600; }
.test-result-elapsed { color: var(--mc-text-tertiary); font-size: 12px; }
.test-result-pre { background: var(--mc-bg-sunken); padding: 12px; border-radius: 8px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; line-height: 1.5; max-height: 360px; overflow: auto; white-space: pre-wrap; word-break: break-word; margin: 0; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 640px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 17px; font-weight: 600; margin: 0; }
.modal-close { width: 30px; height: 30px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 22px; line-height: 1; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 18px 22px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.form-input { padding: 8px 10px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 13px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-input.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.form-textarea { resize: vertical; }
.toggle-inline { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 22px; border-top: 1px solid var(--mc-border-light); }
</style>
