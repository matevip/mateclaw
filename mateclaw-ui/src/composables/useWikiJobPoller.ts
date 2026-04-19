import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import { wikiApi } from '@/api/index'

export interface WikiProcessingJob {
  id: number
  kbId: number
  rawId: number
  jobType: string
  stage: string
  status: string
  primaryModelId: number | null
  currentModelId: number | null
  currentModelName?: string
  errorCode: string | null
  errorMessage: string | null
  retryCount: number
  startedAt: string | null
  finishedAt: string | null
  done?: number
  total?: number
}

/**
 * RFC-033: Polls the latest processing job for a given raw material.
 * Stops polling when the job reaches a terminal status.
 */
export function useWikiJobPoller(kbId: Ref<number | null>, rawId: Ref<number | null>) {
  const job = ref<WikiProcessingJob | null>(null)
  let timer: ReturnType<typeof setTimeout> | null = null

  const poll = async () => {
    if (!kbId.value || !rawId.value) return
    try {
      const jobs: any = await wikiApi.getWikiJobs(kbId.value, rawId.value)
      const list = jobs.data || jobs || []
      job.value = list[0] ?? null
      if (job.value && (job.value.status === 'running' || job.value.status === 'queued')) {
        timer = setTimeout(poll, 3000)
      }
    } catch {
      job.value = null
    }
  }

  onMounted(poll)
  onUnmounted(() => {
    if (timer) clearTimeout(timer)
  })

  return { job, refresh: poll }
}
