<template>
  <div class="feature-flags-page">
    <header class="page-header">
      <div class="mc-page-kicker">{{ t('settings.kicker') }}</div>
      <h2 class="page-title">{{ t('settings.featureFlags.title') }}</h2>
      <p class="page-desc">{{ t('settings.featureFlags.description') }}</p>
    </header>

    <div v-if="loading" class="state-row">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="error" class="state-row state-row--error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <el-button size="small" @click="load">{{ t('common.retry', 'Retry') }}</el-button>
    </div>

    <ul v-else-if="flags.length > 0" class="flag-list">
      <li v-for="flag in flags" :key="flag.flagKey" class="flag-row">
        <div class="flag-text">
          <div class="flag-key">{{ flag.flagKey }}</div>
          <div v-if="describe(flag)" class="flag-desc">{{ describe(flag) }}</div>
          <div v-if="hasScope(flag)" class="flag-scope">
            <span v-if="flag.whitelistKbIds">
              {{ t('settings.featureFlags.scope.kb') }}: {{ flag.whitelistKbIds }}
            </span>
            <span v-if="flag.whitelistUserIds">
              {{ t('settings.featureFlags.scope.user') }}: {{ flag.whitelistUserIds }}
            </span>
            <span v-if="(flag.rolloutPercent ?? 0) > 0 && (flag.rolloutPercent ?? 0) < 100">
              {{ t('settings.featureFlags.scope.rollout', { pct: flag.rolloutPercent }) }}
            </span>
          </div>
        </div>
        <div class="flag-actions">
          <el-switch
            :model-value="flag.enabled"
            :loading="pending[flag.flagKey] === true"
            :disabled="pending[flag.flagKey] === true"
            @change="(value: any) => onToggle(flag, !!value)"
          />
        </div>
      </li>
    </ul>

    <div v-else class="state-row">
      <span>{{ t('settings.featureFlags.empty') }}</span>
    </div>

    <footer class="page-footer">
      <p>{{ t('settings.featureFlags.footer') }}</p>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElIcon, ElMessage, ElSwitch } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { featureFlagApi, type FeatureFlag } from '@/api/index'

const { t, te } = useI18n()

/**
 * Resolves a flag's description from i18n first, falling back to the backend
 * column when no translation key is registered. The backend value is English-
 * only by design (it's a stable reference identifier in the DB seed); UI
 * copy lives next to other strings in the locale files.
 */
function describe(flag: FeatureFlag): string {
  const i18nKey = `settings.featureFlags.descriptions.${flag.flagKey}`
  if (te(i18nKey)) {
    return t(i18nKey)
  }
  return flag.description ?? ''
}

const loading = ref(true)
const error = ref<string>('')
const flags = ref<FeatureFlag[]>([])
const pending = reactive<Record<string, boolean>>({})

function hasScope(flag: FeatureFlag): boolean {
  return !!flag.whitelistKbIds
      || !!flag.whitelistUserIds
      || ((flag.rolloutPercent ?? 0) > 0 && (flag.rolloutPercent ?? 0) < 100)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const resp: any = await featureFlagApi.list()
    flags.value = (resp?.data ?? []).slice().sort((a: FeatureFlag, b: FeatureFlag) =>
        a.flagKey.localeCompare(b.flagKey))
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  } finally {
    loading.value = false
  }
}

async function onToggle(flag: FeatureFlag, next: boolean) {
  pending[flag.flagKey] = true
  try {
    await featureFlagApi.update(flag.flagKey, { enabled: next })
    flag.enabled = next  // optimistic local update
    ElMessage.success(t(next ? 'settings.featureFlags.enabled' : 'settings.featureFlags.disabled',
        { key: flag.flagKey }))
  } catch (e: any) {
    ElMessage.error(e?.message ?? t('settings.featureFlags.toggleFailed'))
    // Revert by refreshing list to true server state.
    await load()
  } finally {
    pending[flag.flagKey] = false
  }
}

onMounted(load)
</script>

<style scoped>
.feature-flags-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 880px;
}

.page-header {
  border-bottom: 1px solid var(--mc-border-light);
  padding-bottom: 16px;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  margin: 4px 0 8px;
  color: var(--mc-text-primary);
}
.page-desc {
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}

.flag-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--mc-border-light);
  border-radius: 10px;
  overflow: hidden;
}

.flag-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  background: var(--mc-bg-base);
}

.flag-text {
  flex: 1;
  min-width: 0;
}

.flag-key {
  font-family: var(--mc-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.flag-desc {
  font-size: 12px;
  color: var(--mc-text-secondary);
  margin-top: 4px;
  line-height: 1.5;
}

.flag-scope {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 4px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.state-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  border-radius: 10px;
}

.state-row--error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.page-footer {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}
</style>
