<template>
  <div class="dream-timeline">
    <div v-if="store.loading" class="loading-state">
      <el-skeleton :rows="5" animated />
    </div>

    <el-empty v-else-if="store.reports.length === 0" :description="t('memory.noReports')" />

    <div v-else class="timeline-list">
      <div
        v-for="report in store.reports"
        :key="report.id"
        class="timeline-item"
        :class="{ active: selectedId === report.id }"
        @click="selectReport(report)"
      >
        <div class="timeline-dot" :class="report.status.toLowerCase()" />
        <div class="timeline-content">
          <div class="timeline-header">
            <el-tag :type="modeTagType(report.mode)" size="small">{{ report.mode }}</el-tag>
            <span class="timeline-time">{{ formatTime(report.startedAt) }}</span>
          </div>
          <div class="timeline-meta">
            <span v-if="report.topic" class="topic">{{ report.topic }}</span>
            <span class="counts">
              +{{ report.promotedCount }} / -{{ report.rejectedCount }} / {{ report.candidateCount }} candidates
            </span>
          </div>
          <div v-if="report.llmReason" class="timeline-reason">{{ truncate(report.llmReason, 80) }}</div>
        </div>
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="store.total"
          layout="prev, pager, next"
          @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- Detail panel -->
    <DreamReportPanel v-if="selectedId" :report="store.currentReport" @close="selectedId = null" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMemoryStore, type DreamReportItem } from '@/stores/useMemoryStore'
import DreamReportPanel from './DreamReportPanel.vue'

const props = defineProps<{ agentId: number }>()
const { t } = useI18n()
const store = useMemoryStore()
const currentPage = ref(1)
const pageSize = 20
const selectedId = ref<string | null>(null)

watch(() => props.agentId, () => {
  currentPage.value = 1
  selectedId.value = null
  loadReports()
}, { immediate: true })

function loadReports() {
  store.fetchReports(props.agentId, currentPage.value, pageSize)
}

function onPageChange(page: number) {
  currentPage.value = page
  loadReports()
}

function selectReport(report: DreamReportItem) {
  selectedId.value = report.id
  store.fetchReport(props.agentId, report.id)
}

function modeTagType(mode: string) {
  return mode === 'FOCUSED' ? 'warning' : 'info'
}

function formatTime(isoStr: string) {
  if (!isoStr) return ''
  const d = new Date(isoStr)
  return d.toLocaleString()
}

function truncate(str: string, max: number) {
  return str.length > max ? str.slice(0, max) + '...' : str
}
</script>

<style scoped>
.dream-timeline {
  position: relative;
}
.timeline-list {
  padding-left: 20px;
  border-left: 2px solid var(--el-border-color-lighter);
}
.timeline-item {
  position: relative;
  padding: 12px 16px;
  margin-bottom: 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}
.timeline-item:hover {
  background-color: var(--el-fill-color-light);
}
.timeline-item.active {
  background-color: var(--el-color-primary-light-9);
}
.timeline-dot {
  position: absolute;
  left: -27px;
  top: 18px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--el-color-info);
}
.timeline-dot.success { background: var(--el-color-success); }
.timeline-dot.failed { background: var(--el-color-danger); }
.timeline-dot.skipped { background: var(--el-color-warning); }
.timeline-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.timeline-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.timeline-meta {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.timeline-meta .topic {
  font-weight: 500;
  margin-right: 8px;
}
.timeline-meta .counts {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.timeline-reason {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-style: italic;
}
.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
.loading-state {
  padding: 20px;
}
</style>
