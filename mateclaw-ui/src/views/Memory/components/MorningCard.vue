<template>
  <transition name="slide-down">
    <div v-if="card" class="morning-card">
      <div class="card-header">
        <span class="card-icon">🌅</span>
        <span class="card-title">{{ t('memory.morningCard.title') }}</span>
        <el-button text size="small" @click="dismiss">{{ t('memory.morningCard.dismiss') }}</el-button>
      </div>
      <div class="card-body">
        <el-tag :type="card.mode === 'FOCUSED' ? 'warning' : 'info'" size="small">{{ card.mode }}</el-tag>
        <span v-if="card.topic" class="card-topic">{{ card.topic }}</span>
        <p class="card-summary">
          {{ t('memory.morningCard.promoted', { count: card.promotedCount }) }}
          <span v-if="card.llmReason" class="card-reason">— {{ truncate(card.llmReason, 100) }}</span>
        </p>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { http } from '@/api'

const props = defineProps<{ agentId: number }>()
const { t } = useI18n()
const card = ref<any>(null)

onMounted(async () => {
  try {
    const res = await http.get(`/memory/${props.agentId}/dream/morning-card`)
    card.value = res.data
  } catch {
    // No card or error — silent
  }
})

async function dismiss() {
  if (!card.value) return
  try {
    await http.post(`/memory/${props.agentId}/dream/morning-card/seen`, {
      reportId: card.value.reportId,
    })
  } catch {
    // Best effort
  }
  card.value = null
}

function truncate(str: string, max: number) {
  return str && str.length > max ? str.slice(0, max) + '...' : str
}
</script>

<style scoped>
.morning-card {
  margin: 12px 0;
  padding: 12px 16px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-icon { font-size: 18px; }
.card-title { font-weight: 600; font-size: 14px; flex: 1; }
.card-body {
  margin-top: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.card-topic {
  margin-left: 8px;
  font-weight: 500;
}
.card-summary { margin: 4px 0 0; }
.card-reason { color: var(--el-text-color-secondary); font-style: italic; }

.slide-down-enter-active, .slide-down-leave-active {
  transition: all 0.3s ease;
}
.slide-down-enter-from, .slide-down-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}
</style>
