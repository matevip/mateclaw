<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner channels-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Connect</div>
            <h1 class="mc-page-title">{{ t('channels.title') }}</h1>
            <p class="mc-page-desc">{{ t('channels.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('channels.newChannel') }}
          </button>
        </div>

        <!-- 加载中骨架 -->
        <div v-if="isInitialLoading" class="channel-grid">
          <div v-for="i in 4" :key="i" class="channel-card mc-surface-card channel-card-skeleton">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <!-- 渠道卡片 -->
        <div v-else class="channel-grid">
          <div v-for="channel in channels" :key="channel.id" class="channel-card mc-surface-card">
            <div class="channel-header">
              <div class="channel-icon-wrap">
                <img class="channel-icon-img" :src="getChannelIconPath(channel.channelType)" :alt="channel.channelType" />
              </div>
              <div class="channel-meta">
                <h3 class="channel-name">{{ channel.name }}</h3>
                <span class="channel-type">{{ channel.channelType }}</span>
              </div>
              <div class="channel-status-group">
                <div class="channel-status" :class="channel.enabled ? 'status-on' : 'status-off'">
                  {{ channel.enabled ? t('channels.status.active') : t('channels.status.inactive') }}
                </div>
                <div
                  v-if="channel.enabled"
                  class="connection-indicator"
                  :class="getConnectionClass(channel)"
                  :title="getConnectionTooltip(channel)"
                >
                  {{ getConnectionIcon(channel) }} {{ getConnectionLabel(channel) }}
                </div>
              </div>
            </div>
            <p class="channel-desc">{{ channel.description }}</p>
            <div class="channel-footer">
              <button class="card-btn" @click="openEditModal(channel)">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
                {{ t('channels.configure') }}
              </button>
              <button class="card-btn" @click="toggleChannel(channel)">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line v-if="channel.enabled" x1="8" y1="12" x2="16" y2="12"/>
                  <polyline v-else points="10 8 16 12 10 16"/>
                </svg>
                {{ channel.enabled ? t('channels.disable') : t('channels.enable') }}
              </button>
              <button class="card-btn danger" @click="deleteChannel(channel.id)">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="3 6 5 6 21 6"/>
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                </svg>
                {{ t('common.delete') }}
              </button>
            </div>
          </div>

          <!-- 添加渠道卡片 -->
          <div class="channel-card add-card mc-surface-card" @click="openCreateModal">
            <div class="add-icon">+</div>
            <p class="add-label">{{ t('channels.addChannel') }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Edit modal: async-loaded the first time it's opened, so the route
         chunk doesn't carry the modal's ~30KB form/auth UI on initial load. -->
    <ChannelEditModal
      v-if="showModal"
      v-model="showModal"
      :editing-channel="editingChannel"
      :agents="agents"
      :default-type="modalDefaults.type"
      :default-name="modalDefaults.name"
      @save="handleSave"
      @add-new-weixin="handleAddNewWeixin"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, defineAsyncComponent, onMounted, onUnmounted, onActivated, onDeactivated } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { channelApi, agentApi } from '@/api'
import type { Channel, Agent } from '@/types'

// Async-loaded modal: separate chunk, only fetched when the user first clicks
// "create" or "edit". The /channels initial load is a list page only.
const ChannelEditModal = defineAsyncComponent(() => import('@/components/channels/ChannelEditModal.vue'))

const { t } = useI18n()

const channels = ref<Channel[]>([])
const agents = ref<Agent[]>([])
const showModal = ref(false)
const editingChannel = ref<Channel | null>(null)
const modalDefaults = ref<{ type?: string; name?: string }>({})

const channelStatusMap = ref<Record<string | number, {
  connectionState: string
  lastError: string | null
  reconnectAttempts: number
}>>({})

let statusPollTimer: ReturnType<typeof setInterval> | null = null
const isInitialLoading = ref(true)
// Tracks whether the page is currently the active route. Guards against the
// race where the user navigates away while Promise.all is still in-flight: by
// the time onMounted's tail runs, onDeactivated may have already fired —
// startStatusPolling must not start a leaked timer in the background.
let isActive = true

// ==================== Lifecycle ====================

async function loadAgents() {
  const res: any = await agentApi.list()
  agents.value = res.data || []
}

function startStatusPolling() {
  if (!isActive) return
  if (statusPollTimer) return
  statusPollTimer = setInterval(loadStatus, 10000)
}

function stopStatusPolling() {
  if (statusPollTimer) {
    clearInterval(statusPollTimer)
    statusPollTimer = null
  }
}

onMounted(async () => {
  // Parallel fan-out (was three serial awaits, ~3x latency before).
  try {
    await Promise.all([loadChannels(), loadStatus(), loadAgents()])
  } finally {
    isInitialLoading.value = false
  }
  startStatusPolling()
})

// keepAlive=true on the route: pause polling when the user navigates away,
// resume on return. onMounted/onUnmounted only fire on first mount and final
// unmount; everything else is onActivated/onDeactivated.
onActivated(() => {
  isActive = true
  if (isInitialLoading.value) return
  loadStatus()
  startStatusPolling()
})

onDeactivated(() => {
  isActive = false
  stopStatusPolling()
})

onUnmounted(() => {
  isActive = false
  stopStatusPolling()
})

// ==================== Data loading ====================

async function loadChannels() {
  try {
    const res: any = await channelApi.list()
    channels.value = (res.data || []).map((c: any) => ({ ...c }))
  } catch (e: any) {
    ElMessage.error(t('channels.messages.loadFailed') + ': ' + (e?.message || ''))
    channels.value = []
  }
}

async function loadStatus() {
  try {
    // Prefer the new typed health endpoint — it surfaces OUT_OF_SERVICE
    // (enabled in DB but adapter not active, e.g. start failed silently)
    // which the legacy /status endpoint conflated with DISCONNECTED. The
    // older shape is still returned by the fallback below in case the
    // backend is mid-rollout.
    const res: any = await channelApi.healthAll()
    const list: any[] = res.data || []
    const map: Record<number, any> = {}
    for (const h of list) {
      const status: string = h.status || 'UNKNOWN'
      // Translate the typed health status onto the existing connection
      // state vocabulary the UI helpers were built against, so the rest
      // of the page (icons, tooltips, css classes) keeps working unchanged.
      const connectionState =
        status === 'UP' ? 'CONNECTED'
        : status === 'RECONNECTING' ? 'RECONNECTING'
        : status === 'DOWN' ? 'ERROR'
        : status === 'OUT_OF_SERVICE' ? 'OUT_OF_SERVICE'
        : 'DISCONNECTED'
      map[h.channelId] = {
        connectionState,
        lastError: h.detail || null,
        reconnectAttempts: 0,
      }
    }
    channelStatusMap.value = map
  } catch {
    // silent — next poll will retry
  }
}

// ==================== Connection state helpers ====================

function getConnectionState(channel: Channel): string {
  return channelStatusMap.value[channel.id]?.connectionState || 'DISCONNECTED'
}

function getConnectionIcon(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return '🟢'
    case 'RECONNECTING': return '🟡'
    case 'ERROR': return '🔴'
    case 'OUT_OF_SERVICE': return '🟠'
    default: return '⚪'
  }
}

function getConnectionLabel(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return t('channels.connection.connected')
    case 'RECONNECTING': return t('channels.connection.reconnecting')
    case 'ERROR': return t('channels.connection.error')
    case 'OUT_OF_SERVICE': return t('channels.connection.outOfService')
    case 'DISCONNECTED': return t('channels.connection.disconnected')
    default: return ''
  }
}

function getConnectionClass(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return 'conn-connected'
    case 'RECONNECTING': return 'conn-reconnecting'
    case 'ERROR': return 'conn-error'
    case 'OUT_OF_SERVICE': return 'conn-out-of-service'
    default: return 'conn-disconnected'
  }
}

function getConnectionTooltip(channel: Channel): string {
  const status = channelStatusMap.value[channel.id]
  if (!status) return ''
  let tip = getConnectionLabel(channel)
  if (status.reconnectAttempts > 0) {
    tip += ` (${t('channels.connection.retryCount', { n: status.reconnectAttempts })})`
  }
  if (status.lastError) {
    tip += `\n${t('channels.connection.errorLabel')}: ${status.lastError}`
  }
  return tip
}

// ==================== Modal control ====================

function openCreateModal() {
  editingChannel.value = null
  modalDefaults.value = {}
  showModal.value = true
}

function openEditModal(channel: Channel) {
  editingChannel.value = channel
  modalDefaults.value = {}
  showModal.value = true
}

/** Modal asked us to switch to a fresh weixin-create flow. Close current,
 *  open a new one in create mode with weixin pre-selected and a numbered name. */
function handleAddNewWeixin() {
  showModal.value = false
  const existingCount = channels.value.filter((c) => c.channelType === 'weixin').length
  const newName = t('channels.weixin.newAccountName') + ' ' + (existingCount + 1)
  setTimeout(() => {
    editingChannel.value = null
    modalDefaults.value = { type: 'weixin', name: newName }
    showModal.value = true
  }, 200)
}

// ==================== Save ====================

async function handleSave(payload: Partial<Channel>) {
  try {
    if (editingChannel.value) {
      await channelApi.update(editingChannel.value.id, payload)
    } else {
      await channelApi.create(payload)
    }
    showModal.value = false
    editingChannel.value = null
    await loadChannels()
  } catch (e: any) {
    ElMessage.error(e?.message || t('channels.messages.saveFailed'))
  }
}

// ==================== Toggle / delete ====================

async function deleteChannel(id: string | number) {
  try {
    await ElMessageBox.confirm(t('channels.messages.deleteConfirm'), t('channels.messages.deleteTitle'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await channelApi.delete(id)
    await loadChannels()
  } catch (e: any) {
    ElMessage.error(e?.message || t('channels.messages.deleteFailed'))
  }
}

async function toggleChannel(channel: Channel) {
  try {
    await channelApi.toggle(channel.id, !channel.enabled)
    await loadChannels()
    await loadStatus()
  } catch (e: any) {
    ElMessage.error(e?.message || t('channels.messages.toggleFailed'))
  }
}

// ==================== Channel icon ====================

const CHANNEL_ICON_TYPES = ['web', 'dingtalk', 'feishu', 'wecom', 'weixin', 'telegram', 'discord', 'qq', 'slack', 'webchat', 'webhook']
function getChannelIconPath(type: string) {
  const name = CHANNEL_ICON_TYPES.includes(type) ? type : 'default'
  return `/icons/channels/${name}.svg`
}
</script>

<style scoped>
.channels-page { gap: 18px; }

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }

/* Channel cards */
.channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
.channel-card { padding: 20px; transition: all 0.15s; min-height: 238px; display: flex; flex-direction: column; }
.channel-card-skeleton { pointer-events: none; opacity: 0.85; }
.channel-card:hover { border-color: var(--mc-primary-light); box-shadow: var(--mc-shadow-medium); transform: translateY(-2px); }
.channel-header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.channel-icon-wrap { width: 48px; height: 48px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; overflow: hidden; background: linear-gradient(135deg, rgba(217,109,87,0.12), rgba(24,74,69,0.08)); }
.channel-icon-img { width: 42px; height: 42px; border-radius: 12px; object-fit: cover; }
.channel-meta { flex: 1; }
.channel-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; }
.channel-type { font-size: 12px; color: var(--mc-text-tertiary); }

.channel-status { padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 500; }
.channel-status-group { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.status-on { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-off { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.connection-indicator { font-size: 11px; padding: 2px 8px; border-radius: 12px; white-space: nowrap; cursor: default; }
.conn-connected { color: var(--mc-primary); background: var(--mc-primary-bg); }
.conn-reconnecting { color: var(--mc-primary-hover); background: var(--mc-primary-bg); animation: pulse-reconnecting 1.5s ease-in-out infinite; }
.conn-error { color: var(--mc-danger); background: var(--mc-danger-bg); }
.conn-out-of-service { color: var(--mc-warning, #f59e0b); background: var(--mc-warning-bg, rgba(245, 158, 11, 0.1)); }
.conn-disconnected { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
@keyframes pulse-reconnecting { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.channel-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 14px; line-height: 1.6; min-height: 42px; }
.channel-footer { display: flex; gap: 6px; border-top: 1px solid var(--mc-border-light); padding-top: 12px; margin-top: auto; flex-wrap: wrap; }
.card-btn { display: flex; align-items: center; gap: 4px; padding: 7px 11px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.card-btn:hover { background: var(--mc-bg-sunken); }
.card-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

.add-card { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 238px; border: 2px dashed var(--mc-border); cursor: pointer; background: transparent; }
.add-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.add-icon { font-size: 28px; color: var(--mc-text-tertiary); margin-bottom: 8px; }
.add-label { font-size: 14px; color: var(--mc-text-tertiary); }
.add-card:hover .add-icon, .add-card:hover .add-label { color: var(--mc-primary); }
</style>
