<template>
  <Teleport to="body">
    <Transition name="cfg-modal">
      <div v-if="open" class="cfg-modal-overlay" @click.self="emit('close')">
        <div class="cfg-modal" style="max-width:680px">

          <!-- Header -->
          <div class="cfg-modal__header">
            <div class="cfg-modal__title-group">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
              </svg>
              <span>{{ t('wiki.configPanel.modelStrategy') }}</span>
              <span class="cfg-modal__kb">{{ kbName }}</span>
            </div>
            <div class="cfg-modal__actions">
              <button class="btn-cfg-reset" @click="emit('reset')">{{ t('common.reset') }}</button>
              <button class="btn-cfg-save" :disabled="saving" @click="emit('save')">
                {{ saving ? t('wiki.saving') : t('common.save') }}
              </button>
              <button class="btn-cfg-close" @click="emit('close')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
          </div>

          <!-- Body -->
          <div class="cfg-modal__body models-body">

            <!-- Priority hint -->
            <div class="models-hint">
              <span class="hint-chain">
                <span class="hint-node hint-node--dim">系统全局</span>
                <span class="hint-arrow">→</span>
                <span class="hint-node" :class="wikiGlobalModelId ? 'hint-node--wiki' : 'hint-node--dim'">Wiki 全局</span>
                <span class="hint-arrow">→</span>
                <span class="hint-node hint-node--step">步骤绑定</span>
              </span>
              <span class="hint-desc">优先级从左到右递增，留空则沿用上一级</span>
            </div>

            <!-- ① Wiki global model -->
            <div class="models-section">
              <div class="models-section__title">
                Wiki 全局模型
                <span v-if="wikiGlobalModelId" class="section-badge section-badge--on">已设置</span>
                <span v-else class="section-badge">未设置</span>
                <button
                  v-if="wikiGlobalModelId"
                  class="section-clear"
                  @click="emit('update:wikiGlobalModelId', '')"
                >清除</button>
              </div>
              <div class="models-section__hint">为此知识库所有 Wiki 步骤统一指定专属模型，优先于系统全局默认</div>
              <ModelSelector
                :providers="providers"
                :active-value="configIdToValue.get(wikiGlobalModelId) || ''"
                :active-label="configIdToLabel(wikiGlobalModelId)"
                class="wiki-model-selector"
                @select="emit('update:wikiGlobalModelId', valueToConfigId.get($event) || '')"
              />
            </div>

            <!-- ② Step models -->
            <div class="models-section">
              <div class="models-section__title">按步骤绑定</div>
              <div class="models-section__hint">针对单个步骤覆盖上方全局设置，最精细的控制层</div>
              <div class="step-grid">
                <div v-for="step in stepKeys" :key="step" class="step-row">
                  <div class="step-row__label">
                    <span class="step-index">{{ stepKeys.indexOf(step) + 1 }}</span>
                    {{ t(`wiki.configPanel.stepModel.${step}`) }}
                  </div>
                  <ModelSelector
                    :providers="providersWithDefault"
                    :active-value="configIdToValue.get(stepModels[step]) || ''"
                    :active-label="stepModelLabel(step)"
                    class="wiki-model-selector"
                    @select="onSelectStep(step, $event)"
                  />
                </div>
              </div>
            </div>

            <!-- ③ Fallback -->
            <div class="models-section">
              <div class="models-section__title">{{ t('wiki.configPanel.fallbackModels') }}</div>
              <div class="models-section__hint">主模型失败时按顺序尝试备选，最多 3 个</div>
              <div class="fallback-list">
                <span v-for="(fId, idx) in fallbackModelIds" :key="idx" class="fallback-tag">
                  <span>{{ configIdToLabel(fId) }}</span>
                  <button class="fallback-tag__remove" @click="emit('removeFallback', idx)">×</button>
                </span>
                <ModelSelector
                  v-if="fallbackModelIds.length < 3"
                  :providers="providers"
                  :active-value="''"
                  :active-label="''"
                  class="wiki-model-selector fallback-add-selector"
                  @select="onAddFallback"
                />
              </div>
            </div>

          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'
import ModelSelector from '@/components/chat/ModelSelector.vue'

const { t } = useI18n()

const props = defineProps<{
  open: boolean
  kbName?: string
  saving?: boolean
  stepKeys: string[]
  stepModels: Record<string, string>
  fallbackModelIds: string[]
  providers: ProviderInfo[]
  configIdToValue: Map<string, string>
  valueToConfigId: Map<string, string>
  configIdToLabel: (id: string) => string
  wikiGlobalModelId: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'save'): void
  (e: 'reset'): void
  (e: 'addFallback', id: string): void
  (e: 'removeFallback', idx: number): void
  (e: 'update:wikiGlobalModelId', id: string): void
}>()

// Default-option label shown in step pickers when nothing is bound
const stepDefaultLabel = computed(() => {
  if (props.wikiGlobalModelId) {
    const name = props.configIdToLabel(props.wikiGlobalModelId)
    return name ? `跟随 Wiki 全局 (${name})` : '跟随 Wiki 全局'
  }
  return '跟随全局默认'
})

// Providers list with a "default" entry prepended for step pickers
const providersWithDefault = computed<ProviderInfo[]>(() => {
  const defaultProvider = {
    id: '__default__',
    name: '默认',
    available: true,
    isLocal: false,
    models: [{ id: '', name: stepDefaultLabel.value }],
    extraModels: [],
    isCustom: false,
    supportModelDiscovery: false,
    supportConnectionCheck: false,
    freezeUrl: false,
    requireApiKey: false,
    configured: true,
  } as unknown as ProviderInfo
  return [defaultProvider, ...props.providers]
})

function stepModelLabel(step: string): string {
  const id = props.stepModels[step]
  if (!id) return stepDefaultLabel.value
  return props.configIdToLabel(id)
}

function onSelectStep(step: string, value: string) {
  // Empty value = "default" option selected
  props.stepModels[step] = value ? (props.valueToConfigId.get(value) || '') : ''
}

function onAddFallback(value: string) {
  if (!value) return
  const configId = props.valueToConfigId.get(value) || ''
  if (configId && !props.fallbackModelIds.includes(configId)) {
    emit('addFallback', configId)
  }
}
</script>

<style scoped>
.models-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.models-hint {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--mc-bg-sunken);
  border-radius: 10px;
  border: 1px solid var(--mc-border-light);
  flex-wrap: wrap;
}
.hint-chain { display: flex; align-items: center; gap: 6px; }
.hint-arrow { font-size: 11px; color: var(--mc-text-tertiary); }
.hint-node {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 99px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
}
.hint-node--wiki { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.hint-node--step { border-color: var(--mc-border); color: var(--mc-text-secondary); }
.hint-desc { font-size: 11px; color: var(--mc-text-tertiary); }

.models-section { display: flex; flex-direction: column; gap: 8px; }
.models-section__title { font-size: 12px; font-weight: 600; color: var(--mc-text-primary); }
.models-section__hint { font-size: 11px; color: var(--mc-text-tertiary); margin-top: -4px; }

.section-badge {
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 99px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  font-weight: 500;
  margin-left: 6px;
  vertical-align: middle;
}
.section-badge--on { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.section-clear {
  margin-left: 8px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
  vertical-align: middle;
}
.section-clear:hover { color: var(--mc-danger, #e53e3e); text-decoration: underline; }

.step-grid { display: flex; flex-direction: column; gap: 6px; }
.step-row { display: grid; grid-template-columns: 160px 1fr; align-items: center; gap: 10px; }
.step-row__label { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--mc-text-secondary); font-weight: 500; }
.step-index {
  width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 50%;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
}

.fallback-list { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.fallback-tag { display: flex; align-items: center; gap: 4px; padding: 3px 8px 3px 10px; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 8px; font-size: 12px; color: var(--mc-text-primary); }
.fallback-tag__remove { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 14px; padding: 0 1px; line-height: 1; }
.fallback-tag__remove:hover { color: var(--mc-danger); }

/* Make ModelSelector stretch to full column width */
.wiki-model-selector { width: 100%; }
.wiki-model-selector :deep(.model-select-trigger) {
  width: 100%;
  max-width: 100%;
  border-radius: 10px;
  background: var(--mc-bg-elevated);
  height: 38px;
}

.fallback-add-selector { width: 180px; }
.fallback-add-selector :deep(.model-select-trigger) {
  border-style: dashed;
  font-size: 12px;
  height: 32px;
  color: var(--mc-text-tertiary);
  background: transparent;
}
</style>
