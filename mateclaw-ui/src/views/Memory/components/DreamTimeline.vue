<template>
  <div class="dream-timeline">
    <div v-if="store.loading" class="loading-state">
      <div class="skeleton-card mc-surface-card" v-for="i in 3" :key="i">
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <div v-else-if="store.reports.length === 0" class="empty-state">
      <div class="empty-icon">🌙</div>
      <p>{{ t('memory.noReports') }}</p>
    </div>

    <div v-else class="timeline-cards">
      <div
        v-for="report in store.reports"
        :key="report.id"
        class="dream-card mc-surface-card"
        :class="{ selected: selectedId === report.id }"
        @click="selectReport(report)"
      >
        <div class="card-top">
          <span class="status-dot" :class="report.status.toLowerCase()" />
          <span class="card-mode">{{ report.mode }}</span>
          <span class="card-time">{{ formatRelativeTime(report.startedAt) }}</span>
        </div>

        <div v-if="report.topic" class="card-topic">{{ report.topic }}</div>

        <div class="card-stats">
          <span class="stat promoted">+{{ report.promotedCount }}</span>
          <span class="stat rejected">-{{ report.rejectedCount }}</span>
          <span class="stat total">{{ report.candidateCount }} candidates</span>
        </div>

        <p v-if="report.llmReason" class="card-reason">{{ truncate(report.llmReason, 120) }}</p>
      </div>

      <div v-if="store.total > pageSize" class="pagination-wrap">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="store.total"
          layout="prev, pager, next"
          small
          @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- Detail drawer -->
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

function formatRelativeTime(isoStr: string) {
  if (!isoStr) return ''
  const d = new Date(isoStr)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffH = Math.floor(diffMs / 3600000)
  if (diffH < 1) return 'just now'
  if (diffH < 24) return `${diffH}h ago`
  const diffD = Math.floor(diffH / 24)
  if (diffD < 7) return `${diffD}d ago`
  return d.toLocaleDateString()
}

function truncate(str: string, max: number) {
  return str.length > max ? str.slice(0, max) + '...' : str
}
</script>

<style scoped>
.dream-timeline {
  margin-top: 8px;
}
.timeline-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dream-card {
  padding: 16px 20px;
  border-radius: 12px;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.dream-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.06);
}
.dream-card.selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info-light-3);
}
.status-dot.success { background: var(--el-color-success); }
.status-dot.failed { background: var(--el-color-danger); }
.status-dot.skipped { background: var(--el-color-warning); }
.card-mode {
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-size: 11px;
}
.card-time { margin-left: auto; }
.card-topic {
  margin-top: 8px;
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.card-stats {
  margin-top: 8px;
  display: flex;
  gap: 12px;
  font-size: 13px;
}
.stat.promoted { color: var(--el-color-success); font-weight: 600; }
.stat.rejected { color: var(--el-color-danger-light-3); }
.stat.total { color: var(--el-text-color-secondary); }
.card-reason {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
.loading-state {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.skeleton-card {
  padding: 16px 20px;
  border-radius: 12px;
}
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px 0;
  color: var(--el-text-color-placeholder);
}
.empty-icon { font-size: 32px; margin-bottom: 12px; }
</style>
