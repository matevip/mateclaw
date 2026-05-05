<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Opportunity, ArrowDown } from '@element-plus/icons-vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import type { MessageSegment } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  segment: MessageSegment
}>()

// Expand while streaming so the user can watch the model think in real time;
// auto-collapse the moment streaming ends so the assistant's final answer
// stays the focal point without a long reasoning block above it. The user
// can click the header to re-expand at any time.
const expanded = ref(props.segment.status === 'running')
const { renderMarkdown } = useMarkdownRenderer()

const renderedThinking = computed(() => renderMarkdown(props.segment.thinkingText || ''))
const isRunning = computed(() => props.segment.status === 'running')

watch(() => props.segment.status, (val) => {
  if (val === 'completed') expanded.value = false
})

const lengthHint = computed(() => {
  const len = props.segment.thinkingText?.length || 0
  if (len < 100) return ''
  return len < 1000 ? `${len} chars` : `${(len / 1000).toFixed(1)}k chars`
})
</script>

<template>
  <div class="seg-thinking" :class="{ 'is-active': isRunning }">
    <div class="seg-thinking__header" @click="expanded = !expanded">
      <span class="seg-thinking__icon">
        <el-icon :class="{ 'is-loading': isRunning }" :size="14"><Opportunity /></el-icon>
      </span>
      <span class="seg-thinking__label">{{ isRunning ? t('chat.thinkingInProgress') : t('chat.thinking') }}</span>
      <span v-if="lengthHint" class="seg-thinking__hint">{{ lengthHint }}</span>
      <el-icon class="seg-thinking__arrow" :class="{ 'is-open': expanded }" :size="12"><ArrowDown /></el-icon>
    </div>
    <Transition name="seg-slide">
      <div v-if="expanded" class="seg-thinking__body markdown-body" v-html="renderedThinking"></div>
    </Transition>
  </div>
</template>

<style scoped>
.seg-thinking {
  margin: 3px 0;
  border-radius: 8px;
  background: var(--mc-thinking-bg);
  border: 1px solid var(--mc-thinking-border);
  overflow: hidden;
  transition: all 0.2s;
}
.seg-thinking:hover {
  background: var(--mc-thinking-hover);
}
.seg-thinking.is-active {
  border-color: var(--mc-primary-light);
}
.seg-thinking__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--mc-thinking-text);
  user-select: none;
}
.seg-thinking__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  background: var(--mc-thinking-icon-bg);
  color: var(--mc-primary);
  flex-shrink: 0;
}
.seg-thinking__label {
  font-weight: 500;
  flex: 1;
}
.seg-thinking__hint {
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.seg-thinking__arrow {
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}
.seg-thinking__arrow.is-open {
  transform: rotate(180deg);
}
.seg-thinking__body {
  padding: 0 12px 10px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--mc-thinking-text);
}

.seg-slide-enter-active, .seg-slide-leave-active {
  transition: all 0.2s ease;
}
.seg-slide-enter-from, .seg-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
