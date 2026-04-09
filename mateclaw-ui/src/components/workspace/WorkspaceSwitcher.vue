<template>
  <div class="workspace-switcher" :class="{ collapsed }">
    <el-dropdown
      v-if="workspaces.length > 0"
      trigger="click"
      :teleported="true"
      @command="onSwitch"
    >
      <button class="ws-trigger" :title="collapsed ? currentLabel : ''">
        <span class="ws-icon">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="7" width="20" height="14" rx="2" ry="2"/>
            <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
          </svg>
        </span>
        <span v-if="!collapsed" class="ws-name">{{ currentLabel }}</span>
        <svg v-if="!collapsed" class="ws-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="ws in workspaces"
            :key="ws.id"
            :command="ws.id"
            :class="{ 'is-active': ws.id === currentWorkspaceId }"
          >
            <span class="ws-menu-icon">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="7" width="20" height="14" rx="2" ry="2"/>
                <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
              </svg>
            </span>
            {{ ws.name }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

defineProps<{
  collapsed?: boolean
}>()

const store = useWorkspaceStore()
const workspaces = computed(() => store.workspaces)
const currentWorkspaceId = computed(() => store.currentWorkspaceId)
const currentLabel = computed(() => store.currentWorkspace?.name || 'Workspace')

onMounted(() => {
  store.fetchWorkspaces()
})

function onSwitch(id: number) {
  store.switchWorkspace(id)
  // Reload current page data by emitting event
  window.dispatchEvent(new CustomEvent('workspace-changed', { detail: { workspaceId: id } }))
}
</script>

<style scoped>
.workspace-switcher {
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.workspace-switcher.collapsed {
  padding: 8px 4px;
  display: flex;
  justify-content: center;
}

.ws-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 6px;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-text-color-primary, #303133);
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.2s;
}

.ws-trigger:hover {
  background: var(--el-fill-color, #f0f2f5);
}

.collapsed .ws-trigger {
  width: 36px;
  height: 36px;
  padding: 0;
  justify-content: center;
}

.ws-icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  opacity: 0.7;
}

.ws-name {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-chevron {
  flex-shrink: 0;
  opacity: 0.5;
}

.ws-menu-icon {
  display: inline-flex;
  margin-right: 6px;
  opacity: 0.6;
}

:deep(.is-active) {
  color: var(--el-color-primary);
  font-weight: 600;
}
</style>
