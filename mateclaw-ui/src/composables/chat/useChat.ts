/**
 * Unified chat composable.
 * Integrates useMessages, useStream, and useMessageQueue into a complete chat feature.
 *
 * Core mechanism (Interrupt + Queue + Resume model):
 * - New messages can be sent while a response is already generating.
 * - Interruptible phases (thinking/streaming/executing_tool): sends an interrupt request;
 *   the queued message resumes automatically after interruption.
 * - Non-interruptible phases: message is queued and auto-resumed when the current step ends.
 * - During approval: message is queued, approval flow is not interrupted.
 */
import { ref, computed } from 'vue'
import { useMessages } from './useMessages'
import { useStream } from './useStream'
import { useMessageQueue } from './useMessageQueue'
import type { Message, MessageContentPart, MessageSegment, StreamPhase, HeartbeatData, QueuedMessage, PhaseEventData } from '@/types'
import { classifyBackendError, type ChatErrorInfo } from '@/types/chatError'
import { http } from '@/api'

export interface UseChatOptions {
  /** Base API URL */
  baseUrl: string
  /** Auth token */
  token?: string
  /** Current thinking depth (reactive ref); when "off", thinking segments are suppressed */
  thinkingLevel?: import('vue').Ref<string>
  /**
   * Unified callback fired when the stream ends (done/error/stopped all trigger this).
   * The caller should perform history reconcile / persistence in this callback.
   */
  onStreamEnd?: (meta: StreamEndMeta) => void
}

/** Metadata emitted when a stream ends */
export interface StreamEndMeta {
  conversationId: string
  reason: 'completed' | 'stopped' | 'interrupted' | 'failed' | 'error' | 'awaiting_approval'
  /** Backend-persisted assistant message ID, if available */
  assistantMessageId?: number
  /** Whether the backend has already persisted the message */
  persisted?: boolean
  /** Total message count reported by the backend */
  messageCount?: number
}

export interface UseChatReturn {
  /** Message list */
  messages: import('vue').Ref<Message[]>
  /** Whether the assistant is currently generating */
  isGenerating: import('vue').ComputedRef<boolean>
  /** Current stream phase */
  streamPhase: import('vue').Ref<StreamPhase>
  /** Most recent phase event */
  phaseInfo: import('vue').Ref<PhaseEventData | null>
  /** Current error */
  error: import('vue').Ref<Error | null>
  /** Queued message waiting to be sent */
  queuedMessage: import('vue').Ref<QueuedMessage | null>
  /** Whether there is a queued message */
  hasQueued: import('vue').ComputedRef<boolean>
  /** Number of queued messages */
  queueSize: import('vue').ComputedRef<number>
  /** Latest heartbeat data */
  heartbeat: import('vue').Ref<HeartbeatData | null>
  /** Send a message (can be called while generating — automatically routes to interrupt/queue) */
  sendMessage: (content: string, options: SendMessageOptions) => Promise<void>
  /** Stop generation (user-initiated stop; does not auto-resume queued messages) */
  stopGeneration: () => void
  /** Cancel the queued message */
  cancelQueued: () => void
  /** Regenerate a message */
  regenerate: (messageId: string | number) => Promise<void>
  /** Add a message */
  addMessage: (message: Omit<Message, 'id' | 'createTime'> & { id?: string | number }) => Message
  /** Clear all messages */
  clearMessages: () => void
  /** Reconnect to a stream that is already running on the backend */
  reconnectStream: (conversationId: string) => Promise<void>
  /** Fully reset stream context — call when switching or creating a conversation */
  resetForNewConversation: () => void
}

export interface SendMessageOptions {
  /** Conversation ID */
  conversationId: string
  /** Agent ID */
  agentId: string | number
  /** Attachment list */
  attachments?: MessageContentPart[]
  /** Message content parts */
  contentParts?: MessageContentPart[]
  /** Thinking depth: off / low / medium / high / max */
  thinkingLevel?: string
}

export function useChat(options: UseChatOptions): UseChatReturn {
  const { baseUrl, token, onStreamEnd } = options
  const thinkingLevelRef = options.thinkingLevel

  /**
   * Authenticated fetch wrapper — reads the token from localStorage (consistent with useStream / http.ts).
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
  /** Fallback timer for stopGeneration — must be cleared when a new stream starts to avoid killing the new connection */
  let stopFallbackTimer: ReturnType<typeof setTimeout> | null = null
  const streamPhase = ref<StreamPhase>('idle')
  const phaseInfo = ref<PhaseEventData | null>(null)

  /** All segments of the current assistant message (for segmented display) */
  const currentSegments = ref<MessageSegment[]>([])
  const segIdCounter = { value: 0 }
  const genSegId = () => `seg-${Date.now()}-${segIdCounter.value++}`

  /** Unique ID for the current turn — prevents flushSegmentsToMessage from writing stale segments to a new message */
  let activeTurnId = ''

  /** Reset streaming state for the current turn — must be called before creating a new assistant placeholder */
  function resetCurrentTurnState() {
    currentSegments.value = []
    segIdCounter.value = 0
    activeTurnId = `turn-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  }

  /** Sync current segments into the assistant message metadata (used for real-time rendering) */
  const flushSegmentsToMessage = () => {
    if (!currentAssistantId.value || currentSegments.value.length === 0) return
    const msg = getMessage(currentAssistantId.value)
    if (!msg) return
    // Guard: only write to the message created in the current turn to avoid stale segment pollution
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
  /** Returns true if the event belongs to an expired conversation (prevents stale stream events from polluting a new session) */
  function isStaleEvent(data: any): boolean {
    const eventConvId = data?.conversationId
    if (eventConvId && streamConversationId && eventConvId !== streamConversationId) {
      return true
    }
    return false
  }
  /** Set of already-processed approval pendingIds (idempotency dedup) */
  const processedApprovalIds = new Set<string>()

  /**
   * Parse metadata — handles JSON strings loaded from the database.
   */
  const parseMetadata = (metadata: any): any => {
    if (!metadata) return {}
    if (typeof metadata === 'string') {
      try {
        let parsed = JSON.parse(metadata)
        // Handle double-encoded JSON (DB metadata is a string; Jackson may escape it again)
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
   * Expire stale awaiting_approval UI state when the stream ends with error/done but the approval
   * is no longer active. Must use updateMessage to trigger Vue reactivity — mutating nested fields
   * alone is not sufficient.
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

  // Message management
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
      // Do not clear currentAssistantId here — the 'done' event handles cleanup
    },
  })

  // Message queue
  const messageQueue = useMessageQueue()

  // Stream connection (inject auth + workspace headers, consistent with the axios interceptor)
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

  // ===== SSE event handlers =====

  stream.on('content_delta', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'text')
      if (['thinking', 'reasoning', 'drafting_answer', 'preparing_context'].includes(streamPhase.value)) {
        streamPhase.value = 'streaming'
      }
      // Segments: append to the current running content segment, or create a new one
      const segs = currentSegments.value
      let contentSeg = segs.findLast((s: MessageSegment) => s.type === 'content' && s.status === 'running')
      if (!contentSeg) {
        // Close any running thinking segment first
        const thinkingSeg = segs.findLast((s: MessageSegment) => s.type === 'thinking' && s.status === 'running')
        if (thinkingSeg) thinkingSeg.status = 'completed'
        contentSeg = { id: genSegId(), type: 'content', status: 'running', text: '', timestamp: Date.now() }
        segs.push(contentSeg)
        flushSegmentsToMessage() // sync once when a new content segment is created
      }
      contentSeg.text = (contentSeg.text || '') + (data.delta || '')
    }
  })

  stream.on('thinking_delta', (data) => {
    if (isStaleEvent(data)) return
    // Suppress thinking display when thinkingLevel=off
    if (options.thinkingLevel?.value === 'off') return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'thinking')
      if (streamPhase.value !== 'summarizing_observations') {
        streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
      }
      // Segments: all thinking deltas merge into one segment (not split by tool_call interruptions)
      const segs = currentSegments.value
      // Reuse an existing thinking segment regardless of status (running or completed)
      let thinkSeg = segs.find((s: MessageSegment) => s.type === 'thinking')
      if (!thinkSeg) {
        thinkSeg = { id: genSegId(), type: 'thinking', status: 'running', thinkingText: '', timestamp: Date.now() }
        // Insert at front — thinking always appears at the top
        segs.unshift(thinkSeg)
        flushSegmentsToMessage()
      }
      // Re-mark as running when new thinking content arrives
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

    // Only create a placeholder here if one does not already exist (the normal path creates it in sendMessage)
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
        // Do not clear currentAssistantId — the 'done' event handles cleanup
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
            // Do not clear currentAssistantId here — the 'done' event does it
            return
          }
        }
      }
      setMessageStatus(currentAssistantId.value, data.status || 'completed')
      // Do not clear currentAssistantId here — the 'done' event does it
    }

    // Segments: mark all running segments as completed and persist to message metadata
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

    // Auto TTS: trigger when message_complete arrives with status=completed
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

      // Update token counts + replace the local temp ID with the backend-persisted ID (critical: enables reconcile by ID)
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        if (data.promptTokens !== undefined) msg.promptTokens = data.promptTokens
        if (data.completionTokens !== undefined) msg.completionTokens = data.completionTokens
        // Replace the local temp ID with the backend-persisted ID so reconcile can match by ID
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

    // Safety cleanup for queue state (no-op if queued_input_started already handled it)
    if (!messageQueue.hasQueued.value) {
      // Queue already empty — phase cannot linger at 'queued'
    } else if (data.status === 'stopped') {
      // User-initiated stop — discard queued message
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
    // Clear queue on error to avoid stale state
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

  // ===== Agent event handlers =====

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
      // Segments: close any running thinking/content segment, then push a new tool_call segment
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
      // Segments: find the matching running tool_call segment and mark it complete
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

  // ===== Browser action events =====

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
        // Dedup: skip updateMessage if the phase hasn't changed, to avoid unnecessary Vue reactivity
        if (metadata.currentPhase === data.phase) return
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, currentPhase: data.phase }
        } as any)
      }
    }
  })

  // ===== Agent delegation events =====
  stream.on('delegation_start', (data) => {
    if (isStaleEvent(data)) return
    streamPhase.value = 'executing_tool'
    if (currentAssistantId.value) {
      const segs = currentSegments.value
      // Close any running thinking/content segment
      const runningSeg = segs.findLast((s: MessageSegment) => s.status === 'running')
      if (runningSeg) runningSeg.status = 'completed'

      if (data.parallel && Array.isArray(data.children)) {
        // Parallel mode: one segment per child. Use childConversationId as the segment ID
        // so downstream events (delegation_child_complete, delegation_progress) can look up
        // the correct row by stable ID instead of agent name — which is not unique when
        // two concurrent tasks go to the same agent.
        for (const child of data.children) {
          segs.push({
            id: child.childConversationId || genSegId(),
            type: 'tool_call',
            status: 'running',
            toolName: `→ ${child.childAgentName || 'Agent'}`,
            toolArgs: child.task || '',
            timestamp: Date.now()
          })
        }
      } else {
        // Single-task mode: same stable ID approach
        segs.push({
          id: data.childConversationId || genSegId(),
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
    if (!currentAssistantId.value) return
    const segs = currentSegments.value

    // Primary lookup: by stable childConversationId (set as the segment ID at creation time).
    // Fallback: any running delegation segment (for older backends that don't send the field).
    const delegSeg = (data.childConversationId
      ? segs.find((s: MessageSegment) => s.id === data.childConversationId)
      : undefined)
      || segs.findLast((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))

    if (!delegSeg) return

    // Normalize data.data: the backend relays the child event's JSON payload.
    // After the P2 fix it arrives as an object; be defensive for older backends.
    const rawPayload = data.data
    const childData: Record<string, any> = rawPayload && typeof rawPayload === 'object'
      ? rawPayload
      : (() => { try { return JSON.parse(String(rawPayload || '{}')) } catch { return {} } })()

    if (data.originalEvent === 'tool_call_started') {
      const toolName = childData?.toolName || ''
      if (toolName) {
        delegSeg.toolArgs = (delegSeg.toolArgs || '') + `\n  → ${toolName}`
      }
    } else if (data.originalEvent === 'tool_call_completed') {
      const toolName = childData?.toolName || ''
      const success = childData?.success !== false
      if (toolName) {
        // Replace the matching "→ toolName" hint with "✓/✗ toolName"
        delegSeg.toolArgs = (delegSeg.toolArgs || '').replace(
          new RegExp(`\\n  → ${toolName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*$`),
          `\n  ${success ? '✓' : '✗'} ${toolName}`)
      }
    } else if (data.originalEvent === 'phase') {
      const phase = childData?.phase || String(rawPayload || '')
      const phaseHints: Record<string, string> = {
        reasoning: '…',
        executing_tool: '→',
        planning: '📋',
        summarizing: '✍',
      }
      const hint = phaseHints[phase]
      if (hint && !delegSeg.toolArgs?.endsWith(hint)) {
        delegSeg.toolArgs = (delegSeg.toolArgs || '').trimEnd() + ' ' + hint
      }
    }
    flushSegmentsToMessage()
  })

  // Per-child completion: fires as soon as each individual child agent finishes,
  // before the overall delegation_end. Marks that child's segment done immediately
  // so the user sees incremental progress rather than a bulk update at the end.
  stream.on('delegation_child_complete', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    const segs = currentSegments.value
    // Prefer childConversationId (stable) over agent name (non-unique)
    const delegSeg = (data.childConversationId
      ? segs.find((s: MessageSegment) => s.id === data.childConversationId)
      : undefined)
      || segs.findLast((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
    if (delegSeg) {
      delegSeg.status = data.success ? 'completed' : 'error'
      delegSeg.toolSuccess = data.success
      if (data.durationMs) {
        const durSec = Math.round(data.durationMs / 1000)
        delegSeg.toolArgs = (delegSeg.toolArgs || '').trimEnd() + ` (${durSec}s)`
      }
      // Write resultPreview for both success and failure so ToolCallSegment can show
      // an expand arrow with the child agent's actual output, not just a green/red dot.
      if (data.resultPreview) {
        delegSeg.toolResult = data.resultPreview
      }
    }
    flushSegmentsToMessage()
  })

  stream.on('delegation_end', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const segs = currentSegments.value
      if (data.parallel) {
        // Parallel mode: use per-child results if available (new backend),
        // fall back to aggregate success flag for older backends.
        if (Array.isArray(data.childResults) && data.childResults.length > 0) {
          for (const cr of data.childResults) {
            // Primary: stable childConversationId lookup. Fallback: agent name substring.
            const seg = (cr.childConversationId
              ? segs.find((s: MessageSegment) => s.id === cr.childConversationId)
              : undefined)
              || segs.findLast((s: MessageSegment) =>
                  s.type === 'tool_call' && s.toolName?.includes(cr.agentName || ''))
            if (seg && seg.status === 'running') {
              // Segment not yet closed by delegation_child_complete (e.g. timed-out child).
              // Write whatever result info is available so ToolCallSegment can show content.
              seg.status = cr.success ? 'completed' : 'error'
              seg.toolSuccess = cr.success
              if (cr.durationMs) {
                const durSec = Math.round(cr.durationMs / 1000)
                seg.toolArgs = (seg.toolArgs || '').trimEnd() + ` (${durSec}s)`
              }
              // Show error reason for failures; for successes leave toolResult empty here
              // (delegation_child_complete already wrote the preview before we get to delegation_end).
              if (cr.error) {
                seg.toolResult = cr.error
              }
            }
          }
        } else {
          // Legacy fallback: mark all remaining running delegation segments with overall status
          segs.filter((s: MessageSegment) =>
            s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
            .forEach((s: MessageSegment) => {
              s.status = data.success ? 'completed' : 'error'
            })
        }
      } else {
        // Single-task mode
        const delegSeg = segs.findLast((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
        if (delegSeg) {
          delegSeg.status = data.success ? 'completed' : 'error'
          delegSeg.toolSuccess = data.success
          if (data.durationMs) {
            delegSeg.toolArgs = (delegSeg.toolArgs || '').trimEnd() + ` (${Math.round(data.durationMs / 1000)}s)`
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

  // ===== Tool approval events (with idempotency dedup) =====

  stream.on('tool_approval_requested', (data) => {
    if (isStaleEvent(data)) return
    // Idempotency: process each pendingId only once
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

  // ===== Heartbeat events =====

  stream.on('heartbeat', (data: HeartbeatData) => {
    heartbeat.value = data
    // Heartbeat arrival means the connection is alive; useStream resets the timeout automatically.
    // Update phase from heartbeat only when the frontend doesn't have a more precise phase yet.
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
    // Use heartbeat queueLength to reconcile local queue state.
    // Only clear when the message has been acknowledged by the backend (status=sending),
    // to avoid discarding a message whose interrupt request is still in flight.
    if (data.queueLength === 0 && messageQueue.hasQueued.value
        && messageQueue.queuedMessage.value?.status === 'sending') {
      messageQueue.clear()
    }
  })

  // ===== Interrupt + Queue events =====

  stream.on('turn_interrupt_requested', () => {
    streamPhase.value = 'interrupting'
  })

  stream.on('turn_interrupted', (data) => {
    if (isStaleEvent(data)) return
    // Current turn has been interrupted. Wait for the backend to resume with the queued message.
    // If the backend will auto-resume, the frontend does nothing extra.
    // Edge case: backend has no queued message but frontend does (should not happen in practice).
    if (data.hasQueuedMessage) {
      streamPhase.value = 'queued'
    }
  })

  stream.on('queued_input_accepted', (data) => {
    // Backend confirmed receipt of the queued message — mark as 'sending' to allow heartbeat cleanup
    messageQueue.markSending()
    streamPhase.value = 'queued'
  })

  stream.on('queued_input_started', (data) => {
    if (isStaleEvent(data)) return
    // Backend has started processing the queued message.
    // 1. Create the user message first (previous turn is now complete so ordering is correct)
    const queued = messageQueue.dequeue()
    const messageContent = data.message || queued?.content || ''
    if (messageContent) {
      const convId = data.conversationId || streamConversationId
      createUserMessage(messageContent, queued?.contentParts, convId)
    }
    // 2. Create the assistant placeholder message
    resetCurrentTurnState()
    const convId2 = data.conversationId || streamConversationId
    const assistantMessage = createAssistantMessage('', convId2)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string
    streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
    phaseInfo.value = null
  })

  // ===== Async task completion events (video generation, image generation, etc.) =====
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

      // Prefer appending to the current assistant message (avoids image appearing above the text reply)
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

      // Fallback: agent already finished — create a standalone message
      addMessage({
        role: 'assistant',
        content: '',
        contentParts: [mediaPart],
        status: 'completed',
        conversationId: streamConversationId,
      })
    }
  })

  // ===== Auto TTS =====
  let ttsAutoModeCache: string | null = null
  let ttsCacheExpiry = 0

  async function triggerAutoTts(conversationId: string, text: string) {
    try {
      // Cache settings for 5 minutes to avoid a request on every message
      const now = Date.now()
      if (!ttsAutoModeCache || now > ttsCacheExpiry) {
        const res: any = await http.get('/system-settings')
        ttsAutoModeCache = res.data?.ttsAutoMode || 'off'
        ttsCacheExpiry = now + 5 * 60 * 1000
      }
      if (ttsAutoModeCache !== 'always') return
      // Kick off backend synthesis; the backend broadcasts tts_ready via SSE when done
      http.post('/tts/synthesize', { conversationId, text }).catch(() => {})
    } catch {
      // Silently ignore TTS errors — it's a best-effort feature
    }
  }

  // ===== Auto TTS: listen for tts_ready events =====
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

  // ===== Send message (supports sending while generating) =====

  const sendMessage = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId, attachments = [], contentParts = [] } = options

    // Approval commands bypass the interrupt logic
    const isApprovalCommand = /^\/(approve|deny)$/i.test(content.trim())

    // ===== Sending while generating: route to interrupt / queue path =====
    if (isGenerating.value && !isApprovalCommand) {
      return await handleInterruptOrQueue(content, options)
    }

    // ===== Normal send path =====
    // Clear the previous stop fallback timer to avoid killing the new connection
    if (stopFallbackTimer) {
      clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
    // Disconnect the old stream when switching conversations to prevent event pollution
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

      // contentParts already includes file entries from buildOutgoingParts — do not re-merge attachments
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
   * Send a new message while one is already generating.
   * - Interruptible phases (thinking/streaming/executing_tool): send an interrupt request.
   * - Non-interruptible phases (awaiting_approval): queue the message.
   */
  const handleInterruptOrQueue = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId } = options

    // Do not create the user message immediately — wait for queued_input_started so the
    // user message appears after the previous turn's reply, preserving correct ordering.
    // Add to the local queue now (saves contentParts for delayed creation).
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
        // Interruptible: backend initiated the interrupt; queued message will auto-resume
        streamPhase.value = 'interrupting'
        messageQueue.markSending()
      } else if (result.data?.queued) {
        // Non-interruptible but queued: will auto-resume when the current step ends
        streamPhase.value = 'queued'
      } else {
        // No active stream — send directly
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
      // Interrupt failed: the backend never received the message, so the heartbeat/queue mechanism
      // cannot be relied upon. Fall back to making the message locally visible + clear the queue
      // to prevent silent message loss.
      const failedQueued = messageQueue.dequeue()
      if (failedQueued) {
        createUserMessage(failedQueued.content, failedQueued.contentParts, conversationId)
      }
      error.value = new Error('Failed to queue message, please resend')
    }
  }

  // Stop generation (user-initiated; does not auto-resume queued messages).
  //
  // Design: do not disconnect the SSE immediately — send a stop signal first and wait for the
  // backend to return a 'done' event. This ensures onStreamEnd fires and message/conversation
  // state is updated correctly.
  // A 3-second fallback timeout guards against 'done' never arriving due to network issues.
  const stopGeneration = async () => {
    // Freeze identifiers and install the fallback timer before any await, so a concurrent
    // resetForNewConversation cannot clear context out from under us.
    const convId = streamConversationId
    const assistantId = currentAssistantId.value

    // Only stop when the frontend is actively involved in the stream (receiving SSE /
    // reconnecting / awaiting approval). As a bystander we must not kill another user's run.
    const activelyStreaming = isGenerating.value
        || streamPhase.value === 'reconnecting'
        || streamPhase.value === 'awaiting_approval'

    if (!activelyStreaming) {
      // Not actually streaming — let the caller go straight to resetForNewConversation
      return
    }

    // Cancel queued message first
    messageQueue.clear()

    // Mark as stopped immediately so the UI gives instant feedback
    streamPhase.value = 'stopped'
    phaseInfo.value = null

    // Install fallback timer before any await so it is not missed by a concurrent resetForNewConversation
    if (stopFallbackTimer) clearTimeout(stopFallbackTimer)
    stopFallbackTimer = setTimeout(() => {
      stopFallbackTimer = null
      console.warn('[useChat] Stop fallback: done event not received within 3s, force cleanup')
      // Only disconnect if the stream still belongs to the old conversation — avoids killing a new session's stream
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

    // Cancel the fallback timer when the done/error event arrives
    const unsubscribe = stream.on('done', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribe()
    })
    const unsubscribeError = stream.on('error', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribeError()
    })

    // Send the backend stop request (fire-and-forget, does not block resetForNewConversation)
    if (convId) {
      fetchWithAuth(`${baseUrl}/api/v1/chat/${convId}/stop`, {
        method: 'POST',
      }).catch(e => {
        console.warn('[useChat] Stop API failed:', e)
      })
    }
  }

  // Cancel the queued message
  const cancelQueued = () => {
    messageQueue.cancel()
    // Notify backend (fire-and-forget)
    if (streamConversationId) {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' }
      if (token) headers.Authorization = `Bearer ${token}`
      // No dedicated cancel-queue API — the stop semantic covers this case
    }
    if (streamPhase.value === 'queued') {
      streamPhase.value = isGenerating.value ? 'streaming' : 'idle'
    }
  }

  // Reconnect to a stream that is already running on the backend
  const reconnectStream = async (conversationId: string) => {
    if (isGenerating.value) return

    // Clear any leftover stop fallback timer
    if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
    streamPhase.value = 'reconnecting'
    streamConversationId = conversationId
    error.value = null
    errorFired = false
    phaseInfo.value = null

    resetCurrentTurnState()

    // Remove trailing empty assistant messages left over from a killed run or a stale placeholder.
    // Prevents "two bubbles" appearing when the reconnect creates a new streaming placeholder.
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
      // Reconnect failed — clean up the placeholder message
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        // Remove the placeholder if it has no content
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

  // Regenerate a message
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

  /** Fully reset stream context — call when switching or creating a conversation to prevent state pollution */
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
