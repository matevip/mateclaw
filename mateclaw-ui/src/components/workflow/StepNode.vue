<template>
  <div
    class="step-node"
    :class="`mode-${data.modeType}`"
    :data-selected="props.selected"
    @click.stop="handleClick"
  >
    <Handle type="target" :position="targetPosition" />
    <div class="step-band" />
    <div class="step-body">
      <div class="step-header">
        <span class="step-icon" v-html="modeIcon" />
        <span class="step-name" :title="data.name">{{ data.name }}</span>
        <span class="step-mode-tag" :title="data.modeType">{{ modeLabel }}</span>
      </div>
      <div v-if="agentSlug" class="step-row">
        <span class="step-row-label">{{ t('workflows.canvas.nodeAgent') }}</span>
        <span class="step-row-value" :title="agentSlug">{{ agentSlug }}</span>
      </div>
      <div v-if="data.expression" class="step-row">
        <span class="step-row-label">{{ t('workflows.canvas.nodeExpression') }}</span>
        <code class="step-row-code" :title="data.expression">{{ data.expression }}</code>
      </div>
      <div v-if="approverHint" class="step-row">
        <span class="step-row-label">{{ t('workflows.canvas.nodeApprovalKind') }}</span>
        <span class="step-row-value">{{ approverHint }}</span>
      </div>
      <div v-if="channelHint" class="step-row">
        <span class="step-row-label">{{ t('workflows.canvas.nodeChannels') }}</span>
        <span class="step-row-value">{{ channelHint }}</span>
      </div>
      <div v-if="mergeHint" class="step-row">
        <span class="step-row-label">{{ t('workflows.canvas.nodeMergeStrategy') }}</span>
        <span class="step-row-value">{{ mergeHint }}</span>
      </div>
      <div v-if="data.promptTemplate" class="step-prompt" :title="data.promptTemplate">
        {{ truncatedPrompt }}
      </div>
    </div>
    <Handle type="source" :position="sourcePosition" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import type { StepNodeData } from '@/composables/useWorkflowGraph'

const props = defineProps<NodeProps<StepNodeData>>()
const { t } = useI18n()

// The canvas wrapper provides a selection callback so we can fire a
// click straight up to the parent without depending on vue-flow's
// node-click event, which doesn't fire reliably across versions when
// the click lands on a custom-template inner element.
const selectStep = inject<(data: StepNodeData | null) => void>(
  'selectStepCallback',
  () => {}
)
function handleClick(event: MouseEvent) {
  selectStep(props.data as StepNodeData)
  const root = event.currentTarget as HTMLElement | null
  const canvasId = root?.closest('.workflow-canvas')?.getAttribute('data-canvas-id') ?? undefined
  window.dispatchEvent(new CustomEvent('mateclaw:workflow-step-select', {
    detail: { canvasId, data: props.data as StepNodeData },
  }))
}

// Position values come from the parent canvas (LR or TB orientation);
// we read them off the node so the arrows attach in the right place.
const sourcePosition = computed(() => props.sourcePosition ?? Position.Right)
const targetPosition = computed(() => props.targetPosition ?? Position.Left)

const data = computed(() => props.data as StepNodeData)

const agentSlug = computed(() => {
  const name = data.value.agentName?.trim()
  if (name) return name
  return data.value.agentId != null ? `#${data.value.agentId}` : ''
})

const approverHint = computed(() => {
  if (data.value.modeType !== 'await_approval') return ''
  return data.value.approvalKind || t('workflows.canvas.approvalDefault')
})

const channelHint = computed(() => {
  if (data.value.modeType !== 'dispatch_channel') return ''
  const list = data.value.channels ?? []
  if (!list.length) return t('workflows.canvas.channelsEmpty')
  return list.join(', ')
})

const mergeHint = computed(() => {
  if (data.value.modeType !== 'write_memory') return ''
  return data.value.mergeStrategy || 'append'
})

const truncatedPrompt = computed(() => {
  const pt = data.value.promptTemplate ?? ''
  if (pt.length <= 80) return pt
  return pt.slice(0, 77) + '…'
})

// Inline icons keep the bundle slim — no external icon set per node type.
const ICONS: Record<string, string> = {
  sequential:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12h16"/><path d="M14 6l6 6-6 6"/></svg>',
  fan_out:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h6"/><path d="M11 6h8"/><path d="M11 18h8"/><path d="M11 6v12"/></svg>',
  collect:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 6h8"/><path d="M5 18h8"/><path d="M13 6v12"/><path d="M13 12h6"/></svg>',
  conditional:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l9 9-9 9-9-9z"/></svg>',
  await_approval:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  dispatch_channel:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l18-8-7 18-2-8z"/></svg>',
  write_memory:
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 9h16"/><path d="M9 14h6"/></svg>',
}

const modeIcon = computed(() => ICONS[data.value.modeType] ?? ICONS.sequential)

// Render the mode tag with the locale-mapped label and fall back to
// the raw schema string for unknown / future modes so the tag never
// shows an empty pill.
const modeLabel = computed(() => {
  const key = `workflows.canvas.modeLabels.${data.value.modeType}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : data.value.modeType
})
</script>

<style scoped>
.step-node {
  position: relative;
  width: 220px;
  min-height: 88px;
  border-radius: 10px;
  background: var(--mc-bg-elevated, #ffffff);
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  color: var(--mc-text-primary, inherit);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: stretch;
  overflow: hidden;
  transition: box-shadow 0.12s ease, transform 0.12s ease;
}
.step-node[data-selected="true"] {
  box-shadow: 0 0 0 2px var(--mc-primary, #4084ff);
  transform: translateY(-1px);
}
.step-band {
  width: 6px;
  flex: 0 0 6px;
  background: var(--node-band, #4084ff);
}
/* Per-mode color bands so the canvas reads at a glance. */
.step-node.mode-sequential       { --node-band: #4084ff; }
.step-node.mode-fan_out          { --node-band: #b388ff; }
.step-node.mode-collect          { --node-band: #7e57c2; }
.step-node.mode-conditional      { --node-band: #ff8f3f; }
.step-node.mode-await_approval   { --node-band: #ffb84d; }
.step-node.mode-dispatch_channel { --node-band: #2ecc71; }
.step-node.mode-write_memory     { --node-band: #1abc9c; }

.step-body {
  flex: 1;
  padding: 8px 10px 8px 10px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.step-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}
.step-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--node-band, #4084ff);
}
.step-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.step-mode-tag {
  font-size: 10px;
  font-weight: 500;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.06));
  color: var(--mc-text-secondary, inherit);
  text-transform: lowercase;
  letter-spacing: 0.02em;
}
.step-row {
  display: flex;
  gap: 6px;
  align-items: baseline;
  font-size: 11px;
  opacity: 0.85;
  min-width: 0;
}
.step-row-label {
  font-size: 10px;
  letter-spacing: 0.04em;
  opacity: 0.6;
  flex: 0 0 auto;
  /* English row labels are short and visually fine in uppercase, but
     CJK glyphs look noisy when the locale flips them through
     text-transform. Leave casing to the locale string. */
}
:lang(en) .step-row-label {
  text-transform: uppercase;
}
.step-row-value {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.step-row-code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.step-prompt {
  margin-top: 2px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10.5px;
  opacity: 0.7;
  line-height: 1.35;
  white-space: pre-wrap;
  word-break: break-word;
  /* Limit to two lines to keep the node a sane height. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
