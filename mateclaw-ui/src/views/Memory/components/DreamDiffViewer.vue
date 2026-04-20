<template>
  <div class="diff-viewer" v-if="diff">
    <div class="diff-header">
      <span class="diff-icon">📝</span>
      <span class="diff-label">{{ t('memory.diff.title') }}</span>
    </div>
    <pre class="diff-content">{{ diff }}</pre>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
defineProps<{ diff: string | null }>()
</script>

<style scoped>
.diff-viewer {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
  border: 1px solid var(--el-border-color-extra-light);
}
.diff-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.diff-icon { font-size: 14px; }
.diff-content {
  margin: 0;
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', monospace;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}
</style>
