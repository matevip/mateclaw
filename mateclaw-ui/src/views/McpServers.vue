<template>
  <div class="page-container">
    <div class="page-shell">
      <div class="page-header">
        <div class="page-lead">
          <div class="page-kicker">{{ t('mcp.kicker') }}</div>
          <h1 class="page-title">{{ t('mcp.title') }}</h1>
          <p class="page-desc">{{ t('mcp.desc') }}</p>
        </div>
        <div class="header-actions">
          <button class="btn-secondary" @click="refreshAll" :disabled="refreshing">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
            </svg>
            {{ t('mcp.refreshAll') }}
          </button>
          <button class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('mcp.addServer') }}
          </button>
        </div>
      </div>

      <div class="page-stage">
        <div class="tools-table-wrap">
          <table class="tools-table">
        <thead>
          <tr>
            <th>{{ t('mcp.columns.name') }}</th>
            <th>{{ t('mcp.columns.lastStatus') }}</th>
            <th>{{ t('mcp.columns.toolCount') }}</th>
            <th>{{ t('mcp.columns.enabled') }}</th>
            <th>{{ t('mcp.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="server in servers" :key="server.id" class="tool-row">
            <td>
              <div class="tool-info">
                <div class="tool-icon-wrap" :class="'status-icon-' + server.lastStatus">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="2" y="2" width="20" height="8" rx="2" ry="2"/>
                    <rect x="2" y="14" width="20" height="8" rx="2" ry="2"/>
                    <line x1="6" y1="6" x2="6.01" y2="6"/>
                    <line x1="6" y1="18" x2="6.01" y2="18"/>
                  </svg>
                </div>
                <div>
                  <div class="tool-name" :title="server.name">{{ server.name }}</div>
                  <div class="tool-type-inline">
                    <span class="type-badge" :class="'type-' + server.transport">
                      {{ t('mcp.transport.' + server.transport) }}
                    </span>
                  </div>
                  <div class="tool-desc" :title="server.description || '-'">{{ server.description || '-' }}</div>
                </div>
              </div>
            </td>
            <td>
              <div class="status-stack">
                <span class="status-badge" :class="'status-' + server.lastStatus" :title="t('mcp.status.' + (server.lastStatus || 'disconnected'))">
                    {{ t('mcp.status.' + (server.lastStatus || 'disconnected')) }}
                </span>
                <span v-if="server.lastConnectedTime" class="status-time" :title="server.lastConnectedTime">{{ server.lastConnectedTime }}</span>
              </div>
              <div v-if="server.lastError" class="status-error" :title="server.lastError">
                {{ truncate(server.lastError, 40) }}
              </div>
            </td>
            <td>
              <span class="tool-count">{{ server.toolCount || 0 }}</span>
            </td>
            <td>
              <label class="toggle-switch">
                <input type="checkbox" :checked="server.enabled" @change="toggleServer(server)" />
                <span class="toggle-slider"></span>
              </label>
            </td>
            <td>
              <div class="row-actions">
                <button class="row-btn" @click="openDetailModal(server)" :title="t('common.view')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="3"/><path d="M2.05 12a9.94 9.94 0 0 1 19.9 0 9.94 9.94 0 0 1-19.9 0z"/>
                  </svg>
                </button>
                <button class="row-btn" @click="testConnection(server)" :disabled="testingId === server.id" :title="t('mcp.actions.test')">
                  <svg v-if="testingId !== server.id" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                    <polyline points="22 4 12 14.01 9 11.01"/>
                  </svg>
                  <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="spin">
                    <line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/>
                    <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/>
                    <line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/>
                    <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"/><line x1="16.24" y1="7.76" x2="19.07" y2="4.93"/>
                  </svg>
                </button>
                <button class="row-btn" @click="openEditModal(server)" :title="t('common.edit')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button class="row-btn danger" @click="deleteServer(server)" :title="t('common.delete')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="servers.length === 0">
            <td colspan="5" class="empty-row">
              <div class="empty-state">
                <span class="empty-icon">
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="color: var(--mc-text-tertiary)">
                    <rect x="2" y="2" width="20" height="8" rx="2" ry="2"/>
                    <rect x="2" y="14" width="20" height="8" rx="2" ry="2"/>
                    <line x1="6" y1="6" x2="6.01" y2="6"/>
                    <line x1="6" y1="18" x2="6.01" y2="18"/>
                  </svg>
                </span>
                <p>{{ t('mcp.messages.empty') }}</p>
                <p class="empty-sub">{{ t('mcp.messages.emptyDesc') }}</p>
              </div>
            </td>
          </tr>
        </tbody>
          </table>
        </div>
      </div>
    </div>

    <div v-if="detailServer" class="modal-overlay">
      <div class="modal modal-wide">
        <div class="modal-header">
          <h2>{{ detailServer.name }}</h2>
          <button class="modal-close" @click="closeDetailModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body detail-grid">
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.columns.transport') }}</div>
            <div class="detail-value">{{ t('mcp.transport.' + detailServer.transport) }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.columns.lastStatus') }}</div>
            <div class="detail-value">{{ t('mcp.status.' + (detailServer.lastStatus || 'disconnected')) }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.columns.toolCount') }}</div>
            <div class="detail-value">{{ detailServer.toolCount || 0 }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.fields.enabled') }}</div>
            <div class="detail-value">{{ detailServer.enabled ? 'Enabled' : 'Disabled' }}</div>
          </div>
          <div class="detail-item detail-item-full" v-if="detailServer.transport === 'stdio'">
            <div class="detail-label">{{ t('mcp.fields.command') }}</div>
            <div class="detail-value detail-block mono">{{ detailServer.command || '-' }}</div>
            <div class="detail-subvalue" v-if="detailServer.argsJson">{{ detailServer.argsJson }}</div>
          </div>
          <div class="detail-item detail-item-full" v-else>
            <div class="detail-label">{{ t('mcp.fields.url') }}</div>
            <div class="detail-value detail-block mono">{{ detailServer.url || '-' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.fields.connectTimeout') }}</div>
            <div class="detail-value">{{ detailServer.connectTimeoutSeconds || 30 }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.fields.readTimeout') }}</div>
            <div class="detail-value">{{ detailServer.readTimeoutSeconds || 30 }}</div>
          </div>
          <div class="detail-item detail-item-full" v-if="detailServer.lastError">
            <div class="detail-label">Error</div>
            <div class="detail-value detail-block">{{ detailServer.lastError }}</div>
          </div>
          <div class="detail-item detail-item-full" v-if="detailServer.description">
            <div class="detail-label">{{ t('mcp.fields.description') }}</div>
            <div class="detail-value detail-block">{{ detailServer.description }}</div>
          </div>
        </div>
      </div>
    </div>

    <transition name="toast">
      <div v-if="testResult" class="test-toast" :class="testResult.success ? 'toast-success' : 'toast-error'">
        <div class="toast-title">{{ testResult.success ? t('mcp.testResult.success') : t('mcp.testResult.failed') }}</div>
        <div v-if="testResult.success" class="toast-detail">
          {{ t('mcp.testResult.tools', { count: testResult.toolCount }) }} &middot;
          {{ t('mcp.testResult.latency', { ms: testResult.latencyMs }) }}
        </div>
        <div v-else class="toast-detail">{{ testResult.message }}</div>
      </div>
    </transition>

    <div v-if="showModal" class="modal-overlay">
      <div class="modal modal-wide">
        <div class="modal-header">
          <h2>{{ editing ? t('mcp.modal.editTitle') : t('mcp.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" :placeholder="t('mcp.placeholders.name')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.transport') }} *</label>
              <select v-model="form.transport" class="form-input">
                <option value="stdio">Stdio</option>
                <option value="sse">SSE</option>
                <option value="streamable_http">Streamable HTTP</option>
              </select>
            </div>

            <div class="form-group full-width">
              <label class="form-label">{{ t('mcp.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('mcp.placeholders.description')" />
            </div>

            <template v-if="form.transport === 'stdio'">
              <div class="form-group">
                <label class="form-label">{{ t('mcp.fields.command') }} *</label>
                <input v-model="form.command" class="form-input" :placeholder="t('mcp.placeholders.command')" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('mcp.fields.cwd') }}</label>
                <input v-model="form.cwd" class="form-input" :placeholder="t('mcp.placeholders.cwd')" />
              </div>
              <div class="form-group full-width">
                <label class="form-label">{{ t('mcp.fields.args') }}</label>
                <input v-model="form.argsJson" class="form-input mono" placeholder='["-y", "@modelcontextprotocol/server-xxx"]' />
              </div>
              <div class="form-group full-width">
                <label class="form-label">{{ t('mcp.fields.env') }}</label>
                <textarea v-model="form.envJson" class="form-input form-textarea mono" placeholder='{"API_KEY": "xxx"}' rows="2"></textarea>
              </div>
            </template>

            <template v-else>
              <div class="form-group full-width">
                <label class="form-label">{{ t('mcp.fields.url') }} *</label>
                <input v-model="form.url" class="form-input mono" :placeholder="t('mcp.placeholders.url')" />
              </div>
              <div class="form-group full-width">
                <label class="form-label">{{ t('mcp.fields.headers') }}</label>
                <textarea v-model="form.headersJson" class="form-input form-textarea mono" placeholder='{"Authorization": "Bearer xxx"}' rows="2"></textarea>
              </div>
            </template>

            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.connectTimeout') }}</label>
              <input v-model.number="form.connectTimeoutSeconds" type="number" class="form-input" min="5" max="300" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.readTimeout') }}</label>
              <input v-model.number="form.readTimeoutSeconds" type="number" class="form-input" min="5" max="300" />
            </div>

            <div class="form-group full-width">
              <label class="toggle-inline">
                <input type="checkbox" v-model="form.enabled" />
                <span class="toggle-slider-inline"></span>
                {{ t('mcp.fields.enabled') }}
              </label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveServer" :disabled="!canSave">{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mcpApi } from '@/api/index'

const { t } = useI18n()

interface McpServer {
  id: number
  name: string
  description: string
  transport: string
  url: string
  headersJson: string
  command: string
  argsJson: string
  envJson: string
  cwd: string
  enabled: boolean
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
  lastStatus: string
  lastError: string
  lastConnectedTime: string
  toolCount: number
  builtin: boolean
}

interface TestResultData {
  success: boolean
  message: string
  toolCount: number
  latencyMs: number
  discoveredTools: string[]
}

const servers = ref<McpServer[]>([])
const showModal = ref(false)
const editing = ref<McpServer | null>(null)
const detailServer = ref<McpServer | null>(null)
const refreshing = ref(false)
const testingId = ref<number | null>(null)
const testResult = ref<TestResultData | null>(null)

const defaultForm = () => ({
  name: '',
  description: '',
  transport: 'stdio',
  url: '',
  headersJson: '',
  command: '',
  argsJson: '',
  envJson: '',
  cwd: '',
  connectTimeoutSeconds: 30,
  readTimeoutSeconds: 30,
  enabled: true,
})
const form = ref<any>(defaultForm())

const canSave = computed(() => {
  if (!form.value.name) return false
  if (form.value.transport === 'stdio' && !form.value.command) return false
  if (form.value.transport !== 'stdio' && !form.value.url) return false
  return true
})

onMounted(loadServers)

let loadGeneration = 0

async function loadServers() {
  const gen = ++loadGeneration
  try {
    const res: any = await mcpApi.list()
    if (gen === loadGeneration) {
      servers.value = res.data || []
    }
  } catch (e: any) {
    if (gen === loadGeneration) {
      ElMessage.error(t('mcp.messages.loadFailed'))
    }
  }
}

function openCreateModal() {
  editing.value = null
  form.value = defaultForm()
  showModal.value = true
}

function openEditModal(server: McpServer) {
  editing.value = server
  form.value = {
    name: server.name,
    description: server.description || '',
    transport: server.transport,
    url: server.url || '',
    headersJson: server.headersJson || '',
    command: server.command || '',
    argsJson: server.argsJson || '',
    envJson: server.envJson || '',
    cwd: server.cwd || '',
    connectTimeoutSeconds: server.connectTimeoutSeconds || 30,
    readTimeoutSeconds: server.readTimeoutSeconds || 30,
    enabled: server.enabled,
  }
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editing.value = null
}

function openDetailModal(server: McpServer) {
  detailServer.value = server
}

function closeDetailModal() {
  detailServer.value = null
}

async function saveServer() {
  try {
    if (editing.value) {
      await mcpApi.update(editing.value.id, form.value)
      ElMessage.success(t('mcp.messages.updateSuccess'))
    } else {
      await mcpApi.create(form.value)
      ElMessage.success(t('mcp.messages.createSuccess'))
    }
    closeModal()
    await loadServers()
  } catch (e: any) {
    ElMessage.error(e?.message || t('mcp.messages.saveFailed'))
  }
}

async function deleteServer(server: McpServer) {
  try {
    await ElMessageBox.confirm(
      t('mcp.messages.deleteConfirm', { name: server.name }),
      t('common.delete'),
      { type: 'warning' }
    )
  } catch { return }
  try {
    await mcpApi.delete(server.id)
    ElMessage.success(t('mcp.messages.deleteSuccess'))
    await loadServers()
  } catch (e: any) {
    ElMessage.error(e?.message || t('mcp.messages.saveFailed'))
  }
}

async function toggleServer(server: McpServer) {
  try {
    await mcpApi.toggle(server.id, !server.enabled)
    ElMessage.success(t('mcp.messages.toggleSuccess'))
    await loadServers()
  } catch (e: any) {
    ElMessage.error(e?.message || t('mcp.messages.saveFailed'))
  }
}

async function testConnection(server: McpServer) {
  testingId.value = server.id
  testResult.value = null
  try {
    const res: any = await mcpApi.test(server.id)
    testResult.value = res.data
    setTimeout(() => { testResult.value = null }, 4000)
  } catch (e: any) {
    testResult.value = { success: false, message: e?.message || 'Unknown error', toolCount: 0, latencyMs: 0, discoveredTools: [] }
    setTimeout(() => { testResult.value = null }, 4000)
  } finally {
    testingId.value = null
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await mcpApi.refresh()
    ElMessage.success(t('mcp.messages.refreshSuccess'))
    await loadServers()
  } catch (e: any) {
    ElMessage.error(e?.message || t('mcp.messages.saveFailed'))
  } finally {
    refreshing.value = false
  }
}

function truncate(str: string, len: number) {
  return str && str.length > len ? str.substring(0, len) + '...' : str
}
</script>

<style scoped>
.page-container {
  height: 100%;
  overflow-y: auto;
  padding: 0;
  background: transparent;
}

.page-shell {
  min-height: 100%;
  padding: 24px;
  background:
    radial-gradient(circle at top left, color-mix(in srgb, var(--mc-primary-bg) 34%, transparent) 0, transparent 36%),
    linear-gradient(180deg, color-mix(in srgb, var(--mc-bg-elevated) 78%, white 22%) 0%, var(--mc-bg) 100%);
}

.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.page-lead { display: flex; flex-direction: column; gap: 8px; }
.page-kicker {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 18%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--mc-primary-bg) 72%, var(--mc-bg-elevated) 28%);
  color: var(--mc-primary-hover);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.page-title { font-size: clamp(28px, 4vw, 40px); line-height: 0.95; font-weight: 800; color: var(--mc-text-primary); margin: 0; }
.page-desc { max-width: 620px; font-size: 15px; line-height: 1.55; color: var(--mc-text-secondary); margin: 0; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }
.page-stage {
  background: linear-gradient(180deg, color-mix(in srgb, var(--mc-bg-elevated) 96%, white 4%) 0%, var(--mc-bg-elevated) 100%);
  border: 1px solid var(--mc-border);
  border-radius: 18px;
  padding: 12px;
  box-shadow: 0 18px 48px rgba(152, 93, 63, 0.06);
}

.tools-table-wrap { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 14px; overflow-x: auto; overflow-y: hidden; }
.tools-table { width: 100%; table-layout: fixed; border-collapse: collapse; }
.tools-table th { position: sticky; top: 0; z-index: 1; padding: 12px 16px; text-align: left; font-size: 11px; font-weight: 700; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.08em; background: color-mix(in srgb, var(--mc-bg-sunken) 86%, white 14%); border-bottom: 1px solid var(--mc-border); }
.tool-row { border-bottom: 1px solid var(--mc-border-light); transition: background 0.1s; }
.tool-row:hover { background: var(--mc-bg-sunken); }
.tool-row:last-child { border-bottom: none; }
.tools-table td { padding: 16px; font-size: 14px; color: var(--mc-text-primary); vertical-align: top; }
.tool-info { display: flex; align-items: center; gap: 10px; }
.tool-icon-wrap { width: 32px; height: 32px; background: var(--mc-bg-sunken); border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--mc-text-secondary); }
.status-icon-connected { color: var(--mc-primary); background: var(--mc-primary-bg); }
.status-icon-error { color: var(--mc-danger); background: var(--mc-danger-bg); }
.tool-name { max-width: 180px; font-weight: 700; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-type-inline { margin-top: 6px; }
.tool-desc { max-width: 240px; font-size: 12px; color: var(--mc-text-tertiary); margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.type-badge { padding: 3px 10px; border-radius: 10px; font-size: 12px; font-weight: 500; }
.type-stdio { background: var(--mc-primary-bg); color: var(--mc-primary); }
.type-sse { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.type-streamable_http { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.status-badge { padding: 3px 10px; border-radius: 10px; font-size: 12px; font-weight: 500; }
.status-connected { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-disconnected { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-stack { display: flex; flex-direction: column; gap: 6px; }
.status-time { font-size: 12px; color: var(--mc-text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-error { font-size: 11px; color: var(--mc-danger); margin-top: 2px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-count { font-weight: 600; color: var(--mc-text-primary); }
.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }
.row-actions { display: flex; gap: 6px; }
.row-btn { width: 30px; height: 30px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--mc-text-secondary); transition: all 0.15s; }
.row-btn:hover { background: var(--mc-bg-sunken); }
.row-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.row-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }
.empty-row { padding: 40px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--mc-text-tertiary); }
.empty-sub { font-size: 13px; margin: 0; }
.empty-state p { font-size: 14px; margin: 0; }

.test-toast { position: fixed; bottom: 24px; right: 24px; padding: 14px 20px; border-radius: 10px; z-index: 2000; box-shadow: 0 4px 20px rgba(0,0,0,0.15); }
.toast-success { background: var(--mc-primary); color: white; }
.toast-error { background: var(--mc-danger); color: white; }
.toast-title { font-weight: 600; font-size: 14px; }
.toast-detail { font-size: 12px; margin-top: 2px; opacity: 0.9; }
.toast-enter-active, .toast-leave-active { transition: all 0.3s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(20px); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-wide { max-width: 600px; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.detail-item { display: flex; flex-direction: column; gap: 6px; }
.detail-item-full { grid-column: 1 / -1; }
.detail-label { font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--mc-text-tertiary); }
.detail-value { font-size: 14px; line-height: 1.5; color: var(--mc-text-primary); word-break: break-word; }
.detail-subvalue { font-size: 12px; color: var(--mc-text-tertiary); white-space: pre-wrap; }
.detail-block { padding: 12px 14px; border: 1px solid var(--mc-border); border-radius: 10px; background: var(--mc-bg-sunken); white-space: pre-wrap; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); width: 100%; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-textarea { resize: vertical; min-height: 40px; font-family: 'SF Mono', 'Fira Code', monospace; }
.mono { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 13px; }
.toggle-inline { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--mc-text-primary); cursor: pointer; }
.toggle-inline input { width: 16px; height: 16px; accent-color: var(--mc-primary); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }

@media (max-width: 900px) {
  .page-header { flex-direction: column; align-items: stretch; }
  .btn-primary, .btn-secondary { width: 100%; justify-content: center; }
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
