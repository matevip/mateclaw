<template>
  <div class="recoverable-banner" role="status">
    <span class="recoverable-banner__icon">⚠️</span>
    <span class="recoverable-banner__text">
      {{ $t('chat.recoverableBanner.message', { name: providerName, fallback: fallbackName }) }}
    </span>
    <button class="recoverable-banner__dismiss" type="button" @click="$emit('dismiss')">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
        <line x1="18" y1="6" x2="6" y2="18"/>
        <line x1="6" y1="6" x2="18" y2="18"/>
      </svg>
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * Issue #81: shown when the active provider is unhealthy but the chain walker
 * can fall back to a LIVE one. Non-blocking — input stays enabled, send still
 * works; this is just a heads-up.
 */
defineProps<{
  providerName: string
  fallbackName: string
}>()

defineEmits<{
  dismiss: []
}>()
</script>

<style scoped>
.recoverable-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 16px 0;
  padding: 8px 12px;
  border: 1px solid rgba(245, 158, 11, 0.35);
  background: rgba(245, 158, 11, 0.10);
  border-radius: 10px;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.4;
}

.recoverable-banner__icon {
  flex-shrink: 0;
  font-size: 14px;
}

.recoverable-banner__text {
  flex: 1;
  min-width: 0;
}

.recoverable-banner__dismiss {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}
.recoverable-banner__dismiss:hover {
  background: rgba(0, 0, 0, 0.06);
  color: var(--mc-text-primary);
}
.dark .recoverable-banner__dismiss:hover {
  background: rgba(255, 255, 255, 0.08);
}
</style>
