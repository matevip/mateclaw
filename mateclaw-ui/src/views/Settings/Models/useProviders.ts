import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { modelApi, oauthApi, providerPoolApi } from '@/api'
import type { ProviderPoolEntry } from '@/api'
import type { ActiveModelsInfo, DiscoverResult, ProviderInfo, ProviderModelInfo, TestResult } from '@/types'

export function useProviders() {
  const { t } = useI18n()

  const providers = ref<ProviderInfo[]>([])
  const activeModels = ref<ActiveModelsInfo | null>(null)
  // RFC-009 Phase 4: pool snapshot, indexed by providerId for O(1) lookup in templates.
  const providerPool = ref<Record<string, ProviderPoolEntry>>({})
  // RFC-009 Phase 4 PR-1e: providerId currently being manually reprobed.
  const reprobingId = ref<string | null>(null)
  const editingProvider = ref<ProviderInfo | null>(null)
  const currentProvider = ref<ProviderInfo | null>(null)
  const showProviderModal = ref(false)
  const showManageModelsModal = ref(false)
  const advancedOpen = ref(false)

  // Discovery & testing state
  const discovering = ref(false)
  const discoverResult = ref<DiscoverResult | null>(null)
  const selectedNewModelIds = ref<string[]>([])
  const applyingModels = ref(false)
  const connectionTestingId = ref<string | null>(null)
  const connectionResults = ref<Record<string, TestResult>>({})
  const testingModelId = ref<string | null>(null)
  const modelTestResults = ref<Record<string, TestResult>>({})

  const providerForm = reactive({
    id: '',
    name: '',
    baseUrl: '',
    apiKey: '',
    apiKeyPrefix: 'sk-',
    protocol: 'openai-compatible',
    chatModel: 'OpenAIChatModel',
    generateKwargsText: '{}',
    enableSearch: false,
    searchStrategy: '',
    // RFC-009 P3.5: position in the multi-model failover chain.
    // 0 = excluded; positive int = ascending try-order.
    fallbackPriority: 0,
  })

  const providerModelForm = reactive({
    id: '',
    name: '',
  })

  const protocolOptions = computed(() => ([
    { value: 'openai-compatible', label: t('settings.model.protocolOpenAI') },
    { value: 'anthropic-messages', label: t('settings.model.protocolAnthropic') },
    { value: 'gemini-native', label: t('settings.model.protocolGemini') },
    { value: 'dashscope-native', label: t('settings.model.protocolDashScope') },
  ]))

  // Data loading
  async function loadProviders() {
    const res: any = await modelApi.listProviders()
    providers.value = res.data || []
  }

  async function loadActiveModel() {
    const res: any = await modelApi.getActive()
    activeModels.value = res.data || null
  }

  /**
   * RFC-009 Phase 4: fetch the pool snapshot. Best-effort — if it 404s
   * (older backend) or errors, the badges just don't render. Don't block
   * the rest of the model settings page on it.
   */
  async function loadProviderPool() {
    try {
      const res: any = await providerPoolApi.snapshot()
      const list: ProviderPoolEntry[] = res.data || []
      providerPool.value = list.reduce((acc, entry) => {
        acc[entry.providerId] = entry
        return acc
      }, {} as Record<string, ProviderPoolEntry>)
    } catch (err) {
      console.warn('[ProviderPool] snapshot failed (badges will be hidden)', err)
      providerPool.value = {}
    }
  }

  /**
   * RFC-009 Phase 4 PR-1e: synchronously re-probe one provider, then
   * refresh the pool snapshot so the badge updates. Returns the result
   * so callers can show a toast.
   */
  async function reprobeProvider(provider: ProviderInfo) {
    reprobingId.value = provider.id
    try {
      const res: any = await providerPoolApi.reprobe(provider.id)
      const data = res.data || {}
      await loadProviderPool()
      if (data.success) {
        ElMessage.success(t('settings.model.poolReprobeOk'))
      } else {
        ElMessage.warning(t('settings.model.poolReprobeFail', { error: data.errorMessage || '—' }))
      }
      return data
    } catch (err) {
      ElMessage.error(t('settings.model.poolReprobeFail', {
        error: err instanceof Error ? err.message : String(err)
      }))
    } finally {
      reprobingId.value = null
    }
  }

  async function refreshCurrentProvider(providerId: string) {
    await Promise.all([loadProviders(), loadActiveModel()])
    currentProvider.value = providers.value.find(provider => provider.id === providerId) || null
  }

  // Provider CRUD
  function openCreateProviderModal() {
    editingProvider.value = null
    advancedOpen.value = false
    Object.assign(providerForm, {
      id: '',
      name: '',
      baseUrl: '',
      apiKey: '',
      apiKeyPrefix: 'sk-',
      protocol: 'openai-compatible',
      chatModel: 'OpenAIChatModel',
      generateKwargsText: '{}',
      enableSearch: false,
      searchStrategy: '',
      fallbackPriority: 0,
    })
    showProviderModal.value = true
  }

  function openProviderConfigModal(provider: ProviderInfo) {
    editingProvider.value = provider
    advancedOpen.value = true
    const kwargs = provider.generateKwargs || {}
    const protocol = provider.protocol || chatModelToProtocol(provider.chatModel)
    // DashScope 默认开启搜索：仅当 kwargs 中显式设为 false 时才关闭
    const isDashScope = protocol === 'dashscope-native'
    const searchDefault = isDashScope ? kwargs.enableSearch !== false : !!kwargs.enableSearch
    Object.assign(providerForm, {
      id: provider.id,
      name: provider.name,
      baseUrl: provider.baseUrl || '',
      apiKey: '',
      apiKeyPrefix: provider.apiKeyPrefix || 'sk-',
      protocol,
      chatModel: provider.chatModel || 'OpenAIChatModel',
      generateKwargsText: JSON.stringify(kwargs, null, 2),
      enableSearch: searchDefault,
      searchStrategy: (kwargs.searchStrategy as string) || '',
      fallbackPriority: provider.fallbackPriority ?? 0,
    })
    showProviderModal.value = true
  }

  function closeProviderModal() {
    showProviderModal.value = false
    editingProvider.value = null
    advancedOpen.value = false
  }

  async function saveProvider() {
    const kwargs = safeParseJson(providerForm.generateKwargsText)
    // 搜索设置写入 generateKwargs
    if (providerForm.enableSearch) {
      kwargs.enableSearch = true
      if (providerForm.searchStrategy) {
        kwargs.searchStrategy = providerForm.searchStrategy
      } else {
        delete kwargs.searchStrategy
      }
    } else {
      delete kwargs.enableSearch
      delete kwargs.searchStrategy
    }
    // RFC-009 P3.5: clamp to non-negative, coerce string input back to integer.
    const fallbackPriority = Math.max(0, Math.floor(Number(providerForm.fallbackPriority) || 0))
    if (editingProvider.value) {
      await modelApi.updateProviderConfig(editingProvider.value.id, {
        apiKey: providerForm.apiKey,
        baseUrl: providerForm.baseUrl,
        protocol: providerForm.protocol,
        chatModel: protocolToChatModel(providerForm.protocol),
        generateKwargs: kwargs,
        fallbackPriority,
      })
    } else {
      await modelApi.createCustomProvider({
        id: providerForm.id,
        name: providerForm.name,
        defaultBaseUrl: providerForm.baseUrl,
        apiKeyPrefix: providerForm.apiKeyPrefix,
        protocol: providerForm.protocol,
        chatModel: protocolToChatModel(providerForm.protocol),
        models: [],
      })
      if (providerForm.apiKey || providerForm.generateKwargsText || fallbackPriority > 0) {
        await modelApi.updateProviderConfig(providerForm.id, {
          apiKey: providerForm.apiKey,
          baseUrl: providerForm.baseUrl,
          protocol: providerForm.protocol,
          chatModel: protocolToChatModel(providerForm.protocol),
          generateKwargs: kwargs,
          fallbackPriority,
        })
      }
    }
    closeProviderModal()
    await Promise.all([loadProviders(), loadActiveModel()])
  }

  async function deleteProvider(provider: ProviderInfo) {
    if (!confirm(t('settings.model.deleteConfirm', { name: provider.name }))) {
      return false
    }
    await modelApi.deleteCustomProvider(provider.id)
    await loadProviders()
    return true
  }

  // Model management
  function openManageModelsModal(provider: ProviderInfo) {
    currentProvider.value = provider
    providerModelForm.id = ''
    providerModelForm.name = ''
    showManageModelsModal.value = true
  }

  function closeManageModelsModal() {
    showManageModelsModal.value = false
    currentProvider.value = null
    discoverResult.value = null
    selectedNewModelIds.value = []
    modelTestResults.value = {}
    testingModelId.value = null
  }

  function isExtraModel(modelId: string) {
    return !!currentProvider.value?.extraModels?.some(model => model.id === modelId)
  }

  async function addProviderModel() {
    if (!currentProvider.value || !providerModelForm.id) return
    await modelApi.addProviderModel(currentProvider.value.id, {
      id: providerModelForm.id,
      name: providerModelForm.name || providerModelForm.id,
    })
    await refreshCurrentProvider(currentProvider.value.id)
    providerModelForm.id = ''
    providerModelForm.name = ''
  }

  async function removeProviderModel(model: ProviderModelInfo) {
    if (!currentProvider.value) return
    if (!confirm(t('settings.model.removeConfirm', { name: model.name }))) return
    await modelApi.removeProviderModel(currentProvider.value.id, model.id)
    await refreshCurrentProvider(currentProvider.value.id)
  }

  // Active model
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

  // Discovery & testing
  const allNewSelected = computed(() => {
    if (!discoverResult.value || discoverResult.value.newCount === 0) return false
    return selectedNewModelIds.value.length === discoverResult.value.newModels.length
  })

  function toggleSelectAll() {
    if (!discoverResult.value) return
    if (allNewSelected.value) {
      selectedNewModelIds.value = []
    } else {
      selectedNewModelIds.value = discoverResult.value.newModels.map(m => m.id)
    }
  }

  async function handleDiscoverModels() {
    if (!currentProvider.value) return
    discovering.value = true
    discoverResult.value = null
    selectedNewModelIds.value = []
    try {
      const res: any = await modelApi.discoverModels(currentProvider.value.id)
      discoverResult.value = res.data
      if (res.data?.newCount > 0) {
        selectedNewModelIds.value = res.data.newModels.map((m: ProviderModelInfo) => m.id)
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : String(error))
    } finally {
      discovering.value = false
    }
  }

  async function handleApplyModels() {
    if (!currentProvider.value || selectedNewModelIds.value.length === 0) return
    applyingModels.value = true
    try {
      const res: any = await modelApi.applyDiscoveredModels(currentProvider.value.id, selectedNewModelIds.value)
      const added = res.data?.added ?? selectedNewModelIds.value.length
      discoverResult.value = null
      selectedNewModelIds.value = []
      await refreshCurrentProvider(currentProvider.value.id)
      return added
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : String(error))
      return 0
    } finally {
      applyingModels.value = false
    }
  }

  async function handleTestConnection(provider: ProviderInfo) {
    connectionTestingId.value = provider.id
    delete connectionResults.value[provider.id]
    try {
      const res: any = await modelApi.testConnection(provider.id)
      connectionResults.value[provider.id] = res.data
    } catch (error) {
      connectionResults.value[provider.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      connectionTestingId.value = null
    }
  }

  async function handleTestModel(model: ProviderModelInfo) {
    if (!currentProvider.value) return
    testingModelId.value = model.id
    delete modelTestResults.value[model.id]
    try {
      const res: any = await modelApi.testModel(currentProvider.value.id, model.id)
      modelTestResults.value[model.id] = res.data
    } catch (error) {
      modelTestResults.value[model.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      testingModelId.value = null
    }
  }

  // Computed helpers
  const currentProviderForForm = computed(() => editingProvider.value ?? {
    id: providerForm.id,
    name: providerForm.name,
  })

  const providerBaseUrlPlaceholder = computed(() => {
    const id = currentProviderForForm.value?.id
    if (id === 'openai') return 'https://api.openai.com/v1'
    if (id === 'azure-openai') return 'https://<resource>.openai.azure.com/openai/v1'
    if (id === 'anthropic') return 'https://api.anthropic.com'
    if (id === 'ollama') return 'http://localhost:11434'
    if (id === 'lmstudio') return 'http://localhost:1234/v1'
    if (id === 'gemini') return 'https://generativelanguage.googleapis.com'
    if (id === 'openrouter') return 'https://openrouter.ai/api/v1'
    if (id === 'zhipu-cn') return 'https://open.bigmodel.cn/api/paas/v4'
    if (id === 'zhipu-intl') return 'https://open.z.ai/api/paas/v4'
    if (id === 'volcengine') return 'https://ark.cn-beijing.volces.com/api/v3'
    return 'https://example.com/v1'
  })

  const providerBaseUrlHint = computed(() => {
    const id = currentProviderForForm.value?.id
    if (id === 'openai') return t('settings.model.hints.openai')
    if (id === 'azure-openai') return t('settings.model.hints.azureOpenai')
    if (id === 'anthropic') return t('settings.model.hints.anthropic')
    if (id === 'ollama') return t('settings.model.hints.ollama')
    if (id === 'lmstudio') return t('settings.model.hints.lmstudio')
    if (id === 'gemini') return t('settings.model.hints.gemini')
    if (id === 'openrouter') return t('settings.model.hints.openrouter')
    if (id === 'zhipu-cn') return t('settings.model.hints.zhipu')
    if (id === 'zhipu-intl') return t('settings.model.hints.zhipuIntl')
    if (id === 'volcengine') return t('settings.model.hints.volcengine')
    return t('settings.model.hints.openaiCompatible')
  })

  const providerApiKeyPlaceholder = computed(() => {
    return providerForm.apiKeyPrefix
      ? `${t('settings.model.apiKeyInput')} (${providerForm.apiKeyPrefix}...)`
      : t('settings.model.apiKeyInput')
  })

  // Utility functions
  function providerStatus(provider: ProviderInfo) {
    if (provider.available) {
      return { type: 'configured', label: t('settings.model.configured') }
    }
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
  }

  function getProviderIcon(providerId: string): string {
    return providerIconMap[providerId] || '/icons/providers/default.svg'
  }

  // ==================== OAuth ====================

  async function handleOAuthLogin() {
    try {
      const res: any = await oauthApi.authorize()
      const { authorizeUrl } = res.data
      // 打开新窗口进行 OAuth 登录
      const authWindow = window.open(authorizeUrl, '_blank', 'width=600,height=700')
      // 轮询检查 OAuth 状态
      const pollInterval = setInterval(async () => {
        try {
          const statusRes: any = await oauthApi.status()
          if (statusRes.data?.connected) {
            clearInterval(pollInterval)
            if (authWindow && !authWindow.closed) authWindow.close()
            ElMessage.success(t('settings.model.oauthLoginSuccess'))
            await loadProviders()
            // 刷新当前编辑的 provider
            if (editingProvider.value) {
              const updated = providers.value.find(p => p.id === editingProvider.value!.id)
              if (updated) editingProvider.value = updated
            }
          }
        } catch { /* ignore polling errors */ }
      }, 2000)
      // 30 秒后停止轮询
      setTimeout(() => clearInterval(pollInterval), 30000)
    } catch (e: any) {
      ElMessage.error(e.msg || 'OAuth login failed')
    }
  }

  async function handleOAuthRevoke() {
    try {
      await oauthApi.revoke()
      ElMessage.success(t('settings.model.oauthRevokeSuccess'))
      await loadProviders()
      if (editingProvider.value) {
        const updated = providers.value.find(p => p.id === editingProvider.value!.id)
        if (updated) editingProvider.value = updated
      }
    } catch (e: any) {
      ElMessage.error(e.msg || 'OAuth revoke failed')
    }
  }

  function onIconError(e: Event) {
    const img = e.target as HTMLImageElement
    img.style.display = 'none'
  }

  return {
    // State
    providers,
    activeModels,
    editingProvider,
    currentProvider,
    showProviderModal,
    showManageModelsModal,
    advancedOpen,
    discovering,
    discoverResult,
    selectedNewModelIds,
    applyingModels,
    connectionTestingId,
    connectionResults,
    testingModelId,
    modelTestResults,
    providerForm,
    providerModelForm,
    protocolOptions,
    // Computed
    allNewSelected,
    providerBaseUrlPlaceholder,
    providerBaseUrlHint,
    providerApiKeyPlaceholder,
    // RFC-009 Phase 4
    providerPool,
    reprobingId,
    loadProviderPool,
    reprobeProvider,
    // Methods
    loadProviders,
    loadActiveModel,
    openCreateProviderModal,
    openProviderConfigModal,
    closeProviderModal,
    saveProvider,
    deleteProvider,
    openManageModelsModal,
    closeManageModelsModal,
    isExtraModel,
    addProviderModel,
    removeProviderModel,
    isProviderActive,
    isActiveModel,
    setActiveModel,
    toggleSelectAll,
    handleDiscoverModels,
    handleApplyModels,
    handleTestConnection,
    handleTestModel,
    providerStatus,
    getProviderIcon,
    onIconError,
    handleOAuthLogin,
    handleOAuthRevoke,
  }
}

// Internal helpers (not exported)
function safeParseJson(value: string) {
  try {
    const parsed = JSON.parse(value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('Generate config must be a JSON object')
    }
    return parsed
  } catch {
    throw new Error('Invalid JSON format')
  }
}

function protocolToChatModel(protocol: string) {
  if (protocol === 'anthropic-messages') return 'AnthropicChatModel'
  if (protocol === 'gemini-native') return 'GeminiChatModel'
  if (protocol === 'dashscope-native') return 'DashScopeChatModel'
  return 'OpenAIChatModel'
}

function chatModelToProtocol(chatModel?: string) {
  if (chatModel === 'AnthropicChatModel') return 'anthropic-messages'
  if (chatModel === 'GeminiChatModel') return 'gemini-native'
  if (chatModel === 'DashScopeChatModel') return 'dashscope-native'
  return 'openai-compatible'
}
