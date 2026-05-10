<template>
  <div v-if="hint" class="routing-hint" :class="`routing-hint--${hint.tone}`">
    <span class="routing-hint__icon" aria-hidden="true">
      <svg v-if="hint.tone === 'info'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="16" x2="12" y2="12"/>
        <line x1="12" y1="8" x2="12.01" y2="8"/>
      </svg>
      <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
        <line x1="12" y1="9" x2="12" y2="13"/>
        <line x1="12" y1="17" x2="12.01" y2="17"/>
      </svg>
    </span>
    <span class="routing-hint__text">{{ hint.text }}</span>
    <button
      v-if="hint.actionLabel"
      type="button"
      class="routing-hint__action"
      @click="onAction"
    >{{ hint.actionLabel }}</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { AgentCapabilities, ChatAttachment } from '@/types'

const props = defineProps<{
  /** Pending attachments in the input box. Hint reacts to image/video parts. */
  attachments: ChatAttachment[]
  /** Capability snapshot loaded for the current agent. Null while in flight. */
  capabilities: AgentCapabilities | null
}>()

const { t } = useI18n()
const router = useRouter()

interface Hint {
  tone: 'info' | 'warn'
  text: string
  actionLabel?: string
  actionHref?: string
}

const hint = computed<Hint | null>(() => {
  const caps = props.capabilities
  if (!caps) return null

  const hasImage = props.attachments.some(a => (a.contentType || '').startsWith('image/'))
  const hasVideo = props.attachments.some(a => (a.contentType || '').startsWith('video/'))
  if (!hasImage && !hasVideo) return null

  const supportsVision = caps.modalities.includes('VISION')
  const supportsVideo = caps.modalities.includes('VIDEO')
  const sidecarVision = caps.defaultVisionModelLabel
  const sidecarVideo = caps.defaultVideoModelLabel

  // Image attachment branches
  if (hasImage && !supportsVision) {
    if (sidecarVision) {
      return {
        tone: 'info',
        text: t('chat.routing.hint.willRoute', {
          kind: t('chat.routing.kind.image'),
          primary: caps.modelName,
          sidecar: sidecarVision,
        }),
      }
    }
    return {
      tone: 'warn',
      text: t('chat.routing.hint.notConfigured', {
        kind: t('chat.routing.kind.image'),
      }),
      actionLabel: t('chat.routing.hint.action.gotoSettings'),
      actionHref: '/settings/models',
    }
  }

  // Video attachment branches — v1 has no sidecar, only point user at switching the primary model
  if (hasVideo && !supportsVideo) {
    return {
      tone: 'warn',
      text: sidecarVideo
        ? t('chat.routing.hint.videoReserved', { sidecar: sidecarVideo })
        : t('chat.routing.hint.notConfigured', { kind: t('chat.routing.kind.video') }),
      actionLabel: t('chat.routing.hint.action.gotoSettings'),
      actionHref: '/settings/models',
    }
  }

  return null
})

function onAction() {
  if (hint.value?.actionHref) router.push(hint.value.actionHref)
}
</script>

<style scoped>
.routing-hint {
  margin: 6px 0;
  padding: 8px 12px;
  border-radius: 6px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  line-height: 1.5;
  border: 1px solid transparent;
}
.routing-hint--info {
  background: var(--mc-primary-bg, rgba(217, 109, 70, 0.08));
  color: var(--mc-text-primary);
  border-color: color-mix(in srgb, var(--mc-primary, #d96d46) 25%, transparent);
}
.routing-hint--warn {
  background: var(--mc-warning-bg, rgba(245, 158, 11, 0.10));
  color: var(--mc-text-primary);
  border-color: color-mix(in srgb, #f59e0b 30%, transparent);
}
.routing-hint__icon {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--mc-primary, #d96d46);
}
.routing-hint--warn .routing-hint__icon {
  color: #f59e0b;
}
.routing-hint__text {
  flex: 1;
  min-width: 0;
}
.routing-hint__action {
  flex-shrink: 0;
  padding: 3px 10px;
  border-radius: 4px;
  border: 1px solid currentColor;
  background: transparent;
  color: var(--mc-primary, #d96d46);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;
}
.routing-hint--warn .routing-hint__action {
  color: #b45309;
}
.routing-hint__action:hover {
  background: var(--mc-primary, #d96d46);
  color: #fff;
}
.routing-hint--warn .routing-hint__action:hover {
  background: #f59e0b;
  color: #fff;
}
</style>
