<template>
  <div class="chat-layout">
    <!-- 移动端会话面板遮罩 -->
    <Transition name="fade">
      <div v-if="isMobile && convPanelOpen" class="conv-backdrop" @click="convPanelOpen = false"></div>
    </Transition>

    <!-- 会话侧边栏 -->
    <div class="conversation-panel" :class="{ 'mobile-open': convPanelOpen }">
      <div class="panel-header">
        <h2 class="panel-title">{{ $t('chat.conversations') }}</h2>
        <button class="new-chat-btn" @click="newConversation" :title="$t('chat.newChat')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
        </button>
      </div>

      <div class="agent-selector">
        <select v-model="selectedAgentId" class="agent-select" @change="onAgentChange">
          <option v-if="agents.length === 0" value="">{{ $t('chat.loadingAgents') }}</option>
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">
            {{ agent.icon || '🤖' }} {{ agent.name }}
          </option>
        </select>
      </div>

      <div class="conversation-list">
        <template v-for="group in groupedConversations" :key="group.label">
          <div class="conv-group-title">{{ group.label }}</div>
          <div
            v-for="conv in group.items"
            :key="conv.conversationId"
            class="conv-item"
            :class="{ active: currentConversationId === conv.conversationId }"
            @click="selectConversation(conv)"
          >
            <div class="conv-icon">
              <img :src="channelIconUrl(conv.source)" width="14" height="14" alt="" />
            </div>
            <div class="conv-info">
              <div class="conv-title">{{ conv.title }}</div>
              <div class="conv-meta">
                <span>{{ $t('chat.messages', { count: conv.messageCount }) }}</span>
                <span class="conv-dot">·</span>
                <span>{{ formatConversationTime(conv.lastActiveTime) }}</span>
              </div>
            </div>
            <button class="conv-delete" @click.stop="deleteConversation(conv.conversationId)" :title="$t('common.delete')">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
              </svg>
            </button>
          </div>
        </template>

        <div v-if="conversations.length === 0" class="empty-convs">
          <p>{{ $t('chat.noConversations') }}</p>
          <p>{{ $t('chat.startNewChat') }}</p>
        </div>
      </div>
    </div>

    <!-- 主聊天区域 -->
    <div
      class="chat-area"
      @dragenter.prevent="onDragEnter"
      @dragover.prevent
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 拖拽上传遮罩 -->
      <Transition name="fade">
        <div v-if="isDragging" class="drop-overlay">
          <div class="drop-overlay__content">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="17 8 12 3 7 8"/>
              <line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
            <span>{{ $t('chat.dropToUpload') }}</span>
          </div>
        </div>
      </Transition>
      <!-- 头部 -->
      <div class="chat-header">
        <div class="chat-header-left">
          <button v-if="isMobile" class="conv-toggle-btn" @click="convPanelOpen = !convPanelOpen" :title="$t('chat.conversations')">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
          </button>
          <div class="agent-badge" v-if="currentAgent" :title="currentAgent.name">
            <span class="agent-badge-icon">{{ currentAgent.icon || '🤖' }}</span>
            <span class="agent-badge-name">{{ currentAgent.name }}</span>
            <span class="agent-badge-type">{{ currentAgent.agentType === 'react' ? 'ReAct' : 'Plan-Execute' }}</span>
          </div>
          <div v-else class="no-agent-hint">{{ $t('chat.selectAgent') }}</div>
        </div>
        <div class="chat-header-right">
          <select
            v-if="eligibleModels.length > 0"
            :value="activeModelValue"
            class="model-select"
            :disabled="modelSaving"
            @change="onModelChange"
          >
            <option v-for="item in eligibleModels" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
          <button v-else class="header-btn" @click="goToModelSettings" :title="$t('chat.configModel')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l-.06.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01A1.65 1.65 0 0 0 10.5 3.1V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
            </svg>
          </button>
          <button class="header-btn" @click="clearMessages" :title="$t('chat.clearMessages')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6"/>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- 使用组件化的 MessageList -->
      <MessageList
        ref="messageListRef"
        :messages="messages"
        :loading="isGenerating"
        :assistant-icon="currentAgent?.icon || '🤖'"
        :user-icon="userInitial"
        :title="showModelPrompt ? modelPromptTitle : $t('app.title')"
        :subtitle="showModelPrompt ? modelPromptDesc : $t('chat.subtitle')"
        :suggestions="showModelPrompt ? [] : suggestions"
        @regenerate="handleRegenerate"
        @suggestion-click="sendSuggestion"
        @toggle-thinking="handleToggleThinking"
        @approve="handleApprove"
        @deny="handleDeny"
      >
        <!-- 自定义模型提示空状态 -->
        <template v-if="showModelPrompt" #empty>
          <div class="model-prompt">
            <div class="model-prompt-title">{{ modelPromptTitle }}</div>
            <div class="model-prompt-desc">{{ modelPromptDesc }}</div>
            <button class="btn-primary" @click="goToModelSettings">{{ $t('chat.goToModelSettings') }}</button>
          </div>
        </template>
      </MessageList>

      <!-- 流式处理 Loading 栏（消息和输入框之间） -->
      <StreamLoadingBar
        :is-loading="isGenerating && !showModelPrompt"
        :tool-count="toolCallCount"
        :completion-tokens="currentGeneratingTokens"
        :prompt-tokens="currentPromptTokens"
        :phase="streamPhase"
        :phase-info="phaseInfo"
        :running-tool-name="currentRunningToolName"
        :has-queued="hasQueued"
      />

      <!-- 使用组件化的 ChatInput -->
      <ChatInput
        ref="chatInputRef"
        v-model="inputText"
        :loading="isGenerating && !hasPendingApproval"
        :disabled="showModelPrompt || !currentAgent"
        :placeholder="$t('chat.messagePlaceholder')"
        :hint="`MateClaw · ${currentRuntimeModel}` + (currentConversationId ? ` · Session · ${currentConversationId.slice(0, 8)}...` : '')"
        :attachments="pendingAttachments"
        :uploading="uploadingAttachment"
        :max-length="10240"
        :pending-approval="activePendingApproval"
        :stream-phase="streamPhase"
        :queued-message="queuedMessage"
        :queue-size="queueSize"
        @submit="handleSendMessage"
        @stop="handleStopStream"
        @cancel-queued="handleCancelQueued"
        @file-select="handleFileSelect"
        @attachment-remove="removeAttachment"
        @approve="handleApprove"
        @deny="handleDeny"
        :enable-talk-mode="!!selectedAgentId"
        @talk="showTalkMode = true"
      />
    </div>

    <!-- Talk Mode 覆盖层 -->
    <TalkMode
      :visible="showTalkMode"
      :agent-id="selectedAgentId"
      :conversation-id="currentConversationId"
      @close="showTalkMode = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { conversationApi, agentApi, modelApi, chatApi } from '@/api/index'
import { channelIconUrl } from '@/utils/channelSource'
import { useChat } from '@/composables/chat/useChat'
import { reconstructErrorInfo } from '@/types/chatError'
import type { Conversation, Agent, ModelConfig, ProviderInfo, ActiveModelsInfo, ChatAttachment, MessageContentPart, Message, ToolCallMeta, StreamPhase } from '@/types'

// 导入组件化组件
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import StreamLoadingBar from '@/components/chat/StreamLoadingBar.vue'
import TalkMode from '@/components/chat/TalkMode.vue'
import { useEChartsRenderer } from '@/composables/useEChartsRenderer'

// ============ Talk Mode ============
const showTalkMode = ref(false)

// ============ 移动端状态 ============
const isMobile = ref(false)
const convPanelOpen = ref(false)
let mobileQuery: MediaQueryList | null = null

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) convPanelOpen.value = false
}

// ============ 配置和常量 ============
const suggestions = computed(() => [
  t('chat.suggestionIntro'),
  t('chat.suggestionPoem'),
  t('chat.suggestionCode'),
  t('chat.suggestionWeather'),
])

// ============ 状态 ============
const router = useRouter()
const route = useRoute()
const { t } = useI18n()

const agents = ref<Agent[]>([])
const conversations = ref<Conversation[]>([])
const selectedAgentId = ref<string | number>('')
const currentConversationId = ref<string>('')
const inputText = ref('')
const modelSaving = ref(false)
const showModelPrompt = ref(false)
const defaultModel = ref<ModelConfig | null>(null)
const providers = ref<ProviderInfo[]>([])
const activeModels = ref<ActiveModelsInfo | null>(null)
const pendingAttachments = ref<ChatAttachment[]>([])
const uploadingAttachment = ref(false)

// 拖拽上传
const isDragging = ref(false)
let dragCounter = 0

function onDragEnter(e: DragEvent) {
  dragCounter++
  if (e.dataTransfer?.types.includes('Files')) {
    isDragging.value = true
  }
}

function onDragLeave() {
  dragCounter--
  if (dragCounter === 0) {
    isDragging.value = false
  }
}

async function onDrop(e: DragEvent) {
  dragCounter = 0
  isDragging.value = false

  const dtFiles = Array.from(e.dataTransfer?.files || [])
  const items = Array.from(e.dataTransfer?.items || [])

  const electronDirs: File[] = []
  const webDirEntries: FileSystemDirectoryEntry[] = []
  const regularFiles: File[] = []

  for (let i = 0; i < items.length; i++) {
    const entry = items[i].webkitGetAsEntry?.()
    const file = dtFiles[i]
    if (entry?.isDirectory) {
      if ((file as any)?.path) {
        // Electron: has absolute path
        electronDirs.push(file)
      } else {
        // Web: need to recursively collect files
        webDirEntries.push(entry as FileSystemDirectoryEntry)
      }
    } else if (file) {
      regularFiles.push(file)
    }
  }

  // Electron directories → record path reference
  if (electronDirs.length) {
    handleDirectoryAttach(electronDirs)
  }
  // Web directories → recursively collect files and upload
  if (webDirEntries.length) {
    const collected = await collectFilesFromEntries(webDirEntries)
    if (collected.length) {
      handleFileSelect(collected)
    }
  }
  // Regular files → normal upload
  if (regularFiles.length) {
    handleFileSelect(regularFiles)
  }
}

function handleDirectoryAttach(dirFiles: File[]) {
  if (!currentConversationId.value) {
    newConversation()
  }
  for (const dir of dirFiles) {
    const dirPath = (dir as any).path as string
    pendingAttachments.value.push({
      name: dirPath.split('/').pop() || dir.name,
      size: 0,
      url: '',
      storedName: '',
      path: dirPath,
      contentType: 'inode/directory',
    })
  }
}

async function collectFilesFromEntries(dirEntries: FileSystemDirectoryEntry[]): Promise<File[]> {
  const files: File[] = []

  async function readDir(dir: FileSystemDirectoryEntry) {
    const reader = dir.createReader()
    let batch: FileSystemEntry[]
    do {
      batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
        reader.readEntries(resolve, reject)
      })
      for (const entry of batch) {
        if (entry.isFile) {
          const file = await new Promise<File>((resolve, reject) => {
            (entry as FileSystemFileEntry).file(resolve, reject)
          })
          files.push(file)
        } else if (entry.isDirectory) {
          await readDir(entry as FileSystemDirectoryEntry)
        }
      }
    } while (batch.length > 0)  // readEntries returns empty when done
  }

  for (const dir of dirEntries) {
    await readDir(dir)
  }
  return files
}

const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

// ECharts: extract DOM element from MessageList component ref
const echartsContainerRef = computed(() => messageListRef.value?.$el as HTMLElement | null)
const { startObserving: startECharts, dispose: disposeECharts } = useEChartsRenderer(echartsContainerRef)

// 使用 useChat composable
const {
  messages,
  isGenerating,
  streamPhase,
  phaseInfo,
  queuedMessage,
  hasQueued,
  queueSize,
  heartbeat,
  sendMessage: sendChatMessage,
  stopGeneration: stopChatGeneration,
  cancelQueued,
  reconnectStream: reconnectChatStream,
} = useChat({
  baseUrl: '',
  onStreamEnd: async (meta) => {
    // 流结束后刷新会话列表（更新 lastActiveTime / 标题等）
    await loadConversations()
    if (meta.conversationId && meta.conversationId === currentConversationId.value) {
      // 审批挂起或中断续跑时不从 DB 刷新消息，避免覆盖本地状态或破坏消息顺序
      if (meta.reason !== 'awaiting_approval' && meta.reason !== 'interrupted') {
        await refreshCurrentConversationMessages(meta.conversationId)
      }
    }
  },
})

// ============ 计算属性 ============
const currentAgent = computed(() => agents.value.find(a => String(a.id) === String(selectedAgentId.value)))

// 按日期分组的会话列表
const groupedConversations = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 86400000
  const last7Start = todayStart - 7 * 86400000

  const groups: { label: string; items: Conversation[] }[] = [
    { label: t('chat.dateToday'), items: [] },
    { label: t('chat.dateYesterday'), items: [] },
    { label: t('chat.dateLast7Days'), items: [] },
    { label: t('chat.dateEarlier'), items: [] },
  ]

  for (const conv of conversations.value) {
    const ts = conv.lastActiveTime ? new Date(conv.lastActiveTime).getTime() : 0
    if (ts >= todayStart) groups[0].items.push(conv)
    else if (ts >= yesterdayStart) groups[1].items.push(conv)
    else if (ts >= last7Start) groups[2].items.push(conv)
    else groups[3].items.push(conv)
  }

  return groups.filter(g => g.items.length > 0)
})

const currentRuntimeModel = computed(() => {
  if (defaultModel.value?.name && defaultModel.value?.modelName) {
    return `${defaultModel.value.name} (${defaultModel.value.modelName})`
  }
  return currentAgent.value?.modelName || 'default'
})

const userInitial = computed(() => (localStorage.getItem('username') || 'U').charAt(0).toUpperCase())

const activeModelValue = computed(() => {
  const providerId = activeModels.value?.activeLlm?.providerId
  const model = activeModels.value?.activeLlm?.model
  return providerId && model ? `${providerId}::${model}` : ''
})

const activeProvider = computed(() => {
  const providerId = activeModels.value?.activeLlm?.providerId
  return providerId ? providers.value.find((provider) => provider.id === providerId) || null : null
})

const modelPromptTitle = computed(() => {
  if (!activeModels.value?.activeLlm?.providerId || !activeModels.value?.activeLlm?.model) {
    return t('chat.configModelFirst')
  }
  if (activeProvider.value && !activeProvider.value.available) {
    return t('chat.modelUnavailable')
  }
  return t('chat.configModelFirst')
})

const modelPromptDesc = computed(() => {
  if (!activeModels.value?.activeLlm?.providerId || !activeModels.value?.activeLlm?.model) {
    return t('chat.noActiveModel')
  }
  if (activeProvider.value && !activeProvider.value.available) {
    return t('chat.providerNotReady', { name: activeProvider.value.name })
  }
  return t('chat.noAvailableModel')
})

const eligibleModels = computed(() => {
  return providers.value
    .filter((provider) => provider.available)
    .flatMap((provider) => {
      const allModels = [...(provider.models || []), ...(provider.extraModels || [])]
      return allModels.map((model) => ({
        value: `${provider.id}::${model.id}`,
        label: `${provider.name} / ${model.name || model.id}`,
      }))
    })
})

// ============ 生命周期 ============
onMounted(async () => {
  document.addEventListener('click', handleCodeCopy)
  startECharts()
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  await Promise.all([loadAgents(), loadModelState(), loadConversations()])
  await hydrateStateFromRoute()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleCodeCopy)
  disposeECharts()
  mobileQuery?.removeEventListener('change', handleMobileChange)
  stopChatGeneration()
  // 释放所有附件的 ObjectURL，防止内存泄漏
  revokeAllPreviewUrls()
})

watch(() => route.query, () => {
  void hydrateStateFromRoute()
})

watch([selectedAgentId, currentConversationId], () => {
  syncRouteState()
})

// ============ 方法 ============
async function loadAgents() {
  try {
    const res: any = await agentApi.list()
    agents.value = res.data || []
    // 只有在 URL 没有指定 agentId 且当前无选中时，才默认选第一个
    if (agents.value.length > 0 && !selectedAgentId.value && !route.query.agentId) {
      selectedAgentId.value = agents.value[0].id
    }
  } catch (e) {
    ElMessage.error(t('chat.loadAgentsFailed'))
  }
}

async function loadModelState() {
  try {
    const [defaultRes, providersRes, activeRes]: any = await Promise.all([
      modelApi.getDefault(),
      modelApi.listProviders(),
      modelApi.getActive(),
    ])
    defaultModel.value = defaultRes.data || null
    providers.value = providersRes.data || []
    activeModels.value = activeRes.data || null
    const providerId = activeModels.value?.activeLlm?.providerId
    const activeProviderInfo = providerId
      ? providers.value.find((provider) => provider.id === providerId)
      : null
    showModelPrompt.value = !activeModels.value?.activeLlm?.providerId
      || !activeModels.value?.activeLlm?.model
      || (Boolean(providerId) && !activeProviderInfo?.available)
  } catch (e) {
    ElMessage.error(t('chat.loadModelFailed'))
    showModelPrompt.value = true
  }
}

async function loadConversations() {
  try {
    const res: any = await conversationApi.list()
    conversations.value = res.data || []
  } catch (e) {
    ElMessage.error(t('chat.loadConversationsFailed'))
  }
}

async function refreshCurrentConversationMessages(conversationId: string) {
  if (!conversationId) return
  // 如果已经在生成新消息（用户在 stop 后又快速发了新消息），不覆盖本地状态
  if (isGenerating.value) return
  // 审批挂起时本地状态比 DB 更丰富（含 thinking + text），不替换
  if (streamPhase.value === 'awaiting_approval') return
  try {
    const res: any = await conversationApi.listMessages(conversationId)
    messages.value = (res.data || []).map((msg: Message) => normalizeMessage(msg))
  } catch (e) {
    console.warn('[ChatConsole] Failed to refresh current conversation messages:', e)
  }
}

async function hydrateStateFromRoute() {
  const agentId = route.query.agentId ? String(route.query.agentId) : ''
  const conversationId = String(route.query.conversationId || '')

  if (agentId && agentId !== String(selectedAgentId.value)) {
    selectedAgentId.value = agentId
  }

  if (conversationId && conversationId !== currentConversationId.value) {
    const matchedConversation = conversations.value.find(conv => conv.conversationId === conversationId)
    if (matchedConversation) {
      await selectConversation(matchedConversation)
    } else {
      // 会话不在已加载列表中（可能来自 Sessions 页面跳转），尝试加载消息
      currentConversationId.value = conversationId
      messages.value = []
      try {
        const res: any = await conversationApi.listMessages(conversationId)
        messages.value = (res.data || []).map((msg: Message) => normalizeMessage(msg))
      } catch {
        // 消息加载失败，保持空
      }
      try {
        const statusRes: any = await conversationApi.getStatus(conversationId)
        if (statusRes.data?.streamStatus === 'running') {
          await reconnectStream(conversationId)
        }
      } catch {
        // 忽略
      }
    }
  }

  // 如果仍然没选中 agent，默认选第一个
  if (!selectedAgentId.value && agents.value.length > 0) {
    selectedAgentId.value = agents.value[0].id
  }
}

function syncRouteState() {
  const query: Record<string, string> = {}
  if (selectedAgentId.value) query.agentId = String(selectedAgentId.value)
  if (currentConversationId.value) query.conversationId = currentConversationId.value
  router.replace({ path: '/chat', query })
}

async function selectConversation(conv: Conversation) {
  if (isMobile.value) convPanelOpen.value = false
  resetStreamingState()
  currentConversationId.value = conv.conversationId
  selectedAgentId.value = conv.agentId || selectedAgentId.value
  try {
    const res: any = await conversationApi.listMessages(conv.conversationId)
    messages.value = (res.data || []).map((msg: Message) => normalizeMessage(msg))

    // Hydrate pending approvals：恢复刷新后丢失的审批卡片
    try {
      const approvalRes: any = await chatApi.getPendingApprovals(conv.conversationId)
      const pendingApprovals = approvalRes.data || []
      if (pendingApprovals.length > 0) {
        // 将 pending approvals 绑定到最近的 assistant 消息
        const assistantMessages = messages.value.filter(m => m.role === 'assistant')
        const lastAssistant = assistantMessages[assistantMessages.length - 1]
        if (lastAssistant) {
          for (const pa of pendingApprovals) {
            (lastAssistant as any).metadata = {
              ...(lastAssistant as any).metadata,
              currentPhase: 'awaiting_approval',
              pendingApproval: {
                pendingId: pa.pendingId,
                toolName: pa.toolName,
                arguments: pa.toolArguments,
                reason: pa.reason,
                status: 'pending_approval',
                // 增强字段（Phase 6: 结构化风险信息）
                findings: pa.findingsJson ? JSON.parse(pa.findingsJson) : undefined,
                maxSeverity: pa.maxSeverity || undefined,
                summary: pa.summary || undefined,
              }
            }
          }
        }
      }
    } catch {
      // hydration 失败不影响正常使用
    }

    if (conv.streamStatus === 'running') {
      await reconnectStream(conv.conversationId)
    }
  } catch (e) {
    ElMessage.error(t('chat.loadMessagesFailed'))
  }
}

function newConversation() {
  resetStreamingState()
  currentConversationId.value = `conv_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  messages.value = []
}

async function deleteConversation(conversationId: string) {
  try {
    await conversationApi.delete(conversationId)
    conversations.value = conversations.value.filter(c => c.conversationId !== conversationId)
    if (currentConversationId.value === conversationId) {
      resetStreamingState()
      messages.value = []
      currentConversationId.value = ''
    }
  } catch (e) {
    ElMessage.error(t('chat.deleteConversationFailed'))
  }
}

async function clearMessages() {
  if (!currentConversationId.value) return
  try {
    resetStreamingState()
    await conversationApi.clearMessages(currentConversationId.value)
    messages.value = []
  } catch {
    messages.value = []
  }
}

function onAgentChange() {
  newConversation()
}

async function onModelChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const [providerId, model] = value.split('::')
  if (!providerId || !model) return

  modelSaving.value = true
  try {
    const res: any = await modelApi.setActive({ providerId, model })
    activeModels.value = res.data || { activeLlm: { providerId, model } }
    await loadModelState()
  } catch (e) {
    ElMessage.error(t('chat.switchModelFailed'))
  } finally {
    modelSaving.value = false
  }
}

function goToModelSettings() {
  router.push('/settings/models')
}

// ============ 计算属性：是否有待审批 ============
const hasPendingApproval = computed(() => {
  return messages.value.some(
    m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
  )
})

// 当前待审批的那条数据（传给 ChatInput 用于渲染审批栏）
const activePendingApproval = computed(() => {
  const msg = messages.value.findLast(
    m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
  )
  return (msg as any)?.metadata?.pendingApproval ?? null
})

// 当前工具调用数
const toolCallCount = computed(() => {
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  return lastMsg?.metadata?.toolCalls?.length ?? 0
})

// 当前正在执行的工具名称
const currentRunningToolName = computed(() => {
  if (!isGenerating.value) return ''
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  const metadata = lastMsg?.metadata
  if (metadata?.runningToolName) return metadata.runningToolName
  const runningTool = metadata?.toolCalls?.findLast((tc: any) => tc.status === 'running')
  return runningTool?.name || heartbeat.value?.runningToolName || ''
})

// 当前正在生成的消息的 token 数据
const currentGeneratingTokens = computed(() => {
  if (!isGenerating.value) return 0
  // 找到最后一条 assistant 消息（可能仍在生成）
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  // 返回 completionTokens（从服务器响应中获取）
  return (lastMsg as any)?.completionTokens ?? 0
})

const currentPromptTokens = computed(() => {
  if (!isGenerating.value) return 0
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  return (lastMsg as any)?.promptTokens ?? 0
})

// ============ 消息发送和处理 ============
async function handleSendMessage(content: string) {
  // 允许在等待审批时发送审批命令
  const isApprovalCommand = /^\/(approve|deny)$/i.test(content.trim())

  if ((!content && pendingAttachments.value.length === 0) || !selectedAgentId.value || showModelPrompt.value) return
  // 不再阻止运行中发送 — useChat 会自动走 interrupt/queue 路径

  // 拦截 /approve 和 /deny 命令 —— 通过 SSE 流发送（和普通消息相同通道）
  const trimmed = content.trim().toLowerCase()
  if (trimmed === '/approve' || trimmed === '/deny') {
    if (!currentConversationId.value) {
      ElMessage.warning('No active conversation')
      inputText.value = ''
      chatInputRef.value?.clear?.()
      return
    }

    // 检查是否有 pending approval
    const pendingMsg = messages.value.findLast(
      m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
    )
    if (!pendingMsg) {
      ElMessage.warning('No pending approval to process')
      inputText.value = ''
      chatInputRef.value?.clear?.()
      return
    }

    // 乐观更新审批状态
    const decision = trimmed === '/approve' ? 'approved' : 'denied'
    ;(pendingMsg as any).metadata.pendingApproval.status = decision

    inputText.value = ''
    chatInputRef.value?.clear?.()

    // 通过正常 SSE 流发送（复用聊天通道，replay 结果实时流式推送）
    try {
      await sendChatMessage(trimmed, {
        conversationId: currentConversationId.value,
        agentId: selectedAgentId.value,
        contentParts: [],
      })
    } catch (e: any) {
      console.error('Approval stream failed:', e)
      // 回滚乐观更新
      ;(pendingMsg as any).metadata.pendingApproval.status = 'pending_approval'
      ElMessage.error(e?.message || 'Approval failed')
    }
    return
  }

  if (!currentConversationId.value) {
    currentConversationId.value = `conv_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  }

  if (!activeModels.value?.activeLlm?.providerId || !activeModels.value?.activeLlm?.model) {
    showModelPrompt.value = true
    return
  }
  if (!activeProvider.value?.available) {
    showModelPrompt.value = true
    return
  }

  const outgoingAttachments = pendingAttachments.value.map((attachment) => ({ ...attachment }))
  const contentParts = buildOutgoingParts(content, outgoingAttachments)

  // 先暂存，发送成功后再清空（失败时恢复）
  const savedInput = inputText.value
  const savedAttachments = [...pendingAttachments.value]
  inputText.value = ''
  chatInputRef.value?.clear?.()
  pendingAttachments.value = []

  try {
    await sendChatMessage(content, {
      conversationId: currentConversationId.value,
      agentId: selectedAgentId.value,
      contentParts,
      attachments: outgoingAttachments.map(a => ({
        type: 'file' as const,
        fileUrl: a.url,
        fileName: a.name,
        storedName: a.storedName,
        contentType: a.contentType,
        fileSize: a.size,
        path: a.path,
      })),
    })
    // 发送成功后释放 ObjectURL
    revokeAllPreviewUrls()
  } catch (e) {
    console.error('Send message failed:', e)
    // 发送失败：恢复输入和附件，用户不丢失已上传的文件
    if (!inputText.value) inputText.value = savedInput
    if (pendingAttachments.value.length === 0) pendingAttachments.value = savedAttachments
  }
}

function handleStopStream() {
  stopChatGeneration()
}

function handleRegenerate(message: Message) {
  if (isGenerating.value) return
  const idx = messages.value.indexOf(message)
  if (idx >= 0) {
    messages.value.splice(idx, 1)
  }
  const lastUserMsg = messages.value.findLast(m => m.role === 'user')
  if (!lastUserMsg) return

  const text = lastUserMsg.contentParts
    .filter(p => p.type === 'text')
    .map(p => p.text || '')
    .join('\n') || lastUserMsg.content || ''

  handleSendMessage(text)
}

function sendSuggestion(text: string) {
  inputText.value = text
  handleSendMessage(text)
}

function handleToggleThinking(message: import('@/types').Message, expanded: boolean) {
  message.thinkingExpanded = expanded
}

// ============ 审批处理 ============
async function handleApprove(pendingId: string) {
  if (!currentConversationId.value) return
  await handleSendMessage('/approve')
}

async function handleDeny(pendingId: string) {
  if (!currentConversationId.value) return
  await handleSendMessage('/deny')
}

// 重连到运行中的流
async function reconnectStream(conversationId: string) {
  if (isGenerating.value) return
  try {
    await reconnectChatStream(conversationId)
  } catch (e) {
    console.error('[ChatConsole] Reconnect failed:', e)
    ElMessage.warning(t('chat.reconnectFailed') || 'Stream reconnection failed')
  }
}

function handleCancelQueued() {
  cancelQueued()
}

// 简化版重置函数
function resetStreamingState() {
  stopChatGeneration()
}

// ============ 附件处理 ============
async function handleFileSelect(files: File[]) {
  if (!currentConversationId.value) {
    newConversation()
  }

  uploadingAttachment.value = true
  try {
    for (const file of files) {
      const res: any = await chatApi.uploadFile(currentConversationId.value, file)
      const data = res.data || {}
      // 图片/视频使用本地 ObjectURL 预览（避免 /api/v1/chat/files/ 需要 JWT 认证导致加载失败）
      const ct = data.contentType || file.type || ''
      const isPreviewable = ct.startsWith('image/') || ct.startsWith('video/')
      const previewUrl = isPreviewable ? URL.createObjectURL(file) : data.url
      pendingAttachments.value.push({
        name: data.fileName || file.name,
        size: data.size || file.size,
        url: data.url,
        storedName: data.storedName,
        path: data.path,
        contentType: data.contentType || file.type,
        previewUrl,
      })
    }
  } catch (e) {
    ElMessage.error(t('chat.uploadFailed'))
  } finally {
    uploadingAttachment.value = false
  }
}

function removeAttachment(key: string) {
  // revoke 被移除附件的 ObjectURL，防止内存泄漏
  const removed = pendingAttachments.value.find(a => a.storedName === key || a.path === key)
  if (removed?.previewUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(removed.previewUrl)
  }
  pendingAttachments.value = pendingAttachments.value.filter(
    a => a.storedName !== key && a.path !== key
  )
}

/** 释放所有 pending 附件的 ObjectURL */
function revokeAllPreviewUrls() {
  for (const a of pendingAttachments.value) {
    if (a.previewUrl?.startsWith('blob:')) {
      URL.revokeObjectURL(a.previewUrl)
    }
  }
}

function buildOutgoingParts(text: string, attachments: ChatAttachment[]): MessageContentPart[] {
  const parts: MessageContentPart[] = []
  if (text) parts.push({ type: 'text', text })
  for (const attachment of attachments) {
    const ct = attachment.contentType || ''
    const partType: MessageContentPart['type'] = ct.startsWith('video/') ? 'video'
      : ct.startsWith('image/') ? 'image'
      : 'file'
    parts.push({
      type: partType,
      fileUrl: attachment.url,
      fileName: attachment.name,
      storedName: attachment.storedName,
      contentType: attachment.contentType,
      fileSize: attachment.size,
      path: attachment.path,
    })
  }
  return parts
}

// ============ 工具函数 ============
function normalizeMessage(raw: Message): Message {
  const msg: Message = { ...raw, contentParts: raw.contentParts ? [...raw.contentParts] : [] }

  // 保留后端返回的 token 字段（MessageVO 新增）
  if ((raw as any).promptTokens) msg.promptTokens = (raw as any).promptTokens
  if ((raw as any).completionTokens) msg.completionTokens = (raw as any).completionTokens

  if (msg.contentParts.length === 0 && msg.content) {
    if (msg.role === 'assistant') {
      const parsed = parseThinkingContent(msg.content)
      if (parsed.thinking) msg.contentParts.push({ type: 'thinking', text: parsed.thinking })
      if (parsed.content) msg.contentParts.push({ type: 'text', text: parsed.content })
      msg.content = parsed.content
    } else {
      msg.contentParts.push({ type: 'text', text: msg.content })
    }
  }

  // 从 tool_call contentParts 还原 metadata.toolCalls
  const toolCallParts = msg.contentParts.filter(p => p.type === 'tool_call')
  if (toolCallParts.length > 0) {
    const toolCalls: ToolCallMeta[] = []
    for (const part of toolCallParts) {
      try {
        const parsed = JSON.parse(part.text || '{}')
        toolCalls.push({
          name: parsed.name || '',
          arguments: parsed.arguments,
          result: parsed.result,
          success: parsed.success,
          // 历史消息中不应有 running 状态的工具调用（流已结束）
          status: 'completed',
        })
      } catch {
        // skip malformed tool_call parts
      }
    }
    if (toolCalls.length > 0) {
      msg.metadata = { ...msg.metadata, toolCalls }
    }
    msg.contentParts = msg.contentParts.filter(p => p.type !== 'tool_call')
  }

  // 历史消息的 metadata.toolCalls 也需要清理 running 状态
  if (msg.metadata?.toolCalls) {
    const cleaned = msg.metadata.toolCalls.map((tc: ToolCallMeta) => ({
      ...tc,
      status: tc.status === 'running' ? 'completed' as const : tc.status,
    }))
    msg.metadata = { ...msg.metadata, toolCalls: cleaned }
  }

  if (msg.status === 'generating') msg.status = 'failed'
  // interrupted 是合法的历史状态（interrupt-with-followup），不映射为 stopped
  if (!msg.status) msg.status = 'completed'

  // 从 file/image/video contentParts 恢复 attachments（历史消息 API 不返回单独的 attachments 字段）
  const fileParts = msg.contentParts.filter(p => (p.type === 'file' || p.type === 'image' || p.type === 'video') && p.fileUrl)
  if (fileParts.length > 0 && (!msg.attachments || msg.attachments.length === 0)) {
    msg.attachments = fileParts.map(p => ({
      name: p.fileName || 'unknown',
      size: typeof p.fileSize === 'number' ? p.fileSize : Number(p.fileSize) || 0,
      url: p.fileUrl!,
      storedName: p.storedName || '',
      path: p.path || '',
      contentType: p.contentType,
    }))
  }

  // 从持久化的 [错误] 文本重建 errorInfo，使刷新后也能显示错误卡片
  if (msg.role === 'assistant' && !msg.errorInfo) {
    const text = msg.content || msg.contentParts?.find(p => p.type === 'text')?.text || ''
    const rebuilt = reconstructErrorInfo(text)
    if (rebuilt) {
      msg.errorInfo = rebuilt
      msg.status = 'failed'
    }
  }

  return msg
}

function parseThinkingContent(raw: string): { content: string; thinking: string; hasThinking: boolean } {
  if (!raw) return { content: '', thinking: '', hasThinking: false }

  const normalized = raw.replace(/<thinking>/gi, '<think>').replace(/<\/thinking>/gi, '</think>')
  const thinkingParts: string[] = []
  const content = normalized.replace(/<think>([\s\S]*?)<\/think>/gi, (_, thinkingText: string) => {
    const cleanText = thinkingText.trim()
    if (cleanText) thinkingParts.push(cleanText)
    return ''
  }).trim()

  return {
    content,
    thinking: thinkingParts.join('\n\n').trim(),
    hasThinking: thinkingParts.length > 0,
  }
}

function formatConversationTime(time?: string) {
  if (!time) return t('chat.timeJustNow')
  const date = new Date(time)
  const diff = Date.now() - date.getTime()
  if (diff < 60 * 60 * 1000) return t('chat.timeMinutesAgo', { n: Math.max(1, Math.floor(diff / (60 * 1000))) })
  if (diff < 24 * 60 * 60 * 1000) return t('chat.timeHoursAgo', { n: Math.floor(diff / (60 * 60 * 1000)) })
  return date.toLocaleDateString()
}

function handleCodeCopy(e: MouseEvent) {
  const btn = (e.target as HTMLElement).closest('.code-block__copy') as HTMLElement | null
  if (!btn) return
  const encoded = btn.getAttribute('data-code')
  if (!encoded) return
  const code = decodeURIComponent(encoded)
  navigator.clipboard.writeText(code).then(() => {
    btn.classList.add('copied')
    const textEl = btn.querySelector('.code-block__copy-text')
    if (textEl) textEl.textContent = t('chat.copied')
    setTimeout(() => {
      btn.classList.remove('copied')
      if (textEl) textEl.textContent = t('chat.copy')
    }, 1500)
  }).catch(() => {
    ElMessage.error(t('chat.copyFailed'))
  })
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.conversation-panel {
  width: 260px;
  min-width: 260px;
  background: var(--mc-bg-elevated);
  border-right: 1px solid var(--mc-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid var(--mc-border-light);
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}

.new-chat-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-primary);
  transition: all 0.15s;
}

.new-chat-btn:hover {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: white;
}

.agent-selector {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border-light);
}

.agent-select {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  cursor: pointer;
  outline: none;
}

.agent-select:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1);
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-group-title {
  padding: 8px 10px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.conv-item:hover {
  background: var(--mc-bg-sunken);
}

.conv-item.active {
  background: var(--mc-primary-bg);
}

.conv-item:hover .conv-delete {
  opacity: 1;
}

.conv-icon {
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
}

.conv-item.active .conv-icon {
  color: var(--mc-primary);
}

.conv-info {
  flex: 1;
  overflow: hidden;
}

.conv-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-item.active .conv-title {
  color: var(--mc-primary);
}

.conv-meta {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 1px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.conv-dot {
  color: var(--mc-text-tertiary);
}

.conv-delete {
  opacity: 0;
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
  flex-shrink: 0;
  transition: all 0.15s;
}

.conv-delete:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.empty-convs {
  text-align: center;
  padding: 32px 16px;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  line-height: 1.8;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--mc-chat-bg);
  position: relative;
}

/* 拖拽上传遮罩 */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(217, 119, 87, 0.06);
  backdrop-filter: blur(2px);
}

.drop-overlay__content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 60px;
  border: 2px dashed var(--mc-primary, #D97757);
  border-radius: 16px;
  background: var(--mc-bg-elevated, #f8fafc);
  color: var(--mc-primary, #D97757);
  font-size: 16px;
  font-weight: 500;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--mc-chat-header-bg);
  border-bottom: 1px solid var(--mc-border);
  min-height: 52px;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--mc-primary-bg);
  border-radius: 20px;
}

.agent-badge-icon {
  font-size: 14px;
}

.agent-badge-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-primary);
}

.agent-badge-type {
  font-size: 11px;
  color: var(--mc-primary-light);
  background: var(--mc-bg-elevated);
  padding: 1px 6px;
  border-radius: 10px;
}

.no-agent-hint {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

.model-select {
  min-width: 260px;
  height: 32px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-size: 13px;
  padding: 0 10px;
}

.header-btn {
  width: 32px;
  height: 32px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-secondary);
  transition: all 0.15s;
}

.header-btn:hover {
  border-color: var(--mc-danger);
  color: var(--mc-danger);
}

.model-prompt {
  margin: 24px auto 0;
  max-width: 540px;
  padding: 20px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  text-align: center;
  box-shadow: 0 8px 24px rgba(124, 63, 30, 0.06);
}

.model-prompt-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin-bottom: 8px;
}

.model-prompt-desc {
  font-size: 14px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
  margin-bottom: 16px;
}

.btn-primary {
  padding: 8px 16px;
  background: var(--mc-primary);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover {
  background: var(--mc-primary-hover);
}

/* ===== 移动端元素（桌面端隐藏） ===== */
.conv-backdrop {
  display: none;
}

.conv-toggle-btn {
  display: none;
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .conversation-panel {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;
    width: 280px;
    min-width: 280px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }

  .conversation-panel.mobile-open {
    transform: translateX(0);
    box-shadow: 4px 0 16px rgba(0, 0, 0, 0.1);
  }

  .conv-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 99;
    background: rgba(0, 0, 0, 0.3);
  }

  .conv-toggle-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid var(--mc-border);
    background: var(--mc-bg-elevated);
    border-radius: 6px;
    cursor: pointer;
    color: var(--mc-text-secondary);
    flex-shrink: 0;
    transition: all 0.15s;
  }

  .conv-toggle-btn:hover {
    border-color: var(--mc-primary);
    color: var(--mc-primary);
  }

  .chat-header {
    padding: 10px 12px;
    gap: 8px;
  }

  .chat-header-left {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 6px;
  }

  .agent-badge {
    padding: 4px 8px;
  }

  .agent-badge-name,
  .agent-badge-type {
    display: none;
  }

  .model-select {
    min-width: 0;
    max-width: 160px;
    flex: 1;
  }

  .drop-overlay__content {
    padding: 24px 32px;
    font-size: 14px;
  }
}


</style>
