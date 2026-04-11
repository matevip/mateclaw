<template>
  <div
    class="message-wrapper"
    :class="[role, { 'is-last': isLast }]"
    :data-role="role"
    :data-status="status"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
  >
    <!-- 头像 -->
    <div class="msg-avatar" :class="`${role}-avatar`">
      <slot name="avatar">
        <img v-if="role === 'assistant'" src="/logo/mateclaw_logo_s.png" alt="" class="avatar-logo" />
        <span v-else>{{ avatarIcon }}</span>
      </slot>
    </div>

    <!-- 消息体 -->
    <div class="msg-body" :class="`${role}-body`">
      <div class="msg-bubble" :class="`${role}-bubble`">
        <!-- ===== 分段式渲染模式（Claude Code 风格）===== -->
        <template v-if="useSegmentedView">
          <div class="segments-view">
            <template v-for="(seg, index) in segments" :key="seg.id">
              <ThinkingSegment v-if="seg.type === 'thinking'" :segment="seg" />
              <ToolCallSegment v-if="seg.type === 'tool_call'" :segment="seg" />
              <ContentSegment v-if="seg.type === 'content'" :segment="seg" :show-cursor="showCursor && seg.status === 'running'" />
            </template>
          </div>
        </template>

        <!-- ===== 传统合并渲染模式（降级兼容）===== -->
        <template v-else>

        <!-- 思考面板 -->
        <div v-if="showThinkingPanel" class="thinking-section">
          <button class="thinking-toggle" type="button" @click="toggleThinking">
            <span class="thinking-toggle__indicator" :class="{ active: isGenerating && !hasContent }">
              <el-icon><Opportunity /></el-icon>
            </span>
            <span class="thinking-toggle__label">{{ $t('chat.thinking') }}</span>
            <span class="thinking-toggle__duration" v-if="thinkingDuration">{{ thinkingDuration }}</span>
            <span class="thinking-toggle__arrow" :class="{ expanded: localThinkingExpanded }">
              <el-icon><ArrowDown /></el-icon>
            </span>
          </button>

          <!-- 思考内容（带折叠动画） -->
          <Transition name="thinking-slide">
            <div
              v-if="localThinkingExpanded"
              class="thinking-content markdown-body"
              v-html="renderedThinkingContent"
            ></div>
          </Transition>
        </div>

        <!-- 执行过程面板（折叠式） -->
        <div v-if="showExecutionPanel" class="execution-section">
          <button class="execution-toggle" type="button" @click="executionExpanded = !executionExpanded">
            <span class="execution-toggle__indicator" :class="{ active: isGenerating }">
              <el-icon><Tools /></el-icon>
            </span>
            <span class="execution-toggle__label">{{ executionPhaseLabel }}</span>
            <span class="execution-toggle__count" v-if="toolCallsMeta.length">{{ toolCallsMeta.length }} calls</span>
            <span class="execution-toggle__arrow" :class="{ expanded: executionExpanded }">
              <el-icon><ArrowDown /></el-icon>
            </span>
          </button>

          <Transition name="thinking-slide">
            <div v-if="executionExpanded" class="execution-content">
              <!-- Plan 步骤进度 -->
              <div v-if="planMeta" class="plan-steps">
                <div class="plan-steps__title">Plan ({{ planMeta.steps.length }} steps)</div>
                <div
                  v-for="(step, i) in planMeta.steps"
                  :key="i"
                  class="plan-step"
                  :class="{
                    'plan-step--done': planMeta.stepResults?.[i]?.status === 'completed',
                    'plan-step--active': i === planMeta.currentStep && isGenerating
                  }"
                >
                  <span class="plan-step__index">{{ i + 1 }}</span>
                  <span class="plan-step__text">{{ step }}</span>
                </div>
              </div>

              <!-- 工具调用列表 -->
              <div v-if="toolCallsMeta.length" class="tool-calls">
                <div
                  v-for="(tc, i) in toolCallsMeta"
                  :key="i"
                  class="tool-call"
                  :class="{ 'tool-call--running': tc.status === 'running', 'tool-call--awaiting': tc.status === 'awaiting_approval', 'tool-call--error': tc.status === 'completed' && tc.success === false }"
                >
                  <span class="tool-call__status">
                    <el-icon v-if="tc.status === 'running'" class="spin"><Loading /></el-icon>
                    <el-icon v-else-if="tc.status === 'awaiting_approval'" style="color: #f59e0b;"><WarningFilled /></el-icon>
                    <el-icon v-else-if="tc.success !== false" style="color: #10b981;"><Select /></el-icon>
                    <el-icon v-else style="color: #ef4444;"><CloseBold /></el-icon>
                  </span>
                  <span class="tool-call__name">{{ tc.name }}</span>
                  <span class="tool-call__args" v-if="tc.arguments">{{ truncateArgs(tc.arguments) }}</span>
                </div>
              </div>

              <div v-if="!toolCallsMeta.length && !planMeta" class="execution-empty">
                {{ currentPhaseName }}...
              </div>
            </div>
          </Transition>
        </div>

        <!-- 浏览器执行时间线 -->
        <BrowserTimeline v-if="browserActionsMeta.length" :actions="browserActionsMeta" />

        <!-- 工具审批面板 -->
        <div v-if="pendingApproval" class="approval-section" :class="approvalSeverityClass">
          <div class="approval-header">
            <el-icon><WarningFilled /></el-icon>
            <span class="approval-title">{{ $t('chat.approvalRequired') || 'Approval Required' }}</span>
            <span v-if="pendingApproval.maxSeverity" class="approval-severity-badge" :class="'severity-' + pendingApproval.maxSeverity?.toLowerCase()">
              {{ pendingApproval.maxSeverity }}
            </span>
          </div>
          <div class="approval-detail">
            <div class="approval-tool"><strong>Tool:</strong> {{ pendingApproval.toolName }}</div>
            <div v-if="pendingApproval.summary" class="approval-summary">{{ pendingApproval.summary }}</div>
            <div class="approval-reason"><strong>Reason:</strong> {{ pendingApproval.reason }}</div>
            <div class="approval-args" v-if="pendingApproval.arguments">
              <strong>Args:</strong> <code>{{ truncateArgs(pendingApproval.arguments) }}</code>
            </div>
          </div>
          <!-- Findings List -->
          <div v-if="pendingApproval.findings?.length" class="approval-findings">
            <div v-for="(finding, idx) in pendingApproval.findings" :key="idx" class="approval-finding-item">
              <span class="finding-severity-dot" :class="'dot-' + finding.severity?.toLowerCase()"></span>
              <span class="finding-category-tag">{{ finding.category }}</span>
              <span class="finding-text">{{ finding.title }}</span>
              <span v-if="finding.remediation" class="finding-fix">{{ finding.remediation }}</span>
            </div>
          </div>
          <div v-if="pendingApproval.status === 'pending_approval'" class="approval-waiting">
            <el-icon class="approval-waiting__spin"><Loading /></el-icon>
            <span>{{ $t('chat.approvalWaiting') }}</span>
          </div>
          <div v-else-if="pendingApproval.status === 'approved'" class="approval-resolved approval-resolved--approved">
            {{ $t('chat.approved') }}
          </div>
          <div v-else class="approval-resolved approval-resolved--denied">
            {{ $t('chat.denied') }}
          </div>
        </div>

        <!-- 主要内容 -->
        <div
          v-if="displayContent"
          class="msg-content"
          :class="{ 'with-cursor': showCursor }"
        >
          <div class="markdown-body" v-html="renderedContent"></div>
          <TypingCursor v-if="showCursor" :typing="isGenerating" />
        </div>

        <!-- 加载指示器 -->
        <div v-if="showLoadingIndicator" class="typing-indicator">
          <span></span><span></span><span></span>
        </div>

        <!-- 停止指示器 -->
        <div v-if="status === 'stopped' || status === 'interrupted'" class="stopped-indicator">
          <el-icon><CloseBold /></el-icon>
          <span>{{ status === 'interrupted' ? ($t('chat.interrupted') || '已中断') : $t('chat.stopped') }}</span>
        </div>

        <!-- 错误卡片 -->
        <div v-if="status === 'failed'" class="error-card">
          <div class="error-card__header">
            <el-icon class="error-card__icon"><WarningFilled /></el-icon>
            <span class="error-card__title">{{ errorTitle }}</span>
          </div>
          <p class="error-card__description">{{ errorDescription }}</p>
          <p class="error-card__action">{{ errorAction }}</p>
          <div class="error-card__footer">
            <span v-if="errorCode" class="error-card__code">{{ errorCode }}</span>
            <button v-if="errorRetryable" class="error-card__retry" type="button" @click="$emit('regenerate')">
              <el-icon><RefreshRight /></el-icon>
              {{ $t('chat.retry') }}
            </button>
          </div>
        </div>

        </template><!-- /传统合并渲染模式 -->

        <!-- 附件列表 -->
        <div v-if="attachments?.length" class="message-attachments">
          <div
            v-for="attachment in imageAttachments"
            :key="attachment.storedName"
            class="message-attachment-image"
          >
            <img
              :src="getDisplayUrl(attachment)"
              :alt="attachment.name"
              loading="lazy"
              @click="openImage(getDisplayUrl(attachment))"
            />
            <span class="message-attachment-image__name">{{ attachment.name }}</span>
          </div>
          <div
            v-for="attachment in videoAttachments"
            :key="attachment.storedName"
            class="message-attachment-video"
          >
            <video
              :src="getDisplayUrl(attachment)"
              controls
              preload="metadata"
              playsinline
            />
            <span class="message-attachment-video__name">{{ attachment.name }}</span>
          </div>
          <button
            v-for="attachment in fileAttachments"
            :key="attachment.storedName"
            class="message-attachment"
            type="button"
            @click="downloadFile(attachment)"
          >
            <el-icon class="message-attachment__icon"><Document /></el-icon>
            <span class="message-attachment__name">{{ attachment.name }}</span>
            <span class="message-attachment__meta">{{ formatFileSize(attachment.size) }}</span>
          </button>
        </div>
      </div>

      <!-- 消息操作栏：始终占位，hover 时显示 -->
        <div
          class="msg-actions"
          :class="{
            'msg-actions--right': role === 'user',
            'msg-actions--visible': showActions
          }"
        >
          <!-- 复制 -->
          <button
            class="action-btn"
            :class="{ copied: copyState === 'copied' }"
            type="button"
            :title="copyState === 'copied' ? $t('chat.copied') : $t('chat.copy')"
            @click="copyMessage"
          >
            <el-icon v-if="copyState !== 'copied'"><CopyDocument /></el-icon>
            <el-icon v-else><Select /></el-icon>
          </button>
          <!-- 朗读 TTS（仅 assistant） -->
          <button
            v-if="role === 'assistant' && !isGenerating"
            class="action-btn"
            :class="{ 'tts-playing': ttsState === 'playing' }"
            type="button"
            :title="ttsState === 'playing' ? $t('chat.ttsStop') : $t('chat.ttsPlay')"
            :disabled="ttsState === 'loading'"
            @click="handleTts"
          >
            <el-icon v-if="ttsState === 'loading'" class="tts-loading-icon"><Loading /></el-icon>
            <el-icon v-else-if="ttsState === 'playing'"><VideoPause /></el-icon>
            <el-icon v-else><Microphone /></el-icon>
          </button>
          <!-- 重新生成（仅 assistant） -->
          <button
            v-if="role === 'assistant' && !isGenerating"
            class="action-btn"
            type="button"
            :title="$t('chat.regenerate')"
            @click="$emit('regenerate')"
          >
            <el-icon><RefreshRight /></el-icon>
          </button>
          <!-- 时间戳（inline） -->
          <span class="action-time">{{ formattedTime }}</span>
        </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ArrowDown,
  CloseBold,
  CopyDocument,
  Document,
  Loading,
  Microphone,
  Opportunity,
  RefreshRight,
  Select,
  Tools,
  VideoPause,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { useAuthenticatedAttachment } from '@/composables/useAuthenticatedAttachment'
import { http } from '@/api'
import TypingCursor from './TypingCursor.vue'
import BrowserTimeline from './BrowserTimeline.vue'
import ToolCallSegment from './ToolCallSegment.vue'
import ThinkingSegment from './ThinkingSegment.vue'
import ContentSegment from './ContentSegment.vue'
import type { BrowserAction } from './BrowserTimeline.vue'
import type { Message, MessageSegment, ChatAttachment, ToolCallMeta, PlanMeta } from '@/types'
import type { ChatErrorInfo } from '@/types/chatError'

const { renderMarkdown } = useMarkdownRenderer()
const { t } = useI18n()
const { blobUrls, loadAllImages, loadAllVideos, downloadFile, openImage, getDisplayUrl, revokeAll } = useAuthenticatedAttachment()

interface Props {
  message: Message
  isLast?: boolean
  assistantIcon?: string
  userIcon?: string
  showCursor?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isLast: false,
  assistantIcon: '🤖',
  userIcon: 'U',
  showCursor: false,
})

const emit = defineEmits<{
  regenerate: []
  'toggle-thinking': [expanded: boolean]
  approve: [pendingId: string]
  deny: [pendingId: string]
}>()

// --- 基础计算 ---
const role = computed(() => props.message.role)
const status = computed(() => props.message.status)
const isGenerating = computed(() => status.value === 'generating' || status.value === 'awaiting_approval')
const hovered = ref(false)

const avatarIcon = computed(() => {
  return role.value === 'user' ? props.userIcon : props.assistantIcon
})

// --- 错误卡片 ---
const errorInfo = computed<ChatErrorInfo | undefined>(() => props.message.errorInfo)

const errorTitle = computed(() => {
  if (!errorInfo.value) return t('chat.failed')
  return t(`chat.error.${errorInfo.value.category}.title`)
})

const errorDescription = computed(() => {
  if (!errorInfo.value) return ''
  return t(`chat.error.${errorInfo.value.category}.description`)
})

const errorAction = computed(() => {
  if (!errorInfo.value) return ''
  return t(`chat.error.${errorInfo.value.category}.action`)
})

const errorCode = computed(() => {
  if (!errorInfo.value) return ''
  const parts: string[] = []
  if (errorInfo.value.httpStatus) parts.push(`HTTP ${errorInfo.value.httpStatus}`)
  if (errorInfo.value.requestId) parts.push(`ID: ${errorInfo.value.requestId}`)
  return parts.join(' | ')
})

const errorRetryable = computed(() => errorInfo.value?.retryable ?? true)

// --- Thinking 面板 ---
const localThinkingExpanded = ref(props.message.thinkingExpanded || false)

watch(() => props.message.thinkingExpanded, (val) => {
  if (val !== undefined) localThinkingExpanded.value = val
})

const thinkingContent = computed(() => {
  const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
  return thinkingPart?.text || ''
})

const hasContent = computed(() => {
  const textPart = props.message.contentParts?.find(p => p.type === 'text')
  return !!(textPart?.text || props.message.content)
})

const showThinkingPanel = computed(() => !!thinkingContent.value)

// 思考耗时（生成结束后显示）
const thinkingDuration = computed(() => {
  if (isGenerating.value) return ''
  const len = thinkingContent.value.length
  if (len < 50) return ''
  // 粗略估计：每 100 字符约 1 秒
  const sec = Math.max(1, Math.round(len / 100))
  return sec >= 60 ? `${Math.floor(sec / 60)}m ${sec % 60}s` : `${sec}s`
})

watch(
  [thinkingContent, hasContent, isGenerating],
  ([thinking, content, generating]) => {
    if (!generating) return
    if (thinking && !content) {
      localThinkingExpanded.value = true
    } else if (content) {
      localThinkingExpanded.value = false
    }
  },
  { immediate: true }
)

const toggleThinking = () => {
  localThinkingExpanded.value = !localThinkingExpanded.value
  emit('toggle-thinking', localThinkingExpanded.value)
}

const renderedThinkingContent = computed(() => {
  if (!thinkingContent.value) return ''
  return renderMarkdown(thinkingContent.value)
})

// --- 主内容 ---
const isApprovalPlaceholder = (text: string) => {
  return text.includes('[APPROVAL_PENDING]')
    || text.includes('[⏳ 等待审批]')
    || text.includes('[等待审批]')
}

const displayContent = computed(() => {
  const textPart = props.message.contentParts?.find(p => p.type === 'text')
  const text = textPart?.text || props.message.content || ''
  if (isGenerating.value && !text) return ''
  // 过滤审批占位文本 — 这些消息由审批面板展示，不应作为正文显示
  if (text && isApprovalPlaceholder(text)) return ''
  // 有错误卡片时隐藏 [错误] 原始文本，避免重复展示
  if (status.value === 'failed' && errorInfo.value && text.startsWith('[错误]')) return ''
  return text
})

const renderedContent = computed(() => {
  if (!displayContent.value) return ''
  return renderMarkdown(displayContent.value)
})

const showLoadingIndicator = computed(() => {
  return isGenerating.value && !displayContent.value
})


// --- 操作栏 ---
const showActions = computed(() => {
  if (isGenerating.value) return false
  return hovered.value && (displayContent.value || status.value === 'stopped' || status.value === 'interrupted' || status.value === 'failed')
})

const copyState = ref<'idle' | 'copied'>('idle')
let copyTimer: ReturnType<typeof setTimeout> | null = null

function copyMessage() {
  const text = displayContent.value || props.message.content || ''
  if (!text) return
  navigator.clipboard.writeText(text).then(() => {
    copyState.value = 'copied'
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copyState.value = 'idle' }, 2000)
  }).catch(() => {})
}

// --- TTS 朗读 ---
const ttsState = ref<'idle' | 'loading' | 'playing'>('idle')
let ttsAudio: HTMLAudioElement | null = null

async function handleTts() {
  if (ttsState.value === 'playing') {
    // 停止播放
    ttsAudio?.pause()
    ttsAudio = null
    ttsState.value = 'idle'
    return
  }

  const text = displayContent.value || props.message.content || ''
  if (!text) return

  const conversationId = props.message.conversationId
  if (!conversationId) return

  ttsState.value = 'loading'
  try {
    const res: any = await http.post('/tts/synthesize', {
      conversationId,
      text,
    })
    if (res.data?.success && res.data?.audioUrl) {
      // 通过认证 fetch 获取音频 blob
      const audioRes = await fetch(res.data.audioUrl, {
        headers: { Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
      })
      const blob = await audioRes.blob()
      const blobUrl = URL.createObjectURL(blob)
      ttsAudio = new Audio(blobUrl)
      ttsAudio.onended = () => {
        ttsState.value = 'idle'
        URL.revokeObjectURL(blobUrl)
        ttsAudio = null
      }
      ttsAudio.onerror = () => {
        ttsState.value = 'idle'
        URL.revokeObjectURL(blobUrl)
        ttsAudio = null
      }
      ttsState.value = 'playing'
      await ttsAudio.play()
    } else {
      ttsState.value = 'idle'
    }
  } catch {
    ttsState.value = 'idle'
  }
}

onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
  if (ttsAudio) { ttsAudio.pause(); ttsAudio = null }
  revokeAll()
})

// --- 附件 ---
const attachments = computed(() => props.message.attachments || [])
const imageAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('image/')))
const videoAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('video/')))
const fileAttachments = computed(() => attachments.value.filter(a =>
  !a.contentType?.startsWith('image/') && !a.contentType?.startsWith('video/')
))

// 增量加载图片/视频附件的鉴权 blob URL（watch 覆盖首次 + 后续变化）
watch(imageAttachments, (atts) => {
  if (atts.length > 0) loadAllImages(atts)
}, { immediate: true })
watch(videoAttachments, (atts) => {
  if (atts.length > 0) loadAllVideos(atts)
}, { immediate: true })

// --- 时间 ---
const formattedTime = computed(() => {
  if (!props.message.createTime) return ''
  return new Date(props.message.createTime).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })
})

const formatFileSize = (size: number) => {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

// openAttachment 已由 useAuthenticatedAttachment 的 openImage / downloadFile 替代

// --- 执行过程面板 ---
const executionExpanded = ref(false)

// --- 分段式渲染（Claude Code 风格） ---
const parsedMetadata = computed(() => {
  const raw = props.message.metadata
  if (!raw) return {} as any
  if (typeof raw === 'string') {
    try {
      let parsed = JSON.parse(raw)
      // 处理双重 JSON 编码（DB 中 metadata 是字符串，Jackson 序列化时可能再次转义）
      if (typeof parsed === 'string') {
        try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
      }
      return parsed
    } catch { return {} }
  }
  return raw
})

const segments = computed<MessageSegment[]>(() => {
  if (props.message.role !== 'assistant') return []
  const meta = parsedMetadata.value

  // 优先：使用 metadata.segments（流式时由前端写入，历史时由后端持久化）
  if (meta?.segments && Array.isArray(meta.segments) && meta.segments.length > 0
      && typeof meta.segments[0] === 'object' && meta.segments[0]?.type) {
    const segs = [...meta.segments] as MessageSegment[]

    // 补充：如果后端 segments 没有 thinking 但 contentParts 有（非原生 thinking 模型）
    const hasThinking = segs.some(s => s.type === 'thinking')
    if (!hasThinking) {
      const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
      if (thinkingPart?.text) {
        segs.unshift({ id: 'th-fb', type: 'thinking', status: 'completed', thinkingText: thinkingPart.text })
      }
    }

    // 去重：相同 toolName + toolArgs 的 tool_call segment 只保留第一个
    const seenToolCalls = new Set<string>()
    const deduped = segs.filter(seg => {
      if (seg.type !== 'tool_call') return true
      const key = `${seg.toolName}::${seg.toolArgs || ''}`
      if (seenToolCalls.has(key)) return false
      seenToolCalls.add(key)
      return true
    })
    segs.length = 0
    segs.push(...deduped)

    // 修复历史消息顺序：如果 thinking 被落在 content 后面，提到首个 content 前
    // 只处理单个 thinking 段的常见场景，避免破坏复杂交错时间线
    const thinkingIndices = segs
      .map((seg, index) => seg.type === 'thinking' ? index : -1)
      .filter(index => index >= 0)
    const firstNonThinkingIdx = segs.findIndex((seg: MessageSegment) => seg.type !== 'thinking')
    if (thinkingIndices.length === 1 && firstNonThinkingIdx >= 0 && thinkingIndices[0] > firstNonThinkingIdx) {
      const [thinkingSeg] = segs.splice(thinkingIndices[0], 1)
      segs.splice(0, 0, thinkingSeg)
    }

    return segs
  }

  // Fallback：从 toolCalls + contentParts 做 best-effort 重建（旧消息兼容）
  // 注意：这会丢失事件交错顺序（所有 thinking 在前，所有 tool calls 在中，content 在后）
  const segs: MessageSegment[] = []
  const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
  if (thinkingPart?.text) {
    segs.push({ id: 'th-0', type: 'thinking', status: 'completed', thinkingText: thinkingPart.text })
  }
  const toolCalls = meta?.toolCalls || []
  toolCalls.forEach((tc: ToolCallMeta, i: number) => {
    segs.push({
      id: `tc-${i}`, type: 'tool_call', status: 'completed',
      toolName: tc.name, toolArgs: tc.arguments,
      toolResult: tc.result, toolSuccess: tc.success,
    })
  })
  if (props.message.content) {
    segs.push({ id: 'ct-0', type: 'content', status: 'completed', text: props.message.content })
  }
  return segs
})

/** 是否使用分段模式渲染（有 segments 数据且包含多个分段） */
const useSegmentedView = computed(() => segments.value.length > 1)

const toolCallsMeta = computed<ToolCallMeta[]>(() => {
  return parsedMetadata.value?.toolCalls || []
})

const browserActionsMeta = computed<BrowserAction[]>(() => {
  return parsedMetadata.value?.browserActions || []
})

const planMeta = computed<PlanMeta | undefined>(() => {
  return parsedMetadata.value?.plan
})

const currentPhaseName = computed(() => {
  const phase = parsedMetadata.value?.currentPhase
  switch (phase) {
    case 'reasoning': return 'Reasoning'
    case 'action': return 'Executing tools'
    case 'planning': return 'Planning'
    case 'summarizing': return 'Summarizing'
    case 'awaiting_approval': return 'Waiting for approval'
    case 'executing': return 'Executing'
    case 'replaying': return 'Resuming execution'
    case 'resumed_execution': return 'Resumed'
    default: return 'Processing'
  }
})

const truncateArgs = (args: string) => {
  if (!args) return ''
  const clean = args.replace(/\s+/g, ' ').trim()
  return clean.length > 60 ? clean.slice(0, 60) + '...' : clean
}

// --- 审批面板 ---
const pendingApproval = computed(() => {
  const approval = parsedMetadata.value?.pendingApproval
  if (!approval || approval.status === 'expired') return null
  return approval
})

const approvalSeverityClass = computed(() => {
  const sev = pendingApproval.value?.maxSeverity?.toLowerCase()
  if (!sev) return ''
  return 'approval-severity-' + sev
})

const executionPhaseLabel = computed(() => {
  if (pendingApproval.value?.status === 'pending_approval') {
    return 'Waiting for approval'
  }
  if (pendingApproval.value?.status === 'approved') {
    return 'Approved - Resuming'
  }
  if (planMeta.value) {
    const done = planMeta.value.stepResults?.filter(r => r?.status === 'completed').length || 0
    return `Plan-Execute (${done}/${planMeta.value.steps.length})`
  }
  if (toolCallsMeta.value.length) {
    const done = toolCallsMeta.value.filter(t => t.status === 'completed').length
    return `Tool Calls (${done}/${toolCallsMeta.value.length})`
  }
  return currentPhaseName.value
})

const showExecutionPanel = computed(() => {
  if (role.value !== 'assistant') return false
  // 审批卡片有独立的渲染区域，但 execution panel 也应该在审批阶段展示上下文
  return toolCallsMeta.value.length > 0 || !!planMeta.value
    || (isGenerating.value && parsedMetadata.value?.currentPhase)
    || !!pendingApproval.value
})

// 自动展开执行面板（工具调用时或审批时）
watch(toolCallsMeta, (calls) => {
  if (calls.length > 0 && isGenerating.value) {
    executionExpanded.value = true
  }
}, { deep: true })

watch(pendingApproval, (approval) => {
  if (approval?.status === 'pending_approval') {
    executionExpanded.value = true
  }
})

// 生成结束后自动折叠（但审批等待中不折叠）
watch(isGenerating, (generating) => {
  if (!generating && executionExpanded.value && !pendingApproval.value) {
    executionExpanded.value = false
  }
})
</script>

<style scoped>
/* 分段式渲染容器 */
.segments-view {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.message-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  max-width: 920px;
  margin-bottom: 6px;
}

.message-wrapper.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-wrapper.assistant {
  margin-right: auto;
}

/* 头像 */
.msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
  margin-top: 2px;
}

.assistant-avatar {
  background: transparent;
}

.avatar-logo {
  width: 30px;
  height: 30px;
  object-fit: contain;
  border-radius: 50%;
}

.user-avatar {
  background: linear-gradient(135deg, var(--mc-success), #3D7A3D);
  color: white;
  font-size: 14px;
  font-weight: 600;
}

/* 消息体 */
.msg-body {
  max-width: calc(100% - 44px);
  min-width: 0;
}

.user-body {
  align-items: flex-end;
  display: flex;
  flex-direction: column;
}

/* 气泡 */
.msg-bubble {
  padding: 14px 16px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.7;
  word-break: break-word;
}

.assistant-bubble {
  background: none;
  border: none;
  border-radius: 0;
  padding: 4px 0;
  color: var(--mc-assistant-bubble-color, #1e293b);
}

.user-bubble {
  background: var(--mc-user-bubble-bg, #D97757);
  color: var(--mc-user-bubble-color, white);
  border-radius: 18px 4px 18px 18px;
}

/* ==================== Thinking 面板 ==================== */
.thinking-section {
  margin-bottom: 12px;
}

.thinking-toggle {
  width: 100%;
  border: 0;
  background: var(--mc-thinking-bg, rgba(217, 119, 87, 0.06));
  border-radius: 10px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-thinking-text, #475569);
  cursor: pointer;
  transition: background 0.15s ease;
  font-family: inherit;
}

.thinking-toggle:hover {
  background: var(--mc-thinking-hover, rgba(217, 119, 87, 0.1));
}

.thinking-toggle__indicator {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--mc-thinking-icon-bg, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary, #D97757);
  flex-shrink: 0;
  transition: all 0.3s ease;
}

.thinking-toggle__indicator.active {
  animation: think-pulse 1.5s ease-in-out infinite;
}

@keyframes think-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(0.92); }
}

.thinking-toggle__label {
  font-size: 13px;
  font-weight: 600;
  flex: 1;
  text-align: left;
}

.thinking-toggle__duration {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  font-weight: 400;
}

.thinking-toggle__arrow {
  display: flex;
  align-items: center;
  color: var(--mc-text-tertiary, #94a3b8);
  transition: transform 0.2s ease;
}

.thinking-toggle__arrow.expanded {
  transform: rotate(180deg);
}

/* Thinking 内容折叠动画 */
.thinking-slide-enter-active,
.thinking-slide-leave-active {
  transition: all 0.25s ease;
  overflow: hidden;
}

.thinking-slide-enter-from,
.thinking-slide-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}

.thinking-slide-enter-to,
.thinking-slide-leave-from {
  opacity: 1;
  max-height: 2000px;
}

.thinking-content {
  padding: 10px 14px 6px;
  color: var(--mc-text-secondary, #64748b);
  font-size: 13px;
  line-height: 1.65;
  border-left: 2px solid var(--mc-thinking-border, rgba(217, 119, 87, 0.2));
  margin-left: 12px;
  margin-top: 8px;
}

.thinking-content :deep(*) {
  max-width: 100%;
}

/* ==================== 执行过程面板 ==================== */
.execution-section {
  margin-bottom: 12px;
}

.execution-toggle {
  width: 100%;
  border: 0;
  background: var(--mc-thinking-bg, rgba(217, 119, 87, 0.06));
  border-radius: 10px;
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-thinking-text, #475569);
  cursor: pointer;
  transition: background 0.15s ease;
  font-family: inherit;
  font-size: 13px;
}

.execution-toggle:hover {
  background: var(--mc-thinking-hover, rgba(217, 119, 87, 0.1));
}

.execution-toggle__indicator {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--mc-thinking-icon-bg, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary, #D97757);
  flex-shrink: 0;
}

.execution-toggle__indicator.active {
  animation: think-pulse 1.5s ease-in-out infinite;
}

.execution-toggle__label {
  font-weight: 600;
  flex: 1;
  text-align: left;
}

.execution-toggle__count {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.execution-toggle__arrow {
  display: flex;
  align-items: center;
  color: var(--mc-text-tertiary, #94a3b8);
  transition: transform 0.2s ease;
}

.execution-toggle__arrow.expanded {
  transform: rotate(180deg);
}

.execution-content {
  padding: 10px 14px 6px;
  margin-top: 8px;
  border-left: 2px solid rgba(217, 119, 87, 0.2);
  margin-left: 12px;
}

.tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tool-call {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  background: var(--mc-bg-elevated, #f8fafc);
}

.tool-call--running {
  background: rgba(217, 119, 87, 0.06);
}

.tool-call--awaiting {
  background: rgba(245, 158, 11, 0.06);
}

.tool-call--error {
  background: rgba(239, 68, 68, 0.06);
}

.tool-call__status {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.tool-call__name {
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

.tool-call__args {
  color: var(--mc-text-tertiary, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.plan-steps {
  margin-bottom: 10px;
}

.plan-steps__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary, #64748b);
  margin-bottom: 6px;
}

.plan-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.plan-step--done {
  color: #10b981;
}

.plan-step--active {
  color: var(--mc-primary, #D97757);
  font-weight: 600;
}

.plan-step__index {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--mc-bg-elevated, #f8fafc);
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.plan-step--done .plan-step__index {
  background: rgba(16, 185, 129, 0.1);
}

.plan-step--active .plan-step__index {
  background: rgba(217, 119, 87, 0.1);
}

.plan-step__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.execution-empty {
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
  padding: 4px 0;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ==================== 审批面板 ==================== */
.approval-section {
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid #f59e0b;
  border-left: 3px solid #f59e0b;
  border-radius: 10px;
  background: rgba(245, 158, 11, 0.06);
}

.approval-section.approval-severity-critical {
  border-color: #ef4444;
  border-left-color: #ef4444;
  background: rgba(239, 68, 68, 0.06);
}

.approval-section.approval-severity-high {
  border-color: #f97316;
  border-left-color: #f97316;
  background: rgba(249, 115, 22, 0.06);
}

.approval-section.approval-severity-medium {
  border-color: #f59e0b;
  border-left-color: #f59e0b;
  background: rgba(245, 158, 11, 0.06);
}

.approval-section.approval-severity-low {
  border-color: #3b82f6;
  border-left-color: #3b82f6;
  background: rgba(59, 130, 246, 0.06);
}

.approval-section.approval-severity-info {
  border-color: #6b7280;
  border-left-color: #6b7280;
  background: rgba(107, 114, 128, 0.06);
}

.approval-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #f59e0b;
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
}

.approval-title {
  font-size: 13px;
}

.approval-detail {
  font-size: 12px;
  color: var(--mc-text-secondary, #64748b);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.approval-detail code {
  font-size: 11px;
  background: var(--mc-inline-code-bg, #f1f5f9);
  padding: 1px 4px;
  border-radius: 4px;
}

.approval-waiting {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.approval-waiting__spin {
  animation: spin 1s linear infinite;
  flex-shrink: 0;
}

.approval-resolved {
  margin-top: 10px;
  font-size: 13px;
  font-weight: 600;
}

.approval-resolved--approved {
  color: #10b981;
}

.approval-severity-badge {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  margin-left: auto;
}

.approval-severity-badge.severity-critical { background: rgba(239, 68, 68, 0.15); color: #ef4444; }
.approval-severity-badge.severity-high { background: rgba(249, 115, 22, 0.15); color: #f97316; }
.approval-severity-badge.severity-medium { background: rgba(245, 158, 11, 0.15); color: #f59e0b; }
.approval-severity-badge.severity-low { background: rgba(59, 130, 246, 0.15); color: #3b82f6; }
.approval-severity-badge.severity-info { background: rgba(107, 114, 128, 0.15); color: #6b7280; }

.approval-summary {
  font-size: 12px;
  color: var(--mc-text-primary, #334155);
  font-weight: 500;
  padding: 4px 0;
}

.approval-findings {
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.03));
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.approval-finding-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}

.finding-severity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.finding-severity-dot.dot-critical { background: #ef4444; }
.finding-severity-dot.dot-high { background: #f97316; }
.finding-severity-dot.dot-medium { background: #f59e0b; }
.finding-severity-dot.dot-low { background: #3b82f6; }
.finding-severity-dot.dot-info { background: #6b7280; }

.finding-category-tag {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 10px;
  color: var(--mc-text-tertiary, #94a3b8);
  background: var(--mc-inline-code-bg, #f1f5f9);
  padding: 1px 5px;
  border-radius: 3px;
}

.finding-text {
  color: var(--mc-text-secondary, #64748b);
}

.finding-fix {
  color: var(--mc-text-tertiary, #94a3b8);
  font-style: italic;
  margin-left: auto;
}

.approval-resolved--denied {
  color: #ef4444;
}

/* ==================== 操作栏 ==================== */
.msg-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 0 0 4px;
  /* 始终占位，防止出现/消失时引起布局抖动 */
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.msg-actions--visible {
  opacity: 1;
  pointer-events: auto;
}

.msg-actions--right {
  justify-content: flex-end;
  padding: 4px 4px 0 0;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary, #94a3b8);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  background: var(--mc-bg-tertiary, rgba(0, 0, 0, 0.05));
  color: var(--mc-text-secondary, #64748b);
}

.action-btn.copied {
  color: #10b981;
}
.action-btn.tts-playing {
  color: var(--mc-primary);
}
.tts-loading-icon {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.action-time {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  margin-left: 4px;
  user-select: none;
}

/* ==================== 主内容区域 ==================== */
.msg-content {
  position: relative;
}

.msg-content.with-cursor {
  display: inline;
}

/* 加载指示器 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  background: var(--mc-text-tertiary, #94a3b8);
  border-radius: 50%;
  animation: bounce 1.2s infinite;
}

.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-6px); }
}


/* 状态指示器 */
.stopped-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0 2px;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

/* 错误卡片 */
.error-card {
  margin-top: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--mc-danger-bg);
  border: 1px solid color-mix(in srgb, var(--mc-danger) 25%, transparent);
  font-size: 13px;
  max-width: 480px;
  line-height: 1.5;
}

.error-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.error-card__icon {
  flex-shrink: 0;
  color: var(--mc-danger);
}

.error-card__title {
  font-weight: 600;
  color: var(--mc-danger);
  font-size: 14px;
}

.error-card__description {
  margin: 4px 0;
  color: var(--mc-text-primary);
  font-size: 13px;
  opacity: 0.85;
}

.error-card__action {
  margin: 4px 0 8px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.error-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.error-card__code {
  font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  color: var(--mc-danger);
  opacity: 0.6;
}

.error-card__retry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid color-mix(in srgb, var(--mc-danger) 30%, transparent);
  background: color-mix(in srgb, var(--mc-danger) 8%, var(--mc-bg-elevated));
  color: var(--mc-danger);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.error-card__retry:hover {
  background: color-mix(in srgb, var(--mc-danger) 15%, var(--mc-bg-elevated));
  border-color: color-mix(in srgb, var(--mc-danger) 50%, transparent);
}

/* ==================== 附件 ==================== */
.message-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.message-attachment-image {
  border-radius: 12px;
  overflow: hidden;
}

.message-attachment-image img {
  max-width: 280px;
  max-height: 200px;
  border-radius: 12px;
  cursor: pointer;
  object-fit: cover;
}

.message-attachment-image__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment-video {
  border-radius: 12px;
  overflow: hidden;
}

.message-attachment-video video {
  max-width: 400px;
  max-height: 280px;
  border-radius: 12px;
}

.message-attachment-video__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.14);
  color: inherit;
  text-decoration: none;
  border: none;
  font: inherit;
  cursor: pointer;
  width: 100%;
}

.user-bubble .message-attachment {
  background: rgba(255, 255, 255, 0.2);
}

.message-attachment__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.message-attachment__meta {
  flex-shrink: 0;
  opacity: 0.76;
  font-size: 12px;
}

.message-attachment__icon {
  flex-shrink: 0;
  opacity: 0.76;
}

/* ==================== Markdown 样式 ==================== */
.markdown-body :deep(p) {
  margin: 0 0 10px;
  line-height: 1.7;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 10px 0;
  padding-left: 24px;
}

.markdown-body :deep(ul) { list-style-type: disc; }
.markdown-body :deep(ol) { list-style-type: decimal; }

.markdown-body :deep(li) {
  margin: 4px 0;
  line-height: 1.7;
}

.markdown-body :deep(li > ul),
.markdown-body :deep(li > ol) {
  margin: 4px 0;
}

.markdown-body :deep(input[type="checkbox"]) {
  margin-right: 8px;
  vertical-align: middle;
}

.markdown-body :deep(blockquote) {
  margin: 14px 0;
  padding: 12px 16px;
  border-left: 4px solid var(--mc-primary, #D97757);
  background: var(--mc-bg-elevated, #f8fafc);
  border-radius: 0 8px 8px 0;
  color: var(--mc-text-secondary, #64748b);
}

.markdown-body :deep(blockquote p) { margin: 0; }

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 14px 0;
  font-size: 14px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 10px 12px;
  border: 1px solid var(--mc-border, #e2e8f0);
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--mc-bg-elevated, #f8fafc);
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

.markdown-body :deep(tr:nth-child(even)) {
  background: var(--mc-bg-sunken, #f1f5f9);
}

.markdown-body :deep(a) {
  color: var(--mc-primary, #D97757);
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s ease;
}

.markdown-body :deep(a:hover) {
  border-bottom-color: var(--mc-primary, #D97757);
}

.user-bubble .markdown-body :deep(a) {
  color: var(--mc-user-bubble-color, white);
  border-bottom: 1px solid rgba(255, 255, 255, 0.5);
}

.user-bubble .markdown-body :deep(a:hover) {
  border-bottom-color: var(--mc-user-bubble-color, white);
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--mc-border, #e2e8f0);
  margin: 20px 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 20px 0 12px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--mc-text-primary, #1e293b);
}

.markdown-body :deep(h1) { font-size: 1.5em; }
.markdown-body :deep(h2) { font-size: 1.3em; }
.markdown-body :deep(h3) { font-size: 1.15em; }
.markdown-body :deep(h4) { font-size: 1em; }

/* 代码块 */
.markdown-body :deep(pre) {
  background: var(--mc-code-bg, #1e293b);
  border-radius: 12px;
  padding: 16px;
  overflow-x: auto;
  margin: 14px 0;
}

.markdown-body :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
}

.markdown-body :deep(pre code) {
  color: #e2e8f0;
  background: transparent;
  padding: 0;
}

.markdown-body :deep(:not(pre) > code) {
  background: var(--mc-inline-code-bg, #f1f5f9);
  color: var(--mc-inline-code-color, #ef4444);
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 0.92em;
}

.markdown-body :deep(.code-block) {
  margin: 14px 0;
  border-radius: 12px;
  overflow: hidden;
  background: var(--mc-code-bg, #1e293b);
}

.markdown-body :deep(.code-block__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: rgba(0, 0, 0, 0.2);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.markdown-body :deep(.code-block__lang) {
  font-size: 12px;
  color: #94a3b8;
  font-weight: 500;
}

.markdown-body :deep(.code-block__copy) {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: #94a3b8;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.markdown-body :deep(.code-block__copy:hover) {
  background: rgba(255, 255, 255, 0.1);
  color: #e2e8f0;
}

.markdown-body :deep(.code-block pre) {
  margin: 0;
  border-radius: 0;
}

.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 10px 0;
}

.markdown-body :deep(del),
.markdown-body :deep(s) {
  text-decoration: line-through;
  opacity: 0.7;
}

.markdown-body :deep(strong),
.markdown-body :deep(b) {
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .message-wrapper {
    max-width: 100%;
    gap: 8px;
  }

  .msg-body {
    max-width: calc(100% - 40px);
  }

  .msg-avatar {
    width: 28px;
    height: 28px;
    font-size: 12px;
  }

  .error-card {
    max-width: 100%;
  }
}
</style>
