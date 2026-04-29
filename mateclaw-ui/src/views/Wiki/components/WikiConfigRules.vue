<template>
  <!-- Rules modal trigger card is rendered by parent; this component IS the modal content -->
  <Teleport to="body">
    <Transition name="cfg-modal">
      <div v-if="open" class="cfg-modal-overlay" @click.self="emit('close')">
        <div class="cfg-modal">

          <!-- Header -->
          <div class="cfg-modal__header">
            <div class="cfg-modal__title-group">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
              </svg>
              <span>处理规则</span>
              <span class="cfg-modal__kb">{{ kbName }}</span>
            </div>
            <div class="cfg-modal__mode-switch">
              <button :class="['mode-btn', { active: mode === 'guided' }]" @click="mode = 'guided'">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
                向导
              </button>
              <button :class="['mode-btn', { active: mode === 'source' }]" @click="switchToSource">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
                源码
              </button>
            </div>
            <div class="cfg-modal__actions">
              <button class="btn-cfg-reset" @click="resetRules">重置</button>
              <button class="btn-cfg-save" :disabled="saving" @click="save">
                {{ saving ? '保存中…' : '保存' }}
              </button>
              <button class="btn-cfg-close" @click="emit('close')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
          </div>

          <!-- Body -->
          <div class="cfg-modal__body">

            <!-- ── Guided mode ── -->
            <div v-if="mode === 'guided'" class="guided-wrap">
              <div class="guided-intro">
                选择启用的规则 — AI 在生成页面时会严格遵循。选中即生效，不需要写配置文件。
              </div>

              <div v-for="cat in categories" :key="cat.id" class="rule-cat">
                <div class="rule-cat__header">
                  <span class="rule-cat__icon">{{ cat.icon }}</span>
                  <span class="rule-cat__title">{{ cat.title }}</span>
                  <span class="rule-cat__count">{{ cat.rules.filter(r => r.active).length }}/{{ cat.rules.length }}</span>
                </div>
                <div class="rule-grid">
                  <button
                    v-for="rule in cat.rules"
                    :key="rule.id"
                    :class="['rule-chip', { 'rule-chip--on': rule.active }]"
                    @click="rule.active = !rule.active"
                  >
                    <svg v-if="rule.active" class="chip-check" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
                    <svg v-else class="chip-plus" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                    {{ rule.label }}
                  </button>
                </div>
                <div v-if="cat.id === 'custom'" class="custom-rules-wrap">
                  <textarea
                    v-model="customExtra"
                    class="custom-rules-textarea"
                    placeholder="# 自定义规则&#10;&#10;- 用 Markdown 写额外规则…"
                    rows="5"
                  />
                </div>
              </div>

              <!-- Live preview pill -->
              <div class="guided-preview">
                <div class="guided-preview__header">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                  生成预览
                  <button class="guided-preview__edit" @click="switchToSource">切换到源码编辑</button>
                </div>
                <pre class="guided-preview__content">{{ generatedContent || '（未选择任何规则）' }}</pre>
              </div>
            </div>

            <!-- ── Source mode ── -->
            <div v-else class="source-wrap">
              <div class="source-bar">
                <span class="source-lang">Markdown</span>
                <span class="source-lines">{{ sourceContent.split('\n').length }} 行</span>
                <button class="source-back" @click="mode = 'guided'">← 返回向导</button>
              </div>
              <textarea
                ref="sourceEl"
                v-model="sourceContent"
                class="source-textarea"
                spellcheck="false"
                @keydown.tab.prevent="insertTab"
              />
            </div>

          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, reactive } from 'vue'

const props = defineProps<{
  open: boolean
  modelValue: string   // current raw config text
  kbName?: string
  saving?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'save', content: string): void
}>()

// ── Mode ──
type Mode = 'guided' | 'source'
const mode = ref<Mode>('guided')
const sourceEl = ref<HTMLTextAreaElement | null>(null)
const sourceContent = ref('')
const customExtra = ref('')

// Rule IDs that are active by default (fresh / unrecognized config)
const DEFAULT_ACTIVE_IDS = new Set(['q1', 'q2', 'q3', 'q4', 'f1', 'f2', 'u1', 'u2', 'l1', 'l2'])

// ── Rule categories ──
interface Rule { id: string; label: string; text: string; active: boolean }
interface Category { id: string; icon: string; title: string; rules: Rule[] }

const categories = reactive<Category[]>([
  {
    id: 'quality',
    icon: '✦',
    title: '质量',
    rules: [
      { id: 'q1', label: '宁少勿多，拒绝浅页', active: true,
        text: '- 宁可生成更少但完整的页面，而非大量浅薄的条目' },
      { id: 'q2', label: '一页一概念', active: true,
        text: '- 每个页面聚焦一个概念、实体或流程' },
      { id: 'q3', label: '至少 3 句实质内容', active: true,
        text: '- 页面必须包含至少 3 句有实质内容的表述，否则不予创建' },
      { id: 'q4', label: '优先更新已有页面', active: true,
        text: '- 若概念已存在于 Wiki，更新而非重复创建' },
    ],
  },
  {
    id: 'format',
    icon: '❑',
    title: '格式',
    rules: [
      { id: 'f1', label: '顶部摘要段', active: true,
        text: '- 每个页面顶部包含一段摘要（1-2 句话概括核心含义）' },
      { id: 'f2', label: '使用 [[Wiki 链接]]', active: true,
        text: '- 在内容中使用 [[页面标题]] 语法建立页面间的双向链接' },
      { id: 'f3', label: '清晰的 Markdown 结构', active: false,
        text: '- 使用 ## 和 ### 标题组织内容结构' },
      { id: 'f4', label: '避免列表堆砌', active: false,
        text: '- 优先用段落叙述而非大量无序列表' },
    ],
  },
  {
    id: 'update',
    icon: '↻',
    title: '更新策略',
    rules: [
      { id: 'u1', label: '合并而非替换', active: true,
        text: '- 将新信息合并到已有页面，不覆盖现有内容' },
      { id: 'u2', label: '保留人工编辑', active: true,
        text: '- 保留 last_updated_by = manual 的内容，不自动改写' },
      { id: 'u3', label: '标注矛盾信息', active: false,
        text: '- 遇到与已有内容矛盾的新信息时，用 "Note:" 明确标注而非静默覆盖' },
    ],
  },
  {
    id: 'language',
    icon: '文',
    title: '语言',
    rules: [
      { id: 'l1', label: '与原材料语言一致', active: true,
        text: '- 以与原始材料相同的语言编写 Wiki 页面' },
      { id: 'l2', label: '术语跨页保持一致', active: true,
        text: '- 同一概念在所有页面中使用相同术语，避免同义词混用' },
    ],
  },
  {
    id: 'custom',
    icon: '+',
    title: '自定义规则',
    rules: [],
  },
])

// ── Generate markdown from selected rules ──
const generatedContent = computed(() => {
  const lines: string[] = ['# Wiki Processing Rules', '']
  for (const cat of categories) {
    if (cat.id === 'custom') continue
    const active = cat.rules.filter(r => r.active)
    if (!active.length) continue
    lines.push(`## ${cat.title}`)
    active.forEach(r => lines.push(r.text))
    lines.push('')
  }
  if (customExtra.value.trim()) {
    lines.push('## 自定义')
    lines.push(customExtra.value.trim())
    lines.push('')
  }
  return lines.join('\n').trim()
})

// ── Sync incoming value → state ──
function applyDefaults() {
  for (const cat of categories) {
    for (const rule of cat.rules) {
      rule.active = DEFAULT_ACTIVE_IDS.has(rule.id)
    }
  }
  customExtra.value = ''
}

function parseIncoming(text: string) {
  sourceContent.value = text
  if (!text.trim()) {
    applyDefaults()
    return
  }
  // Match rule texts against saved content
  let anyMatched = false
  for (const cat of categories) {
    for (const rule of cat.rules) {
      const matched = text.includes(rule.text.trim())
      if (matched) anyMatched = true
      rule.active = matched
    }
  }
  // Content exists but no rules recognized (e.g. header-only or legacy format) → use defaults
  if (!anyMatched) applyDefaults()
  // Extract custom section if present
  const customMatch = text.match(/## 自定义\n([\s\S]*?)(?=\n## |\s*$)/)
  customExtra.value = customMatch ? customMatch[1].trim() : ''
}

watch(() => props.open, (v) => {
  if (v) {
    mode.value = 'guided'  // always land on guided tab when re-opening
    parseIncoming(props.modelValue || '')
  }
})

// ── Mode switching ──
function switchToSource() {
  sourceContent.value = generatedContent.value
  mode.value = 'source'
  nextTick(() => sourceEl.value?.focus())
}

function insertTab(e: KeyboardEvent) {
  const el = e.target as HTMLTextAreaElement
  const s = el.selectionStart
  sourceContent.value = sourceContent.value.substring(0, s) + '  ' + sourceContent.value.substring(el.selectionEnd)
  nextTick(() => { el.selectionStart = el.selectionEnd = s + 2 })
}

function resetRules() {
  parseIncoming(props.modelValue || '')
  mode.value = 'guided'
}

function save() {
  const content = mode.value === 'guided' ? generatedContent.value : sourceContent.value
  emit('save', content)
}
</script>

<style>
/* ── Modal shell (global — teleported) ── */
.cfg-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 3100;
  background: rgba(0,0,0,0.48);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.cfg-modal {
  width: 100%;
  max-width: 860px;
  height: min(88vh, 760px);
  background: var(--mc-bg-elevated);
  border-radius: 18px;
  box-shadow: 0 24px 64px rgba(0,0,0,0.28);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Header */
.cfg-modal__header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--mc-border-light);
  flex-shrink: 0;
}
.cfg-modal__title-group {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  flex: 1;
  min-width: 0;
}
.cfg-modal__kb {
  font-size: 11px;
  font-weight: 500;
  padding: 1px 8px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-radius: 99px;
  white-space: nowrap;
}
.cfg-modal__mode-switch {
  display: flex;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 2px;
  gap: 2px;
  flex-shrink: 0;
}
.mode-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-text-tertiary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.mode-btn.active { background: var(--mc-bg-elevated); color: var(--mc-text-primary); font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.cfg-modal__actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-cfg-reset { padding: 5px 12px; border: 1px solid var(--mc-border); border-radius: 7px; background: transparent; color: var(--mc-text-secondary); font-size: 12px; cursor: pointer; }
.btn-cfg-reset:hover { background: var(--mc-bg-sunken); }
.btn-cfg-save { padding: 5px 16px; border: none; border-radius: 7px; background: var(--mc-primary); color: white; font-size: 12px; font-weight: 600; cursor: pointer; transition: opacity 0.15s; }
.btn-cfg-save:hover { opacity: 0.88; }
.btn-cfg-save:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-cfg-close { width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--mc-border-light); border-radius: 7px; background: transparent; color: var(--mc-text-tertiary); cursor: pointer; }
.btn-cfg-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }

/* Body */
.cfg-modal__body { flex: 1; min-height: 0; overflow-y: auto; }

/* Guided mode */
.guided-wrap { padding: 18px 20px; display: flex; flex-direction: column; gap: 20px; }
.guided-intro { font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.6; padding: 10px 14px; background: var(--mc-bg-sunken); border-radius: 10px; border-left: 3px solid var(--mc-primary); }

.rule-cat {}
.rule-cat__header { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.rule-cat__icon { font-size: 13px; width: 20px; text-align: center; }
.rule-cat__title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.rule-cat__count { margin-left: auto; font-size: 10px; color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); padding: 1px 6px; border-radius: 99px; }

.rule-grid { display: flex; flex-wrap: wrap; gap: 6px; }
.rule-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  user-select: none;
}
.rule-chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.rule-chip--on { border-color: var(--mc-primary); background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 500; }
.chip-check { color: var(--mc-primary); flex-shrink: 0; }
.chip-plus { color: var(--mc-text-tertiary); flex-shrink: 0; }
.rule-chip--on .chip-plus { color: var(--mc-primary); }

.custom-rules-wrap { margin-top: 8px; }
.custom-rules-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  resize: vertical;
  outline: none;
  box-sizing: border-box;
}
.custom-rules-textarea:focus { border-color: var(--mc-primary); }

/* Preview */
.guided-preview { border: 1px solid var(--mc-border-light); border-radius: 10px; overflow: hidden; }
.guided-preview__header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  background: var(--mc-bg-sunken);
  font-size: 11px;
  color: var(--mc-text-tertiary);
  border-bottom: 1px solid var(--mc-border-light);
}
.guided-preview__edit { margin-left: auto; font-size: 11px; color: var(--mc-primary); background: none; border: none; cursor: pointer; padding: 0; }
.guided-preview__edit:hover { text-decoration: underline; }
.guided-preview__content {
  margin: 0;
  padding: 12px 14px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  line-height: 1.7;
  color: var(--mc-text-secondary);
  white-space: pre-wrap;
  max-height: 220px;
  overflow-y: auto;
  background: var(--mc-bg-elevated);
}

/* Source mode */
.source-wrap { display: flex; flex-direction: column; height: 100%; }
.source-bar { display: flex; align-items: center; gap: 10px; padding: 6px 14px; background: var(--mc-bg-sunken); border-bottom: 1px solid var(--mc-border-light); flex-shrink: 0; }
.source-lang { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--mc-text-tertiary); }
.source-lines { font-size: 10px; color: var(--mc-text-tertiary); }
.source-back { margin-left: auto; font-size: 11px; color: var(--mc-primary); background: none; border: none; cursor: pointer; }
.source-textarea {
  flex: 1;
  min-height: 400px;
  padding: 16px 18px;
  border: none;
  outline: none;
  resize: none;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.8;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
}

/* Transitions */
.cfg-modal-enter-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.cfg-modal-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.cfg-modal-enter-from, .cfg-modal-leave-to { opacity: 0; transform: scale(0.97); }
</style>
