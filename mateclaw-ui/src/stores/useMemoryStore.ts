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
  let eventSource: EventSource | null = null

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

  /**
   * Subscribe to dream SSE events for an agent.
   * Automatically refreshes the report list on new dream events.
   */
  function subscribeEvents(agentId: number) {
    unsubscribeEvents()
    const token = localStorage.getItem('token')
    const url = `/api/v1/memory/${agentId}/dream/events`
    eventSource = new EventSource(url)

    eventSource.addEventListener('dream.completed', (e) => {
      // Refresh the report list to show the new dream
      fetchReports(agentId, 1, 20)
    })

    eventSource.addEventListener('dream.failed', (e) => {
      fetchReports(agentId, 1, 20)
    })

    eventSource.onerror = () => {
      // Reconnect after 5s on error
      unsubscribeEvents()
      setTimeout(() => subscribeEvents(agentId), 5000)
    }
  }

  function unsubscribeEvents() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  return {
    reports, total, loading, currentReport,
    fetchReports, fetchReport,
    subscribeEvents, unsubscribeEvents,
  }
})
