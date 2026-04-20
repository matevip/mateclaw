<template>
  <div class="mc-page-shell memory-shell">
    <div class="mc-page-frame memory-frame">
      <div class="mc-page-inner memory-inner">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('memory.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('memory.title') }}</h1>
            <p class="mc-page-desc">{{ t('memory.desc') }}</p>
          </div>
          <el-select
            v-model="selectedAgentId"
            :placeholder="t('memory.selectAgent')"
            class="agent-selector"
            @change="onAgentChange"
          >
            <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
          </el-select>
        </div>

        <!-- Morning Card -->
        <MorningCard v-if="selectedAgentId" :agent-id="selectedAgentId" />

        <!-- Action bar -->
        <div v-if="selectedAgentId" class="memory-actions">
          <el-button size="small" type="primary" plain @click="showFocusedDialog = true">
            {{ t('memory.focused.btn') }}
          </el-button>
        </div>

        <!-- Tab navigation: minimal, no borders -->
        <div class="memory-nav">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="memory-nav-btn"
            :class="{ active: activeTab === tab.key, disabled: tab.disabled }"
            :disabled="tab.disabled"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

        <!-- Content -->
        <div class="memory-content">
          <DreamTimeline v-if="activeTab === 'timeline' && selectedAgentId" :agent-id="selectedAgentId" />
          <div v-else-if="!selectedAgentId" class="empty-state">
            <p>{{ t('memory.selectAgentHint') }}</p>
          </div>
          <div v-else class="empty-state">
            <p>Coming soon</p>
          </div>
        </div>

        <!-- Focused Dream Dialog -->
        <FocusedDreamDialog
          v-if="selectedAgentId"
          v-model="showFocusedDialog"
          :agent-id="selectedAgentId"
          @triggered="onDreamTriggered"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/useAgentStore'
import { useMemoryStore } from '@/stores/useMemoryStore'
import DreamTimeline from './components/DreamTimeline.vue'
import MorningCard from './components/MorningCard.vue'
import FocusedDreamDialog from './components/FocusedDreamDialog.vue'

const { t } = useI18n()
const agentStore = useAgentStore()
const memoryStore = useMemoryStore()
const agents = ref<any[]>([])
const selectedAgentId = ref<number | null>(null)
const activeTab = ref('timeline')
const showFocusedDialog = ref(false)

const tabs = computed(() => [
  { key: 'timeline', label: t('memory.tabTimeline'), disabled: false },
  { key: 'memory', label: t('memory.tabMemory'), disabled: true },
  { key: 'profile', label: t('memory.tabProfile'), disabled: true },
  { key: 'facts', label: t('memory.tabFacts'), disabled: true },
])

onMounted(async () => {
  await agentStore.fetchAgents()
  agents.value = agentStore.agents
  if (agents.value.length > 0) {
    selectedAgentId.value = agents.value[0].id
  }
})

// SSE subscription management
watch(selectedAgentId, (id) => {
  if (id) memoryStore.subscribeEvents(id)
  else memoryStore.unsubscribeEvents()
})

onUnmounted(() => {
  memoryStore.unsubscribeEvents()
})

function onAgentChange() {
  activeTab.value = 'timeline'
}

function onDreamTriggered() {
  // Refresh timeline after focused dream
  if (selectedAgentId.value) {
    memoryStore.fetchReports(selectedAgentId.value, 1, 20)
  }
}
</script>

<style scoped>
.memory-shell {
  --memory-max-width: 960px;
}
.memory-frame {
  max-width: var(--memory-max-width);
}
.agent-selector {
  width: 180px;
}

/* Minimal tab navigation — inspired by Apple's segment control */
.memory-nav {
  display: flex;
  gap: 2px;
  margin: 24px 0 16px;
  padding: 3px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  width: fit-content;
}
.memory-nav-btn {
  padding: 6px 16px;
  border: none;
  border-radius: 6px;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}
.memory-nav-btn:hover:not(.disabled) {
  color: var(--el-text-color-primary);
}
.memory-nav-btn.active {
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.memory-nav-btn.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.memory-content {
  min-height: 300px;
}
.memory-actions {
  margin: 16px 0 0;
}
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  color: var(--el-text-color-placeholder);
  font-size: 14px;
}
</style>
