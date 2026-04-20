import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/api'

export interface DreamReportItem {
  id: string
  agentId: number
  mode: string
  topic: string | null
  triggerSource: string
  triggeredBy: string
  startedAt: string
  finishedAt: string
  candidateCount: number
  promotedCount: number
  rejectedCount: number
  memoryDiff: string | null
  llmReason: string | null
  status: string
  errorMessage: string | null
}

export const useMemoryStore = defineStore('memory', () => {
  const reports = ref<DreamReportItem[]>([])
  const total = ref(0)
  const loading = ref(false)
  const currentReport = ref<DreamReportItem | null>(null)

  async function fetchReports(agentId: number, page = 1, size = 20) {
    loading.value = true
    try {
      const res = await http.get(`/memory/${agentId}/dream/reports`, {
        params: { page, size },
      })
      reports.value = res.data.records || []
      total.value = res.data.total || 0
    } finally {
      loading.value = false
    }
  }

  async function fetchReport(agentId: number, reportId: string) {
    loading.value = true
    try {
      const res = await http.get(`/memory/${agentId}/dream/reports/${reportId}`)
      currentReport.value = res.data
    } finally {
      loading.value = false
    }
  }

  return { reports, total, loading, currentReport, fetchReports, fetchReport }
})
