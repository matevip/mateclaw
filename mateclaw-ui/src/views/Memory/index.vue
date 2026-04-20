<template>
  <div class="memory-view">
    <div class="memory-header">
      <h2>{{ t('memory.title') }}</h2>
      <el-select v-model="selectedAgentId" :placeholder="t('memory.selectAgent')" style="width: 200px" @change="onAgentChange">
        <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
      </el-select>
    </div>

    <el-tabs v-model="activeTab" class="memory-tabs">
      <el-tab-pane :label="t('memory.tabTimeline')" name="timeline">
        <DreamTimeline v-if="selectedAgentId" :agent-id="selectedAgentId" />
        <el-empty v-else :description="t('memory.selectAgentHint')" />
      </el-tab-pane>
      <el-tab-pane :label="t('memory.tabMemory')" name="memory" disabled>
        <el-empty description="Phase 2b" />
      </el-tab-pane>
      <el-tab-pane :label="t('memory.tabProfile')" name="profile" disabled>
        <el-empty description="Phase 2b" />
      </el-tab-pane>
      <el-tab-pane :label="t('memory.tabFacts')" name="facts" disabled>
        <el-empty description="Phase 3" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/useAgentStore'
import DreamTimeline from './components/DreamTimeline.vue'

const { t } = useI18n()
const agentStore = useAgentStore()
const agents = ref<any[]>([])
const selectedAgentId = ref<number | null>(null)
const activeTab = ref('timeline')

onMounted(async () => {
  await agentStore.fetchAgents()
  agents.value = agentStore.agents
  if (agents.value.length > 0) {
    selectedAgentId.value = agents.value[0].id
  }
})

function onAgentChange() {
  // DreamTimeline watches agentId prop
}
</script>

<style scoped>
.memory-view {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}
.memory-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.memory-header h2 {
  margin: 0;
  font-size: 20px;
}
.memory-tabs {
  margin-top: 10px;
}
</style>
