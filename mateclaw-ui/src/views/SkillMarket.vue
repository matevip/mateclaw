<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner skills-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Capabilities</div>
            <h1 class="mc-page-title">{{ t('skills.title') }}</h1>
            <p class="mc-page-desc">{{ t('skills.desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="btn-secondary" @click="handleRefreshRuntime" :disabled="refreshing">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
              </svg>
              {{ refreshing ? t('skills.refreshing') : t('skills.refreshRuntime') }}
            </button>
            <button class="btn-secondary" @click="showImportDialog = true">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
              </svg>
              {{ t('skills.importSkill') }}
            </button>
            <button class="btn-primary" @click="openCreateModal">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              {{ t('skills.newSkill') }}
            </button>
          </div>
        </div>

        <!-- 分类 Tab -->
        <div class="category-tabs mc-surface-card">
          <button v-for="tab in categoryTabs" :key="tab.value" class="cat-tab"
            :class="{ active: query.skillType === tab.value }" @click="onTabChange(tab.value)">
            <span class="cat-icon">{{ tab.icon }}</span>
            {{ tab.label }}
            <span class="cat-count">{{ getCategoryCount(tab.value) }}</span>
          </button>
        </div>

        <!-- Search + status filter (RFC-042 §2.1) -->
        <div class="skill-filter-bar mc-surface-card">
          <input
            v-model="query.keyword"
            class="skill-search-input"
            type="search"
            :placeholder="t('skills.search.placeholder')"
          />
          <select v-model="query.enabledFilter" class="skill-status-filter" @change="onFilterChange">
            <option value="">{{ t('skills.filter.all') }}</option>
            <option value="true">{{ t('skills.filter.enabled') }}</option>
            <option value="false">{{ t('skills.filter.disabled') }}</option>
          </select>
        </div>

        <!-- 技能列表 -->
        <div class="skill-grid" v-if="skills.length > 0">
          <div v-for="skill in skills" :key="skill.id" class="skill-card mc-surface-card"
        :class="{ disabled: !skill.enabled }">
        <div class="skill-header">
          <div class="skill-icon-wrap" :class="getSkillIconBg(skill.skillType)">
            <span class="skill-icon">{{ skill.icon || getSkillIcon(skill.skillType) }}</span>
          </div>
          <div class="skill-meta">
            <h3 class="skill-name">{{ skill.name }}</h3>
            <div class="skill-meta-row">
              <span class="skill-type-badge" :class="getSkillTypeBadge(skill.skillType)">
                {{ getSkillTypeLabel(skill.skillType) }}
              </span>
              <span v-if="skill.version" class="skill-version">v{{ skill.version }}</span>
            </div>
          </div>
          <label class="toggle-switch">
            <input type="checkbox" :checked="skill.enabled" @change="toggleSkill(skill)" />
            <span class="toggle-slider"></span>
          </label>
        </div>
        <p class="skill-desc">{{ skill.description || t('skills.noDescription') }}</p>

        <!-- Runtime Status -->
        <div class="skill-runtime-row">
          <span class="runtime-badge" :class="getRuntimeBadgeClass(skill)">
            {{ getRuntimeLabel(skill) }}
          </span>
          <!-- RFC-023: AI Synthesized Badge -->
          <span v-if="skill.sourceConversationId" class="runtime-badge rt-synthesized" title="Auto-synthesized from conversation">
            🤖 AI
          </span>
          <!-- Security Scan Status (RFC-023) -->
          <span v-if="skill.securityScanStatus === 'FAILED'" class="runtime-badge rt-blocked">
            🛡️ Scan Failed
          </span>
          <span v-else-if="skill.securityScanStatus === 'PASSED'" class="runtime-badge rt-ready">
            ✓ Scanned
          </span>
          <!-- Security Badge (runtime) -->
          <span v-if="getSecurityBadge(skill)" class="runtime-badge" :class="getSecurityBadge(skill)?.cls">
            {{ getSecurityBadge(skill)?.label }}
          </span>
          <!-- Dependency Badge -->
          <span v-if="getDependencyBadge(skill)" class="runtime-badge" :class="getDependencyBadge(skill)?.cls">
            {{ getDependencyBadge(skill)?.label }}
          </span>
          <span v-if="getSourceBadge(skill)" class="source-badge">{{ getSourceBadge(skill) }}</span>
          <span v-if="getRuntimePath(skill)" class="skill-source-path">{{ getRuntimePath(skill) }}</span>
        </div>
        <!-- Missing Dependencies Detail -->
        <div v-if="getMissingDeps(skill).length > 0" class="runtime-deps-missing">
          Missing: {{ getMissingDeps(skill).join(', ') }}
        </div>
        <!-- Security Findings Summary -->
        <div v-if="getSecurityFindingsSummary(skill)" class="runtime-security-detail">
          {{ getSecurityFindingsSummary(skill) }}
        </div>
        <div v-if="getRuntimeError(skill)" class="runtime-error">{{ getRuntimeError(skill) }}</div>

        <div class="skill-tags" v-if="skill.tags">
          <span v-for="tag in parseTags(skill.tags)" :key="tag" class="skill-tag">{{ tag }}</span>
        </div>
        <div class="skill-footer">
          <span v-if="skill.author" class="skill-author">by {{ skill.author }}</span>
          <div class="skill-actions">
            <button class="skill-btn" @click="openEditModal(skill)">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
              {{ t('skills.actions.configure') }}
            </button>
            <button v-if="skill.skillType !== 'builtin'" class="skill-btn danger" @click="deleteSkill(skill.id)">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
              </svg>
              {{ t('skills.actions.delete') }}
            </button>
          </div>
        </div>
          </div>
        </div>

        <div v-else class="empty-state mc-surface-card">
          <div class="empty-icon">🛠️</div>
          <h3>{{ t('skills.empty') }}</h3>
          <p>{{ t('skills.emptyDesc') }}</p>
        </div>

        <!-- Pagination (RFC-042 §2.1) -->
        <el-pagination
          v-if="total > 0"
          class="skill-pagination"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[12, 24, 48]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="onPageSizeChange"
          @current-change="loadSkills"
        />
      </div>
    </div>

    <!-- Import Hub Dialog -->
    <ImportHubDialog v-model:visible="showImportDialog" @installed="loadAll" />

    <!-- Modal -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editingSkill ? t('skills.modal.configureTitle') : t('skills.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" :placeholder="t('skills.placeholders.name')"
                :disabled="isBuiltinEditing" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.type') }}</label>
              <select v-model="form.skillType" class="form-input" :disabled="isBuiltinEditing">
                <option value="dynamic">{{ t('skills.types.dynamic') }}</option>
                <option value="mcp">{{ t('skills.types.mcp') }}</option>
                <option value="builtin" v-if="isBuiltinEditing">{{ t('skills.types.builtin') }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.icon') }}</label>
              <input v-model="form.icon" class="form-input" :placeholder="t('skills.placeholders.icon')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.version') }}</label>
              <input v-model="form.version" class="form-input" :placeholder="t('skills.placeholders.version')"
                :disabled="isBuiltinEditing" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.author') }}</label>
              <input v-model="form.author" class="form-input" :placeholder="t('skills.placeholders.author')"
                :disabled="isBuiltinEditing" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('skills.fields.tags') }}</label>
              <input v-model="form.tags" class="form-input" :placeholder="t('skills.placeholders.tags')" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('skills.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('skills.placeholders.description')" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('skills.fields.configJson') }}</label>
              <textarea v-model="form.configJson" class="form-textarea" rows="3" placeholder='{"key": "value"}'></textarea>
            </div>
            <div class="form-group full-width">
              <label class="form-label">
                {{ t('skills.fields.skillContent') }}
                <span class="form-hint" v-if="getEditingRuntimeSource() === 'directory'">
                  {{ t('skills.hints.directorySkill') }}
                </span>
                <span class="form-hint" v-else>
                  {{ t('skills.hints.primaryContent') }}
                </span>
              </label>
              <textarea v-model="form.skillContent" class="form-textarea code" rows="8"
                placeholder="# Skill Guide&#10;&#10;## When to use&#10;..."></textarea>
            </div>
            <div class="form-group full-width" v-if="form.skillType === 'dynamic'">
              <label class="form-label">{{ t('skills.fields.sourceCode') }}</label>
              <textarea v-model="form.sourceCode" class="form-textarea code" rows="6"
                :placeholder="t('skills.placeholders.sourceCode')"></textarea>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveSkill" :disabled="!form.name">
            {{ editingSkill ? t('skills.actions.saveChanges') : t('skills.actions.createSkill') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { skillApi } from '@/api/index'
import type { Skill, SkillRuntimeStatus, SkillSecurityFinding } from '@/types/index'
import ImportHubDialog from '@/components/skill/ImportHubDialog.vue'

const { t } = useI18n()
const skills = ref<Skill[]>([])
const total = ref(0)
const counts = ref<Record<string, number>>({})
const runtimeStatusMap = ref<Record<string, SkillRuntimeStatus>>({})
const showModal = ref(false)
const editingSkill = ref<Skill | null>(null)
const refreshing = ref(false)
const showImportDialog = ref(false)

/** Paginated query state — RFC-042 §2.1 */
const query = reactive({
  page: 1,
  size: 24,
  keyword: '',
  skillType: 'all' as string,
  /** '' = all, 'true' = enabled only, 'false' = disabled only (string to avoid tri-state checkbox quirks) */
  enabledFilter: '' as string,
})

const categoryTabs = computed(() => [
  { label: t('skills.tabs.all'), value: 'all', icon: '🗂️' },
  { label: t('skills.tabs.builtin'), value: 'builtin', icon: '🔧' },
  { label: t('skills.tabs.mcp'), value: 'mcp', icon: '🔌' },
  { label: t('skills.tabs.dynamic'), value: 'dynamic', icon: '📦' },
])

const defaultForm = () => ({
  name: '',
  description: '',
  skillType: 'dynamic' as string,
  icon: '',
  version: '1.0.0',
  author: '',
  tags: '',
  configJson: '',
  sourceCode: '',
  skillContent: '',
  enabled: true,
})
const form = ref<any>(defaultForm())

/** 是否正在编辑内置技能（限制可编辑字段） */
const isBuiltinEditing = computed(() => editingSkill.value?.skillType === 'builtin')

function getCategoryCount(category: string) {
  return counts.value[category] ?? 0
}

function parseTags(tags: string): string[] {
  if (!tags) return []
  return tags.split(',').map(t => t.trim()).filter(Boolean)
}

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadSkills(), loadCounts(), loadRuntimeStatus()])
}

/** Coalesce keyword edits into one server call per 300ms so typing doesn't thrash. */
let searchDebounce: ReturnType<typeof setTimeout> | null = null
watch(() => query.keyword, () => {
  if (searchDebounce) clearTimeout(searchDebounce)
  searchDebounce = setTimeout(() => {
    query.page = 1
    loadSkills()
  }, 300)
})

function onTabChange(tab: string) {
  query.skillType = tab
  query.page = 1
  loadSkills()
}

function onFilterChange() {
  query.page = 1
  loadSkills()
}

function onPageSizeChange() {
  query.page = 1
  loadSkills()
}

async function loadSkills() {
  try {
    const params: Record<string, unknown> = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword.trim()
    if (query.skillType && query.skillType !== 'all') params.skillType = query.skillType
    if (query.enabledFilter !== '') params.enabled = query.enabledFilter === 'true'

    const res: any = await skillApi.page(params)
    const data = res.data || {}
    skills.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    skills.value = []
    total.value = 0
  }
}

async function loadCounts() {
  try {
    const res: any = await skillApi.counts()
    counts.value = res.data || {}
  } catch (e) {
    counts.value = {}
  }
}

async function loadRuntimeStatus() {
  try {
    const res: any = await skillApi.getRuntimeStatus()
    const list: SkillRuntimeStatus[] = res.data || []
    const map: Record<string, SkillRuntimeStatus> = {}
    for (const s of list) {
      map[s.name] = s
    }
    runtimeStatusMap.value = map
  } catch (e) {
    runtimeStatusMap.value = {}
  }
}

function openCreateModal() {
  editingSkill.value = null
  form.value = defaultForm()
  showModal.value = true
}

function openEditModal(skill: Skill) {
  editingSkill.value = skill
  form.value = {
    name: skill.name,
    description: skill.description || '',
    skillType: skill.skillType,
    icon: skill.icon || '',
    version: skill.version || '',
    author: skill.author || '',
    tags: skill.tags || '',
    configJson: skill.configJson || '',
    sourceCode: skill.sourceCode || '',
    skillContent: skill.skillContent || '',
    enabled: skill.enabled,
  }
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editingSkill.value = null
}

function getEditingRuntimeSource(): string {
  if (!editingSkill.value) return ''
  const rt = runtimeStatusMap.value[editingSkill.value.name]
  return rt?.source || ''
}

async function saveSkill() {
  try {
    if (editingSkill.value) {
      await skillApi.update(editingSkill.value.id, form.value)
    } else {
      await skillApi.create(form.value)
    }
    closeModal()
    await loadAll()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.saveFailed'))
  }
}

async function deleteSkill(id: string | number) {
  try {
    await ElMessageBox.confirm(t('skills.messages.deleteConfirm'), t('skills.messages.deleteTitle'), { type: 'warning' })
  } catch { return }
  try {
    await skillApi.delete(id)
    await loadAll()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.deleteFailed'))
  }
}

async function toggleSkill(skill: Skill) {
  try {
    await skillApi.toggle(skill.id, !skill.enabled)
    await loadAll()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.toggleFailed'))
  }
}

async function handleRefreshRuntime() {
  refreshing.value = true
  try {
    await skillApi.refreshRuntime()
    await loadRuntimeStatus()
    ElMessage.success(t('skills.refreshSuccess'))
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.refreshFailed'))
  } finally {
    refreshing.value = false
  }
}

// ==================== Runtime Display Helpers ====================

function getRuntimeStatus(skill: Skill): SkillRuntimeStatus | null {
  return runtimeStatusMap.value[skill.name] || null
}

function getRuntimeLabel(skill: Skill): string {
  if (!skill.enabled) return 'Disabled'
  const rt = getRuntimeStatus(skill)
  if (!rt) return 'Unknown'
  // Use computed label from backend if available
  if (rt.runtimeStatusLabel) return rt.runtimeStatusLabel
  if (rt.securityBlocked) return 'Security Blocked'
  if (rt.dependencyReady === false) return 'Dependencies Missing'
  if (rt.source === 'directory' && rt.runtimeAvailable) return 'Directory Active'
  if (rt.source === 'convention' && rt.runtimeAvailable) return 'Convention Active'
  if (rt.source === 'database' && rt.runtimeAvailable && rt.configuredSkillDir) return 'Database Fallback'
  if (rt.source === 'database' && rt.runtimeAvailable) return 'Database Active'
  if (!rt.runtimeAvailable) return 'Unresolved'
  return rt.source
}

function getRuntimeBadgeClass(skill: Skill): string {
  if (!skill.enabled) return 'rt-disabled'
  const rt = getRuntimeStatus(skill)
  if (!rt) return 'rt-unknown'
  if (rt.securityBlocked) return 'rt-blocked'
  if (rt.dependencyReady === false) return 'rt-deps-missing'
  if (rt.source === 'directory' && rt.runtimeAvailable) return 'rt-directory'
  if (rt.source === 'convention' && rt.runtimeAvailable) return 'rt-convention'
  if (rt.source === 'database' && rt.runtimeAvailable && rt.configuredSkillDir) return 'rt-fallback'
  if (rt.source === 'database' && rt.runtimeAvailable) return 'rt-database'
  if (!rt.runtimeAvailable) return 'rt-error'
  return 'rt-unknown'
}

function getRuntimePath(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt) return ''
  return rt.skillDirPath || rt.configuredSkillDir || ''
}

function getRuntimeError(skill: Skill): string {
  if (!skill.enabled) return ''
  const rt = getRuntimeStatus(skill)
  return rt?.resolutionError || ''
}

// ==================== Security & Dependency Helpers ====================

function getSecurityBadge(skill: Skill): { label: string; cls: string } | null {
  if (!skill.enabled) return null
  const rt = getRuntimeStatus(skill)
  if (!rt) return null
  if (rt.securityBlocked) return { label: 'Security Blocked', cls: 'rt-blocked' }
  if (rt.securityFindings && rt.securityFindings.length > 0) {
    return { label: `Security Warning (${rt.securityFindings.length})`, cls: 'rt-sec-warning' }
  }
  return null
}

function getDependencyBadge(skill: Skill): { label: string; cls: string } | null {
  if (!skill.enabled) return null
  const rt = getRuntimeStatus(skill)
  if (!rt) return null
  if (rt.dependencyReady === false) {
    const count = rt.missingDependencies?.length || 0
    return { label: `Deps Missing (${count})`, cls: 'rt-deps-missing' }
  }
  return null
}

function getMissingDeps(skill: Skill): string[] {
  const rt = getRuntimeStatus(skill)
  return rt?.missingDependencies || []
}

function getSecurityFindingsSummary(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt || !rt.securityFindings || rt.securityFindings.length === 0) return ''
  const critical = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'CRITICAL').length
  const high = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'HIGH').length
  const medium = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'MEDIUM').length
  const parts: string[] = []
  if (critical > 0) parts.push(`${critical} critical`)
  if (high > 0) parts.push(`${high} high`)
  if (medium > 0) parts.push(`${medium} medium`)
  return parts.length > 0 ? `Findings: ${parts.join(', ')}` : ''
}

// ==================== UI Helpers ====================

function getSourceBadge(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt) return ''
  if (rt.source === 'convention') return '📁'
  // 检查 configJson 中的 source.type
  try {
    const config = skill.configJson ? JSON.parse(skill.configJson) : null
    const sourceType = config?.source?.type || config?.upstream
    if (sourceType === 'github') return '🐙'
    if (sourceType === 'clawhub') return '🐾'
  } catch { /* ignore */ }
  return ''
}

function getSkillIcon(type: string) {
  return { builtin: '🔧', mcp: '🔌', dynamic: '📦' }[type] ?? '🛠️'
}

function getSkillIconBg(type: string) {
  return { builtin: 'bg-blue', mcp: 'bg-purple', dynamic: 'bg-green' }[type] ?? 'bg-gray'
}

function getSkillTypeBadge(type: string) {
  return { builtin: 'badge-blue', mcp: 'badge-purple', dynamic: 'badge-green' }[type] ?? 'badge-gray'
}

function getSkillTypeLabel(type: string) {
  const map: Record<string, string> = { builtin: t('skills.types.builtin'), mcp: t('skills.types.mcp'), dynamic: t('skills.types.dynamic') }
  return map[type] ?? type
}
</script>

<style scoped>
.skills-page { gap: 18px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; transition: background 0.15s; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: flex; align-items: center; gap: 6px; padding: 9px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; }

/* 分类 Tab */
.category-tabs { display: flex; gap: 8px; flex-wrap: wrap; padding: 14px; }
.cat-tab { display: flex; align-items: center; gap: 6px; padding: 9px 16px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.cat-tab:hover { background: var(--mc-bg-sunken); }
.cat-tab.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 500; }
.cat-icon { font-size: 14px; }
.cat-count { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); padding: 1px 6px; border-radius: 10px; font-size: 11px; }
.cat-tab.active .cat-count { background: rgba(217, 119, 87, 0.2); color: var(--mc-primary); }

/* RFC-042 §2.1 — search + status filter bar (frosted glass) */
.skill-filter-bar {
  display: flex;
  gap: 10px;
  padding: 10px 14px;
  align-items: center;
  backdrop-filter: blur(14px) saturate(1.1);
  -webkit-backdrop-filter: blur(14px) saturate(1.1);
}
.skill-search-input,
.skill-status-filter {
  height: 34px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.45);
  border-radius: 10px;
  font-size: 13px;
  color: var(--mc-text-primary);
  outline: none;
  transition: background 0.18s, border-color 0.18s, box-shadow 0.18s;
}
html.dark .skill-search-input,
html.dark .skill-status-filter {
  background: rgba(255, 255, 255, 0.06);
}
.skill-search-input { flex: 1; padding: 0 12px; }
.skill-status-filter { padding: 0 10px; cursor: pointer; min-width: 140px; }
.skill-search-input:hover,
.skill-status-filter:hover {
  background: rgba(255, 255, 255, 0.65);
}
html.dark .skill-search-input:hover,
html.dark .skill-status-filter:hover {
  background: rgba(255, 255, 255, 0.1);
}
.skill-search-input:focus,
.skill-status-filter:focus {
  border-color: rgba(217, 119, 87, 0.45);
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
html.dark .skill-search-input:focus,
html.dark .skill-status-filter:focus {
  background: rgba(255, 255, 255, 0.12);
}

/* Pagination — strip Element Plus's heavy boxed look, blend with the glass surface */
.skill-pagination { margin-top: 18px; display: flex; justify-content: center; }
.skill-pagination :deep(.el-pagination) {
  --el-pagination-bg-color: transparent;
  --el-pagination-button-bg-color: transparent;
  --el-pagination-hover-color: var(--mc-primary);
  background: transparent;
  font-weight: 500;
  color: var(--mc-text-secondary);
}
.skill-pagination :deep(.el-pagination .btn-prev),
.skill-pagination :deep(.el-pagination .btn-next),
.skill-pagination :deep(.el-pagination .el-pager li),
.skill-pagination :deep(.el-pagination .el-input__wrapper),
.skill-pagination :deep(.el-pagination .el-select .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.45) !important;
  box-shadow: none !important;
  border: 1px solid transparent;
  border-radius: 8px;
  transition: background 0.15s, border-color 0.15s;
}
html.dark .skill-pagination :deep(.el-pagination .btn-prev),
html.dark .skill-pagination :deep(.el-pagination .btn-next),
html.dark .skill-pagination :deep(.el-pagination .el-pager li),
html.dark .skill-pagination :deep(.el-pagination .el-input__wrapper),
html.dark .skill-pagination :deep(.el-pagination .el-select .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06) !important;
}
.skill-pagination :deep(.el-pagination .btn-prev:hover),
.skill-pagination :deep(.el-pagination .btn-next:hover),
.skill-pagination :deep(.el-pagination .el-pager li:hover) {
  background: rgba(217, 119, 87, 0.12) !important;
  color: var(--mc-primary);
}
.skill-pagination :deep(.el-pagination .el-pager li.is-active) {
  background: var(--mc-primary) !important;
  color: #fff;
  border-color: transparent;
}
.skill-pagination :deep(.el-pagination .el-pagination__total),
.skill-pagination :deep(.el-pagination .el-pagination__sizes) {
  margin-right: 12px;
}

/* 技能网格 */
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
.skill-card { padding: 18px; transition: all 0.15s; display: flex; flex-direction: column; min-height: 280px; }
.skill-card:hover { border-color: var(--mc-primary-light); box-shadow: var(--mc-shadow-medium); transform: translateY(-2px); }
.skill-card.disabled { opacity: 0.6; }
.skill-header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.skill-icon-wrap { width: 44px; height: 44px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.bg-blue { background: var(--mc-primary-bg); }
.bg-purple { background: var(--mc-primary-bg); }
.bg-green { background: var(--mc-primary-bg); }
.bg-gray { background: var(--mc-bg-sunken); }
.skill-icon { font-size: 20px; }
.skill-meta { flex: 1; overflow: hidden; }
.skill-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-meta-row { display: flex; align-items: center; gap: 6px; }
.skill-type-badge { padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 500; }
.badge-blue { background: var(--mc-primary-bg); color: var(--mc-primary); }
.badge-purple { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.badge-green { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.badge-gray { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.skill-version { font-size: 11px; color: var(--mc-text-tertiary); }
.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }
.skill-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; flex: 1; }

/* Runtime Status */
.skill-runtime-row { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; min-height: 20px; flex-wrap: wrap; }
.runtime-badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; letter-spacing: 0.02em; }
.rt-directory { background: var(--mc-primary-bg); color: var(--mc-primary); }
.rt-convention { background: var(--mc-primary-bg); color: var(--mc-primary); }
.source-badge { font-size: 13px; cursor: default; }
.rt-database { background: var(--mc-primary-bg); color: var(--mc-primary); }
.rt-fallback { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.rt-error { background: var(--mc-danger-bg); color: var(--mc-danger); }
.rt-blocked { background: var(--mc-danger-bg); color: var(--mc-danger); font-weight: 600; }
.rt-synthesized { background: #f0f0ff; color: #6366f1; }
:root.dark .rt-synthesized { background: rgba(99, 102, 241, 0.15); color: #818cf8; }
.rt-sec-warning { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.rt-deps-missing { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.rt-disabled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.rt-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.skill-source-path { font-size: 11px; color: var(--mc-text-tertiary); font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }
.runtime-error { font-size: 11px; color: var(--mc-text-tertiary); margin: 0 0 6px; line-height: 1.4; font-style: italic; }
.runtime-deps-missing { font-size: 11px; color: var(--mc-primary); margin: 0 0 4px; line-height: 1.4; font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; }
.runtime-security-detail { font-size: 11px; color: var(--mc-primary-hover); margin: 0 0 4px; line-height: 1.4; }

/* Tags */
.skill-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 10px; }
.skill-tag { padding: 2px 8px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); border-radius: 4px; font-size: 11px; }

/* Footer */
.skill-footer { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--mc-border-light); padding-top: 12px; }
.skill-author { font-size: 12px; color: var(--mc-text-tertiary); }
.skill-actions { display: flex; gap: 6px; }
.skill-btn { display: flex; align-items: center; gap: 4px; padding: 7px 11px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.skill-btn:hover { background: var(--mc-bg-sunken); }
.skill-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

/* Empty */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 80px 20px; text-align: center; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state h3 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 8px; }
.empty-state p { font-size: 14px; color: var(--mc-text-tertiary); margin: 0; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 580px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); display: flex; align-items: baseline; gap: 6px; }
.form-hint { font-size: 11px; font-weight: 400; color: var(--mc-text-tertiary); }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; transition: border-color 0.15s; background: var(--mc-bg-sunken); }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-input:disabled, .form-textarea:disabled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); cursor: not-allowed; }
.form-textarea { resize: vertical; font-family: inherit; }
.form-textarea.code { font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 13px; background: var(--mc-bg-sunken); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

@media (max-width: 900px) {
  .header-actions {
    width: 100%;
  }
}
</style>
