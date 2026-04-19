<template>
  <div class="job-stage-bar">
    <div class="stage-dots">
      <div
        v-for="(stage, idx) in stages"
        :key="stage.key"
        class="stage-dot-group"
      >
        <div
          class="stage-dot"
          :class="dotClass(stage.key)"
          :title="stage.key === currentStage && errorCode ? `${errorCode}: ${errorMessage}` : ''"
        />
        <span v-if="idx < stages.length - 1" class="stage-line" :class="{ done: isStageComplete(stage.key) }" />
      </div>
    </div>
    <div class="stage-labels">
      <span v-for="stage in stages" :key="stage.key" class="stage-label" :class="{ active: stage.key === currentStage }">
        {{ t(`wiki.jobStage.${stage.key}`) }}
      </span>
    </div>

    <!-- Model & progress info -->
    <div class="stage-info">
      <span v-if="currentModel" class="info-model">
        {{ t('wiki.jobInfo.currentModel') }}: {{ currentModel }}
      </span>
      <span v-if="isFallbackActive" class="info-fallback">
        ⚡ {{ t('wiki.jobInfo.fallbackActive') }}
      </span>
      <span v-if="pagesProgress" class="info-pages">
        {{ pagesProgress }}
      </span>
      <span v-if="elapsed" class="info-elapsed">
        {{ t('wiki.jobInfo.elapsedTime') }}: {{ elapsed }}
      </span>
    </div>

    <!-- Error state with actions -->
    <div v-if="status === 'failed'" class="stage-error">
      <span class="error-badge">❌ {{ t('wiki.jobStage.failed') }} — {{ errorCode }}</span>
      <div class="error-actions">
        <button class="btn-mini" @click="$emit('reprocess')">{{ t('wiki.reprocess') }}</button>
        <button class="btn-mini btn-mini-alt" @click="$emit('repair')">{{ t('wiki.page.repair') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  stage: string
  status: string
  currentModel?: string
  isFallbackActive?: boolean
  errorCode?: string
  errorMessage?: string
  done?: number
  total?: number
  startedAt?: string
}>()

defineEmits<{
  reprocess: []
  repair: []
}>()

const stages = [
  { key: 'queued' },
  { key: 'routing' },
  { key: 'phase_a_running' },
  { key: 'phase_b_running' },
  { key: 'enriching' },
  { key: 'embedding' },
  { key: 'completed' },
]

const stageOrder = stages.map(s => s.key)
const currentStage = computed(() => props.stage)

function stageIndex(key: string): number {
  return stageOrder.indexOf(key)
}

function isStageComplete(key: string): boolean {
  const cur = stageIndex(currentStage.value)
  const target = stageIndex(key)
  return target < cur
}

function dotClass(key: string) {
  const cur = stageIndex(currentStage.value)
  const target = stageIndex(key)
  if (props.status === 'failed' && key === currentStage.value) return 'failed'
  if (target < cur) return 'done'
  if (target === cur) return 'active'
  return 'pending'
}

const pagesProgress = computed(() => {
  if (props.total && props.total > 0) {
    return t('wiki.jobInfo.pagesProgress', { done: props.done ?? 0, total: props.total })
  }
  return ''
})

const elapsed = computed(() => {
  if (!props.startedAt) return ''
  const start = new Date(props.startedAt).getTime()
  const now = Date.now()
  const sec = Math.floor((now - start) / 1000)
  if (sec < 60) return `${sec}s`
  return `${Math.floor(sec / 60)}m ${sec % 60}s`
})
</script>

<style scoped>
.job-stage-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
}

.stage-dots {
  display: flex;
  align-items: center;
  gap: 0;
}

.stage-dot-group {
  display: flex;
  align-items: center;
  flex: 1;
}

.stage-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: all 0.3s;
}

.stage-dot.done { background: var(--mc-primary); }
.stage-dot.active { background: var(--mc-primary); box-shadow: 0 0 0 3px rgba(217,119,87,0.25); animation: pulse 1.5s infinite; }
.stage-dot.pending { background: var(--mc-border); }
.stage-dot.failed { background: var(--mc-danger); box-shadow: 0 0 0 3px rgba(245,108,108,0.2); }

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(217,119,87,0.25); }
  50% { box-shadow: 0 0 0 5px rgba(217,119,87,0.1); }
}

.stage-line {
  flex: 1;
  height: 2px;
  background: var(--mc-border);
  margin: 0 2px;
  transition: background 0.3s;
}
.stage-line.done { background: var(--mc-primary); }

.stage-labels {
  display: flex;
  justify-content: space-between;
}
.stage-label {
  font-size: 9px;
  color: var(--mc-text-tertiary);
  text-align: center;
  flex: 1;
}
.stage-label.active { color: var(--mc-primary); font-weight: 600; }

.stage-info {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--mc-text-secondary);
}
.info-fallback { color: var(--mc-primary); font-weight: 500; }
.info-pages { font-variant-numeric: tabular-nums; }

.stage-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: var(--mc-danger-bg);
  border-radius: 8px;
}
.error-badge { font-size: 11px; color: var(--mc-danger); font-weight: 500; }
.error-actions { display: flex; gap: 6px; }
.btn-mini {
  padding: 3px 10px;
  font-size: 11px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  cursor: pointer;
}
.btn-mini:hover { background: var(--mc-bg-sunken); }
.btn-mini-alt { color: var(--mc-primary); border-color: var(--mc-primary); }
</style>
