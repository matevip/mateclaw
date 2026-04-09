<template>
  <div v-if="actions.length > 0" class="browser-timeline">
    <div class="bt-header" @click="expanded = !expanded">
      <span class="bt-icon">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2"/>
          <line x1="2" y1="9" x2="22" y2="9"/>
          <circle cx="6" cy="6" r="0.5" fill="currentColor"/>
          <circle cx="9" cy="6" r="0.5" fill="currentColor"/>
        </svg>
      </span>
      <span class="bt-title">{{ t('browser.timeline.title', { count: actions.length }) }}</span>
      <svg class="bt-chevron" :class="{ open: expanded }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </div>

    <Transition name="slide">
      <div v-if="expanded" class="bt-actions">
        <div
          v-for="(action, idx) in actions"
          :key="idx"
          class="bt-action"
          :class="{ 'bt-action--failed': !action.success }"
        >
          <span class="bt-action-dot" :class="actionClass(action.action)"></span>
          <span class="bt-action-label">{{ action.action }}</span>
          <span v-if="action.url" class="bt-action-url" :title="action.url">{{ truncateUrl(action.url) }}</span>
          <span v-if="action.title" class="bt-action-title">{{ action.title }}</span>
          <span v-if="action.durationMs > 0" class="bt-action-duration">{{ action.durationMs }}ms</span>
        </div>

        <!-- Screenshot preview -->
        <div v-if="latestScreenshot" class="bt-screenshot">
          <img :src="'data:image/png;base64,' + latestScreenshot" alt="Browser screenshot" />
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

export interface BrowserAction {
  action: string
  success: boolean
  url?: string
  title?: string
  screenshot?: string
  durationMs: number
  timestamp: number
}

const props = defineProps<{
  actions: BrowserAction[]
}>()

const expanded = ref(true)

const latestScreenshot = computed(() => {
  for (let i = props.actions.length - 1; i >= 0; i--) {
    if (props.actions[i].screenshot) {
      return props.actions[i].screenshot
    }
  }
  return null
})

function actionClass(action: string) {
  switch (action) {
    case 'start': return 'bt-dot--start'
    case 'stop': return 'bt-dot--stop'
    case 'open': return 'bt-dot--open'
    case 'click': return 'bt-dot--click'
    case 'type': return 'bt-dot--type'
    case 'screenshot': return 'bt-dot--screenshot'
    default: return ''
  }
}

function truncateUrl(url: string) {
  try {
    const u = new URL(url)
    const path = u.pathname.length > 30 ? u.pathname.slice(0, 30) + '...' : u.pathname
    return u.hostname + path
  } catch {
    return url.length > 50 ? url.slice(0, 50) + '...' : url
  }
}
</script>

<style scoped>
.browser-timeline {
  margin: 8px 0;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.bt-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--el-fill-color-light, #f5f7fa);
  cursor: pointer;
  user-select: none;
}

.bt-header:hover {
  background: var(--el-fill-color, #f0f2f5);
}

.bt-icon {
  display: flex;
  opacity: 0.6;
}

.bt-title {
  flex: 1;
  font-weight: 500;
  color: var(--el-text-color-regular, #606266);
}

.bt-chevron {
  transition: transform 0.2s;
  opacity: 0.5;
}

.bt-chevron.open {
  transform: rotate(180deg);
}

.bt-actions {
  padding: 4px 0;
}

.bt-action {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 16px;
  color: var(--el-text-color-regular, #606266);
}

.bt-action--failed {
  color: var(--el-color-danger, #f56c6c);
}

.bt-action-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--el-color-info, #909399);
}

.bt-dot--start { background: var(--el-color-success, #67c23a); }
.bt-dot--stop { background: var(--el-color-danger, #f56c6c); }
.bt-dot--open { background: var(--el-color-primary, #409eff); }
.bt-dot--click { background: var(--el-color-warning, #e6a23c); }
.bt-dot--type { background: #9b59b6; }
.bt-dot--screenshot { background: #1abc9c; }

.bt-action-label {
  font-weight: 500;
  min-width: 64px;
  font-family: monospace;
}

.bt-action-url {
  color: var(--el-color-primary, #409eff);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.bt-action-title {
  color: var(--el-text-color-secondary, #909399);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.bt-action-duration {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  flex-shrink: 0;
}

.bt-screenshot {
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-extra-light, #f2f6fc);
}

.bt-screenshot img {
  width: 100%;
  max-height: 300px;
  object-fit: contain;
  border-radius: 4px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.slide-enter-active,
.slide-leave-active {
  transition: all 0.2s ease;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  max-height: 0;
}
</style>
