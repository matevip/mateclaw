<template>
  <div class="path-select">
    <div
      class="path-card"
      @click="emit('select', 'local')"
    >
      <div class="path-icon">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="4" y="2" width="16" height="20" rx="2" />
          <line x1="8" y1="6" x2="16" y2="6" />
          <line x1="8" y1="10" x2="16" y2="10" />
          <circle cx="12" cy="16" r="2" />
        </svg>
      </div>
      <div class="path-info">
        <div class="path-title-row">
          <span class="path-title">{{ t('onboarding.localTitle') }}</span>
          <span v-if="ollamaOnline" class="detected-badge">{{ t('onboarding.localDetected') }}</span>
        </div>
        <p class="path-desc">{{ t('onboarding.localDesc') }}</p>
      </div>
    </div>

    <div
      class="path-card"
      @click="emit('select', 'cloud')"
    >
      <div class="path-icon">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z" />
        </svg>
      </div>
      <div class="path-info">
        <span class="path-title">{{ t('onboarding.cloudTitle') }}</span>
        <p class="path-desc">{{ t('onboarding.cloudDesc') }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{ ollamaOnline: boolean }>()
const emit = defineEmits<{ (e: 'select', path: 'local' | 'cloud'): void }>()
const { t } = useI18n()
</script>

<style scoped>
.path-select {
  display: flex;
  gap: 16px;
}

.path-card {
  flex: 1;
  padding: 24px;
  border: 2px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.path-card:hover {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.path-icon {
  color: var(--mc-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.path-card:hover .path-icon {
  color: var(--mc-primary);
}

.path-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.path-title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.path-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.detected-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--mc-success);
  color: var(--mc-text-inverse);
  font-weight: 500;
}

.path-desc {
  font-size: 13px;
  color: var(--mc-text-secondary);
  margin: 0;
  line-height: 1.5;
}
</style>
