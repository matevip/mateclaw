<template>
  <div v-if="modelValue" class="modal-overlay" @click.self="close">
    <div class="picker">
      <div class="picker-header">
        <div>
          <h2 class="picker-title">{{ t('channels.newChannel') }}</h2>
          <p class="picker-subtitle">{{ t('channels.catalog.subtitle') }}</p>
        </div>
        <button class="picker-close" @click="close" :title="t('common.cancel')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <!-- Catalog grouped by category. The catalog only renders here in
           the picker — never on the main list page — so users browse the
           full surface area exactly when they're trying to add something. -->
      <div class="picker-scroll">
        <section v-for="group in groups" :key="group.key" class="picker-section">
          <h3 class="picker-section-title">{{ t(`channels.catalog.groups.${group.key}`) }}</h3>
          <div class="picker-grid">
            <button
              v-for="type in group.types"
              :key="type"
              class="picker-card"
              @click="pick(type)"
            >
              <img :src="`/icons/channels/${type}.svg`" :alt="type" class="picker-icon" />
              <div class="picker-text">
                <span class="picker-name">{{ t(`channels.types.${type}`) }}</span>
                <span class="picker-desc">{{ t(`channels.catalog.descriptions.${type}`) }}</span>
              </div>
            </button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** User picked a channel type — parent decides which UI to open. */
  pick: [channelType: string]
}>()

const { t } = useI18n()

// Three categories so the catalog reads as a curated shelf, not a flat
// dump. Order within each group is from "most common" to "edge" so first
// glance lands on the right thing.
const groups = [
  { key: 'im', types: ['telegram', 'discord', 'slack', 'qq'] },
  { key: 'enterprise', types: ['wecom', 'weixin', 'feishu', 'dingtalk'] },
  { key: 'web', types: ['web', 'webchat', 'webhook'] },
]

function pick(type: string) {
  emit('pick', type)
  emit('update:modelValue', false)
}
function close() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.picker { background: var(--mc-bg-elevated); border-radius: 18px; width: 100%; max-width: 640px; max-height: 88vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.18); overflow: hidden; }
.picker-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 22px 26px 16px; border-bottom: 1px solid var(--mc-border-light); }
.picker-title { font-size: 19px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.picker-subtitle { font-size: 13px; color: var(--mc-text-secondary); margin: 4px 0 0; line-height: 1.5; }
.picker-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 8px; flex-shrink: 0; }
.picker-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }

.picker-scroll { flex: 1; overflow-y: auto; padding: 18px 22px 22px; }
.picker-section { margin-top: 18px; }
.picker-section:first-child { margin-top: 4px; }
.picker-section-title { font-size: 12px; font-weight: 700; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.6px; margin: 0 4px 10px; }
.picker-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }

.picker-card { display: flex; align-items: flex-start; gap: 12px; padding: 12px 14px; background: var(--mc-bg-sunken); border: 1.5px solid transparent; border-radius: 12px; cursor: pointer; transition: all 0.15s; font-family: inherit; text-align: left; }
.picker-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg, rgba(217,119,87,0.06)); transform: translateY(-1px); }
.picker-icon { width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0; }
.picker-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.picker-name { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); line-height: 1.3; }
.picker-desc { font-size: 12px; color: var(--mc-text-secondary); line-height: 1.4; }
</style>
