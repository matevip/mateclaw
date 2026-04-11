<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown } from '@element-plus/icons-vue'
import type { Message } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  message: Message
}>()

const expanded = ref(false)

const compressedCount = computed(() => {
  try {
    const metadata = typeof props.message.metadata === 'string'
      ? JSON.parse(props.message.metadata)
      : props.message.metadata
    return metadata?.compressedCount || 0
  } catch {
    return 0
  }
})
</script>

<template>
  <div class="seg-compression">
    <div class="seg-compression__header" @click="expanded = !expanded">
      <span class="seg-compression__icon">
        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
      </span>
      <span v-if="compressedCount > 0" class="seg-compression__label">
        {{ t('chat.compressionWithCount', { count: compressedCount }) }}
      </span>
      <span v-else class="seg-compression__label">{{ t('chat.compressionSummary') }}</span>
      <el-icon class="seg-compression__arrow" :class="{ 'is-open': expanded }" :size="12"><ArrowDown /></el-icon>
    </div>
    <div v-if="expanded" class="seg-compression__body">
      <div class="markdown-body">{{ message.content }}</div>
    </div>
  </div>
</template>

<style scoped>
.seg-compression {
  margin: 6px 0;
  border-radius: 8px;
  border: 1px dashed var(--mc-border);
  overflow: hidden;
}
.seg-compression__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  user-select: none;
  transition: color 0.15s;
}
.seg-compression__header:hover {
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
}
.seg-compression__icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.seg-compression__label {
  flex: 1;
}
.seg-compression__arrow {
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}
.seg-compression__arrow.is-open {
  transform: rotate(180deg);
}
.seg-compression__body {
  padding: 0 12px 10px;
  font-size: 13px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
}
</style>
