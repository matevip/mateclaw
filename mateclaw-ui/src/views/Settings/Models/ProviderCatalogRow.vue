<template>
  <div class="catalog-row">
    <img
      class="catalog-row__icon"
      :src="iconUrl"
      :alt="provider.name"
      @error="onIconError"
    />
    <div class="catalog-row__meta">
      <div class="catalog-row__name">{{ provider.name }}</div>
      <div class="catalog-row__id">{{ provider.id }}</div>
    </div>
    <button
      v-if="!provider.enabled"
      class="catalog-row__cta"
      :disabled="busy"
      @click="$emit('enable', provider)"
    >
      {{ busy ? t('common.loading') : t('settings.model.enable') }}
    </button>
    <span v-else class="catalog-row__badge">{{ t('settings.model.alreadyEnabled') }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'

const props = defineProps<{
  provider: ProviderInfo
  togglingId: string | null
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
}>()

defineEmits<{
  enable: [provider: ProviderInfo]
}>()

const { t } = useI18n()

const iconUrl = computed(() => props.getProviderIcon(props.provider.id))
const busy = computed(() => props.togglingId === props.provider.id)
</script>

<style scoped>
.catalog-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  background: transparent;
  transition: background 0.18s ease;
  cursor: default;
}
.catalog-row + .catalog-row {
  border-top: 1px solid rgba(123, 88, 67, 0.08);
}
.catalog-row:hover {
  background: rgba(255, 255, 255, 0.5);
}
:global(html.dark .catalog-row + .catalog-row) {
  /* Slightly more visible hairline so rows don't melt into one solid block. */
  border-top-color: rgba(255, 255, 255, 0.07);
}
:global(html.dark .catalog-row:hover) {
  /* Bumped from 0.04 — pointer feedback was too subtle on dark surfaces. */
  background: rgba(255, 255, 255, 0.06);
}

.catalog-row__icon {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 8px;
  object-fit: contain;
}
/*
 * In dark mode, monochrome dark-foreground logos (OpenRouter, Zhipu, …)
 * disappear against the panel. Tile them on a cream surface — same trick
 * .provider-icon-shell uses on the card itself, so the visual language
 * stays consistent across drawer rows and cards.
 * `padding + object-fit: contain` shrinks the logo into the tile without
 * cropping; box-sizing: border-box keeps the cell at 32×32.
 */
:global(html.dark .catalog-row__icon) {
  background: linear-gradient(180deg, #ffffff, #f5ede6);
  border: 1px solid rgba(255, 248, 241, 0.18);
  padding: 3px;
  box-sizing: border-box;
}
.catalog-row__meta {
  flex: 1;
  min-width: 0;
}
.catalog-row__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  letter-spacing: -0.005em;
}
.catalog-row__id {
  margin-top: 2px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.catalog-row__cta {
  flex-shrink: 0;
  padding: 7px 16px;
  border-radius: 999px;
  border: 0;
  cursor: pointer;
  background: var(--mc-primary);
  color: white;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.005em;
  box-shadow: 0 1px 2px rgba(217, 119, 87, 0.24);
  transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}
.catalog-row__cta:hover:not(:disabled) {
  background: var(--mc-primary-hover, var(--mc-primary));
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(217, 119, 87, 0.28);
}
.catalog-row__cta:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: 0 1px 2px rgba(217, 119, 87, 0.24);
}
.catalog-row__cta:disabled {
  opacity: 0.6;
  cursor: wait;
}

.catalog-row__badge {
  flex-shrink: 0;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-tertiary);
  font-size: 12px;
  font-weight: 500;
}
:global(html.dark .catalog-row__badge) {
  background: rgba(255, 255, 255, 0.06);
}
</style>
