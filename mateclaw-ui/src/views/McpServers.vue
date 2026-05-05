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

      <!-- Table -->
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ t('mcp.columns.name') }}</th>
              <th>{{ t('mcp.columns.lastStatus') }}</th>
              <th class="th-center">{{ t('mcp.columns.toolCount') }}</th>
              <th class="th-center">{{ t('mcp.columns.enabled') }}</th>
              <th>{{ t('mcp.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="server in servers" :key="server.id" class="data-row">
              <td>
                <div class="server-info">
                  <div class="server-icon" :class="'icon-' + (server.lastStatus || 'disconnected')">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="2" y="2" width="20" height="8" rx="2" ry="2"/>
                      <rect x="2" y="14" width="20" height="8" rx="2" ry="2"/>
                      <line x1="6" y1="6" x2="6.01" y2="6"/>
                      <line x1="6" y1="18" x2="6.01" y2="18"/>
                    </svg>
                  </div>
                  <div>
                    <div class="server-name">{{ server.name }}</div>
                    <div class="server-meta">
                      <span class="transport-tag">{{ server.transport }}</span>
                      <span v-if="server.description" class="server-desc">{{ server.description }}</span>
                    </div>
                  </div>
                </div>
              </td>
              <td>
                <div class="status-row">
                  <span class="status-dot" :class="'dot-' + (server.lastStatus || 'disconnected')"></span>
                  <span class="status-label">{{ t('mcp.status.' + (server.lastStatus || 'disconnected')) }}</span>
                  <span v-if="server.lastConnectedTime" class="status-time">{{ formatRelativeTime(server.lastConnectedTime) }}</span>
                </div>
                <div v-if="server.lastError" class="status-error" :title="server.lastError">
                  {{ truncate(server.lastError, 36) }}
                </div>
              </td>
              <td class="td-center">
                <span class="count-pill">{{ server.toolCount || 0 }}</span>
              </td>
              <td class="td-center">
                <label class="toggle">
                  <input type="checkbox" :checked="server.enabled" @change="toggleServer(server)" />
                  <span class="toggle-track"></span>
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
                  <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="opacity:.35">
                    <rect x="2" y="2" width="20" height="8" rx="2" ry="2"/>
                    <rect x="2" y="14" width="20" height="8" rx="2" ry="2"/>
                    <line x1="6" y1="6" x2="6.01" y2="6"/>
                    <line x1="6" y1="18" x2="6.01" y2="18"/>
                  </svg>
                  <p>{{ t('mcp.messages.empty') }}</p>
                  <p class="empty-sub">{{ t('mcp.messages.emptyDesc') }}</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Detail Modal -->
    <div v-if="detailServer" class="modal-overlay" @click.self="closeDetailModal">
      <div class="modal modal-wide">
        <div class="modal-header">
          <h2>{{ detailServer.name }}</h2>
          <button class="modal-close" @click="closeDetailModal">&times;</button>
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
          <div class="detail-item detail-full" v-if="detailServer.transport === 'stdio'">
            <div class="detail-label">{{ t('mcp.fields.command') }}</div>
            <div class="detail-value detail-code">{{ detailServer.command || '-' }}</div>
            <div class="detail-sub" v-if="detailServer.argsJson">{{ detailServer.argsJson }}</div>
          </div>
          <div class="detail-item detail-full" v-else>
            <div class="detail-label">{{ t('mcp.fields.url') }}</div>
            <div class="detail-value detail-code">{{ detailServer.url || '-' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.fields.connectTimeout') }}</div>
            <div class="detail-value">{{ detailServer.connectTimeoutSeconds || 30 }}s</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">{{ t('mcp.fields.readTimeout') }}</div>
            <div class="detail-value">{{ detailServer.readTimeoutSeconds || 30 }}s</div>
          </div>
          <div class="detail-item detail-full" v-if="detailServer.lastError">
            <div class="detail-label">Error</div>
            <div class="detail-value detail-code" style="color: var(--mc-danger)">{{ detailServer.lastError }}</div>
          </div>
          <div class="detail-item detail-full" v-if="detailServer.description">
            <div class="detail-label">{{ t('mcp.fields.description') }}</div>
            <div class="detail-value">{{ detailServer.description }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Toast -->
    <transition name="toast">
      <div v-if="testResult" class="test-toast" :class="testResult.success ? 'toast-ok' : 'toast-fail'">
        <strong>{{ testResult.success ? t('mcp.testResult.success') : t('mcp.testResult.failed') }}</strong>
        <span v-if="testResult.success">
          {{ t('mcp.testResult.tools', { count: testResult.toolCount }) }} &middot;
          {{ t('mcp.testResult.latency', { ms: testResult.latencyMs }) }}
        </span>
        <span v-else>{{ testResult.message }}</span>
      </div>
    </transition>

    <!-- Create / Edit Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal modal-wide">
        <div class="modal-header">
          <h2>{{ editing ? t('mcp.modal.editTitle') : t('mcp.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">&times;</button>
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
import { ElMessage } from 'element-plus'
import { mcConfirm } from '@/components/common/useConfirm'
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
  const ok = await mcConfirm({
    title: t('common.delete'),
    message: t('mcp.messages.deleteConfirm', { name: server.name }),
    tone: 'danger',
  })
  if (!ok) return
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

function formatRelativeTime(dateStr: string): string {
  if (!dateStr) return ''
  const now = Date.now()
  const time = new Date(dateStr).getTime()
  const diff = now - time
  if (diff < 0) return ''
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return t('chat.timeJustNow', 'Just now')
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}h ago`
  const d = new Date(dateStr)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<style scoped>
/* ===== Shell ===== */
.page-container { height: 100%; overflow-y: auto; }
.page-shell { padding: 24px; }

/* ===== Header ===== */
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.page-lead { display: flex; flex-direction: column; gap: 6px; }
.page-kicker {
  display: inline-flex; width: fit-content;
  padding: 4px 10px; border-radius: 999px;
  font-size: 11px; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase;
  color: var(--mc-primary); background: var(--mc-primary-bg);
}
.page-title { font-size: clamp(24px, 3.5vw, 36px); font-weight: 800; color: var(--mc-text-primary); margin: 0; }
.page-desc { font-size: 14px; color: var(--mc-text-secondary); margin: 0; }
.header-actions { display: flex; gap: 8px; }

/* ===== Buttons ===== */
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 9px 16px; background: var(--mc-primary); color: #fff; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: .4; cursor: not-allowed; }
.btn-secondary { display: flex; align-items: center; gap: 6px; padding: 9px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: .4; cursor: not-allowed; }

/* ===== Table — one surface, no nesting ===== */
.table-wrap {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  overflow: hidden;
}
.data-table { width: 100%; border-collapse: collapse; }
.data-table th {
  padding: 10px 16px; text-align: left;
  font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-sunken);
  border-bottom: 1px solid var(--mc-border);
  white-space: nowrap;
}
.th-center { text-align: center; }
.data-row { border-bottom: 1px solid var(--mc-border-light); transition: background .12s; }
.data-row:last-child { border-bottom: none; }
.data-row:hover { background: var(--mc-bg-sunken); }
.data-table td { padding: 12px 16px; font-size: 14px; color: var(--mc-text-primary); vertical-align: middle; }
.td-center { text-align: center; }

/* ===== Server name cell ===== */
.server-info { display: flex; align-items: center; gap: 10px; }
.server-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  background: var(--mc-bg-sunken); color: var(--mc-text-tertiary);
}
.icon-connected { color: var(--mc-primary); background: var(--mc-primary-bg); }
.icon-error { color: var(--mc-danger, #ef4444); background: color-mix(in srgb, var(--mc-danger, #ef4444) 10%, transparent); }
.server-name { font-weight: 600; color: var(--mc-text-primary); }
.server-meta { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.transport-tag {
  font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em;
  padding: 1px 6px; border-radius: 4px;
  color: var(--mc-text-tertiary); background: var(--mc-bg-sunken);
}
.server-desc { font-size: 12px; color: var(--mc-text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 200px; }

/* ===== Status cell ===== */
.status-row { display: flex; align-items: center; gap: 6px; white-space: nowrap; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.dot-connected { background: #34d399; box-shadow: 0 0 4px rgba(52,211,153,.45); }
.dot-disconnected { background: var(--mc-text-tertiary); opacity: .4; }
.dot-error { background: var(--mc-danger, #ef4444); box-shadow: 0 0 4px rgba(239,68,68,.4); }
.status-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.status-time { font-size: 12px; color: var(--mc-text-tertiary); }
.status-time::before { content: '\00b7'; margin: 0 3px; }
.status-error { font-size: 11px; color: var(--mc-danger, #ef4444); margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 240px; }

/* ===== Count pill ===== */
.count-pill {
  display: inline-block; min-width: 24px; padding: 1px 8px;
  border-radius: 8px; font-size: 13px; font-weight: 600; text-align: center;
  color: var(--mc-text-secondary); background: var(--mc-bg-sunken);
}

/* ===== Toggle ===== */
.toggle { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-track { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: .2s; }
.toggle-track::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-track { background: var(--mc-primary); }
.toggle input:checked + .toggle-track::before { transform: translateX(16px); }

/* ===== Row actions ===== */
.row-actions { display: flex; gap: 5px; }
.row-btn {
  width: 30px; height: 30px; border: 1px solid var(--mc-border); border-radius: 8px;
  background: transparent; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: var(--mc-text-tertiary); transition: all .12s;
}
.row-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.row-btn:disabled { opacity: .3; cursor: not-allowed; }
.row-btn.danger:hover { background: color-mix(in srgb, var(--mc-danger, #ef4444) 10%, transparent); border-color: var(--mc-danger); color: var(--mc-danger); }

/* ===== Empty state ===== */
.empty-row { padding: 48px 16px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 6px; color: var(--mc-text-tertiary); }
.empty-state p { font-size: 14px; margin: 0; }
.empty-sub { font-size: 12px; }

/* ===== Toast ===== */
.test-toast { position: fixed; bottom: 24px; right: 24px; padding: 12px 18px; border-radius: 10px; z-index: 2000; box-shadow: 0 4px 16px rgba(0,0,0,.15); display: flex; align-items: center; gap: 8px; font-size: 13px; }
.toast-ok { background: var(--mc-primary); color: #fff; }
.toast-fail { background: var(--mc-danger, #ef4444); color: #fff; }
.toast-enter-active, .toast-leave-active { transition: all .25s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(16px); }

/* ===== Modal ===== */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 14px; width: 100%; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.18); }
.modal-wide { max-width: 580px; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 17px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 28px; height: 28px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 20px; display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 18px 22px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 22px; border-top: 1px solid var(--mc-border-light); }

/* Detail grid */
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.detail-item { display: flex; flex-direction: column; gap: 4px; }
.detail-full { grid-column: 1 / -1; }
.detail-label { font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--mc-text-tertiary); }
.detail-value { font-size: 14px; color: var(--mc-text-primary); word-break: break-word; }
.detail-sub { font-size: 12px; color: var(--mc-text-tertiary); }
.detail-code { padding: 8px 12px; border-radius: 8px; background: var(--mc-bg-sunken); font-family: 'SF Mono', 'Fira Code', monospace; font-size: 13px; white-space: pre-wrap; }

/* Form */
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); width: 100%; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,.12); }
.form-textarea { resize: vertical; min-height: 40px; font-family: 'SF Mono', 'Fira Code', monospace; }
.mono { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 13px; }
.toggle-inline { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--mc-text-primary); cursor: pointer; }
.toggle-inline input { width: 16px; height: 16px; accent-color: var(--mc-primary); }

/* Anim */
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }

/* Responsive */
@media (max-width: 900px) {
  .page-header { flex-direction: column; }
  .header-actions { width: 100%; }
  .btn-primary, .btn-secondary { flex: 1; justify-content: center; }
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
