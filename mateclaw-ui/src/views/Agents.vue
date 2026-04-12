<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner agents-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Agent Studio</div>
            <h1 class="mc-page-title">{{ t('agents.title') }}</h1>
            <p class="mc-page-desc">{{ t('agents.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('agents.newAgent') }}
          </button>
        </div>

        <div class="agents-toolbar mc-surface-card">
          <div class="filter-bar">
            <div class="search-box">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
              </svg>
              <input v-model="searchText" :placeholder="t('agents.search')" class="search-input" />
            </div>
            <div class="filter-tabs">
              <button v-for="tab in filterTabs" :key="tab.value" class="filter-tab"
                :class="{ active: activeFilter === tab.value }" @click="activeFilter = tab.value">
                {{ t(tab.key) }}
              </button>
            </div>
          </div>
        </div>

        <!-- Agent card grid -->
        <div class="agent-grid" v-if="filteredAgents.length > 0">
          <div
            v-for="agent in filteredAgents"
            :key="agent.id"
            class="agent-card mc-surface-card"
            :class="{ 'agent-card--disabled': !agent.enabled }"
          >
            <div class="agent-card__header">
              <span class="agent-card__icon">{{ agent.icon || '🤖' }}</span>
              <label class="toggle-switch toggle-switch--sm">
                <input type="checkbox" :checked="agent.enabled" @change="toggleAgent(agent)" />
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="agent-card__body">
              <h3 class="agent-card__name">{{ agent.name }}</h3>
              <p class="agent-card__desc">{{ agent.description || t('agents.messages.noDescription') }}</p>
            </div>
            <div class="agent-card__meta">
              <span class="tag type-tag">{{ agent.agentType === 'react' ? 'ReAct' : 'Plan-Execute' }}</span>
              <div class="tags-cell" v-if="agent.tags">
                <span v-for="tag in parseTags(agent.tags)" :key="tag" class="tag tag-item">{{ tag }}</span>
              </div>
            </div>
            <div class="agent-card__footer">
              <span class="time-label">{{ formatTime(agent.updateTime) }}</span>
              <div class="agent-card__actions">
                <button class="action-btn" :title="t('agents.tabs.context')" @click="goToAgentContextFor(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>
                  </svg>
                </button>
                <button class="action-btn" :title="t('agents.actions.edit')" @click="openEditModal(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button class="action-btn danger" :title="t('agents.actions.delete')" @click="deleteAgent(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Empty state -->
        <div v-else class="empty-state mc-surface-card">
          <div class="empty-icon">🤖</div>
          <h3>{{ t('agents.emptyTitle') }}</h3>
          <p>{{ t('agents.emptyDesc') }}</p>
          <button class="btn-primary" @click="openCreateModal">{{ t('agents.newAgent') }}</button>
        </div>
      </div>
    </div>
    

    <!-- Template Selector Modal -->
    <div v-if="showTemplateSelector" class="modal-overlay">
      <div class="modal template-modal">
        <div class="modal-header">
          <h2>{{ t('agents.templates.title') }}</h2>
          <button class="modal-close" @click="showTemplateSelector = false">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p class="template-desc">{{ t('agents.templates.desc') }}</p>
          <div class="template-grid">
            <div
              v-for="tpl in templates"
              :key="tpl.id"
              class="template-card mc-surface-card"
              :class="{ applying: applyingTemplate }"
              @click="!applyingTemplate && applyTemplate(tpl.id)"
            >
              <div class="template-icon">{{ tpl.icon }}</div>
              <div class="template-info">
                <h4 class="template-name">{{ $i18n.locale === 'zh-CN' && tpl.nameZh ? tpl.nameZh : tpl.name }}</h4>
                <p class="template-detail">{{ $i18n.locale === 'zh-CN' && tpl.descriptionZh ? tpl.descriptionZh : tpl.description }}</p>
              </div>
              <div class="template-tags">
                <span v-for="tag in (tpl.tags || '').split(',').filter(Boolean)" :key="tag" class="tag-chip">{{ tag.trim() }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="openBlankCreateModal">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('agents.templates.skip') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Create/Edit Modal -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editingAgent ? t('agents.modal.editTitle') : t('agents.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <!-- Tab Bar -->
          <div class="modal-tabs">
            <button class="modal-tab" :class="{ active: modalTab === 'basic' }" @click="modalTab = 'basic'">
              {{ t('agents.tabs.basic', 'Basic') }}
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'skills' }" @click="modalTab = 'skills'">
              {{ t('agents.tabs.skills', 'Skills') }}
              <span v-if="selectedSkillIds.length" class="tab-badge">{{ selectedSkillIds.length }}</span>
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'tools' }" @click="modalTab = 'tools'">
              {{ t('agents.tabs.tools', 'Tools') }}
              <span v-if="selectedToolNames.length" class="tab-badge">{{ selectedToolNames.length }}</span>
            </button>
          </div>

          <!-- Basic Tab -->
          <div v-if="modalTab === 'basic'" class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" :placeholder="t('agents.placeholders.name')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.icon') }}</label>
              <input v-model="form.icon" class="form-input" :placeholder="t('agents.placeholders.icon')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.type') }}</label>
              <select v-model="form.agentType" class="form-input">
                <option value="react">{{ t('agents.types.react') }}</option>
                <option value="plan_execute">{{ t('agents.types.planExecute') }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.maxIterations') }}</label>
              <input v-model.number="form.maxIterations" type="number" min="1" max="50" class="form-input" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.defaultThinkingLevel') }}</label>
              <select v-model="form.defaultThinkingLevel" class="form-input">
                <option :value="null">{{ t('agents.thinkingLevels.auto') }}</option>
                <option value="off">{{ t('agents.thinkingLevels.off') }}</option>
                <option value="low">{{ t('agents.thinkingLevels.low') }}</option>
                <option value="medium">{{ t('agents.thinkingLevels.medium') }}</option>
                <option value="high">{{ t('agents.thinkingLevels.high') }}</option>
                <option value="max">{{ t('agents.thinkingLevels.max') }}</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('agents.placeholders.description')" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.systemPrompt') }}</label>
              <textarea v-model="form.systemPrompt" class="form-textarea" rows="5" :placeholder="t('agents.placeholders.systemPrompt')"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.tags') }}</label>
              <input v-model="form.tags" class="form-input" :placeholder="t('agents.placeholders.tags')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.enabled') }}</label>
              <label class="toggle-switch" style="margin-top: 6px;">
                <input type="checkbox" v-model="form.enabled" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>

          <!-- Skills Tab -->
          <div v-if="modalTab === 'skills'" class="binding-tab">
            <p class="binding-hint">{{ t('agents.binding.skillsHint') }}</p>
            <div v-if="availableSkills.length === 0" class="binding-empty">{{ t('agents.binding.noSkills') }}</div>
            <div v-else class="binding-list">
              <label
                v-for="skill in availableSkills"
                :key="skill.id"
                class="binding-item"
                :class="{ selected: selectedSkillIds.includes(skill.id) }"
              >
                <input type="checkbox" :value="skill.id" v-model="selectedSkillIds" class="binding-checkbox" />
                <span class="binding-icon">{{ skill.icon || '🧩' }}</span>
                <div class="binding-info">
                  <span class="binding-name">{{ skill.name }}</span>
                  <span v-if="skill.description" class="binding-desc">{{ skill.description?.slice(0, 80) }}</span>
                </div>
                <span v-if="skill.version" class="binding-version">v{{ skill.version }}</span>
              </label>
            </div>
          </div>

          <!-- Tools Tab -->
          <div v-if="modalTab === 'tools'" class="binding-tab">
            <p class="binding-hint">{{ t('agents.binding.toolsHint') }}</p>
            <div v-if="availableTools.length === 0" class="binding-empty">{{ t('agents.binding.noTools') }}</div>
            <div v-else class="binding-list">
              <label
                v-for="tool in availableTools"
                :key="tool.name"
                class="binding-item"
                :class="{ selected: selectedToolNames.includes(tool.name) }"
              >
                <input type="checkbox" :value="tool.name" v-model="selectedToolNames" class="binding-checkbox" />
                <span class="binding-icon">{{ tool.icon || '🔧' }}</span>
                <div class="binding-info">
                  <span class="binding-name">{{ tool.displayName || tool.name }}</span>
                  <span v-if="tool.description" class="binding-desc">{{ tool.description?.slice(0, 80) }}</span>
                </div>
                <span class="binding-type-badge">{{ tool.toolType }}</span>
              </label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveAgent" :disabled="!form.name">
            {{ editingAgent ? t('agents.actions.update') : t('agents.actions.create') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi, agentBindingApi, skillApi, toolApi, templateApi } from '@/api/index'
import type { Agent } from '@/types/index'

const router = useRouter()
const { t } = useI18n()
const agents = ref<Agent[]>([])
const searchText = ref('')
const activeFilter = ref('all')
const showModal = ref(false)
const editingAgent = ref<Agent | null>(null)
const modalTab = ref<'basic' | 'skills' | 'tools'>('basic')

// Binding state
const availableSkills = ref<any[]>([])
const availableTools = ref<any[]>([])
const selectedSkillIds = ref<number[]>([])
const selectedToolNames = ref<string[]>([])

// Template selector state
const showTemplateSelector = ref(false)
const templates = ref<any[]>([])
const applyingTemplate = ref(false)

const filterTabs = [
  { key: 'agents.tabs.all', value: 'all' },
  { key: 'agents.tabs.react', value: 'react' },
  { key: 'agents.tabs.planExecute', value: 'plan_execute' },
  { key: 'agents.tabs.enabled', value: 'enabled' },
  { key: 'agents.tabs.disabled', value: 'disabled' },
]

const defaultForm = (): Partial<Agent> & { name: string; defaultThinkingLevel: string | null } => ({
  name: '',
  description: '',
  agentType: 'react',
  systemPrompt: '',
  maxIterations: 10,
  icon: '🤖',
  tags: '',
  enabled: true,
  defaultThinkingLevel: null,
})

const form = ref(defaultForm())

const filteredAgents = computed(() => {
  let list = agents.value
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    list = list.filter(a =>
      a.name.toLowerCase().includes(q) ||
      a.description?.toLowerCase().includes(q) ||
      a.tags?.toLowerCase().includes(q)
    )
  }
  if (activeFilter.value === 'react') list = list.filter(a => a.agentType === 'react')
  else if (activeFilter.value === 'plan_execute') list = list.filter(a => a.agentType === 'plan_execute')
  else if (activeFilter.value === 'enabled') list = list.filter(a => a.enabled)
  else if (activeFilter.value === 'disabled') list = list.filter(a => !a.enabled)
  return list
})

onMounted(() => {
  loadAgents()
})

async function loadAgents() {
  try {
    const res: any = await agentApi.list()
    agents.value = res.data || []
  } catch {
    ElMessage.error(t('agents.messages.loadFailed'))
  }
}

function parseTags(tags: string): string[] {
  return tags.split(',').map(s => s.trim()).filter(Boolean)
}

function formatTime(time?: string): string {
  if (!time) return '-'
  const d = new Date(time)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function openCreateModal() {
  // Show template selector first
  showTemplateSelector.value = true
  loadTemplates()
}

function openBlankCreateModal() {
  showTemplateSelector.value = false
  editingAgent.value = null
  form.value = defaultForm()
  modalTab.value = 'basic'
  selectedSkillIds.value = []
  selectedToolNames.value = []
  showModal.value = true
}

async function loadTemplates() {
  try {
    const res: any = await templateApi.list()
    templates.value = res.data || []
  } catch {
    // Fallback: skip templates, open blank form
    openBlankCreateModal()
  }
}

async function applyTemplate(id: string) {
  applyingTemplate.value = true
  try {
    await templateApi.apply(id)
    ElMessage.success(t('agents.templates.applied'))
    showTemplateSelector.value = false
    await loadAgents()
  } catch {
    ElMessage.error(t('agents.messages.saveFailed'))
  } finally {
    applyingTemplate.value = false
  }
}

async function openEditModal(agent: Agent) {
  editingAgent.value = agent
  form.value = {
    name: agent.name,
    description: agent.description || '',
    agentType: agent.agentType,
    systemPrompt: agent.systemPrompt || '',
    maxIterations: agent.maxIterations,
    icon: agent.icon || '🤖',
    tags: agent.tags || '',
    enabled: agent.enabled,
    defaultThinkingLevel: (agent as any).defaultThinkingLevel || null,
  }
  modalTab.value = 'basic'
  showModal.value = true

  // Load available skills/tools and current bindings in parallel
  try {
    const [skillsRes, toolsRes, boundSkillsRes, boundToolsRes] = await Promise.all([
      skillApi.list(),
      toolApi.list(),
      agentBindingApi.listSkills(agent.id),
      agentBindingApi.listTools(agent.id),
    ])
    availableSkills.value = (skillsRes as any).data || []
    availableTools.value = (toolsRes as any).data || []
    selectedSkillIds.value = ((boundSkillsRes as any).data || [])
      .filter((b: any) => b.enabled)
      .map((b: any) => b.skillId)
    selectedToolNames.value = ((boundToolsRes as any).data || [])
      .filter((b: any) => b.enabled)
      .map((b: any) => b.toolName)
  } catch {
    // Non-blocking: binding data load failure doesn't prevent editing basic info
  }
}

function closeModal() {
  showModal.value = false
  editingAgent.value = null
}

async function saveAgent() {
  try {
    let agentId: string | number
    if (editingAgent.value) {
      await agentApi.update(editingAgent.value.id, form.value)
      agentId = editingAgent.value.id
    } else {
      const res: any = await agentApi.create(form.value)
      agentId = res.data?.id
    }

    // Save bindings (only for existing agents or after create returns id)
    if (agentId && editingAgent.value) {
      await Promise.all([
        agentBindingApi.setSkills(agentId, selectedSkillIds.value),
        agentBindingApi.setTools(agentId, selectedToolNames.value),
      ])
    }

    ElMessage.success(t('agents.messages.saveSuccess'))
    closeModal()
    await loadAgents()
  } catch {
    ElMessage.error(t('agents.messages.saveFailed'))
  }
}

async function deleteAgent(agent: Agent) {
  try {
    await ElMessageBox.confirm(t('agents.messages.deleteConfirm'), t('agents.actions.delete'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await agentApi.delete(agent.id)
    ElMessage.success(t('agents.messages.deleteSuccess'))
    await loadAgents()
  } catch {
    ElMessage.error(t('agents.messages.deleteFailed'))
  }
}

function goToAgentContext() {
  const agentId = editingAgent.value?.id
  closeModal()
  router.push({ path: '/settings/agent-context', query: agentId ? { agentId: String(agentId) } : {} })
}

function goToAgentContextFor(agent: Agent) {
  router.push({ path: '/settings/agent-context', query: { agentId: String(agent.id) } })
}

async function toggleAgent(agent: Agent) {
  try {
    await agentApi.update(agent.id, { ...agent, enabled: !agent.enabled })
    ElMessage.success(t('agents.messages.toggleSuccess'))
    await loadAgents()
  } catch {
    ElMessage.error(t('agents.messages.toggleFailed'))
  }
}
</script>

<style scoped>
.agents-page { gap: 18px; }

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; transition: background 0.15s, transform 0.15s; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.agents-toolbar { padding: 18px; }
.filter-bar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.search-box { display: flex; align-items: center; gap: 8px; background: var(--mc-bg-muted); border: 1px solid var(--mc-border); border-radius: 14px; padding: 10px 12px; flex: 1; max-width: 360px; }
.search-box svg { color: var(--mc-text-tertiary); flex-shrink: 0; }
.search-input { border: none; outline: none; font-size: 14px; color: var(--mc-text-primary); flex: 1; background: transparent; }
.filter-tabs { display: flex; gap: 6px; flex-wrap: wrap; }
.filter-tab { padding: 8px 14px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.filter-tab:hover { background: var(--mc-bg-sunken); }
.filter-tab.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 500; }

/* Agent Card Grid */
.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.agent-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  transition: all 0.15s;
  cursor: default;
}

.agent-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.agent-card--disabled {
  opacity: 0.55;
}

.agent-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.agent-card__icon {
  font-size: 36px;
  width: 52px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-primary-bg);
  border-radius: 14px;
}

.agent-card__body {
  flex: 1;
  min-height: 0;
}

.agent-card__name {
  font-size: 16px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0 0 4px;
  letter-spacing: -0.02em;
}

.agent-card__desc {
  font-size: 13px;
  color: var(--mc-text-tertiary);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

.agent-card__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.agent-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid var(--mc-border-light);
}

.agent-card__actions {
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

.agent-card:hover .agent-card__actions {
  opacity: 1;
}

.toggle-switch--sm { width: 32px; height: 18px; }
.toggle-switch--sm .toggle-slider::before { width: 12px; height: 12px; }
.toggle-switch--sm input:checked + .toggle-slider::before { transform: translateX(14px); }

/* Context link card */
.context-link-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  cursor: pointer;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  transition: all 0.15s;
}
.context-link-card:hover {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}
.context-link-card__icon { font-size: 28px; }
.context-link-card__info { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.context-link-card__title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.context-link-card__desc { font-size: 12px; color: var(--mc-text-tertiary); }
.context-link-card__arrow { color: var(--mc-text-tertiary); flex-shrink: 0; }

.tag { padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 500; }
.type-tag { background: var(--mc-primary-bg); color: var(--mc-primary); }
.tag-item { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.tags-cell { display: flex; gap: 4px; flex-wrap: wrap; }
.time-label { font-size: 13px; color: var(--mc-text-tertiary); }
.text-muted { color: var(--mc-text-tertiary); }

.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }

.action-btns { display: flex; gap: 4px; }
.action-btn { width: 30px; height: 30px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 6px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: var(--mc-text-secondary); transition: all 0.15s; }
.action-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.action-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

/* Empty state */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 80px 20px; text-align: center; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state h3 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 8px; }
.empty-state p { font-size: 14px; color: var(--mc-text-tertiary); margin: 0 0 24px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 600px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }

/* Modal Tabs */
.modal-tabs { display: flex; gap: 4px; margin-bottom: 20px; border-bottom: 1px solid var(--mc-border-light); padding-bottom: 0; }
.modal-tab {
  padding: 8px 16px; border: none; background: none; cursor: pointer;
  font-size: 13px; font-weight: 500; color: var(--mc-text-tertiary);
  border-bottom: 2px solid transparent; margin-bottom: -1px; transition: all 0.15s;
  display: inline-flex; align-items: center; gap: 6px;
}
.modal-tab:hover { color: var(--mc-text-primary); }
.modal-tab.active { color: var(--mc-primary); border-bottom-color: var(--mc-primary); }
.tab-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: 9px; background: var(--mc-primary); color: white;
  font-size: 11px; font-weight: 600;
}

/* Binding Tab */
.binding-tab { min-height: 200px; }
.binding-hint { font-size: 13px; color: var(--mc-text-tertiary); margin: 0 0 16px; }
.binding-empty { padding: 40px; text-align: center; color: var(--mc-text-tertiary); font-size: 14px; }
.binding-list { display: flex; flex-direction: column; gap: 6px; }
.binding-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border: 1px solid var(--mc-border-light); border-radius: 8px;
  cursor: pointer; transition: all 0.15s; background: var(--mc-bg);
}
.binding-item:hover { border-color: var(--mc-primary-light, rgba(217,119,87,0.3)); background: var(--mc-bg-elevated); }
.binding-item.selected { border-color: var(--mc-primary); background: rgba(217,119,87,0.04); }
.binding-checkbox { flex-shrink: 0; accent-color: var(--mc-primary); width: 16px; height: 16px; }
.binding-icon { font-size: 20px; flex-shrink: 0; }
.binding-info { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.binding-name { font-size: 14px; font-weight: 500; color: var(--mc-text-primary); }
.binding-desc { font-size: 12px; color: var(--mc-text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.binding-version { font-size: 11px; color: var(--mc-text-tertiary); flex-shrink: 0; }
.binding-type-badge {
  font-size: 10px; padding: 2px 6px; border-radius: 4px; flex-shrink: 0;
  background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); text-transform: uppercase;
}

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; transition: border-color 0.15s; background: var(--mc-bg-sunken); }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-textarea { resize: vertical; font-family: inherit; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

@media (max-width: 900px) {
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-box {
    max-width: none;
  }
}

/* Template Selector */
.template-modal { max-width: 640px; }
.template-desc { font-size: 14px; color: var(--mc-text-secondary); margin: 0 0 18px; }

.template-grid { display: flex; flex-direction: column; gap: 10px; }

.template-card {
  display: flex; align-items: flex-start; gap: 14px; padding: 16px; cursor: pointer;
  border: 1px solid var(--mc-border); border-radius: 12px; transition: all 0.15s;
}
.template-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.template-card.applying { opacity: 0.5; pointer-events: none; }

.template-icon { font-size: 28px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; background: var(--mc-bg-muted); border-radius: 10px; flex-shrink: 0; }

.template-info { flex: 1; min-width: 0; }
.template-name { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 4px; }
.template-detail { font-size: 13px; color: var(--mc-text-secondary); margin: 0; line-height: 1.5; }

.template-tags { display: flex; flex-wrap: wrap; gap: 4px; align-self: flex-start; margin-top: 2px; }
.tag-chip { font-size: 11px; padding: 2px 8px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border-radius: 999px; white-space: nowrap; }
</style>
