<template>
  <el-drawer v-model="visible" :title="panelTitle" size="480px" @close="emit('close')">
    <div v-if="!report" class="panel-loading">
      <el-skeleton :rows="6" animated />
    </div>
    <div v-else class="panel-content">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item :label="t('memory.report.mode')">
          <el-tag :type="report.mode === 'FOCUSED' ? 'warning' : 'info'" size="small">{{ report.mode }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="report.topic" :label="t('memory.report.topic')">
          {{ report.topic }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.status')">
          <el-tag :type="statusType(report.status)" size="small">{{ report.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.time')">
          {{ formatTime(report.startedAt) }} ~ {{ formatTime(report.finishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.candidates')">
          {{ report.candidateCount }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.promoted')">
          <span class="text-success">{{ report.promotedCount }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.rejected')">
          <span class="text-danger">{{ report.rejectedCount }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('memory.report.trigger')">
          {{ report.triggerSource }} / {{ report.triggeredBy }}
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="report.llmReason" class="section">
        <h4>{{ t('memory.report.reason') }}</h4>
        <p class="reason-text">{{ report.llmReason }}</p>
      </div>

      <div v-if="report.memoryDiff" class="section">
        <h4>{{ t('memory.report.diff') }}</h4>
        <code class="diff-text">{{ report.memoryDiff }}</code>
      </div>

      <div v-if="report.errorMessage" class="section error-section">
        <h4>Error</h4>
        <p class="error-text">{{ report.errorMessage }}</p>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DreamReportItem } from '@/stores/useMemoryStore'

const props = defineProps<{ report: DreamReportItem | null }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const visible = computed(() => props.report !== null)

const panelTitle = computed(() => {
  if (!props.report) return ''
  return `Dream Report — ${props.report.mode}`
})

function statusType(status: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

function formatTime(isoStr: string) {
  if (!isoStr) return ''
  return new Date(isoStr).toLocaleString()
}
</script>

<style scoped>
.panel-content {
  padding: 0 4px;
}
.panel-loading {
  padding: 20px;
}
.section {
  margin-top: 16px;
}
.section h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.reason-text {
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}
.diff-text {
  display: block;
  padding: 8px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
}
.error-section .error-text {
  color: var(--el-color-danger);
  font-size: 13px;
}
.text-success { color: var(--el-color-success); font-weight: 600; }
.text-danger { color: var(--el-color-danger); font-weight: 600; }
</style>
