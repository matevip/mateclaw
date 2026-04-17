/**
 * 聊天功能统一 Composable
 * 整合 useMessages、useStream、useMessageQueue，提供完整的聊天功能
 *
 * 核心机制（参考 claude-code-haha 的 Interrupt + Queue + Resume 模型）：
 * - 运行中允许继续输入新消息
 * - 可中断阶段：发送 interrupt 请求，中断后自动续跑排队消息
 * - 不可中断阶段：消息排队，等当前步骤结束后自动继续
 * - 审批中：消息排队，不打断审批流程
 */
import { ref, computed } from 'vue'
import { useMessages } from './useMessages'
import { useStream } from './useStream'
import { useMessageQueue } from './useMessageQueue'
import type { Message, MessageContentPart, MessageSegment, StreamPhase, HeartbeatData, QueuedMessage, PhaseEventData } from '@/types'
import { classifyBackendError, type ChatErrorInfo } from '@/types/chatError'
import { http } from '@/api'

export interface UseChatOptions {
  /** API 基础 URL */
  baseUrl: string
  /** 认证 Token */
  token?: string
  /** 当前思考深度（响应式 ref），off 时抑制 thinking 展示 */
  thinkingLevel?: import('vue').Ref<string>
  /**
   * 统一回调：流结束后（done/error/stopped 都会触发）。
   * 前端应在此回调中做持久化历史收口（reconcile）。
   */
  onStreamEnd?: (meta: StreamEndMeta) => void
}

/** 流结束元信息 */
export interface StreamEndMeta {
  conversationId: string
  reason: 'completed' | 'stopped' | 'interrupted' | 'failed' | 'error' | 'awaiting_approval'
  /** 后端持久化的 assistant 消息 ID（若有） */
  assistantMessageId?: number
  /** 后端是否已持久化 */
  persisted?: boolean
  /** 后端当前消息总数 */
  messageCount?: number
}

export interface UseChatReturn {
  /** 消息列表 */
  messages: import('vue').Ref<Message[]>
  /** 是否正在生成 */
  isGenerating: import('vue').ComputedRef<boolean>
  /** 当前流阶段 */
  streamPhase: import('vue').Ref<StreamPhase>
  /** 最近一次阶段事件 */
  phaseInfo: import('vue').Ref<PhaseEventData | null>
  /** 当前错误 */
  error: import('vue').Ref<Error | null>
  /** 排队的消息 */
  queuedMessage: import('vue').Ref<QueuedMessage | null>
  /** 是否有排队消息 */
  hasQueued: import('vue').ComputedRef<boolean>
  /** 排队消息数量 */
  queueSize: import('vue').ComputedRef<number>
  /** 心跳数据 */
  heartbeat: import('vue').Ref<HeartbeatData | null>
  /** 发送消息（运行中也可调用，自动走 interrupt/queue） */
  sendMessage: (content: string, options: SendMessageOptions) => Promise<void>
  /** 停止生成（用户主动停止，不自动续跑） */
  stopGeneration: () => void
  /** 取消排队消息 */
  cancelQueued: () => void
  /** 重新生成 */
  regenerate: (messageId: string | number) => Promise<void>
  /** 添加消息 */
  addMessage: (message: Omit<Message, 'id' | 'createTime'> & { id?: string | number }) => Message
  /** 清空消息 */
  clearMessages: () => void
  /** 重连到运行中的流 */
  reconnectStream: (conversationId: string) => Promise<void>
  /** 彻底重置流上下文 — 切换/新建会话时调用 */
  resetForNewConversation: () => void
}

export interface SendMessageOptions {
  /** 会话 ID */
  conversationId: string
  /** Agent ID */
  agentId: string | number
  /** 附件列表 */
  attachments?: MessageContentPart[]
  /** 消息内容 */
  contentParts?: MessageContentPart[]
  /** 思考深度：off / low / medium / high / max */
  thinkingLevel?: string
}

export function useChat(options: UseChatOptions): UseChatReturn {
  const { baseUrl, token, onStreamEnd } = options
  const thinkingLevelRef = options.thinkingLevel

  /**
   * 带认证的 fetch 封装 — 从 localStorage 读取 token（与 useStream / http.ts 一致）
   */
  const fetchWithAuth = (url: string, init: RequestInit = {}): Promise<Response> => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(init.headers as Record<string, string> || {}),
    }
    const storedToken = localStorage.getItem('token')
    if (storedToken) headers.Authorization = `Bearer ${storedToken}`
    if (token) headers.Authorization = `Bearer ${token}`
    return fetch(url, { ...init, headers })
  }

  const error = ref<Error | null>(null)
  const currentAssistantId = ref<string | null>(null)
  /** stopGeneration 的 fallback timer，新流开始时必须清除，防止误杀新连接 */
  let stopFallbackTimer: ReturnType<typeof setTimeout> | null = null
  const streamPhase = ref<StreamPhase>('idle')
  const phaseInfo = ref<PhaseEventData | null>(null)

  /** 分段式展示数据：当前助手消息的所有分段 */
  const currentSegments = ref<MessageSegment[]>([])
  const segIdCounter = { value: 0 }
  const genSegId = () => `seg-${Date.now()}-${segIdCounter.value++}`

  /** 当前 turn 的唯一标识 — 确保 flushSegmentsToMessage 不会把旧 turn 的 segments 写到新消息 */
  let activeTurnId = ''

  /** 重置当前 turn 的流式状态 — 必须在每次创建新 assistant placeholder 之前调用 */
  function resetCurrentTurnState() {
    currentSegments.value = []
    segIdCounter.value = 0
    activeTurnId = `turn-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  }

  /** 将当前 segments 同步到助手消息的 metadata 中（实时渲染用） */
  const flushSegmentsToMessage = () => {
    if (!currentAssistantId.value || currentSegments.value.length === 0) return
    const msg = getMessage(currentAssistantId.value)
    if (!msg) return
    // 保护：只写入当前 turn 创建的消息，避免旧 turn segments 污染新消息
    if ((msg as any)._turnId && (msg as any)._turnId !== activeTurnId) return
    const metadata = parseMetadata((msg as any).metadata)
    updateMessage(currentAssistantId.value, {
      ...msg,
      metadata: { ...metadata, segments: [...currentSegments.value] }
    } as any)
  }
  const heartbeat = ref<HeartbeatData | null>(null)
  /** Track which conversation the current stream belongs to */
  let streamConversationId = ''
  /** 判断事件是否属于已过期的对话（防止旧流事件污染新会话） */
  function isStaleEvent(data: any): boolean {
    const eventConvId = data?.conversationId
    if (eventConvId && streamConversationId && eventConvId !== streamConversationId) {
      return true
    }
    return false
  }
  /** 已处理的 approval pendingId 集合（幂等去重） */
  const processedApprovalIds = new Set<string>()

  /**
   * 解析 metadata - 处理从数据库加载的 JSON 字符串
   */
  const parseMetadata = (metadata: any): any => {
    if (!metadata) return {}
    if (typeof metadata === 'string') {
      try {
        let parsed = JSON.parse(metadata)
        // 处理双重 JSON 编码（DB metadata 是字符串，Jackson 可能再次转义）
        if (typeof parsed === 'string') {
          try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
        }
        return parsed
      } catch (e) {
        console.warn('[useChat] Failed to parse metadata:', e)
        return {}
      }
    }
    return metadata
  }

  /**
   * SSE 以 error/done 结束但审批实际上已失效时，收口残留的 awaiting_approval UI 状态。
   * 必须用 updateMessage 触发响应式更新，不能只改嵌套字段。
   */
  const expirePendingApprovals = (finalStatus: 'completed' | 'failed' | 'stopped') => {
    for (const m of messages.value) {
      if (m.role !== 'assistant') continue
      const metadata = parseMetadata((m as any).metadata)
      const pendingApproval = metadata?.pendingApproval
      const hasPendingApproval = pendingApproval?.status === 'pending_approval'
      const isAwaitingApprovalMsg = m.status === 'awaiting_approval' || metadata?.currentPhase === 'awaiting_approval'
      if (!hasPendingApproval && !isAwaitingApprovalMsg) continue
      if (m.id === undefined || m.id === null) continue

      const toolCalls = Array.isArray(metadata?.toolCalls)
        ? metadata.toolCalls.map((tc: any) => (
            tc?.status === 'running' || tc?.status === 'awaiting_approval'
              ? { ...tc, status: 'completed' }
              : tc
          ))
        : metadata?.toolCalls

      updateMessage(m.id, {
        ...m,
        status: m.status === 'awaiting_approval' ? finalStatus : m.status,
        metadata: {
          ...metadata,
          currentPhase: undefined,
          runningToolName: undefined,
          toolCalls,
          pendingApproval: hasPendingApproval
            ? { ...pendingApproval, status: 'expired' }
            : pendingApproval,
        },
      } as any)
    }
  }

  // 消息管理
  const {
    messages,
    isGenerating,
    addMessage,
    updateMessage,
    appendMessageContent,
    setMessageStatus,
    createUserMessage,
    createAssistantMessage,
    clearMessages,
    getMessage,
  } = useMessages({
    onComplete: () => {
      // 不在 onComplete 里清 currentAssistantId — 让 done 事件来清
    },
  })

  // 消息队列
  const messageQueue = useMessageQueue()

  // 流连接（注入 auth + workspace header，与 axios interceptor 保持一致）
  const streamHeaders: Record<string, string> = {}
  if (token) {
    streamHeaders['Authorization'] = `Bearer ${token}`
  }
  const wsId = localStorage.getItem('mc-workspace-id')
  if (wsId) {
    streamHeaders['X-Workspace-Id'] = wsId
  }
  const stream = useStream({
    url: `${baseUrl}/api/v1/chat/stream`,
    headers: streamHeaders,
  })

  // ===== SSE 事件处理器 =====

  stream.on('content_delta', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'text')
      if (['thinking', 'reasoning', 'drafting_answer', 'preparing_context'].includes(streamPhase.value)) {
        streamPhase.value = 'streaming'
      }
      // 分段：追加到当前 content segment 或创建新的
      const segs = currentSegments.value
      let contentSeg = segs.findLast((s: MessageSegment) => s.type === 'content' && s.status === 'running')
      if (!contentSeg) {
        // 关闭之前的 thinking segment
        const thinkingSeg = segs.findLast((s: MessageSegment) => s.type === 'thinking' && s.status === 'running')
        if (thinkingSeg) thinkingSeg.status = 'completed'
        contentSeg = { id: genSegId(), type: 'content', status: 'running', text: '', timestamp: Date.now() }
        segs.push(contentSeg)
        flushSegmentsToMessage() // 新 content segment 创建时同步一次
      }
      contentSeg.text = (contentSeg.text || '') + (data.delta || '')
    }
  })

  stream.on('thinking_delta', (data) => {
    if (isStaleEvent(data)) return
    // thinkingLevel=off 时抑制 thinking 展示
    if (options.thinkingLevel?.value === 'off') return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'thinking')
      if (streamPhase.value !== 'summarizing_observations') {
        streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
      }
      // 分段：所有 thinking 合并到一个 segment（不因 tool_call 中断而创建多个）
      const segs = currentSegments.value
      // 优先复用已有的 thinking segment（无论 running 还是 completed）
      let thinkSeg = segs.find((s: MessageSegment) => s.type === 'thinking')
      if (!thinkSeg) {
        thinkSeg = { id: genSegId(), type: 'thinking', status: 'running', thinkingText: '', timestamp: Date.now() }
        // 插入到开头（thinking 始终在最上方）
        segs.unshift(thinkSeg)
        flushSegmentsToMessage()
      }
      // 新的 thinking 到来，重新设为 running
      thinkSeg.status = 'running'
      thinkSeg.thinkingText = (thinkSeg.thinkingText || '') + (data.delta || '')
    }
  })

  stream.on('message_start', (data) => {
    if (isStaleEvent(data)) return
    if (data?.role !== 'assistant') return
    const currentMsg = currentAssistantId.value ? getMessage(currentAssistantId.value) : null
    if (currentMsg?.role === 'assistant') {
      if (currentMsg.status !== 'generating') {
        setMessageStatus(currentAssistantId.value!, 'generating')
      }
      return
    }

    // 没有 placeholder 时才创建（正常路径 placeholder 已在 sendMessage 中创建）
    resetCurrentTurnState()
    const assistantMessage = createAssistantMessage('', streamConversationId)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string
  })

  stream.on('warning', (data) => {
    if (isStaleEvent(data)) return
    console.warn('[Chat] Warning from server:', data.delta || data.message || data)
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const warnings = metadata?.warnings || []
        warnings.push(data.delta || data.message || String(data))
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, warnings }
        } as any)
      }
    }
  })

  stream.on('message_complete', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg?.status === 'failed') {
        // 不清除 currentAssistantId — 让 done 事件来做
        return
      }
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.toolCalls) {
          const toolCalls = [...metadata.toolCalls]
          let needsUpdate = false
          for (let i = 0; i < toolCalls.length; i++) {
            if (toolCalls[i].status !== 'completed') {
              toolCalls[i] = { ...toolCalls[i], status: 'completed' }
              needsUpdate = true
            }
          }
          if (needsUpdate) {
            updateMessage(currentAssistantId.value, {
              ...msg,
              status: data.status || 'completed',
              metadata: { ...metadata, toolCalls }
            } as any)
            // 关键修复：不在这里清除 currentAssistantId，让 done 来清
            return
          }
        }
      }
      setMessageStatus(currentAssistantId.value, data.status || 'completed')
      // 关键修复：不在这里清除 currentAssistantId
    }

    // 分段：标记所有 running segments 为 completed，并持久化到 message metadata
    if (currentAssistantId.value && currentSegments.value.length > 0) {
      currentSegments.value.forEach((s: MessageSegment) => { if (s.status === 'running') s.status = 'completed' })
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, segments: [...currentSegments.value] }
        } as any)
      }
    }

    // === 自动 TTS：message_complete 且 status=completed 时触发 ===
    if (data.status === 'completed' && data.hasContent && currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg?.content && streamConversationId) {
        triggerAutoTts(streamConversationId, msg.content)
      }
    }
  })

  stream.on('done', (data) => {
    if (isStaleEvent(data)) return

    if (currentAssistantId.value) {
      const existingMsg = getMessage(currentAssistantId.value)
      if (existingMsg?.status !== 'failed') {
        setMessageStatus(currentAssistantId.value, data.status || 'completed')
      }

      // 更新 token 信息 + 用持久化 id 替换本地临时 id（关键：让 reconcile 能匹配）
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        if (data.promptTokens !== undefined) msg.promptTokens = data.promptTokens
        if (data.completionTokens !== undefined) msg.completionTokens = data.completionTokens
        // 用后端持久化 id 替换本地临时 id，使 reconcile 时能按 id 匹配
        if (data.assistantMessageId) {
          msg.id = data.assistantMessageId
        }
        messages.value[msgIndex] = { ...msg }
      }
      currentAssistantId.value = null
    }

    streamPhase.value = data.status === 'awaiting_approval' ? 'awaiting_approval'
      : data.status === 'stopped' ? 'stopped' : 'completed'
    if (data.status !== 'awaiting_approval') {
      phaseInfo.value = null
      expirePendingApprovals(data.status === 'stopped' ? 'stopped' : 'completed')
    }

    // 兜底清理排队状态（如果 queued_input_started 已经处理了则这里是 no-op）
    if (!messageQueue.hasQueued.value) {
      // 队列已空，确保 phase 不残留 queued
    } else if (data.status === 'stopped') {
      // 用户主动停止，清除排队
      messageQueue.clear()
    }

    // Fire unified onStreamEnd
    const reason = data.status === 'stopped' ? 'stopped'
      : data.status === 'interrupted' ? 'interrupted'
      : data.status === 'awaiting_approval' ? 'awaiting_approval'
      : 'completed'
    onStreamEnd?.({
      conversationId: data.conversationId || streamConversationId,
      reason,
      assistantMessageId: data.assistantMessageId,
      persisted: data.persisted,
      messageCount: data.messageCount,
    })
  })

  let errorFired = false
  stream.on('error', (data) => {
    if (isStaleEvent(data)) return
    const errorInfo: ChatErrorInfo = data.errorInfo
      || (data.errorType ? classifyBackendError(data) : { category: 'unknown', retryable: true, timestamp: Date.now() })
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        updateMessage(currentAssistantId.value, {
          ...msg,
          status: 'failed',
          errorInfo,
        } as any)
      } else {
        setMessageStatus(currentAssistantId.value, 'failed')
      }
      currentAssistantId.value = null
    }
    error.value = new Error(data.message || '请求失败')
    streamPhase.value = 'idle'
    phaseInfo.value = null
    // 错误时清理排队状态，避免脏残留
    messageQueue.clear()
    expirePendingApprovals('failed')

    if (errorFired) return
    errorFired = true
    onStreamEnd?.({
      conversationId: data.conversationId || streamConversationId,
      reason: 'error',
      assistantMessageId: data.assistantMessageId,
      persisted: data.persisted,
      messageCount: data.messageCount,
    })
  })

  // ===== Agent 事件处理 =====

  stream.on('tool_call_started', (data) => {
    if (isStaleEvent(data)) return
    streamPhase.value = 'executing_tool'
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const toolCalls = metadata?.toolCalls || []
        toolCalls.push({
          name: data.toolName,
          arguments: data.arguments,
          status: 'running',
          startTime: data.timestamp || Date.now()
        })
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, toolCalls, currentPhase: 'executing_tool', runningToolName: data.toolName }
        } as any)
      }
      // 分段：关闭之前的 thinking/content segment，创建新的 tool_call segment
      const segs = currentSegments.value
      const runningSeg = segs.findLast((s: MessageSegment) => s.status === 'running' && (s.type === 'thinking' || s.type === 'content'))
      if (runningSeg) runningSeg.status = 'completed'
      segs.push({
        id: genSegId(), type: 'tool_call', status: 'running',
        toolName: data.toolName, toolArgs: data.arguments,
        timestamp: data.timestamp || Date.now(),
      })
      flushSegmentsToMessage()
    }
  })

  stream.on('tool_call_completed', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const toolCalls = [...(metadata?.toolCalls || [])]
        const lastRunning = toolCalls.findLastIndex((tc: any) => tc.status === 'running')
        if (lastRunning >= 0) {
          toolCalls[lastRunning] = {
            ...toolCalls[lastRunning],
            result: data.result,
            success: data.success,
            status: 'completed'
          }
        }
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, toolCalls, runningToolName: undefined }
        } as any)
      }
      // 分段：找到对应的 running tool_call segment 并标记完成
      const segs = currentSegments.value
      const toolSeg = segs.findLast((s: MessageSegment) =>
        s.type === 'tool_call' && s.status === 'running' && s.toolName === data.toolName)
      if (toolSeg) {
        toolSeg.status = data.success !== false ? 'completed' : 'error'
        toolSeg.toolResult = data.result
        toolSeg.toolSuccess = data.success
      }
      flushSegmentsToMessage()
    }
  })

  // ===== Browser 执行事件 =====

  stream.on('browser_action', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const browserActions = [...(metadata?.browserActions || [])]
        browserActions.push({
          action: data.action,
          success: data.success,
          url: data.url,
          title: data.title,
          screenshot: data.screenshot,
          durationMs: data.durationMs,
          timestamp: data.timestamp || Date.now()
        })
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, browserActions }
        } as any)
      }
    }
  })

  stream.on('phase', (data) => {
    if (isStaleEvent(data)) return
    const phase = data.phase as StreamPhase
    if (phase) {
      streamPhase.value = phase
      phaseInfo.value = { ...data, phase }
    }
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        // 去重：相同 phase 不触发 updateMessage，避免不必要的 Vue 响应式更新
        if (metadata.currentPhase === data.phase) return
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, currentPhase: data.phase }
        } as any)
      }
    }
  })

  // ===== Agent 委派事件 =====
  stream.on('delegation_start', (data) => {
    if (isStaleEvent(data)) return
    streamPhase.value = 'executing_tool'
    if (currentAssistantId.value) {
      const segs = currentSegments.value
      // 关闭之前的 thinking/content segment
      const runningSeg = segs.findLast((s: MessageSegment) => s.status === 'running')
      if (runningSeg) runningSeg.status = 'completed'

      if (data.parallel && Array.isArray(data.children)) {
        // 并行模式：为每个子任务创建一个 delegation segment
        for (const child of data.children) {
          segs.push({
            id: genSegId(),
            type: 'tool_call',
            status: 'running',
            toolName: `→ ${child.childAgentName || 'Agent'}`,
            toolArgs: child.task || '',
            timestamp: Date.now()
          })
        }
      } else {
        // 单任务模式
        segs.push({
          id: genSegId(),
          type: 'tool_call',
          status: 'running',
          toolName: `→ ${data.childAgentName || 'Agent'}`,
          toolArgs: data.task || '',
          timestamp: Date.now()
        })
      }
      flushSegmentsToMessage()
    }
  })

  stream.on('delegation_progress', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value && data.originalEvent === 'tool_call_started') {
      const segs = currentSegments.value
      // 按 childAgentName 匹配对应的 delegation segment（并行时多个）
      const childName = data.childAgentName || ''
      const delegSeg = segs.findLast((s: MessageSegment) =>
        s.type === 'tool_call' && s.status === 'running' && s.toolName === `→ ${childName}`)
        || segs.findLast((s: MessageSegment) => s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
      if (delegSeg) {
        const childData = typeof data.data === 'string' ? data.data : JSON.stringify(data.data)
        delegSeg.toolArgs = (delegSeg.toolArgs || '') + '\n  [子任务] ' + childData
      }
    }
  })

  stream.on('delegation_end', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const segs = currentSegments.value
      if (data.parallel) {
        // 并行模式：关闭所有 running 的 delegation segments
        const totalMs = data.totalDurationMs ? Math.round(data.totalDurationMs / 1000) : 0
        segs.filter((s: MessageSegment) => s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
          .forEach((s: MessageSegment) => {
            s.status = 'completed'
            s.toolName = (s.toolName || '') + (data.success ? ' ✓' : ' ✗')
          })
      } else {
        // 单任务模式
        const delegSeg = segs.findLast((s: MessageSegment) => s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
        if (delegSeg) {
          delegSeg.status = 'completed'
          delegSeg.toolName = (delegSeg.toolName || '') + (data.success ? ' ✓' : ' ✗')
          if (data.durationMs) {
            delegSeg.toolArgs = (delegSeg.toolArgs || '') + `\n  耗时: ${Math.round(data.durationMs / 1000)}s`
          }
        }
      }
      flushSegmentsToMessage()
    }
  })

  stream.on('plan_created', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: {
            ...metadata,
            plan: { planId: data.planId, steps: data.steps, currentStep: 0 }
          }
        } as any)
      }
    }
  })

  stream.on('plan_step_started', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.plan) {
          updateMessage(currentAssistantId.value, {
            ...msg,
            metadata: {
              ...metadata,
              plan: { ...metadata.plan, currentStep: data.index }
            }
          } as any)
        }
      }
    }
  })

  stream.on('plan_step_completed', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.plan) {
          const plan = { ...metadata.plan }
          const stepResults = [...(plan.stepResults || [])]
          stepResults[data.index] = { result: data.result, status: 'completed' }
          updateMessage(currentAssistantId.value, {
            ...msg,
            metadata: {
              ...metadata,
              plan: { ...plan, stepResults }
            }
          } as any)
        }
      }
    }
  })

  // ===== 工具审批事件（带幂等去重） =====

  stream.on('tool_approval_requested', (data) => {
    if (isStaleEvent(data)) return
    // 幂等去重：同一 pendingId 只处理一次
    if (data.pendingId && processedApprovalIds.has(data.pendingId)) {
      // duplicate approval ignored
      return
    }
    if (data.pendingId) processedApprovalIds.add(data.pendingId)

    streamPhase.value = 'awaiting_approval'

    let targetId = currentAssistantId.value
    if (!targetId) {
      const assistantMessages = messages.value.filter(m => m.role === 'assistant')
      if (assistantMessages.length > 0) {
        targetId = assistantMessages[assistantMessages.length - 1].id as string
      }
    }
    if (!targetId) {
      resetCurrentTurnState()
      const placeholder = createAssistantMessage('', streamConversationId)
      ;(placeholder as any)._turnId = activeTurnId
      targetId = placeholder.id as string
      currentAssistantId.value = targetId
    }

    const msg = getMessage(targetId)
    if (msg) {
      const metadata = parseMetadata((msg as any).metadata)
      const toolCalls = [...(metadata?.toolCalls || [])]
      for (let i = 0; i < toolCalls.length; i++) {
        if (toolCalls[i].status === 'running') {
          toolCalls[i] = { ...toolCalls[i], status: 'awaiting_approval' }
        }
      }
      updateMessage(targetId, {
        ...msg,
        status: 'awaiting_approval',
        metadata: {
          ...metadata,
          currentPhase: 'awaiting_approval',
          toolCalls,
          pendingApproval: {
            pendingId: data.pendingId,
            toolName: data.toolName,
            arguments: data.arguments,
            reason: data.reason,
            status: 'pending_approval',
            findings: data.findings || undefined,
            maxSeverity: data.maxSeverity || undefined,
            summary: data.summary || undefined,
          }
        }
      } as any)
    }
  })

  stream.on('tool_approval_resolved', (data) => {
    if (isStaleEvent(data)) return
    const targetMsg = messages.value.findLast((m) => {
      if (m.role !== 'assistant') return false
      const metadata = parseMetadata((m as any).metadata)
      return metadata?.pendingApproval?.pendingId === data.pendingId
    })
    if (targetMsg) {
      const targetId = targetMsg.id as string
      const msg = getMessage(targetId)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.pendingApproval) {
          const toolCalls = [...(metadata?.toolCalls || [])]
          for (let i = 0; i < toolCalls.length; i++) {
            if (toolCalls[i].status !== 'completed') {
              toolCalls[i] = { ...toolCalls[i], status: 'completed' }
            }
          }
          updateMessage(targetId, {
            ...msg,
            status: 'completed',
            metadata: {
              ...metadata,
              currentPhase: 'completed',
              toolCalls,
              pendingApproval: {
                ...metadata.pendingApproval,
                status: data.decision === 'approved' ? 'approved' : 'denied'
              }
            }
          } as any)
        }
      }
    }
    streamPhase.value = data.decision === 'approved' ? 'streaming' : 'completed'
  })

  // ===== Heartbeat 事件 =====

  stream.on('heartbeat', (data: HeartbeatData) => {
    heartbeat.value = data
    // heartbeat 到达意味着连接活跃，useStream 的 timeout 已由 resetStreamTimeout 自动重置
    // 从 heartbeat 中更新 phase（如果前端还没有更精确的 phase）
    if (data.currentPhase && streamPhase.value !== 'interrupting') {
      const phaseMap: Record<string, StreamPhase> = {
        'preparing_context': 'preparing_context',
        'reading_memory': 'reading_memory',
        'reasoning': 'reasoning',
        'drafting_answer': 'drafting_answer',
        'summarizing_observations': 'summarizing_observations',
        'thinking': 'thinking',
        'streaming': 'streaming',
        'executing_tool': 'executing_tool',
        'awaiting_approval': 'awaiting_approval',
        'finalizing': 'finalizing',
        'failed': 'failed',
      }
      const mapped = phaseMap[data.currentPhase]
      if (mapped) streamPhase.value = mapped
    }
    // 利用 heartbeat 的 queueLength 校准本地排队状态
    // 仅在消息已被后端确认（status=sending）后才以 heartbeat 兜底清理，
    // 避免在 interrupt 请求仍在途中时误清尚未到达后端的消息
    if (data.queueLength === 0 && messageQueue.hasQueued.value
        && messageQueue.queuedMessage.value?.status === 'sending') {
      messageQueue.clear()
    }
  })

  // ===== Interrupt + Queue 事件 =====

  stream.on('turn_interrupt_requested', () => {
    streamPhase.value = 'interrupting'
  })

  stream.on('turn_interrupted', (data) => {
    if (isStaleEvent(data)) return
    // 当前 turn 已中断，等待后端自动启动排队消息
    // 如果后端会自动续跑，前端不需要做额外操作
    // 如果后端没有排队消息但前端有（应该不会发生），则前端发送
    if (data.hasQueuedMessage) {
      streamPhase.value = 'queued'
    }
  })

  stream.on('queued_input_accepted', (data) => {
    // 后端已确认接收排队消息，标记为 sending（允许 heartbeat 兜底清理）
    messageQueue.markSending()
    streamPhase.value = 'queued'
  })

  stream.on('queued_input_started', (data) => {
    if (isStaleEvent(data)) return
    // 后端已开始处理排队消息
    // 1. 先用排队的内容创建用户消息（此时上一轮回答已完成，顺序正确）
    const queued = messageQueue.dequeue()
    const messageContent = data.message || queued?.content || ''
    if (messageContent) {
      const convId = data.conversationId || streamConversationId
      createUserMessage(messageContent, queued?.contentParts, convId)
    }
    // 2. 再创建 assistant 占位消息
    resetCurrentTurnState()
    const convId2 = data.conversationId || streamConversationId
    const assistantMessage = createAssistantMessage('', convId2)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string
    streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
    phaseInfo.value = null
  })

  // ===== 异步任务完成事件（视频生成、图片生成等） =====
  stream.on('async_task_completed', (data) => {
    if (isStaleEvent(data)) return
    if (data.success && streamConversationId) {
      let mediaPart: MessageContentPart | null = null
      if (data.videoUrl) {
        mediaPart = {
          type: 'video',
          fileUrl: data.videoUrl,
          fileName: `video_${data.taskId}.mp4`,
          contentType: 'video/mp4',
        } as MessageContentPart
      } else if (data.imageUrl) {
        mediaPart = {
          type: 'image',
          fileUrl: data.imageUrl,
          fileName: `image_${data.taskId}.png`,
          contentType: 'image/png',
        } as MessageContentPart
      }

      if (!mediaPart) return

      // 优先附加到当前 assistant 消息（避免图片跑到文字回复上方）
      if (currentAssistantId.value) {
        const msg = getMessage(currentAssistantId.value)
        if (msg) {
          const existingParts = (msg as any).contentParts || []
          updateMessage(currentAssistantId.value, {
            contentParts: [...existingParts, mediaPart],
          } as any)
          return
        }
      }

      // 回退：Agent 已结束，新建独立消息
      addMessage({
        role: 'assistant',
        content: '',
        contentParts: [mediaPart],
        status: 'completed',
        conversationId: streamConversationId,
      })
    }
  })

  // ===== TTS 自动朗读 =====
  let ttsAutoModeCache: string | null = null
  let ttsCacheExpiry = 0

  async function triggerAutoTts(conversationId: string, text: string) {
    try {
      // 缓存 settings 5 分钟，避免每条消息都请求
      const now = Date.now()
      if (!ttsAutoModeCache || now > ttsCacheExpiry) {
        const res: any = await http.get('/system-settings')
        ttsAutoModeCache = res.data?.ttsAutoMode || 'off'
        ttsCacheExpiry = now + 5 * 60 * 1000
      }
      if (ttsAutoModeCache !== 'always') return
      // 调用后端合成，后端会通过 SSE tts_ready 广播
      http.post('/tts/synthesize', { conversationId, text }).catch(() => {})
    } catch {
      // 静默失败
    }
  }

  // ===== TTS 自动朗读：监听 tts_ready 事件 =====
  stream.on('tts_ready', (data) => {
    if (data.audioUrl) {
      const token = localStorage.getItem('token') || ''
      fetch(data.audioUrl, { headers: { Authorization: `Bearer ${token}` } })
        .then(res => res.blob())
        .then(blob => {
          const url = URL.createObjectURL(blob)
          const audio = new Audio(url)
          audio.onended = () => URL.revokeObjectURL(url)
          audio.play().catch(() => URL.revokeObjectURL(url))
        })
        .catch(() => {})
    }
  })

  // ===== 发送消息（支持运行中继续发送） =====

  const sendMessage = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId, attachments = [], contentParts = [] } = options

    // 审批命令不走 interrupt 逻辑
    const isApprovalCommand = /^\/(approve|deny)$/i.test(content.trim())

    // ===== 运行中发送新消息：走 interrupt / queue 路径 =====
    if (isGenerating.value && !isApprovalCommand) {
      return await handleInterruptOrQueue(content, options)
    }

    // ===== 正常发送路径 =====
    // 清除上一次 stop 的 fallback timer，防止误杀新连接
    if (stopFallbackTimer) {
      clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
    // 切换会话时断开旧流，防止旧事件污染新会话
    if (streamConversationId && streamConversationId !== conversationId) {
      stream.disconnect()
      currentAssistantId.value = null
    }
    error.value = null
    errorFired = false
    streamConversationId = conversationId
    streamPhase.value = thinkingLevelRef?.value === 'off' ? 'streaming' : 'thinking'
    phaseInfo.value = null

    try {
      if (!isApprovalCommand) {
        createUserMessage(content, contentParts, conversationId)
      }

      resetCurrentTurnState()
      const assistantMessage = createAssistantMessage('', conversationId)
      ;(assistantMessage as any)._turnId = activeTurnId
      currentAssistantId.value = assistantMessage.id as string

      // contentParts 已由 buildOutgoingParts 包含 file entries，不要重复合并 attachments
      const body: Record<string, any> = {
        agentId,
        message: content,
        conversationId,
        contentParts,
      }
      if (options.thinkingLevel) {
        body.thinkingLevel = options.thinkingLevel
      }
      await stream.connect(body)
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
      streamPhase.value = 'idle'
      throw e
    }
  }

  /**
   * 运行中发送新消息：判断当前阶段是否可中断
   * - 可中断（thinking/streaming/executing_tool）：发送 interrupt 请求
   * - 不可中断（awaiting_approval）：排队
   */
  const handleInterruptOrQueue = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId } = options

    // 不立即创建用户消息 —— 等 queued_input_started 再插入，
    // 这样用户消息会出现在上一轮回答之后，保证正确的消息顺序。
    // 加入本地队列（保存 contentParts 以便延迟创建时使用）
    messageQueue.enqueue(content, options.contentParts, conversationId)

    try {
      const res = await fetchWithAuth(`${baseUrl}/api/v1/chat/${conversationId}/interrupt`, {
        method: 'POST',
        body: JSON.stringify({
          message: content,
          agentId,
          contentParts: options.contentParts || [],
        }),
      })
      const result = await res.json()

      if (result.data?.interrupted) {
        // 可中断：后端已发起中断，排队消息会被后端自动续跑
        streamPhase.value = 'interrupting'
        messageQueue.markSending()
      } else if (result.data?.queued) {
        // 不可中断但已排队：等当前步骤结束后自动续跑
        streamPhase.value = 'queued'
      } else {
        // 没有活跃的流，直接发送
        messageQueue.clear()
        createUserMessage(content, options.contentParts, conversationId)
        resetCurrentTurnState()
        const assistantMessage = createAssistantMessage('', conversationId)
        ;(assistantMessage as any)._turnId = activeTurnId
        currentAssistantId.value = assistantMessage.id as string
        streamPhase.value = thinkingLevelRef?.value === 'off' ? 'streaming' : 'thinking'
        phaseInfo.value = null
        await stream.connect({
          agentId,
          message: content,
          conversationId,
          contentParts: options.contentParts || [],
        })
      }
    } catch (e) {
      console.error('[useChat] Interrupt request failed:', e)
      // interrupt 失败：后端从未收到这条消息，不能指望 heartbeat/queue 机制。
      // 回退为本地可见消息 + 清队列，避免消息静默丢失。
      const failedQueued = messageQueue.dequeue()
      if (failedQueued) {
        createUserMessage(failedQueued.content, failedQueued.contentParts, conversationId)
      }
      error.value = new Error('Failed to queue message, please resend')
    }
  }

  // 停止生成（用户主动停止，不自动续跑）
  //
  // 设计参考 claude-code-haha 的 useCancelRequest：
  // 不立即断开 SSE，而是先发 stop 信号，等后端通过 SSE 返回 done 事件后再清理。
  // 这样 done 事件能正常到达，onStreamEnd 被触发，消息状态和会话列表都能正确更新。
  // 加一个 fallback timeout（3 秒），防止 done 事件因网络问题永远不到达。
  const stopGeneration = async () => {
    // 在任何 await 之前冻结标识符 + 安装 fallback timer，防止 resetForNewConversation 并发清空后丢失上下文
    const convId = streamConversationId
    const assistantId = currentAssistantId.value

    // 仅在前端确实在生成时才触发停止（含 SSE 接收中 / reconnect 中 / 审批等待中）。
    // 否则只是"旁观者"身份，不能把对方（渠道用户）的 agent run 也一起杀掉。
    const activelyStreaming = isGenerating.value
        || streamPhase.value === 'reconnecting'
        || streamPhase.value === 'awaiting_approval'

    if (!activelyStreaming) {
      // 没有真正在流 → 什么都不做，让调用方直接走 resetForNewConversation
      return
    }

    // 先取消排队消息
    messageQueue.clear()

    // 标记为停止中（让 UI 立即反馈）
    streamPhase.value = 'stopped'
    phaseInfo.value = null

    // 在 await 之前安装 fallback timer，确保即使 resetForNewConversation 并发执行也不会遗漏
    if (stopFallbackTimer) clearTimeout(stopFallbackTimer)
    stopFallbackTimer = setTimeout(() => {
      stopFallbackTimer = null
      console.warn('[useChat] Stop fallback: done event not received within 3s, force cleanup')
      // 只有当 stream 仍属于旧会话时才 disconnect，防止误杀新会话的流
      if (streamConversationId === convId || !streamConversationId) {
        stream.disconnect()
      }
      if (currentAssistantId.value === assistantId && assistantId) {
        setMessageStatus(assistantId, 'stopped')
        currentAssistantId.value = null
      }
      onStreamEnd?.({
        conversationId: convId,
        reason: 'stopped',
      })
    }, 3000)

    // 当 done 事件到达时，取消 fallback timer
    const unsubscribe = stream.on('done', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribe()
    })
    const unsubscribeError = stream.on('error', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribeError()
    })

    // 发送后端 stop 请求（fire-and-forget，不阻塞 resetForNewConversation）
    if (convId) {
      fetchWithAuth(`${baseUrl}/api/v1/chat/${convId}/stop`, {
        method: 'POST',
      }).catch(e => {
        console.warn('[useChat] Stop API failed:', e)
      })
    }
  }

  // 取消排队消息
  const cancelQueued = () => {
    messageQueue.cancel()
    // 通知后端清除排队消息（fire-and-forget）
    if (streamConversationId) {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' }
      if (token) headers.Authorization = `Bearer ${token}`
      // 后端没有专门的取消排队 API，用 stop 的语义来处理
    }
    if (streamPhase.value === 'queued') {
      streamPhase.value = isGenerating.value ? 'streaming' : 'idle'
    }
  }

  // 重连到运行中的流
  const reconnectStream = async (conversationId: string) => {
    if (isGenerating.value) return

    // 清除残留的 stop fallback timer
    if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
    streamPhase.value = 'reconnecting'
    streamConversationId = conversationId
    error.value = null
    errorFired = false
    phaseInfo.value = null

    resetCurrentTurnState()

    // 清理尾部的空 assistant 消息（来自上一轮被误杀的 run 留下的空壳，或 placeholder 遗留），
    // 避免与即将重连产生的 streaming 气泡共存形成"重复两条"假象。
    while (messages.value.length > 0) {
      const tail = messages.value[messages.value.length - 1]
      if (tail && tail.role === 'assistant'
          && tail.conversationId === conversationId
          && !tail.content
          && (!tail.contentParts || tail.contentParts.length === 0)) {
        messages.value.pop()
      } else {
        break
      }
    }

    const assistantMessage = createAssistantMessage('', conversationId)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string

    try {
      await stream.connect({
        conversationId,
        reconnect: true,
      })
    } catch (e) {
      console.error('[useChat] Reconnect failed:', e)
      // 重连失败：清理占位消息
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        // 如果占位消息没有内容，移除它
        if (!msg.content && (!msg.contentParts || msg.contentParts.length === 0)) {
          messages.value.splice(msgIndex, 1)
        } else {
          setMessageStatus(currentAssistantId.value!, 'completed')
        }
      }
      currentAssistantId.value = null
      streamPhase.value = 'idle'
      error.value = e instanceof Error ? e : new Error('重连失败: ' + String(e))
    }
  }

  // 重新生成
  const regenerate = async (messageId: string | number) => {
    const message = getMessage(messageId)
    if (!message) return

    const index = messages.value.findIndex(m => m.id === messageId)
    if (index <= 0) return

    const userMessage = messages.value[index - 1]
    if (userMessage.role !== 'user') return

    messages.value = messages.value.filter(m => m.id !== messageId)

    const text = userMessage.contentParts
      .filter(p => p.type === 'text')
      .map(p => p.text || '')
      .join('\n') || userMessage.content || ''

    await sendMessage(text, {
      conversationId: userMessage.conversationId,
      agentId: '',
    })
  }

  /** 彻底重置流上下文 — 切换/新建会话时调用，确保旧流状态不污染新会话 */
  const resetForNewConversation = () => {
    stream.disconnect()
    streamConversationId = ''
    currentAssistantId.value = null
    currentSegments.value = []
    segIdCounter.value = 0
    streamPhase.value = 'idle'
    phaseInfo.value = null
    error.value = null
    messageQueue.clear()
    if (stopFallbackTimer) {
      clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
  }

  return {
    messages,
    isGenerating,
    streamPhase,
    phaseInfo,
    error,
    queuedMessage: messageQueue.queuedMessage,
    hasQueued: messageQueue.hasQueued,
    queueSize: messageQueue.queueSize,
    heartbeat,
    sendMessage,
    stopGeneration,
    cancelQueued,
    regenerate,
    addMessage,
    clearMessages,
    reconnectStream,
    resetForNewConversation,
  }
}

export default useChat
