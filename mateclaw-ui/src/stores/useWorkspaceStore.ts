import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { workspaceTeamApi } from '@/api/index'

export interface Workspace {
  id: number
  name: string
  slug: string
  description?: string
  ownerId?: number
  settingsJson?: string
  createTime?: string
  updateTime?: string
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const currentWorkspaceId = ref<number | null>(
    Number(localStorage.getItem('mc-workspace-id')) || null
  )
  const loading = ref(false)

  const currentWorkspace = computed(() =>
    workspaces.value.find((ws) => ws.id === currentWorkspaceId.value) || workspaces.value[0] || null
  )

  async function fetchWorkspaces() {
    loading.value = true
    try {
      const res: any = await workspaceTeamApi.list()
      workspaces.value = res.data || []
      // If no workspace selected or selected workspace not in list, default to first
      if (
        !currentWorkspaceId.value ||
        !workspaces.value.find((ws) => ws.id === currentWorkspaceId.value)
      ) {
        if (workspaces.value.length > 0) {
          switchWorkspace(workspaces.value[0].id)
        }
      }
    } catch (e) {
      console.warn('Failed to fetch workspaces:', e)
    } finally {
      loading.value = false
    }
  }

  function switchWorkspace(id: number) {
    currentWorkspaceId.value = id
    localStorage.setItem('mc-workspace-id', String(id))
  }

  return {
    workspaces,
    currentWorkspaceId,
    currentWorkspace,
    loading,
    fetchWorkspaces,
    switchWorkspace,
  }
})
