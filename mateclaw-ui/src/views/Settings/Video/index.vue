<template>
  <div class="settings-section video-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.videoTitle') }}</h2>
      <p class="section-desc">{{ t('settings.videoDesc') }}</p>
    </div>

    <div class="settings-card">
      <!-- 总开关 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.videoEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.videoEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.videoEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <!-- 首选 Provider -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.videoProvider') }}</div>
          <div class="setting-hint">{{ t('settings.hints.videoProvider') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.videoProvider" class="form-input" :disabled="!settings.videoEnabled">
            <option value="auto">{{ t('settings.videoProviderOptions.auto') }}</option>
            <option value="dashscope">DashScope (通义万相)</option>
            <option value="zhipu-cogvideo">智谱 CogVideoX</option>
            <option value="fal">fal.ai (Kling/Runway/Luma)</option>
            <option value="kling">快手可灵 Kling</option>
            <option value="runway">Runway</option>
            <option value="minimax">MiniMax (Hailuo)</option>
          </select>
        </div>
      </div>

      <!-- Fallback 开关 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.videoFallbackEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.videoFallbackEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.videoFallbackEnabled" type="checkbox" :disabled="!settings.videoEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </div>

    <!-- Provider 配置区块（仅在启用时显示） -->
    <template v-if="settings.videoEnabled">
      <!-- DashScope -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">DashScope (通义万相)</span>
          <span class="provider-tag">{{ t('settings.videoProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.dashscopeStatus') }}</div>
              <div class="setting-hint">{{ t('settings.hints.dashscopeVideoStatus') }}</div>
            </div>
            <div class="setting-control">
              <span class="status-tag">{{ t('settings.videoProviderTags.configuredInModels') }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 智谱 CogVideo -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">智谱 CogVideoX</span>
          <span class="provider-tag tag-free">{{ t('settings.videoProviderTags.freeQuota') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.zhipuApiKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.zhipuApiKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="zhipuApiKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.zhipuApiKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.zhipuBaseUrl') }}</div>
              <div class="setting-hint">{{ t('settings.hints.zhipuBaseUrl') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="settings.zhipuBaseUrl"
                type="text"
                class="form-input"
                placeholder="https://open.bigmodel.cn"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- fal.ai -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">fal.ai</span>
          <span class="provider-tag">Kling / Runway / Luma / MiniMax</span>
        </div>
        <div class="settings-card">
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.falApiKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.falApiKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="falApiKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.falApiKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 快手可灵 -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">快手可灵 Kling</span>
        </div>
        <div class="settings-card">
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.klingAccessKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.klingAccessKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="klingAccessKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.klingAccessKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.klingSecretKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.klingSecretKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="klingSecretKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.klingSecretKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Runway -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">Runway</span>
          <span class="provider-tag">gen4.5 / gen4_turbo</span>
        </div>
        <div class="settings-card">
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.runwayApiKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.runwayApiKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="runwayApiKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.runwayApiKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- MiniMax (Hailuo) -->
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">MiniMax (Hailuo)</span>
          <span class="provider-tag tag-free">{{ t('settings.videoProviderTags.freeQuota') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item setting-item-vertical">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.minimaxApiKey') }}</div>
              <div class="setting-hint">{{ t('settings.hints.minimaxApiKey') }}</div>
            </div>
            <div class="setting-control-full">
              <input
                v-model="minimaxApiKeyInput"
                type="password"
                class="form-input"
                :placeholder="settings.minimaxApiKeyMasked || t('settings.model.apiKeyInput')"
                autocomplete="off"
              />
            </div>
          </div>
        </div>
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

// API Key 独立管理，不回显明文
const zhipuApiKeyInput = ref('')
const falApiKeyInput = ref('')
const klingAccessKeyInput = ref('')
const klingSecretKeyInput = ref('')
const runwayApiKeyInput = ref('')
const minimaxApiKeyInput = ref('')

const settings = reactive({
  videoEnabled: false,
  videoProvider: 'auto',
  videoFallbackEnabled: true,
  zhipuBaseUrl: '',
  zhipuApiKeyMasked: '',
  falApiKeyMasked: '',
  klingAccessKeyMasked: '',
  klingSecretKeyMasked: '',
  runwayApiKeyMasked: '',
  minimaxApiKeyMasked: '',
})

onMounted(async () => {
  await loadSettings()
})

async function loadSettings() {
  const res: any = await settingsApi.get()
  const data = res.data || {}
  settings.videoEnabled = data.videoEnabled ?? false
  settings.videoProvider = data.videoProvider ?? 'auto'
  settings.videoFallbackEnabled = data.videoFallbackEnabled ?? true
  settings.zhipuBaseUrl = data.zhipuBaseUrl ?? ''
  settings.zhipuApiKeyMasked = data.zhipuApiKeyMasked ?? ''
  settings.falApiKeyMasked = data.falApiKeyMasked ?? ''
  settings.klingAccessKeyMasked = data.klingAccessKeyMasked ?? ''
  settings.klingSecretKeyMasked = data.klingSecretKeyMasked ?? ''
  settings.runwayApiKeyMasked = data.runwayApiKeyMasked ?? ''
  settings.minimaxApiKeyMasked = data.minimaxApiKeyMasked ?? ''
  // 清空密钥输入
  zhipuApiKeyInput.value = ''
  falApiKeyInput.value = ''
  klingAccessKeyInput.value = ''
  klingSecretKeyInput.value = ''
  runwayApiKeyInput.value = ''
  minimaxApiKeyInput.value = ''
}

async function onSaveSettings() {
  const payload: any = {
    videoEnabled: settings.videoEnabled,
    videoProvider: settings.videoProvider,
    videoFallbackEnabled: settings.videoFallbackEnabled,
    zhipuBaseUrl: settings.zhipuBaseUrl,
  }
  if (zhipuApiKeyInput.value) payload.zhipuApiKey = zhipuApiKeyInput.value
  if (falApiKeyInput.value) payload.falApiKey = falApiKeyInput.value
  if (klingAccessKeyInput.value) payload.klingAccessKey = klingAccessKeyInput.value
  if (klingSecretKeyInput.value) payload.klingSecretKey = klingSecretKeyInput.value
  if (runwayApiKeyInput.value) payload.runwayApiKey = runwayApiKeyInput.value
  if (minimaxApiKeyInput.value) payload.minimaxApiKey = minimaxApiKeyInput.value

  await settingsApi.update(payload)
  await loadSettings()
  showSavedTip(t('settings.messages.saveSuccess'))
}

function showSavedTip(message: string) {
  savedTip.value = message
  window.setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.settings-section.video-section { max-width: none; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-item-vertical { flex-direction: column; gap: 10px; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }
.setting-control-full { width: 100%; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:focus { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
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
.provider-tag.tag-free { background: #e8f5e9; color: #2e7d32; }
.status-tag { font-size: 13px; color: var(--mc-text-secondary); }

.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124, 63, 30, 0.22); }

@media (max-width: 900px) {
  .setting-item { flex-direction: column; }
  .setting-control { width: 100%; justify-content: flex-start; }
}
</style>
