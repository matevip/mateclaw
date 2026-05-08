<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner triggers-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('triggers.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('triggers.title') }}</h1>
            <p class="mc-page-desc">{{ t('triggers.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreate">{{ t('triggers.newTrigger') }}</button>
        </div>

        <table class="triggers-table mc-surface-card">
          <thead>
            <tr>
              <th>{{ t('triggers.columns.name') }}</th>
              <th>{{ t('triggers.columns.pattern') }}</th>
              <th>{{ t('triggers.columns.target') }}</th>
              <th>{{ t('triggers.columns.rate') }}</th>
              <th>{{ t('triggers.columns.fires') }}</th>
              <th>{{ t('triggers.columns.patternVersion') }}</th>
              <th>{{ t('triggers.columns.lastFired') }}</th>
              <th>{{ t('triggers.columns.state') }}</th>
              <th>{{ t('triggers.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in triggers" :key="row.id">
              <td>
                <div class="trigger-name">{{ row.name || t('triggers.unnamed') }}</div>
                <div v-if="row.lastError" class="trigger-last-error" :title="row.lastError">
                  ⚠ {{ truncateError(row.lastError) }}
                </div>
              </td>
              <td>
                <span class="trigger-type-pill" :title="row.patternType">{{ patternTypeLabel(row.patternType) }}</span>
                <div class="pattern-summary" :title="row.patternJson">{{ patternSummary(row) }}</div>
              </td>
              <td>{{ formatTarget(row) }}</td>
              <td>{{ t('triggers.rateUnit', { count: row.rateLimitPerMin }) }}</td>
              <td>{{ row.fireCount }}<span v-if="row.maxFires > 0"> / {{ row.maxFires }}</span></td>
              <td>{{ row.patternVersion }}</td>
              <td>
                <div>{{ formatTime(row.lastFiredAt) }}</div>
                <div v-if="row.lastDispatchedAt && row.lastDispatchedAt !== row.lastFiredAt"
                     class="trigger-attempt-time"
                     :title="t('triggers.lastDispatchedAt')">
                  {{ formatTime(row.lastDispatchedAt) }}
                </div>
              </td>
              <td>
                <label class="toggle">
                  <input type="checkbox" :checked="row.enabled" @change="toggleEnabled(row)" />
                  <span>{{ row.enabled ? t('triggers.enabled') : t('triggers.disabled') }}</span>
                </label>
              </td>
              <td class="actions">
                <button class="btn-ghost" @click="openEdit(row)">{{ t('triggers.actions.edit') }}</button>
                <button class="btn-danger" @click="remove(row)">{{ t('triggers.actions.delete') }}</button>
              </td>
            </tr>
            <tr v-if="!triggers.length">
              <td colspan="9" class="empty-row">{{ t('triggers.empty') }}</td>
            </tr>
          </tbody>
        </table>

        <Teleport to="body">
          <div v-if="formOpen" class="trigger-modal-overlay" @click.self="closeForm">
            <section class="trigger-modal" role="dialog" aria-modal="true">
              <header class="trigger-modal-header">
                <strong>{{ editing?.id ? t('triggers.formTitleEdit') : t('triggers.formTitleNew') }}</strong>
                <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.close') }}</button>
              </header>

              <div class="trigger-modal-body">
                <div class="form-grid">
                  <label>{{ t('triggers.fields.name') }}
                    <input v-model="formState.name" :placeholder="t('triggers.fields.namePlaceholder')" />
                  </label>
                  <label>{{ t('triggers.fields.patternType') }}
                    <!-- v0 ships four pattern types in the manual create UI:
                         cron, channel_message (which now carries the keyword
                         filter that content_match used to provide),
                         workflow_completion, webhook. content_match and
                         agent_lifecycle remain supported by the matcher for
                         legacy rows but are hidden from the new-trigger
                         dropdown so the manual entry matches what the
                         natural-language generator surfaces. They re-appear
                         only when editing a row that already uses one, so
                         the operator can tweak/disable/delete it without
                         being stuck on a dropdown that excludes its type. -->
                    <select v-model="formState.patternType">
                      <option value="cron">cron</option>
                      <option value="channel_message">channel_message</option>
                      <option value="workflow_completion">workflow_completion</option>
                      <option value="webhook">webhook</option>
                      <option v-if="showLegacyContentMatch" value="content_match">content_match (legacy)</option>
                      <option v-if="showLegacyAgentLifecycle" value="agent_lifecycle">agent_lifecycle (legacy)</option>
                    </select>
                  </label>
                  <div class="span-2">
                    <span class="form-grid-label">{{ t('triggers.fields.patternJson') }}</span>
                    <TriggerPatternForm
                      v-model="formState.patternJson"
                      :pattern-type="formState.patternType"
                      :available-workflows="availableWorkflows"
                      @validation="onPatternValidation"
                    />
                  </div>
                  <label>{{ t('triggers.fields.targetType') }}
                    <!-- v0 only ships the workflow dispatcher; agent target is
                         reserved for v1 and rejected by the API to avoid the
                         "looks enabled, never fires" trap. -->
                    <select v-model="formState.targetType" disabled>
                      <option value="workflow">workflow</option>
                    </select>
                  </label>
                  <label>{{ t('triggers.fields.targetId') }}
                    <select v-model.number="formState.targetId">
                      <option v-if="!availableWorkflows.length" :value="0">
                        {{ t('triggers.targetWorkflowEmpty') }}
                      </option>
                      <option v-else v-for="wf in availableWorkflows" :key="wf.id" :value="wf.id">
                        #{{ wf.id }} — {{ wf.name || t('triggers.unnamed') }}
                      </option>
                    </select>
                  </label>
                  <label>{{ t('triggers.fields.ratePerMin') }}
                    <input v-model.number="formState.rateLimitPerMin" type="number" />
                  </label>
                  <label>{{ t('triggers.fields.dedupWindowSecs') }}
                    <input v-model.number="formState.dedupWindowSecs" type="number" />
                  </label>
                  <label>{{ t('triggers.fields.maxFires') }}
                    <input v-model.number="formState.maxFires" type="number" />
                  </label>
                  <label class="checkbox-field">
                    <input type="checkbox" v-model="formState.botSelfFilter" />
                    <span>{{ t('triggers.fields.botSelfFilter') }}</span>
                  </label>
                  <label class="span-2">{{ t('triggers.fields.payloadTemplate') }}
                    <textarea v-model="formState.payloadTemplate" rows="3"
                      :placeholder="t('triggers.fields.payloadTemplatePlaceholder')" />
                  </label>
                  <label class="span-2 checkbox-field">
                    <input type="checkbox" v-model="formState.enabled" />
                    <span>{{ t('triggers.fields.enabled') }}</span>
                  </label>
                </div>
              </div>

              <footer class="trigger-modal-footer">
                <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.cancel') }}</button>
                <button
                  class="btn-primary"
                  :disabled="busy || patternHasErrors"
                  :title="patternHasErrors ? Object.values(patternErrors).join('; ') : ''"
                  @click="save"
                >{{ t('triggers.actions.save') }}</button>
              </footer>
            </section>
          </div>
        </Teleport>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { mcConfirm } from '@/components/common/useConfirm'
import { triggerApi, type TriggerSummary, workflowApi, type WorkflowSummary } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import TriggerPatternForm from '@/components/workflow/TriggerPatternForm.vue'

const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)
const triggers = ref<TriggerSummary[]>([])
const workflows = ref<WorkflowSummary[]>([])

const availableWorkflows = computed(() =>
  workflows.value.filter((w) => w.latestRevisionId)
)

// Show the legacy pattern types only when the trigger being edited
// actually uses one — otherwise they stay hidden so the new-trigger
// flow matches the four-pattern v0 product surface.
const showLegacyContentMatch = computed(() => formState.value?.patternType === 'content_match')
const showLegacyAgentLifecycle = computed(() => formState.value?.patternType === 'agent_lifecycle')

const patternErrors = ref<Record<string, string>>({})
function onPatternValidation(errs: Record<string, string>) {
  patternErrors.value = errs
}
const patternHasErrors = computed(() => Object.keys(patternErrors.value).length > 0)

const formOpen = ref(false)
const editing = ref<TriggerSummary | null>(null)
const busy = ref(false)

interface FormState {
  name: string
  patternType: string
  patternJson: string
  targetType: string
  targetId: number
  rateLimitPerMin: number
  dedupWindowSecs: number
  botSelfFilter: boolean
  enabled: boolean
  maxFires: number
  payloadTemplate: string
}

const formState = ref<FormState>(emptyForm())

function emptyForm(): FormState {
  return {
    name: '',
    patternType: 'cron',
    patternJson: '{"cron":"0 0 * * * *","timezone":"UTC"}',
    targetType: 'workflow',
    targetId: 0,
    rateLimitPerMin: 60,
    dedupWindowSecs: 60,
    botSelfFilter: true,
    enabled: true,
    maxFires: 0,
    payloadTemplate: '',
  }
}

async function reload() {
  if (!workspaceId.value) return
  try {
    const res = await triggerApi.list(workspaceId.value)
    triggers.value = (res.data as unknown as TriggerSummary[]) ?? []
  } catch (e) {
    console.error('listTriggers failed', e)
  }
  try {
    const res = await workflowApi.list(workspaceId.value)
    workflows.value = (res.data as unknown as WorkflowSummary[]) ?? []
  } catch (e) {
    console.error('listWorkflows failed', e)
  }
}

function formatTarget(row: TriggerSummary) {
  if (row.targetType === 'workflow') {
    const wf = workflows.value.find((w) => w.id === row.targetId)
    if (wf?.name) return `${wf.name} (#${row.targetId})`
  }
  return `${row.targetType}#${row.targetId}`
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 19)
}

function patternTypeLabel(pt: string): string {
  const key = `triggers.patternTypeLabels.${pt}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : pt
}

/**
 * Render a one-line, human-readable summary of the pattern_json so the
 * list reads as a product surface rather than a developer console. The
 * raw JSON is still available via the row's title attribute (tooltip)
 * and the edit form's "raw pattern_json (advanced)" details element.
 */
function patternSummary(row: TriggerSummary): string {
  if (!row.patternJson) return '-'
  let parsed: Record<string, unknown> = {}
  try { parsed = JSON.parse(row.patternJson) } catch { return row.patternJson }
  switch (row.patternType) {
    case 'cron': {
      const cron = parsed.cron ?? ''
      const tz = parsed.timezone ?? ''
      return tz ? `${cron} (${tz})` : `${cron}`
    }
    case 'channel_message': {
      const ch = parsed.channelType ? `${parsed.channelType}` : t('triggers.pattern.channelTypeAny')
      const sender = parsed.senderEquals ? ` · @${parsed.senderEquals}` : ''
      const contains = parsed.contentContains ? ` · "${parsed.contentContains}"` : ''
      return `${ch}${sender}${contains}`
    }
    case 'content_match': {
      return parsed.substring ? `"${parsed.substring}"` : '—'
    }
    case 'agent_lifecycle': {
      const aid = parsed.agentId ? `#${parsed.agentId}` : t('triggers.pattern.agentIdPlaceholder')
      const ph = parsed.phase ? ` · ${parsed.phase}` : ''
      return `${aid}${ph}`
    }
    case 'workflow_completion': {
      const src = parsed.sourceWorkflowId ? `#${parsed.sourceWorkflowId}` : t('triggers.pattern.sourceWorkflowAny')
      const st = parsed.stateFilter ? ` · ${parsed.stateFilter}` : ''
      return `${src}${st}`
    }
    case 'webhook':
      return t('triggers.pattern.webhookHeader')
    default:
      return row.patternJson
  }
}

function truncateError(msg: string | undefined): string {
  if (!msg) return ''
  return msg.length <= 64 ? msg : msg.slice(0, 60) + '…'
}

function openCreate() {
  editing.value = null
  formState.value = emptyForm()
  formOpen.value = true
}

function openEdit(row: TriggerSummary) {
  editing.value = row
  formState.value = {
    name: row.name ?? '',
    patternType: row.patternType,
    patternJson: row.patternJson,
    targetType: row.targetType,
    targetId: row.targetId,
    rateLimitPerMin: row.rateLimitPerMin ?? 60,
    dedupWindowSecs: row.dedupWindowSecs ?? 60,
    botSelfFilter: row.botSelfFilter ?? true,
    enabled: row.enabled,
    maxFires: row.maxFires ?? 0,
    payloadTemplate: row.payloadTemplate ?? '',
  }
  formOpen.value = true
}

function closeForm() {
  formOpen.value = false
}

async function save() {
  if (!workspaceId.value) return
  busy.value = true
  try {
    const payload: Partial<TriggerSummary> = {
      workspaceId: workspaceId.value,
      ...formState.value,
    }
    if (editing.value?.id) {
      await triggerApi.update(editing.value.id, payload)
    } else {
      await triggerApi.create(payload)
    }
    formOpen.value = false
    await reload()
  } catch (e) {
    ElMessage.error(t('triggers.saveFailed', { msg: (e as Error).message }))
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(row: TriggerSummary) {
  try {
    await triggerApi.update(row.id, { ...row, enabled: !row.enabled })
    await reload()
  } catch (e) {
    ElMessage.error(t('triggers.toggleFailed', { msg: (e as Error).message }))
  }
}

async function remove(row: TriggerSummary) {
  const ok = await mcConfirm({
    title: t('triggers.actions.delete'),
    message: t('triggers.deleteConfirm', { name: row.name || String(row.id) }),
    confirmText: t('triggers.actions.delete'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await triggerApi.delete(row.id)
    await reload()
  } catch (e) {
    ElMessage.error(t('triggers.deleteFailed', { msg: (e as Error).message }))
  }
}

onMounted(reload)
watch(workspaceId, reload)
</script>

<style scoped>
.triggers-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.triggers-table {
  width: 100%;
  border-collapse: collapse;
  padding: 0;
  overflow: hidden;
}
.triggers-table th,
.triggers-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border, rgba(0, 0, 0, 0.06));
  text-align: left;
  font-size: 13px;
  vertical-align: top;
}
.triggers-table th {
  font-weight: 600;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
}
.pattern-detail {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.7;
  margin-top: 2px;
}
.pattern-summary {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.7;
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 240px;
}
.trigger-name {
  font-weight: 500;
}
.trigger-last-error {
  font-size: 11px;
  color: var(--mc-danger, #c0392b);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 240px;
  cursor: help;
}
.trigger-attempt-time {
  font-size: 10px;
  opacity: 0.55;
  margin-top: 1px;
}
.trigger-type-pill {
  display: inline-block;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 999px;
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.06));
  color: var(--mc-text-secondary, inherit);
  text-transform: lowercase;
  letter-spacing: 0.02em;
}
:lang(zh-CN) .trigger-type-pill { text-transform: none; }
.empty-row {
  text-align: center;
  opacity: 0.6;
  padding: 24px;
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.actions {
  display: flex;
  gap: 6px;
}
.trigger-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 12, 8, 0.42);
  backdrop-filter: blur(6px);
}
.trigger-modal {
  width: min(1120px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 12px;
  background: var(--mc-bg-elevated, #fff);
  color: var(--mc-text-primary, inherit);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.28);
}
:global(html.dark) .trigger-modal {
  background: #1f1713;
}
.trigger-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border, rgba(0, 0, 0, 0.08));
  flex: 0 0 auto;
}
.trigger-modal-body {
  padding: 16px 20px 20px;
  overflow: auto;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px 16px;
}
.form-grid .span-2 {
  grid-column: span 2;
}
.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
}
.form-grid input,
.form-grid select,
.form-grid textarea {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-family: inherit;
  font-size: 13px;
  min-height: 38px;
}
.form-grid textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  min-height: 96px;
  resize: vertical;
}
.pattern-hint {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 400;
  font-family: 'JetBrains Mono', Consolas, monospace;
  opacity: 0.7;
  white-space: pre-wrap;
}
.form-grid-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  margin-bottom: 4px;
  color: var(--mc-text-secondary, inherit);
}
.checkbox-field {
  flex-direction: row !important;
  align-items: center;
  justify-content: flex-start;
  min-height: 38px;
  padding-top: 20px;
}
.checkbox-field input[type='checkbox'] {
  width: 16px;
  height: 16px;
  min-height: 0;
  flex: 0 0 auto;
}
.trigger-modal-footer {
  flex: 0 0 auto;
  padding: 14px 20px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  border-top: 1px solid var(--mc-border, rgba(0, 0, 0, 0.08));
  background: var(--mc-bg-elevated, #fff);
}
.btn-primary,
.btn-ghost,
.btn-danger {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
}
.btn-primary {
  background: var(--mc-primary, #4084ff);
  border-color: var(--mc-primary, #4084ff);
  color: white;
}
.btn-danger {
  background: rgba(231, 76, 60, 0.12);
  border-color: rgba(231, 76, 60, 0.6);
  color: #c0392b;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Mobile / tablet: the trigger table has 9 columns and breaks on
   phones. Wrap it in a horizontal-scroll container, collapse the
   form to one column, and stack the page header. */
@media (max-width: 900px) {
  .triggers-table {
    display: block;
    overflow-x: auto;
    white-space: nowrap;
  }
  .triggers-table thead,
  .triggers-table tbody,
  .triggers-table tr {
    display: table;
    width: 100%;
    table-layout: auto;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .form-grid .span-2 {
    grid-column: span 1;
  }
  .mc-page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
