<template>
  <Transition name="talk-fade">
    <div v-if="visible" class="talk-overlay">
      <!-- Header -->
      <div class="talk-header">
        <span class="talk-title">{{ t('talk.title') }}</span>
        <button class="talk-close" @click="$emit('close')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <!-- Visualizer -->
      <div class="talk-visualizer">
        <div class="talk-pulse" :class="stateClass">
          <div class="talk-pulse-ring"></div>
          <div class="talk-pulse-ring talk-pulse-ring--2"></div>
          <div class="talk-pulse-core">
            <svg v-if="state === 'idle'" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
              <path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/>
              <line x1="8" y1="23" x2="16" y2="23"/>
            </svg>
            <svg v-else-if="state === 'listening'" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="pulse-anim">
              <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
              <path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/>
            </svg>
            <svg v-else-if="state === 'processing'" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="spin">
              <path d="M21 12a9 9 0 11-6.219-8.56"/>
            </svg>
            <svg v-else width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>
              <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
            </svg>
          </div>
        </div>
        <div class="talk-state-label">{{ stateLabel }}</div>
      </div>

      <!-- Transcript -->
      <div class="talk-transcript">
        <div v-for="(msg, i) in transcript" :key="i" class="talk-msg" :class="'talk-msg--' + msg.role">
          <span class="talk-msg-role">{{ msg.role === 'user' ? t('talk.you') : t('talk.ai') }}</span>
          <span class="talk-msg-text">{{ msg.text }}</span>
        </div>
      </div>

      <!-- Push-to-Talk button -->
      <div class="talk-controls">
        <button
          class="talk-ptt"
          :class="{ active: state === 'listening' }"
          :disabled="state === 'processing' || state === 'speaking'"
          @mousedown="startListening"
          @mouseup="stopListening"
          @touchstart.prevent="startListening"
          @touchend.prevent="stopListening"
        >
          {{ state === 'listening' ? t('talk.releaseToSend') : t('talk.holdToTalk') }}
        </button>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'

const { t } = useI18n()

const props = defineProps<{
  visible: boolean
  agentId: string | number | null
  conversationId?: string
}>()

const emit = defineEmits<{
  close: []
}>()

type TalkState = 'idle' | 'listening' | 'processing' | 'speaking'

const state = ref<TalkState>('idle')
const transcript = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])

let ws: WebSocket | null = null
let mediaRecorder: MediaRecorder | null = null
let audioChunks: Blob[] = []
let audioContext: AudioContext | null = null

const stateClass = computed(() => 'talk-state--' + state.value)
const stateLabel = computed(() => {
  switch (state.value) {
    case 'idle': return t('talk.ready')
    case 'listening': return t('talk.listening')
    case 'processing': return t('talk.processing')
    case 'speaking': return t('talk.speaking')
    default: return ''
  }
})

onMounted(() => {
  connectWebSocket()
})

onBeforeUnmount(() => {
  disconnectWebSocket()
})

function connectWebSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = localStorage.getItem('token')
  const wsUrl = `${protocol}//${location.host}/api/v1/talk/ws${token ? '?token=' + token : ''}`

  ws = new WebSocket(wsUrl)

  ws.onopen = () => {
    // Send init message
    ws?.send(JSON.stringify({
      type: 'init',
      agentId: props.agentId,
      conversationId: props.conversationId || 'talk-' + Date.now(),
      username: localStorage.getItem('username') || 'anonymous',
    }))
  }

  ws.onmessage = (event) => {
    if (event.data instanceof Blob) {
      // Binary = TTS audio
      playAudio(event.data)
      return
    }

    try {
      const data = JSON.parse(event.data)
      switch (data.type) {
        case 'ready':
          state.value = 'idle'
          break
        case 'state':
          state.value = data.state as TalkState
          break
        case 'transcript':
          transcript.value.push({ role: 'user', text: data.text })
          break
        case 'reply':
          transcript.value.push({ role: 'assistant', text: data.text })
          break
        case 'tts_url':
          playAudioUrl(data.url)
          break
        case 'error':
          ElMessage.error(data.message || t('talk.connectionError'))
          state.value = 'idle'
          break
      }
    } catch {
      // ignore non-JSON frames
    }
  }

  ws.onclose = () => {
    state.value = 'idle'
  }
}

function disconnectWebSocket() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  ws?.close()
  ws = null
  audioContext?.close()
  audioContext = null
}

async function startListening() {
  if (state.value !== 'idle') return

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    audioChunks = []
    mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' })

    mediaRecorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        audioChunks.push(event.data)
      }
    }

    mediaRecorder.onstop = async () => {
      // Stop all tracks
      stream.getTracks().forEach(t => t.stop())

      if (audioChunks.length === 0) {
        state.value = 'idle'
        return
      }

      // Combine chunks and send
      const audioBlob = new Blob(audioChunks, { type: 'audio/webm' })
      const arrayBuffer = await audioBlob.arrayBuffer()
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(arrayBuffer)
        state.value = 'processing'
      } else {
        state.value = 'idle'
      }
    }

    mediaRecorder.start()
    state.value = 'listening'
  } catch {
    ElMessage.error(t('talk.micError'))
    state.value = 'idle'
  }
}

function stopListening() {
  if (state.value !== 'listening' || !mediaRecorder) return
  mediaRecorder.stop()
}

async function playAudio(blob: Blob) {
  try {
    if (!audioContext) {
      audioContext = new AudioContext()
    }
    const arrayBuffer = await blob.arrayBuffer()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)
    const source = audioContext.createBufferSource()
    source.buffer = audioBuffer
    source.connect(audioContext.destination)
    source.onended = () => {
      state.value = 'idle'
    }
    source.start(0)
    state.value = 'speaking'
  } catch {
    ElMessage.warning(t('talk.playbackError'))
    state.value = 'idle'
  }
}

function playAudioUrl(url: string) {
  const audio = new Audio(url)
  audio.onended = () => { state.value = 'idle' }
  audio.onerror = () => { state.value = 'idle' }
  audio.play().catch(() => { state.value = 'idle' })
}
</script>

<style scoped>
.talk-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: var(--el-bg-color, #fff);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.talk-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
}

.talk-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.talk-close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--el-text-color-regular, #606266);
  padding: 4px;
  border-radius: 6px;
}

.talk-close:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}

.talk-visualizer {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
}

.talk-pulse {
  position: relative;
  width: 120px;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.talk-pulse-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 2px solid var(--el-color-primary, #409eff);
  opacity: 0.2;
}

.talk-state--listening .talk-pulse-ring {
  animation: pulse-ring 1.5s ease-out infinite;
  opacity: 0.4;
}

.talk-state--listening .talk-pulse-ring--2 {
  animation-delay: 0.5s;
}

.talk-state--speaking .talk-pulse-ring {
  animation: pulse-ring 2s ease-out infinite;
  border-color: var(--el-color-success, #67c23a);
  opacity: 0.3;
}

.talk-pulse-core {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--el-fill-color-light, #f5f7fa);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary, #409eff);
  transition: all 0.3s;
}

.talk-state--listening .talk-pulse-core {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.talk-state--processing .talk-pulse-core {
  background: var(--el-color-warning-light-9, #fdf6ec);
  color: var(--el-color-warning, #e6a23c);
}

.talk-state--speaking .talk-pulse-core {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.talk-state-label {
  font-size: 14px;
  color: var(--el-text-color-secondary, #909399);
  font-weight: 500;
}

.talk-transcript {
  width: 100%;
  max-width: 600px;
  max-height: 200px;
  overflow-y: auto;
  padding: 0 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.talk-msg {
  display: flex;
  gap: 8px;
  font-size: 14px;
  line-height: 1.5;
}

.talk-msg-role {
  font-weight: 600;
  min-width: 32px;
  color: var(--el-text-color-secondary, #909399);
}

.talk-msg--user .talk-msg-role { color: var(--el-color-primary, #409eff); }
.talk-msg--assistant .talk-msg-role { color: var(--el-color-success, #67c23a); }

.talk-msg-text {
  color: var(--el-text-color-primary, #303133);
}

.talk-controls {
  padding: 32px;
}

.talk-ptt {
  width: 200px;
  height: 56px;
  border-radius: 28px;
  border: 2px solid var(--el-color-primary, #409eff);
  background: transparent;
  color: var(--el-color-primary, #409eff);
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
  -webkit-user-select: none;
}

.talk-ptt:hover:not(:disabled) {
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.talk-ptt.active {
  background: var(--el-color-primary, #409eff);
  color: white;
  transform: scale(1.05);
}

.talk-ptt:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

@keyframes pulse-ring {
  0% { transform: scale(1); opacity: 0.4; }
  100% { transform: scale(1.6); opacity: 0; }
}

.pulse-anim {
  animation: pulse-icon 1s ease-in-out infinite;
}

@keyframes pulse-icon {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.1); }
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.talk-fade-enter-active,
.talk-fade-leave-active {
  transition: opacity 0.3s;
}

.talk-fade-enter-from,
.talk-fade-leave-to {
  opacity: 0;
}
</style>
