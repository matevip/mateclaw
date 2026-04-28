import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelApi } from '@/api'
import type { ActiveModelsInfo, ProviderInfo, ProviderModelInfo } from '@/types'

/**
 * RFC-074 PR-1: list / active-model / display helpers.
 * Owns the "what providers exist + which one is currently active + which one
 * the user is focused on" slice of state. Other composables receive these refs
 * via dep injection rather than duplicating data fetches.
 */
export function useProviderList() {
  const { t } = useI18n()

  const providers = ref<ProviderInfo[]>([])
  const activeModels = ref<ActiveModelsInfo | null>(null)
  /** The provider currently shown in the manage-models modal (cross-composable focus point). */
  const currentProvider = ref<ProviderInfo | null>(null)

  async function loadProviders() {
    const res: any = await modelApi.listProviders()
    providers.value = res.data || []
  }

  async function loadActiveModel() {
    const res: any = await modelApi.getActive()
    activeModels.value = res.data || null
  }

  /** Re-fetch providers + active model, then re-resolve currentProvider so the modal stays in sync. */
  async function refreshCurrentProvider(providerId: string) {
    await Promise.all([loadProviders(), loadActiveModel()])
    currentProvider.value = providers.value.find(provider => provider.id === providerId) || null
  }

  function isProviderActive(provider: ProviderInfo) {
    return activeModels.value?.activeLlm?.providerId === provider.id
  }

  function isActiveModel(model: ProviderModelInfo) {
    return activeModels.value?.activeLlm?.providerId === currentProvider.value?.id
      && activeModels.value?.activeLlm?.model === model.id
  }

  async function setActiveModel(model: ProviderModelInfo) {
    if (!currentProvider.value) return
    await modelApi.setActive({ providerId: currentProvider.value.id, model: model.id })
    await loadActiveModel()
  }

  // RFC-073: status pill is driven by liveness. Falls back to legacy
  // configured/available booleans for older backends without liveness.
  function providerStatus(provider: ProviderInfo) {
    switch (provider.liveness) {
      case 'LIVE':
        return { type: 'configured', label: t('settings.model.livenessLive') }
      case 'COOLDOWN':
        return { type: 'partial', label: t('settings.model.livenessCooldown') }
      case 'REMOVED':
        return { type: 'unavailable', label: t('settings.model.livenessRemoved') }
      case 'UNPROBED':
        return { type: 'partial', label: t('settings.model.livenessUnprobed') }
      case 'UNCONFIGURED':
        return { type: 'unavailable', label: t('settings.model.livenessUnconfigured') }
    }
    if (provider.available) return { type: 'configured', label: t('settings.model.configured') }
    if (provider.configured || (provider.models?.length || 0) + (provider.extraModels?.length || 0) > 0) {
      return { type: 'partial', label: t('settings.model.partial') }
    }
    return { type: 'unavailable', label: t('settings.model.unavailable') }
  }

  const providerIconMap: Record<string, string> = {
    'dashscope': '/icons/providers/dashscope.png',
    'modelscope': '/icons/providers/modelscope.svg',
    'aliyun-codingplan': '/icons/providers/aliyun-codingplan.svg',
    'openai': '/icons/providers/openai.svg',
    'azure-openai': '/icons/providers/azure-openai.svg',
    'minimax': '/icons/providers/minimax.png',
    'minimax-cn': '/icons/providers/minimax.png',
    'kimi-cn': '/icons/providers/kimi.svg',
    'kimi-intl': '/icons/providers/kimi.svg',
    'kimi-code': '/icons/providers/kimi.svg',
    'deepseek': '/icons/providers/deepseek.svg',
    'anthropic': '/icons/providers/anthropic.svg',
    'gemini': '/icons/providers/gemini.svg',
    'ollama': '/icons/providers/ollama.svg',
    'lmstudio': '/icons/providers/lmstudio.svg',
    'llamacpp': '/icons/providers/llamacpp.svg',
    'mlx': '/icons/providers/mlx.svg',
    'openrouter': '/icons/providers/openrouter.svg',
    'zhipu-cn': '/icons/providers/zhipu.svg',
    'zhipu-intl': '/icons/providers/zhipu.svg',
    'volcengine': '/icons/providers/volcengine.svg',
    'openai-chatgpt': '/icons/providers/openai.svg',
    'anthropic-claude-code': '/icons/providers/anthropic.svg',
  }

  function getProviderIcon(providerId: string): string {
    return providerIconMap[providerId] || '/icons/providers/default.svg'
  }

  function onIconError(e: Event) {
    const img = e.target as HTMLImageElement
    img.style.display = 'none'
  }

  return {
    providers,
    currentProvider,
    loadProviders,
    loadActiveModel,
    isProviderActive,
    isActiveModel,
    setActiveModel,
    providerStatus,
    getProviderIcon,
    onIconError,
    // Cross-composable wiring — facade hands these to other composables (e.g.
    // Discovery uses refreshCurrentProvider, OAuth would read activeModels via
    // List's helpers). Not part of the public surface index.vue destructures;
    // they're returned here only so the facade has somewhere to grab them from.
    activeModels,
    refreshCurrentProvider,
  }
}
