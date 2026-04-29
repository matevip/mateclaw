<template>
  <div class="card">
    <!-- HEADLINE: icon · name · status pill -->
    <!-- Note: the "active" left rail lives on the outer .provider-card
         wrapper (in index.vue) so it can ride the wrapper's border-radius
         via inset box-shadow — pulling it into this child made it leak
         outside the rounded corners. -->

    <div class="card-headline">
      <span class="provider-icon-shell">
        <img
          :src="getProviderIcon(provider.id)"
          :alt="provider.name"
          class="provider-icon"
          @error="onIconError"
        />
      </span>
      <div class="card-headline__meta">
        <h3 class="provider-name">{{ provider.name }}</h3>
        <p class="provider-id">{{ provider.id }}</p>
      </div>
      <span
        class="status-pill"
        :class="`status-pill--${headlineStatus.tone}`"
        :title="headlineStatus.title"
      >
        <span v-if="headlineStatus.dot" class="status-pill__dot"></span>
        {{ headlineStatus.label }}
      </span>
    </div>

    <!-- SUBLINE: model count + active marker -->
    <p class="card-subline">
      {{ t('settings.model.modelCountAvailable', { count: totalModelCount }) }}
      <span v-if="isProviderActive(provider)" class="card-subline__active">
        · {{ t('settings.model.active') }}
      </span>
      <span
        v-if="provider.fallbackPriority && provider.fallbackPriority > 0"
        class="card-subline__chip"
        :title="t('settings.model.fallbackBadgeTitle')"
      >
        {{ t('settings.model.fallbackBadge', { priority: provider.fallbackPriority }) }}
      </span>
    </p>

    <!-- CREDENTIAL: the showcase. Inline API Key for the 90% case. -->
    <div class="cred-block">
      <!-- API-key flow (non-OAuth providers that need a key) -->
      <template v-if="provider.authType !== 'oauth' && provider.requireApiKey">
        <!-- no key set OR user clicked Change → input form -->
        <form
          v-if="!provider.apiKey || editing"
          class="cred-form"
          @submit.prevent="onSubmit"
        >
          <label class="cred-form__label" :for="`cred-${provider.id}`">
            {{ t('settings.model.inlineApiKeyTitle') }}
          </label>
          <div class="cred-form__row">
            <input
              :id="`cred-${provider.id}`"
              ref="inputRef"
              v-model="draft"
              type="password"
              class="cred-form__input"
              :placeholder="t('settings.model.inlineApiKeyPlaceholder')"
              :disabled="saving"
              autocomplete="off"
              spellcheck="false"
              @keydown.escape="onCancel"
            />
            <button
              class="cred-form__submit"
              type="submit"
              :disabled="saving || !draft.trim()"
            >
              {{ saving ? t('settings.model.inlineApiKeySaving') : t('common.save') }}
            </button>
            <button
              v-if="editing && provider.apiKey"
              class="cred-form__cancel"
              type="button"
              :disabled="saving"
              @click="onCancel"
            >
              {{ t('common.cancel') }}
            </button>
          </div>
        </form>
        <!-- key already set → masked display + Change -->
        <div v-else class="cred-set">
          <svg class="cred-set__check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
          <span class="cred-set__masked">{{ t('settings.model.inlineApiKeyMasked') }}</span>
          <button class="cred-set__change" type="button" @click="beginEdit">
            {{ t('settings.model.inlineApiKeyChange') }}
          </button>
        </div>
      </template>

      <!-- OAuth flow -->
      <template v-else-if="provider.authType === 'oauth'">
        <div class="cred-oauth">
          <span
            v-if="provider.oauthConnected"
            class="cred-oauth__badge cred-oauth__badge--ok"
          >
            {{ t('settings.model.oauthConnected') }}
          </span>
          <span v-else class="cred-oauth__badge cred-oauth__badge--off">
            {{ t('settings.model.oauthDisconnected') }}
          </span>
          <button
            v-if="!provider.oauthConnected"
            class="cred-oauth__btn"
            type="button"
            @click="$emit('oauth-login', provider)"
          >
            {{ provider.id === 'anthropic-claude-code'
              ? t('settings.model.claudeCodeOauthDetect')
              : t('settings.model.oauthLogin') }}
          </button>
        </div>
      </template>

      <!-- Local providers / no-auth: just show base URL -->
      <template v-else>
        <div class="cred-info">
          <span class="cred-info__label">{{ t('settings.model.baseUrl') }}</span>
          <span class="cred-info__value mono">
            {{ provider.baseUrl || t('settings.model.notSet') }}
          </span>
        </div>
      </template>

      <!-- Connection-test feedback: lives under the credential it tested. -->
      <div
        v-if="connectionResults[provider.id]"
        class="cred-feedback"
        :class="connectionResults[provider.id].success ? 'cred-feedback--ok' : 'cred-feedback--err'"
      >
        <span v-if="connectionResults[provider.id].success">
          {{ t('settings.model.discovery.connectionOk') }} ·
          {{ t('settings.model.discovery.latency', { ms: connectionResults[provider.id].latencyMs }) }}
        </span>
        <span v-else>
          {{ t('settings.model.discovery.connectionFail') }}: {{ connectionResults[provider.id].errorMessage }}
        </span>
      </div>
    </div>

    <!-- SECONDARY ACTIONS -->
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
        :class="{ 'card-btn--busy': connectionTestingId === provider.id }"
        :disabled="connectionTestingId === provider.id"
        @click="$emit('test-connection', provider)"
      >
        {{ connectionTestingId === provider.id
          ? t('settings.model.discovery.testing')
          : t('settings.model.discovery.testConnection') }}
      </button>
      <button
        v-if="provider.liveness === 'REMOVED' || provider.liveness === 'COOLDOWN'"
        class="card-btn"
        :class="{ 'card-btn--busy': reprobing }"
        :disabled="reprobing"
        @click="$emit('reprobe', provider)"
      >
        {{ reprobing
          ? t('settings.model.poolReprobing')
          : t('settings.model.poolReprobe') }}
      </button>
      <button
        v-if="provider.enabled"
        class="card-btn card-btn--danger-soft"
        @click="$emit('disable-provider', provider)"
      >
        {{ t('settings.model.disable') }}
      </button>
      <button
        v-if="provider.isCustom"
        class="card-btn card-btn--danger"
        @click="$emit('delete-provider', provider)"
      >
        {{ t('common.delete') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'

const props = defineProps<{
  provider: ProviderInfo
  connectionTestingId: string | null
  connectionResults: Record<string, { success: boolean; latencyMs?: number; errorMessage?: string }>
  /** True while a manual reprobe is in flight for this provider. */
  reprobing?: boolean
  /** Provider id whose inline API key save is in flight (or null). */
  savingApiKeyId?: string | null
  isProviderActive: (provider: ProviderInfo) => boolean
  providerStatus: (provider: ProviderInfo) => { type: string; label: string }
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
}>()

const emit = defineEmits<{
  'manage-models': [provider: ProviderInfo]
  'provider-settings': [provider: ProviderInfo]
  'test-connection': [provider: ProviderInfo]
  'delete-provider': [provider: ProviderInfo]
  'disable-provider': [provider: ProviderInfo]
  'reprobe': [provider: ProviderInfo]
  'oauth-login': [provider: ProviderInfo]
  'save-api-key': [payload: { provider: ProviderInfo; apiKey: string }]
}>()

const { t } = useI18n()

const draft = ref('')
const editing = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

const saving = computed(() => props.savingApiKeyId === props.provider.id)

const totalModelCount = computed(() =>
  (props.provider.models?.length || 0) + (props.provider.extraModels?.length || 0)
)

/**
 * Single source of truth for the headline pill — collapses liveness + configured
 * + credential state into ONE label. Specifically: a missing credential always
 * wins, because that's the action the user needs to take next.
 */
const headlineStatus = computed(() => {
  const p = props.provider
  const credMissing =
    (p.authType === 'oauth' && !p.oauthConnected) ||
    (p.requireApiKey && !p.apiKey && p.authType !== 'oauth')
  if (credMissing) {
    return { tone: 'warn', label: t('settings.model.statusNotSet'), title: '', dot: false }
  }
  switch (p.liveness) {
    case 'LIVE':
      return { tone: 'ok', label: t('settings.model.statusReady'), title: '', dot: true }
    case 'COOLDOWN':
      return {
        tone: 'warn',
        label: t('settings.model.poolBadgeCooldown'),
        title: t('settings.model.poolBadgeCooldownTitle', {
          seconds: Math.max(1, Math.ceil((p.cooldownRemainingMs || 0) / 1000)),
        }),
        dot: true,
      }
    case 'REMOVED':
      return {
        tone: 'err',
        label: t('settings.model.livenessRemoved'),
        title: t('settings.model.poolBadgeRemovedTitle', {
          source: t('settings.model.poolSourceInitProbe'),
          message: p.unavailableReason || '—',
        }),
        dot: true,
      }
    case 'UNPROBED':
      return {
        tone: 'pending',
        label: t('settings.model.livenessUnprobed'),
        title: t('settings.model.livenessUnprobedTooltip'),
        dot: true,
      }
  }
  return {
    tone: p.available ? 'ok' : 'pending',
    label: props.providerStatus(p).label,
    title: '',
    dot: true,
  }
})

// When parent finishes saving (savingApiKeyId clears for our id), reset the
// editing state so the masked view comes back automatically.
watch(saving, (isSaving, wasSaving) => {
  if (wasSaving && !isSaving) {
    draft.value = ''
    editing.value = false
  }
})

function onSubmit() {
  const key = draft.value.trim()
  if (!key) return
  emit('save-api-key', { provider: props.provider, apiKey: key })
}

async function beginEdit() {
  editing.value = true
  draft.value = ''
  await nextTick()
  inputRef.value?.focus()
}

function onCancel() {
  if (saving.value) return
  draft.value = ''
  editing.value = false
}
</script>

<style scoped>
/*
 * Card root — the shell is provided by the parent's .provider-card wrapper.
 * This component owns the internal layout only. Active-state highlight
 * (the left rail) is the wrapper's job — see .provider-card--active in
 * index.vue.
 */
.card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* HEADLINE */
.card-headline {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-headline__meta {
  flex: 1;
  min-width: 0;
}
.provider-name {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.provider-id {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
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
  filter: drop-shadow(0 1px 1px rgba(44, 24, 10, 0.12));
}
:global(html.dark .provider-icon-shell) {
  border-color: rgba(255, 248, 241, 0.28);
  background: linear-gradient(180deg, #fffdfb, #f3e8dc);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 8px 22px rgba(0, 0, 0, 0.26);
}

/* STATUS PILL — single source of truth, four tones */
.status-pill {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  cursor: help;
}
.status-pill__dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.status-pill--ok {
  background: rgba(34, 197, 94, 0.14);
  color: #16a34a;
}
.status-pill--warn {
  background: rgba(245, 158, 11, 0.16);
  color: #b45309;
}
.status-pill--err {
  background: rgba(239, 68, 68, 0.14);
  color: #dc2626;
}
.status-pill--pending {
  background: rgba(156, 163, 175, 0.16);
  color: var(--mc-text-tertiary);
}
.status-pill--pending .status-pill__dot {
  animation: pill-pulse 1.6s ease-in-out infinite;
}
@keyframes pill-pulse {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}

/* SUBLINE */
.card-subline {
  margin: -4px 0 0;
  font-size: 13px;
  color: var(--mc-text-secondary);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.card-subline__active {
  color: var(--mc-primary);
  font-weight: 600;
}
.card-subline__chip {
  margin-left: 4px;
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(99, 102, 241, 0.10);
  color: #6366f1;
  font-size: 11px;
  font-weight: 600;
  cursor: help;
}

/* CRED BLOCK — the showcase */
.cred-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 12px;
  background: rgba(123, 88, 67, 0.04);
  border: 1px solid rgba(123, 88, 67, 0.10);
}
:global(html.dark .cred-block) {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
}

/* form (key not set OR editing) */
.cred-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cred-form__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  letter-spacing: -0.005em;
}
.cred-form__row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}
.cred-form__input {
  flex: 1;
  min-width: 0;
  border: 1px solid rgba(123, 88, 67, 0.18);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 14px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.cred-form__input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
.cred-form__input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
:global(html.dark .cred-form__input) {
  border-color: rgba(255, 255, 255, 0.10);
}
.cred-form__submit {
  flex-shrink: 0;
  padding: 9px 18px;
  border: 0;
  border-radius: 10px;
  background: var(--mc-primary);
  color: white;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, opacity 0.15s ease;
}
.cred-form__submit:hover:not(:disabled) {
  background: var(--mc-primary-hover);
}
.cred-form__submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.cred-form__cancel {
  flex-shrink: 0;
  padding: 9px 14px;
  border: 1px solid rgba(123, 88, 67, 0.18);
  border-radius: 10px;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.cred-form__cancel:hover:not(:disabled) {
  background: rgba(123, 88, 67, 0.06);
}
:global(html.dark .cred-form__cancel) {
  border-color: rgba(255, 255, 255, 0.10);
}
:global(html.dark .cred-form__cancel:hover:not(:disabled)) {
  background: rgba(255, 255, 255, 0.06);
}

/* set state (key already configured) */
.cred-set {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cred-set__check {
  flex-shrink: 0;
  color: #16a34a;
}
.cred-set__masked {
  flex: 1;
  font-size: 13px;
  color: var(--mc-text-secondary);
  letter-spacing: 0.04em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.cred-set__change {
  flex-shrink: 0;
  background: transparent;
  border: 0;
  padding: 4px 10px;
  border-radius: 999px;
  color: var(--mc-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}
.cred-set__change:hover {
  background: rgba(217, 119, 87, 0.10);
}

/* OAuth block */
.cred-oauth {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cred-oauth__badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
}
.cred-oauth__badge--ok {
  background: rgba(34, 197, 94, 0.14);
  color: #16a34a;
}
.cred-oauth__badge--off {
  background: rgba(156, 163, 175, 0.14);
  color: var(--mc-text-tertiary);
}
.cred-oauth__btn {
  margin-left: auto;
  padding: 7px 14px;
  border: 0;
  border-radius: 999px;
  background: var(--mc-primary);
  color: white;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}
.cred-oauth__btn:hover {
  background: var(--mc-primary-hover);
}

/* Local providers / no-auth */
.cred-info {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.cred-info__label {
  font-size: 13px;
  color: var(--mc-text-secondary);
}
.cred-info__value {
  font-size: 13px;
  color: var(--mc-text-primary);
  text-align: right;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

/* Connection-test feedback */
.cred-feedback {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
}
.cred-feedback--ok {
  background: rgba(34, 197, 94, 0.10);
  color: #15803d;
}
.cred-feedback--err {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}
:global(html.dark .cred-feedback--ok) {
  background: rgba(34, 197, 94, 0.16);
  color: #4ade80;
}

/* SECONDARY ACTIONS */
.card-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.card-btn {
  border: 0;
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.card-btn:hover:not(:disabled) {
  background: rgba(217, 119, 87, 0.18);
}
.card-btn--busy {
  opacity: 0.6;
  cursor: wait;
}
.card-btn--danger {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}
.card-btn--danger-soft {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
}
.card-btn--danger-soft:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}
</style>
