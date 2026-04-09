<template>
  <div class="workspace-switcher" :class="{ collapsed }">
    <button
      class="ws-trigger"
      :title="collapsed ? currentLabel : ''"
      @click="open = !open"
    >
      <span class="ws-trigger__icon">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="7" width="20" height="14" rx="2" ry="2"/>
          <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
        </svg>
      </span>
      <template v-if="!collapsed">
        <span class="ws-trigger__name">{{ currentLabel }}</span>
        <svg class="ws-trigger__arrow" :class="{ open }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
      </template>
    </button>

    <Transition name="fade">
      <div v-if="open" class="ws-backdrop" @click="open = false"></div>
    </Transition>
    <Transition name="ws-dropdown">
      <div v-if="open" class="ws-dropdown">
        <div
          v-for="ws in workspaces"
          :key="ws.id"
          class="ws-item"
          :class="{ active: ws.id === currentWorkspaceId }"
          @click="onSelect(ws.id)"
        >
          <svg class="ws-item__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="7" width="20" height="14" rx="2" ry="2"/>
            <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>
          </svg>
          <span class="ws-item__name">{{ ws.name }}</span>
          <svg v-if="ws.id === currentWorkspaceId" class="ws-item__check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
        </div>
        <div class="ws-divider"></div>
        <div class="ws-item ws-item--manage" @click="onManage">
          <svg class="ws-item__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
          </svg>
          <span class="ws-item__name">{{ t('common.manageWorkspaces') }}</span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

defineProps<{
  collapsed?: boolean
}>()

const { t } = useI18n()
const store = useWorkspaceStore()
const router = useRouter()
const open = ref(false)
const workspaces = computed(() => store.workspaces)
const currentWorkspaceId = computed(() => store.currentWorkspaceId)
const currentLabel = computed(() => store.currentWorkspace?.name || 'Workspace')

onMounted(() => {
  store.fetchWorkspaces()
})

function onSelect(id: number) {
  open.value = false
  if (id !== currentWorkspaceId.value) {
    store.switchWorkspace(id)
  }
}

function onManage() {
  open.value = false
  router.push('/settings/workspaces')
}
</script>

<style scoped>
.workspace-switcher {
  padding: 8px 12px;
  position: relative;
}

.workspace-switcher.collapsed {
  padding: 8px 6px;
  display: flex;
  justify-content: center;
}

/* Trigger */
.ws-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--mc-sidebar-border, var(--mc-border-light));
  border-radius: 12px;
  background: var(--mc-sidebar-hover, rgba(0,0,0,0.04));
  color: var(--mc-sidebar-text, var(--mc-text-primary));
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: all 0.15s;
}

.ws-trigger:hover {
  background: var(--mc-sidebar-active, rgba(0,0,0,0.06));
  border-color: var(--mc-primary, #d96d46);
}

.collapsed .ws-trigger {
  width: 36px;
  height: 36px;
  padding: 0;
  justify-content: center;
  border-radius: 10px;
}

.ws-trigger__icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  color: var(--mc-accent, var(--mc-primary));
}

.ws-trigger__name {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  letter-spacing: -0.01em;
}

.ws-trigger__arrow {
  flex-shrink: 0;
  color: var(--mc-sidebar-text, var(--mc-text-tertiary));
  opacity: 0.5;
  transition: transform 0.2s;
}

.ws-trigger__arrow.open {
  transform: rotate(180deg);
}

/* Backdrop */
.ws-backdrop {
  position: fixed;
  inset: 0;
  z-index: 99;
}

/* Dropdown */
.ws-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 12px;
  right: 12px;
  z-index: 100;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  max-height: 280px;
  overflow-y: auto;
}

.collapsed .ws-dropdown {
  left: 6px;
  right: auto;
  min-width: 200px;
}

.ws-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.12s;
  font-size: 13px;
  color: var(--mc-text-primary);
}

.ws-item:hover {
  background: var(--mc-bg-sunken);
}

.ws-item.active {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  font-weight: 600;
}

.ws-item__icon {
  flex-shrink: 0;
  opacity: 0.5;
}

.ws-item.active .ws-item__icon {
  opacity: 1;
  color: var(--mc-primary);
}

.ws-item__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-item__check {
  flex-shrink: 0;
  color: var(--mc-primary);
}

.ws-divider {
  height: 1px;
  background: var(--mc-border-light);
  margin: 4px 8px;
}

.ws-item--manage {
  color: var(--mc-text-secondary);
}

.ws-item--manage:hover {
  color: var(--mc-text-primary);
}

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.ws-dropdown-enter-active { transition: all 0.15s ease-out; }
.ws-dropdown-leave-active { transition: all 0.1s ease-in; }
.ws-dropdown-enter-from { opacity: 0; transform: translateY(-6px) scale(0.97); }
.ws-dropdown-leave-to { opacity: 0; transform: translateY(-4px) scale(0.98); }
</style>
