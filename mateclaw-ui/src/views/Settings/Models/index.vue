<template>
  <div class="settings-section model-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('settings.model.title') }}</h2>
        <p class="section-desc">{{ t('settings.model.desc') }}</p>
      </div>
      <button class="btn-primary" @click="openCreateProviderModal">
        {{ t('settings.model.addProvider') }}
      </button>
    </div>

    <!-- RFC-074 PR-1: skeleton placeholder so the page paints something
         immediately on first load instead of blank-then-pop. -->
    <div v-if="loading" class="provider-group">
      <el-skeleton :rows="4" animated />
    </div>

    <!-- 本地模型 -->
    <div v-if="!loading && localProviders.length" class="provider-group">
      <h3 class="group-title">
        <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
        </svg>
        {{ t('settings.model.localProviders') }}
      </h3>
      <div class="provider-grid">
        <div v-for="provider in localProviders" :key="provider.id" class="provider-card">
          <ProviderCard
            :provider="provider"
            :connection-testing-id="connectionTestingId"
            :connection-results="connectionResults"
            :reprobing="reprobingId === provider.id"
            :is-provider-active="isProviderActive"
            :provider-status="providerStatus"
            :get-provider-icon="getProviderIcon"
            :on-icon-error="onIconError"
            @manage-models="openManageModelsModal"
            @provider-settings="openProviderConfigModal"
            @test-connection="handleTestConnection"
            @delete-provider="onDeleteProvider"
            @reprobe="reprobeProvider"
          />
        </div>
      </div>
    </div>

    <!-- 云端模型 -->
    <div v-if="!loading && cloudProviders.length" class="provider-group">
      <h3 class="group-title">
        <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>
        </svg>
        {{ t('settings.model.cloudProviders') }}
      </h3>
      <div class="provider-grid">
        <div v-for="provider in cloudProviders" :key="provider.id" class="provider-card">
          <ProviderCard
            :provider="provider"
            :connection-testing-id="connectionTestingId"
            :connection-results="connectionResults"
            :reprobing="reprobingId === provider.id"
            :is-provider-active="isProviderActive"
            :provider-status="providerStatus"
            :get-provider-icon="getProviderIcon"
            :on-icon-error="onIconError"
            @manage-models="openManageModelsModal"
            @provider-settings="openProviderConfigModal"
            @test-connection="handleTestConnection"
            @delete-provider="onDeleteProvider"
            @reprobe="reprobeProvider"
          />
        </div>
      </div>
    </div>

    <!-- Embedding 模型（RFC Embedding UI） -->
    <EmbeddingModelsSection />

    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>

    <!-- Provider Config Modal -->
    <ProviderConfigModal
      :show="showProviderModal"
      :editing-provider="editingProvider"
      :form="providerForm"
      :advanced-open="advancedOpen"
      :protocol-options="protocolOptions"
      :base-url-placeholder="providerBaseUrlPlaceholder"
      :base-url-hint="providerBaseUrlHint"
      :api-key-placeholder="providerApiKeyPlaceholder"
      @close="closeProviderModal"
      @save="onSaveProvider"
      @toggle-advanced="advancedOpen = !advancedOpen"
      @oauth-login="handleOAuthLogin"
      @oauth-revoke="handleOAuthRevoke"
    />

    <!-- Manage Models Modal -->
    <ManageModelsModal
      :show="showManageModelsModal"
      :provider="currentProvider"
      :model-form="providerModelForm"
      :discovering="discovering"
      :discover-result="discoverResult"
      :selected-new-model-ids="selectedNewModelIds"
      :applying-models="applyingModels"
      :all-new-selected="allNewSelected"
      :testing-model-id="testingModelId"
      :model-test-results="modelTestResults"
      :is-extra-model="isExtraModel"
      :is-active-model="isActiveModel"
      :get-provider-icon="getProviderIcon"
      :on-icon-error="onIconError"
      @close="closeManageModelsModal"
      @discover="handleDiscoverModels"
      @toggle-select-all="toggleSelectAll"
      @toggle-model="onToggleModel"
      @apply-models="onApplyModels"
      @test-model="handleTestModel"
      @set-active="onSetActiveModel"
      @remove-model="onRemoveProviderModel"
      @add-model="onAddProviderModel"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { ProviderInfo, ProviderModelInfo } from '@/types'
import { useProviders } from './useProviders'
import ProviderCard from './ProviderCard.vue'
import EmbeddingModelsSection from './EmbeddingModelsSection.vue'
// RFC-074 PR-1: defer modal JS until the user actually opens one — same
// pattern as ChannelEditModal in commit 9300559b. Drops ~30KB from the
// initial Settings/Models route chunk.
const ProviderConfigModal = defineAsyncComponent(() => import('./modals/ProviderConfigModal.vue'))
const ManageModelsModal = defineAsyncComponent(() => import('./modals/ManageModelsModal.vue'))

const { t } = useI18n()
const savedTip = ref('')
// Skeleton gate. Driven by onMounted only — fine because /settings/models is
// NOT a keepAlive route. If anyone re-adds keepAlive in router/index.ts,
// switch to onActivated (or reset loading there) so cached re-entries don't
// skip the load + leave loading=false stale.
const loading = ref(true)

const {
  providers,
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
  allNewSelected,
  providerBaseUrlPlaceholder,
  providerBaseUrlHint,
  providerApiKeyPlaceholder,
  reprobingId,
  reprobeProvider,
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
} = useProviders()

const localProviders = computed(() => providers.value.filter(p => p.isLocal))
const cloudProviders = computed(() => providers.value.filter(p => !p.isLocal))

onMounted(async () => {
  try {
    await Promise.all([loadProviders(), loadActiveModel()])
  } finally {
    loading.value = false
  }
})

async function onSaveProvider() {
  try {
    await saveProvider()
    showSavedTip(t('settings.model.providerSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.messages.saveFailed'))
  }
}

async function onDeleteProvider(provider: ProviderInfo) {
  const deleted = await deleteProvider(provider)
  if (deleted) showSavedTip(t('settings.model.providerDeleted'))
}

async function onAddProviderModel() {
  try {
    await addProviderModel()
    showSavedTip(t('settings.model.modelAdded'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.model.modelAddFailed'))
  }
}

async function onRemoveProviderModel(model: ProviderModelInfo) {
  try {
    await removeProviderModel(model)
    showSavedTip(t('settings.model.modelRemoved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.model.modelRemoveFailed'))
  }
}

async function onSetActiveModel(model: ProviderModelInfo) {
  try {
    await setActiveModel(model)
    showSavedTip(t('settings.model.activeChanged'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.model.activeChangeFailed'))
  }
}

async function onApplyModels() {
  const added = await handleApplyModels()
  if (added) showSavedTip(t('settings.model.discovery.addedCount', { count: added }))
}

function onToggleModel(modelId: string) {
  const idx = selectedNewModelIds.value.indexOf(modelId)
  if (idx >= 0) {
    selectedNewModelIds.value.splice(idx, 1)
  } else {
    selectedNewModelIds.value.push(modelId)
  }
}

function showSavedTip(message: string) {
  savedTip.value = message
  window.setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.settings-section.model-section { max-width: none; }
.section-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 20px; }
.section-title { margin: 0 0 6px; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.provider-group {
  margin-bottom: 28px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.group-title__icon {
  flex-shrink: 0;
  color: var(--mc-text-secondary);
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}
.provider-card {
  background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04);
}
.btn-primary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }

.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124, 63, 30, 0.22); }

@media (max-width: 900px) {
  .section-header { flex-direction: column; }
}
@media (max-width: 640px) {
  .provider-grid { grid-template-columns: 1fr; }
}
</style>
