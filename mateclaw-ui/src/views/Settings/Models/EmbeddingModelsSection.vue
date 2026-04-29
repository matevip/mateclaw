<template>
  <div class="provider-group embedding-section">
    <h3 class="group-title">
      <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <circle cx="12" cy="12" r="4"/>
        <line x1="4.93" y1="4.93" x2="9.17" y2="9.17"/>
        <line x1="14.83" y1="14.83" x2="19.07" y2="19.07"/>
        <line x1="14.83" y1="9.17" x2="19.07" y2="4.93"/>
        <line x1="4.93" y1="19.07" x2="9.17" y2="14.83"/>
      </svg>
      Embedding 模型
      <span class="group-hint">知识库语义检索使用，与 Chat 模型共享 Provider 的 API Key</span>
    </h3>

    <div v-if="loading" class="loading-state">加载中...</div>
    <div v-else-if="models.length === 0" class="empty-state">
      暂无可用的 Embedding 模型。系统已预置 DashScope Text Embedding v3/v2，
      请在"云端模型"下的 <strong>DashScope</strong> Provider 中配置 API Key。
    </div>

    <div v-else class="embedding-grid">
      <div v-for="model in models" :key="model.id" class="embedding-card">
        <div class="embedding-card-header">
          <div class="embedding-name">
            {{ model.name }}
            <span v-if="String(model.id) === defaultModelId" class="default-badge">默认</span>
          </div>
          <span class="provider-badge">{{ model.provider }}</span>
        </div>
        <div class="embedding-model-id">{{ model.modelName }}</div>
        <div v-if="model.description" class="embedding-desc">{{ model.description }}</div>

        <!-- 测试结果 -->
        <div v-if="testResults[String(model.id)]" class="test-result" :class="testResults[String(model.id)].success ? 'success' : 'error'">
          <span v-if="testResults[String(model.id)].success">
            ✓ 测试通过 · 维度 {{ testResults[String(model.id)].dimensions }}
          </span>
          <span v-else>✗ {{ testResults[String(model.id)].message }}</span>
        </div>

        <div class="embedding-actions">
          <button
            class="card-btn test-btn"
            :disabled="testingId === String(model.id)"
            @click="onTest(model)"
          >
            {{ testingId === String(model.id) ? '测试中...' : '测试连通性' }}
          </button>
          <button
            v-if="String(model.id) !== defaultModelId"
            class="card-btn"
            @click="onSetDefault(model)"
          >
            设为系统默认
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { modelApi } from '@/api'

interface EmbeddingModel {
  id: string | number
  name: string
  provider: string
  modelName: string
  description?: string
  enabled?: boolean
  isDefault?: boolean
}

const models = ref<EmbeddingModel[]>([])
const loading = ref(false)
const defaultModelId = ref<string>('')
const testingId = ref<string>('')
const testResults = ref<Record<string, { success: boolean; dimensions?: number; message?: string }>>({})

async function loadAll() {
  loading.value = true
  try {
    const [listRes, defaultRes] = await Promise.all([
      modelApi.listByType('embedding'),
      modelApi.getDefaultEmbedding(),
    ])
    models.value = (listRes.data as any[]) || []
    defaultModelId.value = String((defaultRes.data as any)?.defaultModelId || '')
  } catch (e: any) {
    console.error('[EmbeddingModels] Load failed:', e?.message)
  } finally {
    loading.value = false
  }
}

async function onTest(model: EmbeddingModel) {
  testingId.value = String(model.id)
  try {
    const res = await modelApi.testEmbedding(model.id)
    const data = res.data as any
    testResults.value[String(model.id)] = {
      success: !!data?.success,
      dimensions: data?.dimensions,
      message: data?.message,
    }
  } catch (e: any) {
    testResults.value[String(model.id)] = {
      success: false,
      message: e?.message || '请求失败',
    }
  } finally {
    testingId.value = ''
  }
}

async function onSetDefault(model: EmbeddingModel) {
  try {
    await modelApi.setDefaultEmbedding(model.id)
    defaultModelId.value = String(model.id)
  } catch (e: any) {
    console.error('[EmbeddingModels] Set default failed:', e?.message)
  }
}

onMounted(loadAll)
defineExpose({ refresh: loadAll })
</script>

<style scoped>
.embedding-section {
  margin-top: 24px;
}
/* Mirror the flex layout used by .group-title in index.vue — scoped styles
 * don't cross component boundaries, so without this the icon stacks above
 * the title instead of sitting inline. */
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
.group-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--mc-text-tertiary);
  margin-left: 4px;
}
.loading-state, .empty-state {
  padding: 32px;
  text-align: center;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-sunken);
  border-radius: 8px;
}
.empty-state strong { color: var(--mc-primary); }

.embedding-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.embedding-card {
  padding: 16px;
  background: var(--mc-bg-surface);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.embedding-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.embedding-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--mc-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.default-badge {
  font-size: 10px;
  padding: 2px 6px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-radius: 4px;
  font-weight: 600;
}
.provider-badge {
  font-size: 11px;
  padding: 2px 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  border-radius: 999px;
}
.embedding-model-id {
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  color: var(--mc-text-tertiary);
}
.embedding-desc {
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
}
.test-result {
  font-size: 12px;
  padding: 6px 8px;
  border-radius: 4px;
}
.test-result.success {
  background: rgba(34, 197, 94, 0.1);
  color: rgb(21, 128, 61);
}
.test-result.error {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}
.embedding-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.card-btn {
  flex: 1;
  padding: 6px 12px;
  font-size: 12px;
  border-radius: 4px;
  border: 1px solid var(--mc-border);
  background: transparent;
  color: var(--mc-text-primary);
  cursor: pointer;
}
.card-btn:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.card-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.test-btn {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-color: var(--mc-primary);
}
</style>
