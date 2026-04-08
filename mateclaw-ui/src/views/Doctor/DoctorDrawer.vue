<template>
  <Teleport to="body">
    <div v-if="visible" class="drawer-overlay" @click.self="emit('close')">
      <div class="drawer-panel">
        <div class="drawer-header">
          <h2 class="drawer-title">{{ t('doctor.title') }}</h2>
          <button class="drawer-close" @click="emit('close')">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        <!-- Overall status banner -->
        <div class="status-banner" :class="health?.overall || 'loading'">
          <span class="status-dot"></span>
          <span v-if="loading">{{ t('doctor.checking') }}</span>
          <span v-else-if="health?.overall === 'healthy'">{{ t('doctor.allGood') }}</span>
          <span v-else-if="health?.overall === 'warning'">{{ t('doctor.hasWarnings', { count: warningCount }) }}</span>
          <span v-else-if="health?.overall === 'error'">{{ t('doctor.hasErrors', { count: errorCount }) }}</span>
        </div>

        <!-- Check list -->
        <div class="check-list">
          <div v-for="check in health?.checks" :key="check.name" class="check-item">
            <span class="check-dot" :class="check.status"></span>
            <div class="check-info">
              <div class="check-name">{{ check.name }}</div>
              <div class="check-message">{{ check.message }}</div>
            </div>
            <router-link
              v-if="check.action && check.status !== 'healthy'"
              :to="check.action.route"
              class="check-action"
              @click="emit('close')"
            >
              {{ check.action.label }}
            </router-link>
          </div>
        </div>

        <!-- Refresh -->
        <div class="drawer-footer">
          <button class="btn-secondary" @click="fetchHealth" :disabled="loading">
            {{ loading ? t('doctor.checking') : t('doctor.refresh') }}
          </button>
          <span v-if="lastChecked" class="last-checked">{{ t('doctor.lastChecked', { time: lastCheckedText }) }}</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { http } from '@/api/index'

interface HealthAction { label: string; route: string }
interface HealthCheck { name: string; status: string; message: string; action?: HealthAction }
interface HealthResponse { overall: string; checks: HealthCheck[] }

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'status', overall: string): void }>()
const { t } = useI18n()

const health = ref<HealthResponse | null>(null)
const loading = ref(false)
const lastChecked = ref<Date | null>(null)

const warningCount = computed(() => health.value?.checks.filter(c => c.status === 'warning').length || 0)
const errorCount = computed(() => health.value?.checks.filter(c => c.status === 'error').length || 0)
const lastCheckedText = computed(() => {
  if (!lastChecked.value) return ''
  const secs = Math.floor((Date.now() - lastChecked.value.getTime()) / 1000)
  if (secs < 60) return `${secs}s`
  return `${Math.floor(secs / 60)}m`
})

async function fetchHealth() {
  loading.value = true
  try {
    const res: any = await http.get('/system/health')
    health.value = res.data || res
    lastChecked.value = new Date()
    emit('status', health.value?.overall || 'healthy')
  } catch (e) {
    console.error('Health check failed', e)
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) fetchHealth()
})
</script>

<style scoped>
.drawer-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 1500; display: flex; justify-content: flex-end; }
.drawer-panel { width: 400px; max-width: 90vw; height: 100%; background: var(--mc-bg-elevated); border-left: 1px solid var(--mc-border); display: flex; flex-direction: column; animation: slide-in 0.2s ease; }
@keyframes slide-in { from { transform: translateX(100%); } to { transform: translateX(0); } }

.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.drawer-title { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.drawer-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.drawer-close:hover { background: var(--mc-bg-sunken); }

.status-banner { display: flex; align-items: center; gap: 10px; padding: 12px 24px; font-size: 14px; font-weight: 500; }
.status-banner.healthy { color: var(--mc-success); background: rgba(90,138,90,0.08); }
.status-banner.warning { color: var(--mc-primary); background: var(--mc-primary-bg); }
.status-banner.error { color: var(--mc-danger); background: var(--mc-danger-bg); }
.status-banner.loading { color: var(--mc-text-secondary); background: var(--mc-bg-sunken); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; flex-shrink: 0; }

.check-list { flex: 1; overflow-y: auto; padding: 16px 24px; display: flex; flex-direction: column; gap: 12px; }
.check-item { display: flex; align-items: flex-start; gap: 12px; padding: 12px; background: var(--mc-bg); border-radius: 8px; border: 1px solid var(--mc-border-light); }
.check-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; flex-shrink: 0; }
.check-dot.healthy { background: var(--mc-success); }
.check-dot.warning { background: var(--mc-primary); }
.check-dot.error { background: var(--mc-danger); }
.check-info { flex: 1; min-width: 0; }
.check-name { font-size: 13px; font-weight: 500; color: var(--mc-text-primary); }
.check-message { font-size: 12px; color: var(--mc-text-secondary); margin-top: 2px; }
.check-action { font-size: 12px; color: var(--mc-primary); text-decoration: none; white-space: nowrap; padding: 4px 10px; border: 1px solid var(--mc-primary); border-radius: 6px; flex-shrink: 0; }
.check-action:hover { background: var(--mc-primary-bg); }

.drawer-footer { padding: 16px 24px; border-top: 1px solid var(--mc-border-light); display: flex; align-items: center; gap: 12px; }
.btn-secondary { padding: 6px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 6px; font-size: 13px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }
.last-checked { font-size: 11px; color: var(--mc-text-tertiary); }
</style>
