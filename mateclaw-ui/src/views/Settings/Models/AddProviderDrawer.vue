<template>
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="visible" class="drawer-overlay" @click.self="$emit('close')">
        <div class="drawer-panel">
          <div class="drawer-header">
            <div>
              <h3 class="drawer-title">{{ t('settings.model.addProviderDrawerTitle') }}</h3>
              <p class="drawer-subtitle">{{ t('settings.model.addProviderDrawerSubtitle') }}</p>
            </div>
            <button class="drawer-close" :title="t('common.close')" @click="$emit('close')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="drawer-content">
            <!-- Cloud providers -->
            <section v-if="cloudGroup.length" class="drawer-group">
              <h4 class="drawer-group-title">{{ t('settings.model.cloudProviders') }}</h4>
              <div class="provider-row" v-for="p in cloudGroup" :key="p.id">
                <img class="row-icon" :src="getProviderIcon(p.id)" :alt="p.name" @error="onIconError" />
                <div class="row-meta">
                  <div class="row-name">{{ p.name }}</div>
                  <div class="row-id">{{ p.id }}</div>
                </div>
                <button
                  v-if="!p.enabled"
                  class="row-cta"
                  :disabled="togglingId === p.id"
                  @click="onEnable(p)"
                >
                  {{ togglingId === p.id ? t('common.loading') : t('settings.model.enable') }}
                </button>
                <span v-else class="row-enabled-badge">{{ t('settings.model.alreadyEnabled') }}</span>
              </div>
            </section>

            <!-- Local providers -->
            <section v-if="localGroup.length" class="drawer-group">
              <h4 class="drawer-group-title">{{ t('settings.model.localProviders') }}</h4>
              <div class="provider-row" v-for="p in localGroup" :key="p.id">
                <img class="row-icon" :src="getProviderIcon(p.id)" :alt="p.name" @error="onIconError" />
                <div class="row-meta">
                  <div class="row-name">{{ p.name }}</div>
                  <div class="row-id">{{ p.id }}</div>
                </div>
                <button
                  v-if="!p.enabled"
                  class="row-cta"
                  :disabled="togglingId === p.id"
                  @click="onEnable(p)"
                >
                  {{ togglingId === p.id ? t('common.loading') : t('settings.model.enable') }}
                </button>
                <span v-else class="row-enabled-badge">{{ t('settings.model.alreadyEnabled') }}</span>
              </div>
            </section>

            <div v-if="!cloudGroup.length && !localGroup.length" class="drawer-empty">
              {{ t('settings.model.catalogEmpty') }}
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { ProviderInfo } from '@/types'

const props = defineProps<{
  visible: boolean
  catalog: ProviderInfo[]
  togglingId: string | null
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
  enableProvider: (id: string) => Promise<unknown>
}>()

const emit = defineEmits<{
  close: []
}>()

const { t } = useI18n()

// Sort: enabled rows sink to the bottom of each group so the actionable
// (still-disabled) options sit at the top where the user lands.
const cloudGroup = computed(() =>
  props.catalog
    .filter(p => !p.isLocal)
    .sort((a, b) => Number(!!a.enabled) - Number(!!b.enabled) || a.name.localeCompare(b.name))
)
const localGroup = computed(() =>
  props.catalog
    .filter(p => p.isLocal)
    .sort((a, b) => Number(!!a.enabled) - Number(!!b.enabled) || a.name.localeCompare(b.name))
)

async function onEnable(p: ProviderInfo) {
  await props.enableProvider(p.id)
  ElMessage.success(t('settings.model.enabledToast', { name: p.name }))
}
</script>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 1500;
  display: flex;
  justify-content: flex-end;
}
.drawer-panel {
  width: 460px;
  max-width: 92vw;
  height: 100%;
  background: var(--mc-bg-elevated);
  border-left: 1px solid var(--mc-border);
  display: flex;
  flex-direction: column;
  animation: drawer-slide 0.22s ease;
}
@keyframes drawer-slide {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--mc-border-light);
}
.drawer-title {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.drawer-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--mc-text-tertiary);
}
.drawer-close {
  background: transparent;
  border: 0;
  padding: 6px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  flex-shrink: 0;
}
.drawer-close:hover { background: var(--mc-bg-sunken); }
.drawer-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px 32px;
}
.drawer-group + .drawer-group { margin-top: 28px; }
.drawer-group-title {
  margin: 0 0 10px;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--mc-text-tertiary);
}
.provider-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
}
.provider-row + .provider-row { margin-top: 8px; }
.row-icon { width: 32px; height: 32px; flex-shrink: 0; border-radius: 8px; object-fit: contain; }
.row-meta { flex: 1; min-width: 0; }
.row-name { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.row-id { margin-top: 2px; font-size: 12px; color: var(--mc-text-tertiary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.row-cta {
  flex-shrink: 0;
  padding: 7px 14px;
  border-radius: 8px;
  border: 0;
  cursor: pointer;
  background: var(--mc-primary);
  color: white;
  font-size: 13px;
  font-weight: 600;
  transition: background 0.15s;
}
.row-cta:hover:not(:disabled) { background: var(--mc-primary-hover, var(--mc-primary)); }
.row-cta:disabled { opacity: 0.6; cursor: wait; }
.row-enabled-badge {
  flex-shrink: 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  font-size: 12px;
}
.drawer-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--mc-text-tertiary);
}

.drawer-fade-enter-active, .drawer-fade-leave-active {
  transition: opacity 0.18s ease;
}
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }

/* Mobile: full-screen sheet that slides up. */
@media (max-width: 768px) {
  .drawer-overlay { justify-content: stretch; }
  .drawer-panel {
    width: 100%;
    max-width: 100%;
    border-left: 0;
    animation: drawer-slide-up 0.22s ease;
  }
  @keyframes drawer-slide-up {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }
}
</style>
