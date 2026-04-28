<template>
  <div>
    <div class="provider-header">
      <div>
        <div class="provider-title-row">
          <span class="provider-icon-shell">
            <img
              :src="getProviderIcon(provider.id)"
              :alt="provider.name"
              class="provider-icon"
              @error="onIconError"
            />
          </span>
          <h3 class="provider-name">{{ provider.name }}</h3>
          <span class="provider-badge" :class="provider.isCustom ? 'custom' : 'builtin'">
            {{ provider.isCustom ? t('settings.model.custom') : t('settings.model.builtin') }}
          </span>
          <span v-if="isProviderActive(provider)" class="provider-badge active">
            {{ t('settings.model.active') }}
          </span>
          <!-- RFC-009 P3.5: surface failover priority so users can see at a glance
               which providers participate in the chain and in what order. -->
          <span
            v-if="provider.fallbackPriority && provider.fallbackPriority > 0"
            class="provider-badge fallback"
            :title="t('settings.model.fallbackBadgeTitle')"
          >
            {{ t('settings.model.fallbackBadge', { priority: provider.fallbackPriority }) }}
          </span>
          <!-- RFC-073: liveness badge. Single source of truth replacing the old
               configured / pool-entry combo. UNCONFIGURED renders no badge — the
               status pill on the right already says "needs configuration". -->
          <span
            v-if="provider.liveness === 'LIVE'"
            class="provider-badge pool-in"
            :title="t('settings.model.poolBadgeInPoolTitle')"
          >
            {{ t('settings.model.poolBadgeInPool') }}
          </span>
          <span
            v-else-if="provider.liveness === 'COOLDOWN'"
            class="provider-badge pool-cooldown"
            :title="t('settings.model.poolBadgeCooldownTitle', {
              seconds: Math.max(1, Math.ceil((provider.cooldownRemainingMs || 0) / 1000))
            })"
          >
            {{ t('settings.model.poolBadgeCooldown') }}
          </span>
          <span
            v-else-if="provider.liveness === 'REMOVED'"
            class="provider-badge pool-removed"
            :title="t('settings.model.poolBadgeRemovedTitle', {
              source: t('settings.model.poolSourceInitProbe'),
              message: provider.unavailableReason || '—'
            })"
          >
            {{ t('settings.model.poolBadgeRemoved') }}
          </span>
          <span
            v-else-if="provider.liveness === 'UNPROBED'"
            class="provider-badge pool-unprobed"
            :title="t('settings.model.livenessUnprobedTooltip')"
          >
            {{ t('settings.model.livenessUnprobed') }}
          </span>
        </div>
        <p class="provider-id">{{ provider.id }}</p>
      </div>
      <div class="provider-status" :class="providerStatus(provider).type">
        {{ providerStatus(provider).label }}
      </div>
    </div>

    <div class="provider-info">
      <div class="info-row">
        <span class="info-label">{{ t('settings.model.baseUrl') }}</span>
        <span class="info-value mono" :title="provider.baseUrl || ''">
          {{ provider.baseUrl || t('settings.model.notSet') }}
        </span>
      </div>
      <div v-if="provider.authType === 'oauth'" class="info-row">
        <span class="info-label">OAuth</span>
        <span class="info-value">
          <span v-if="provider.oauthConnected" class="oauth-card-badge connected">{{ t('settings.model.oauthConnected') }}</span>
          <span v-else class="oauth-card-badge disconnected">{{ t('settings.model.oauthDisconnected') }}</span>
        </span>
      </div>
      <div v-else class="info-row">
        <span class="info-label">{{ t('settings.model.apiKey') }}</span>
        <span class="info-value mono">{{ provider.apiKey || t('settings.model.notSet') }}</span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('settings.fields.modelName') }}</span>
        <span class="info-value">
          {{ t('settings.model.modelCount', { count: (provider.models?.length || 0) + (provider.extraModels?.length || 0) }) }}
        </span>
      </div>
    </div>

    <div class="card-actions">
      <button class="card-btn" @click="$emit('manage-models', provider)">
        {{ t('settings.model.actions.manageModels') }}
      </button>
      <button class="card-btn" @click="$emit('provider-settings', provider)">
        {{ t('settings.model.actions.providerSettings') }}
      </button>
      <button
        v-if="provider.supportConnectionCheck && provider.configured"
        class="card-btn"
        :class="{ testing: connectionTestingId === provider.id }"
        :disabled="connectionTestingId === provider.id"
        @click="$emit('test-connection', provider)"
      >
        {{ connectionTestingId === provider.id ? t('settings.model.discovery.testing') : t('settings.model.discovery.testConnection') }}
      </button>
      <button
        v-if="provider.isCustom"
        class="card-btn danger"
        @click="$emit('delete-provider', provider)"
      >
        {{ t('common.delete') }}
      </button>
      <!-- RFC-073: manual reprobe — visible when the provider was HARD-removed,
           lets the user recover without restart. Also useful in COOLDOWN to
           short-circuit the wait. -->
      <button
        v-if="provider.liveness === 'REMOVED' || provider.liveness === 'COOLDOWN'"
        class="card-btn"
        :class="{ testing: reprobing }"
        :disabled="reprobing"
        @click="$emit('reprobe', provider)"
      >
        {{ reprobing ? t('settings.model.poolReprobing') : t('settings.model.poolReprobe') }}
      </button>
      <!-- RFC-074 PR-2: hide an enabled provider. Soft-disable; the row moves
           back into the catalog drawer where the user can re-enable later. -->
      <button
        v-if="provider.enabled"
        class="card-btn danger-soft"
        @click="$emit('disable-provider', provider)"
      >
        {{ t('settings.model.disable') }}
      </button>
    </div>

    <div v-if="connectionResults[provider.id]" class="connection-result" :class="connectionResults[provider.id].success ? 'success' : 'error'">
      <span v-if="connectionResults[provider.id].success">
        {{ t('settings.model.discovery.connectionOk') }} · {{ t('settings.model.discovery.latency', { ms: connectionResults[provider.id].latencyMs }) }}
      </span>
      <span v-else>
        {{ t('settings.model.discovery.connectionFail') }}: {{ connectionResults[provider.id].errorMessage }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'

defineProps<{
  provider: ProviderInfo
  connectionTestingId: string | null
  connectionResults: Record<string, any>
  // RFC-073: true while a manual reprobe is in flight for this provider.
  reprobing?: boolean
  isProviderActive: (provider: ProviderInfo) => boolean
  providerStatus: (provider: ProviderInfo) => { type: string; label: string }
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
}>()

defineEmits<{
  'manage-models': [provider: ProviderInfo]
  'provider-settings': [provider: ProviderInfo]
  'test-connection': [provider: ProviderInfo]
  'delete-provider': [provider: ProviderInfo]
  'disable-provider': [provider: ProviderInfo]
  'reprobe': [provider: ProviderInfo]
}>()

const { t } = useI18n()
</script>

<style scoped>
.provider-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.provider-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.provider-icon-shell {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 8px;
  border-radius: 14px;
  border: 1px solid rgba(123, 88, 67, 0.18);
  background: linear-gradient(180deg, #ffffff, #f5ede6);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 6px 16px rgba(25, 14, 8, 0.14);
}

.provider-icon {
  width: 100%;
  height: 100%;
  object-fit: contain;
  flex-shrink: 0;
  filter: drop-shadow(0 1px 1px rgba(44, 24, 10, 0.12));
}

:global(html.dark) .provider-icon-shell {
  border-color: rgba(255, 248, 241, 0.28);
  background: linear-gradient(180deg, #fffdfb, #f3e8dc);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 8px 22px rgba(0, 0, 0, 0.26);
}

:global(html.dark) .provider-icon {
  filter: drop-shadow(0 1px 1px rgba(44, 24, 10, 0.18));
}
.provider-name { margin: 0; font-size: 18px; color: var(--mc-text-primary); }
.provider-id { margin: 6px 0 0; font-size: 13px; color: var(--mc-primary); }
.provider-badge { display: inline-flex; align-items: center; border-radius: 999px; padding: 3px 9px; font-size: 12px; font-weight: 600; }
.provider-badge.builtin { background: var(--mc-primary-bg); color: var(--mc-primary); }
.provider-badge.custom { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.provider-badge.active { background: rgba(217, 119, 87, 0.12); color: var(--mc-primary-light); }
.provider-badge.fallback { background: rgba(99, 102, 241, 0.12); color: #6366f1; cursor: help; }
/* RFC-009 Phase 4: pool status. Green = healthy, amber = cooldown, red = removed. */
.provider-badge.pool-in { background: rgba(34, 197, 94, 0.12); color: #16a34a; cursor: help; }
.provider-badge.pool-cooldown { background: rgba(245, 158, 11, 0.14); color: #b45309; cursor: help; }
.provider-badge.pool-removed { background: rgba(239, 68, 68, 0.14); color: #dc2626; cursor: help; }
/* RFC-073: UNPROBED — neutral grey with a gentle pulse so it's clearly transient. */
.provider-badge.pool-unprobed {
  background: rgba(156, 163, 175, 0.16);
  color: var(--mc-text-tertiary, #6b7280);
  cursor: help;
  animation: mc-card-dot-pulse 1.6s ease-in-out infinite;
}
@keyframes mc-card-dot-pulse { 0%, 100% { opacity: 0.55; } 50% { opacity: 1; } }
.provider-status { flex-shrink: 0; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; }
.provider-status.configured { background: var(--mc-primary-bg); color: var(--mc-primary); }
.provider-status.partial { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.provider-status.unavailable { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.provider-info { display: grid; gap: 10px; }
.info-row { display: flex; justify-content: space-between; gap: 12px; }
.info-label { color: var(--mc-text-secondary); font-size: 13px; }
.info-value { color: var(--mc-text-primary); font-size: 13px; text-align: right; word-break: break-all; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.card-actions { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.card-btn { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; background: var(--mc-primary-bg); color: var(--mc-primary); }
.card-btn:hover { background: rgba(217, 119, 87, 0.18); }
.card-btn.danger { background: var(--mc-danger-bg); color: var(--mc-danger); }
/* RFC-074 PR-2: soft-danger (disable) — softer than delete to signal reversibility. */
.card-btn.danger-soft { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.card-btn.danger-soft:hover { background: var(--mc-danger-bg); color: var(--mc-danger); }
.card-btn.testing { opacity: 0.6; cursor: wait; }
.connection-result { margin-top: 10px; padding: 8px 12px; border-radius: 8px; font-size: 12px; }
.connection-result.success { background: var(--mc-primary-bg); color: var(--mc-primary); }
.connection-result.error { background: var(--mc-danger-bg); color: var(--mc-danger); }
.oauth-card-badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 6px; font-size: 12px; font-weight: 600; }
.oauth-card-badge.connected { background: rgba(34, 197, 94, 0.12); color: #22c55e; }
.oauth-card-badge.disconnected { background: rgba(156, 163, 175, 0.12); color: var(--mc-text-tertiary); }
</style>
