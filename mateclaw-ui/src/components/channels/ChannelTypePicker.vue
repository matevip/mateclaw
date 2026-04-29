<template>
  <div v-if="modelValue" class="modal-overlay" @click.self="close">
    <div class="picker">
      <div class="picker-header">
        <h2 class="picker-title">{{ t('channels.newChannel') }}</h2>
        <button class="picker-close" @click="close" :title="t('common.cancel')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
      <div class="picker-grid">
        <button
          v-for="type in types"
          :key="type"
          class="picker-card"
          @click="pick(type)"
        >
          <img :src="`/icons/channels/${type}.svg`" :alt="type" class="picker-icon" />
          <span class="picker-name">{{ t(`channels.types.${type}`) }}</span>
        </button>
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

// Order matches the dropdown in ChannelEditModal so users see the same
// ordering. Web/WebChat/Webhook last because they're "platform" rather
// than "messaging service".
const types = [
  'telegram', 'discord', 'slack',
  'dingtalk', 'feishu', 'wecom', 'weixin', 'qq',
  'web', 'webchat', 'webhook',
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
.picker { background: var(--mc-bg-elevated); border-radius: 18px; width: 100%; max-width: 520px; max-height: 88vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.18); overflow: hidden; }
.picker-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.picker-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.picker-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 8px; }
.picker-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.picker-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 20px 24px; overflow-y: auto; }
.picker-card { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px 8px; background: var(--mc-bg-sunken); border: 1.5px solid transparent; border-radius: 12px; cursor: pointer; transition: all 0.15s; font-family: inherit; }
.picker-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg, rgba(217,119,87,0.06)); transform: translateY(-1px); }
.picker-icon { width: 36px; height: 36px; border-radius: 8px; }
.picker-name { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); text-align: center; }
</style>
