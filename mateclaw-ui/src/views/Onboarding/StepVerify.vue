<template>
  <div class="step-verify">
    <h3 class="verify-title">{{ t('onboarding.verifyTitle') }}</h3>

    <!-- Mini chat area -->
    <div class="chat-area">
      <!-- User message -->
      <div v-if="sent" class="chat-msg user-msg">
        <span>{{ message }}</span>
      </div>

      <!-- Assistant response -->
      <div v-if="response" class="chat-msg assistant-msg">
        <span>{{ response }}</span>
      </div>

      <!-- Loading indicator -->
      <div v-if="streaming && !response" class="chat-msg assistant-msg">
        <span class="loading-dots">...</span>
      </div>
    </div>

    <!-- Input area -->
    <div v-if="!sent" class="input-area">
      <input
        v-model="message"
        class="text-input"
        :placeholder="t('onboarding.verifyMessage')"
        @keydown.enter="sendMessage"
      />
      <button
        class="btn-send"
        :disabled="!message.trim()"
        @click="sendMessage"
      >{{ t('onboarding.send') }}</button>
    </div>

    <!-- Complete button -->
    <button
      v-if="completed"
      class="btn-start"
      @click="emit('complete')"
    >{{ t('onboarding.startUsing') }}</button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

const emit = defineEmits<{ (e: 'complete'): void }>()
const { t } = useI18n()

const message = ref(t('onboarding.verifyMessage'))
const sent = ref(false)
const streaming = ref(false)
const response = ref('')
const completed = ref(false)

async function sendMessage() {
  if (!message.value.trim()) return
  sent.value = true
  streaming.value = true

  try {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'Cache-Control': 'no-cache',
    }
    const token = localStorage.getItem('token')
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const res = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        message: message.value,
      }),
    })

    if (!res.ok || !res.body) {
      response.value = 'Error: Unable to connect'
      completed.value = true
      streaming.value = false
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const chunk = decoder.decode(value, { stream: true })
      const lines = chunk.split('\n')

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') continue
          try {
            const parsed = JSON.parse(data)
            if (parsed.content) {
              response.value += parsed.content
            } else if (parsed.result) {
              response.value += parsed.result
            } else if (typeof parsed === 'string') {
              response.value += parsed
            }
          } catch {
            // Not JSON, treat as raw text
            if (data && data !== '[DONE]') {
              response.value += data
            }
          }
        }
      }
    }
  } catch {
    if (!response.value) {
      response.value = 'Error: Connection failed'
    }
  } finally {
    streaming.value = false
    completed.value = true
  }
}
</script>

<style scoped>
.step-verify {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.verify-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}

.chat-area {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 120px;
  max-height: 250px;
  overflow-y: auto;
  padding: 16px;
  background: var(--mc-bg-sunken);
  border-radius: 10px;
  border: 1px solid var(--mc-border-light);
}

.chat-msg {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  max-width: 85%;
  word-break: break-word;
}

.user-msg {
  align-self: flex-end;
  background: var(--mc-primary);
  color: var(--mc-text-inverse);
}

.assistant-msg {
  align-self: flex-start;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border-light);
}

.loading-dots {
  animation: blink 1.2s infinite;
}

@keyframes blink {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}

.input-area {
  display: flex;
  gap: 10px;
}

.text-input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s ease;
}

.text-input:focus {
  border-color: var(--mc-primary);
}

.btn-send {
  padding: 10px 20px;
  background: var(--mc-primary);
  color: var(--mc-text-inverse);
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease;
  white-space: nowrap;
}

.btn-send:hover:not(:disabled) {
  background: var(--mc-primary-hover);
}

.btn-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-start {
  padding: 12px 24px;
  background: var(--mc-primary);
  color: var(--mc-text-inverse);
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
  align-self: center;
}

.btn-start:hover {
  background: var(--mc-primary-hover);
}
</style>
