<script setup lang="ts">
import { computed } from 'vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import TypingCursor from './TypingCursor.vue'
import type { MessageSegment } from '@/types'

const props = withDefaults(defineProps<{
  segment: MessageSegment
  showCursor?: boolean
}>(), {
  showCursor: false,
})

const { renderMarkdown } = useMarkdownRenderer()

const renderedContent = computed(() => renderMarkdown(props.segment.text || ''))
const isRunning = computed(() => props.segment.status === 'running')
</script>

<template>
  <div class="seg-content">
    <div class="markdown-body" v-html="renderedContent"></div>
    <TypingCursor v-if="isRunning && showCursor" />
  </div>
</template>

<style scoped>
.seg-content {
  padding: 4px 0;
  margin-top: 4px;
}
</style>
