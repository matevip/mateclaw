<template>
  <div class="settings-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.sttTitle') }}</h2>
      <p class="section-desc">{{ t('settings.sttDesc') }}</p>
    </div>

    <div class="settings-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.sttEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttProvider') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttProvider') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.sttProvider" class="form-input" :disabled="!settings.sttEnabled">
            <option value="auto">{{ t('settings.sttProviderOptions.auto') }}</option>
            <option value="openai">OpenAI Whisper</option>
            <option value="dashscope">DashScope (Paraformer)</option>
          </select>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttFallbackEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttFallbackEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.sttFallbackEnabled" type="checkbox" :disabled="!settings.sttEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </div>

    <template v-if="settings.sttEnabled">
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">OpenAI Whisper</span>
          <span class="provider-tag">{{ t('settings.sttProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card"><div class="setting-item"><div class="setting-info"><div class="setting-hint">{{ t('settings.hints.openaiSttInfo') }}</div></div></div></div>
      </div>
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">DashScope (Paraformer)</span>
          <span class="provider-tag">{{ t('settings.sttProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card"><div class="setting-item"><div class="setting-info"><div class="setting-hint">{{ t('settings.hints.dashscopeSttInfo') }}</div></div></div></div>
      </div>
    </template>

    <div class="save-bar">
      <button class="btn-secondary" @click="loadSettings">{{ t('common.reset') }}</button>
      <button class="btn-primary" @click="onSaveSettings">{{ t('settings.actions.saveSystem') }}</button>
    </div>
    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { settingsApi } from '@/api'

const { t } = useI18n()
const savedTip = ref('')
const settings = reactive({ sttEnabled: false, sttProvider: 'auto', sttFallbackEnabled: true })

onMounted(() => loadSettings())

async function loadSettings() {
  const res: any = await settingsApi.get()
  const d = res.data || {}
  settings.sttEnabled = d.sttEnabled ?? false
  settings.sttProvider = d.sttProvider ?? 'auto'
  settings.sttFallbackEnabled = d.sttFallbackEnabled ?? true
}

async function onSaveSettings() {
  await settingsApi.update({ sttEnabled: settings.sttEnabled, sttProvider: settings.sttProvider, sttFallbackEnabled: settings.sttFallbackEnabled })
  await loadSettings()
  savedTip.value = t('settings.messages.saveSuccess')
  setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }
.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124,63,30,0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:disabled { opacity: 0.5; cursor: not-allowed; }
.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }
.toggle-switch input:disabled + .toggle-slider { opacity: 0.5; cursor: not-allowed; }
.provider-section { margin-top: 24px; }
.provider-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.provider-name { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); }
.provider-tag { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124,63,30,0.22); }
</style>
