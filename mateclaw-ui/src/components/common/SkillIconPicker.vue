<template>
  <Teleport to="body">
    <Transition name="mc-picker-fade">
      <div
        v-if="visible"
        class="mc-picker-overlay"
        @click.self="onCancel"
        @keydown.esc.stop="onCancel"
      >
        <div class="mc-picker-panel" role="dialog" aria-modal="true">
          <header class="mc-picker-head">
            <div class="mc-picker-head__meta">
              <SkillIcon :value="draft" :size="36" :fallback="'🛠️'" class="mc-picker-preview" />
              <div>
                <h3 class="mc-picker-title">{{ t('common.iconPicker.title') }}</h3>
                <p class="mc-picker-current">
                  <span v-if="draftSummary" class="mc-picker-current__value">{{ draftSummary }}</span>
                  <span v-else class="mc-picker-current__empty">{{ t('common.iconPicker.none') }}</span>
                </p>
              </div>
            </div>
            <button class="mc-picker-close" :title="t('common.cancel')" @click="onCancel">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
                <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </header>

          <!-- Tab strip — frosted pill, same look as the skill drawer. -->
          <div class="mc-picker-tabs">
            <button
              v-for="tab in tabs"
              :key="tab.id"
              type="button"
              class="mc-picker-tab"
              :class="{ 'mc-picker-tab--active': activeTab === tab.id }"
              @click="activeTab = tab.id"
            >{{ tab.label }}</button>
          </div>

          <!-- Pixelart browser -->
          <div v-if="activeTab === 'pixelart'" class="mc-picker-body mc-picker-body--pixelart">
            <div class="mc-picker-search">
              <svg class="mc-picker-search__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="7" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                ref="searchInputRef"
                v-model="search"
                class="mc-picker-search__input"
                type="text"
                :placeholder="t('common.iconPicker.search')"
                spellcheck="false"
                autocomplete="off"
              />
              <button
                v-if="search"
                class="mc-picker-search__clear"
                :title="t('common.clear')"
                type="button"
                @click="search = ''"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>

            <div v-if="filteredNames.length === 0" class="mc-picker-empty">
              {{ t('common.iconPicker.empty') }}
            </div>
            <!-- Grid: ~50px tiles, fills width. With ~400 icons the
                 native scroll is fine; virtualization isn't worth a dep. -->
            <div v-else class="mc-picker-grid" role="listbox">
              <button
                v-for="name in filteredNames"
                :key="name"
                type="button"
                class="mc-picker-tile"
                :class="{ 'mc-picker-tile--active': draft === `pi:${name}` }"
                :title="name"
                role="option"
                :aria-selected="draft === `pi:${name}`"
                @click="pickPixelart(name)"
              >
                <SkillIcon :value="`pi:${name}`" :size="22" />
              </button>
            </div>

            <p class="mc-picker-foot">
              {{ t('common.iconPicker.pixelartCount', { n: pixelartCount }) }}
            </p>
          </div>

          <!-- Free-form emoji input -->
          <div v-else-if="activeTab === 'emoji'" class="mc-picker-body mc-picker-body--emoji">
            <p class="mc-picker-hint">{{ t('common.iconPicker.emojiHint') }}</p>
            <input
              v-model="emojiDraft"
              class="mc-picker-input"
              type="text"
              maxlength="8"
              :placeholder="t('common.iconPicker.emojiPlaceholder')"
              @input="onEmojiInput"
            />
          </div>

          <!-- URL -->
          <div v-else class="mc-picker-body mc-picker-body--url">
            <p class="mc-picker-hint">{{ t('common.iconPicker.urlHint') }}</p>
            <input
              v-model="urlDraft"
              class="mc-picker-input"
              type="url"
              :placeholder="t('common.iconPicker.urlPlaceholder')"
              @input="onUrlInput"
            />
          </div>

          <footer class="mc-picker-actions">
            <button type="button" class="mc-picker-btn mc-picker-btn--ghost" @click="clearDraft">
              {{ t('common.iconPicker.none') }}
            </button>
            <span class="mc-picker-actions__spacer" />
            <button type="button" class="mc-picker-btn mc-picker-btn--ghost" @click="onCancel">
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="mc-picker-btn mc-picker-btn--primary"
              :disabled="draft === modelValue"
              @click="onApply"
            >{{ t('common.iconPicker.apply') }}</button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import SkillIcon from './SkillIcon.vue'
import { pixelartIconNames, parseIconValue } from '@/composables/usePixelarticons'

const props = withDefaults(defineProps<{
  /** Two-way bound visibility flag — `v-model:visible` on the parent. */
  visible: boolean
  /** Currently persisted icon string. The picker stages edits in {@link draft}
   *  and only applies on confirm so cancel is truly a cancel. */
  modelValue?: string | null
}>(), {
  modelValue: '',
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'update:modelValue': [value: string]
  'apply': [value: string]
}>()

const { t } = useI18n()

const tabs = computed(() => [
  { id: 'pixelart' as const, label: t('common.iconPicker.tabPixelart') },
  { id: 'emoji' as const,    label: t('common.iconPicker.tabEmoji') },
  { id: 'url' as const,      label: t('common.iconPicker.tabUrl') },
])

const activeTab = ref<'pixelart' | 'emoji' | 'url'>('pixelart')
const search = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)

/** Working buffer — what the user has staged but not committed. */
const draft = ref<string>(props.modelValue || '')
const emojiDraft = ref('')
const urlDraft = ref('')

const pixelartCount = computed(() => pixelartIconNames.length)

/**
 * Reset all staging state when the picker re-opens, and pick the right
 * tab based on what the current value looks like — so opening for an
 * existing emoji lands on the Emoji tab, not Pixelart.
 */
watch(() => props.visible, async (open) => {
  if (!open) return
  draft.value = props.modelValue || ''
  search.value = ''
  const parsed = parseIconValue(props.modelValue)
  if (parsed.kind === 'pixelart') {
    activeTab.value = 'pixelart'
    emojiDraft.value = ''
    urlDraft.value = ''
  } else if (parsed.kind === 'url') {
    activeTab.value = 'url'
    urlDraft.value = parsed.url
    emojiDraft.value = ''
  } else if (parsed.kind === 'emoji') {
    activeTab.value = 'emoji'
    emojiDraft.value = parsed.value
    urlDraft.value = ''
  } else {
    activeTab.value = 'pixelart'
    emojiDraft.value = ''
    urlDraft.value = ''
  }
  await nextTick()
  if (activeTab.value === 'pixelart') searchInputRef.value?.focus()
})

const filteredNames = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return pixelartIconNames
  return pixelartIconNames.filter(n => n.includes(q))
})

/** Human-readable label for the current draft, surfaced in the header. */
const draftSummary = computed(() => {
  const parsed = parseIconValue(draft.value)
  if (parsed.kind === 'pixelart') return `pi:${parsed.name}`
  if (parsed.kind === 'url') return parsed.url
  if (parsed.kind === 'emoji') return parsed.value
  return ''
})

function pickPixelart(name: string) {
  draft.value = `pi:${name}`
}
function onEmojiInput() {
  draft.value = emojiDraft.value
}
function onUrlInput() {
  // Only treat as URL once it actually looks like one — typing "h"
  // shouldn't immediately mark the draft as a URL icon.
  const v = urlDraft.value.trim()
  draft.value = (v.startsWith('http://') || v.startsWith('https://')) ? v : ''
}
function clearDraft() {
  draft.value = ''
  emojiDraft.value = ''
  urlDraft.value = ''
}

function onCancel() {
  emit('update:visible', false)
}
function onApply() {
  emit('update:modelValue', draft.value)
  emit('apply', draft.value)
  emit('update:visible', false)
}
</script>

<style scoped>
/* Overlay + panel mirror the MateClaw drawer / confirm — same blur,
 * same warm tone, same iOS-spring entrance. */
.mc-picker-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 1900;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
:global(html.dark .mc-picker-overlay) {
  background: rgba(0, 0, 0, 0.5);
}

.mc-picker-panel {
  width: 100%;
  max-width: 540px;
  max-height: min(680px, 92vh);
  display: flex;
  flex-direction: column;
  background: rgba(255, 250, 245, 0.85);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 18px;
  box-shadow:
    0 24px 60px rgba(25, 14, 8, 0.2),
    0 2px 6px rgba(25, 14, 8, 0.06);
  animation: mc-picker-pop 0.28s cubic-bezier(0.32, 0.72, 0, 1.2);
  overflow: hidden;
}
:global(html.dark .mc-picker-panel) {
  background: rgba(32, 26, 22, 0.86);
  border-color: rgba(255, 255, 255, 0.10);
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.6),
    0 2px 6px rgba(0, 0, 0, 0.4);
}

@keyframes mc-picker-pop {
  from { opacity: 0; transform: scale(0.95) translateY(8px); }
  to   { opacity: 1; transform: scale(1) translateY(0); }
}

/* Header */
.mc-picker-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 14px;
  border-bottom: 1px solid rgba(123, 88, 67, 0.10);
}
:global(html.dark .mc-picker-head) {
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.mc-picker-head__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.mc-picker-preview {
  flex-shrink: 0;
  background: rgba(123, 88, 67, 0.08);
  border-radius: 10px;
  padding: 4px;
  box-sizing: content-box;
}
:global(html.dark .mc-picker-preview) {
  background: rgba(255, 255, 255, 0.06);
}
.mc-picker-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
}
.mc-picker-current {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  max-width: 320px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-picker-current__empty {
  font-style: italic;
  font-family: inherit;
}
.mc-picker-close {
  background: transparent;
  border: 0;
  padding: 8px;
  border-radius: 999px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
}
.mc-picker-close:hover {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-picker-close:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-text-primary);
}

/* Tabs */
.mc-picker-tabs {
  display: flex;
  gap: 2px;
  margin: 12px 22px 0;
  padding: 4px;
  background: rgba(123, 88, 67, 0.06);
  border-radius: 999px;
  width: fit-content;
}
:global(html.dark .mc-picker-tabs) {
  background: rgba(255, 255, 255, 0.06);
}
.mc-picker-tab {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.mc-picker-tab:hover:not(.mc-picker-tab--active) {
  color: var(--mc-text-primary);
}
.mc-picker-tab--active {
  background: rgba(255, 255, 255, 0.85);
  color: var(--mc-primary-hover);
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.08);
}
:global(html.dark .mc-picker-tab--active) {
  background: rgba(255, 255, 255, 0.14);
}

/* Body containers */
.mc-picker-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 14px 22px 0;
  display: flex;
  flex-direction: column;
}

/* Pixelart-specific */
.mc-picker-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(123, 88, 67, 0.06);
  margin-bottom: 12px;
  flex-shrink: 0;
}
.mc-picker-search:focus-within {
  background: rgba(255, 255, 255, 0.65);
  box-shadow:
    inset 0 0 0 1px rgba(217, 119, 87, 0.3),
    0 0 0 3px rgba(217, 119, 87, 0.10);
}
:global(html.dark .mc-picker-search) {
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .mc-picker-search:focus-within) {
  background: rgba(255, 255, 255, 0.14);
}
.mc-picker-search__icon { color: var(--mc-text-tertiary); flex-shrink: 0; }
.mc-picker-search__input {
  flex: 1;
  border: 0;
  background: transparent;
  padding: 4px 0;
  font-size: 13px;
  color: var(--mc-text-primary);
  outline: none;
}
.mc-picker-search__input::placeholder { color: var(--mc-text-tertiary); }
.mc-picker-search__clear {
  background: transparent;
  border: 0;
  padding: 2px;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  display: inline-flex;
  border-radius: 999px;
}
.mc-picker-search__clear:hover {
  color: var(--mc-text-primary);
  background: rgba(123, 88, 67, 0.10);
}
:global(html.dark .mc-picker-search__clear:hover) {
  background: rgba(255, 255, 255, 0.10);
}

.mc-picker-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(44px, 1fr));
  gap: 4px;
  padding: 4px 0 4px;
  /* Fade scrollbar for the picker body. */
  scrollbar-width: thin;
}
.mc-picker-grid::-webkit-scrollbar {
  width: 6px;
}
.mc-picker-grid::-webkit-scrollbar-thumb {
  background: rgba(123, 88, 67, 0.2);
  border-radius: 3px;
}

.mc-picker-tile {
  appearance: none;
  border: 0;
  background: transparent;
  width: 100%;
  aspect-ratio: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  transition: background 0.12s ease, color 0.12s ease, transform 0.08s ease;
}
.mc-picker-tile:hover {
  background: rgba(123, 88, 67, 0.10);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-picker-tile:hover) {
  background: rgba(255, 255, 255, 0.10);
}
.mc-picker-tile:active {
  transform: scale(0.94);
}
.mc-picker-tile--active {
  background: var(--mc-primary);
  color: #fff;
}
.mc-picker-tile--active:hover {
  background: var(--mc-primary-hover);
  color: #fff;
}

.mc-picker-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--mc-text-tertiary);
  font-style: italic;
}

.mc-picker-foot {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  text-align: center;
  flex-shrink: 0;
}

/* Emoji & URL panes share an input + hint layout. */
.mc-picker-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
}
.mc-picker-input {
  width: 100%;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid rgba(123, 88, 67, 0.12);
  background: rgba(255, 255, 255, 0.7);
  font-size: 14px;
  color: var(--mc-text-primary);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.mc-picker-input:focus {
  border-color: rgba(217, 119, 87, 0.45);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.10);
}
:global(html.dark .mc-picker-input) {
  background: rgba(0, 0, 0, 0.25);
  border-color: rgba(255, 255, 255, 0.10);
}

/* Footer */
.mc-picker-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 22px 18px;
  border-top: 1px solid rgba(123, 88, 67, 0.10);
  margin-top: 12px;
}
:global(html.dark .mc-picker-actions) {
  border-top-color: rgba(255, 255, 255, 0.06);
}
.mc-picker-actions__spacer { flex: 1; }
.mc-picker-btn {
  appearance: none;
  border: 0;
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, transform 0.1s ease;
}
.mc-picker-btn:active { transform: scale(0.98); }
.mc-picker-btn--ghost {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
.mc-picker-btn--ghost:hover {
  background: rgba(123, 88, 67, 0.14);
}
:global(html.dark .mc-picker-btn--ghost) {
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .mc-picker-btn--ghost:hover) {
  background: rgba(255, 255, 255, 0.14);
}
.mc-picker-btn--primary {
  background: var(--mc-primary);
  color: #fff;
  box-shadow: 0 1px 3px rgba(217, 119, 87, 0.25);
}
.mc-picker-btn--primary:hover:not(:disabled) {
  background: var(--mc-primary-hover);
}
.mc-picker-btn--primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.mc-picker-fade-enter-active,
.mc-picker-fade-leave-active {
  transition: opacity 0.18s ease;
}
.mc-picker-fade-enter-from,
.mc-picker-fade-leave-to {
  opacity: 0;
}

/* Mobile: full-bleed sheet, taller body */
@media (max-width: 600px) {
  .mc-picker-overlay {
    align-items: flex-end;
    padding: 0;
  }
  .mc-picker-panel {
    max-width: 100%;
    max-height: 88vh;
    border-radius: 20px 20px 0 0;
  }
}
</style>
