<template>
  <div class="wmp-wrap" ref="triggerRef">
    <!-- Trigger button -->
    <button
      class="wmp-trigger"
      :class="{ 'wmp-trigger--set': !!modelValue, 'wmp-trigger--disabled': disabled }"
      :disabled="disabled"
      @click="toggle"
    >
      <span class="wmp-trigger__icon">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <rect x="2" y="3" width="20" height="14" rx="2"/>
          <line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
        </svg>
      </span>
      <span class="wmp-trigger__name">{{ selectedLabel }}</span>
      <svg class="wmp-trigger__chevron" :class="{ open }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </button>

    <!-- Popover (teleported to avoid overflow clipping) -->
    <Teleport to="body">
      <Transition name="wmp-fade">
        <div v-if="open" class="wmp-backdrop" @click="open = false" />
      </Transition>
      <Transition name="wmp-pop">
        <div v-if="open" ref="popRef" class="wmp-pop" :style="popStyle">
          <!-- Search (only when enough options) -->
          <div v-if="flatOptions.length > 6" class="wmp-search">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              ref="searchEl"
              v-model="query"
              class="wmp-search__input"
              :placeholder="t('wiki.configPanel.searchModel')"
              @keydown.esc.stop="open = false"
            />
            <button v-if="query" class="wmp-search__clear" @click="query = ''">✕</button>
          </div>

          <!-- List -->
          <div class="wmp-list" ref="listEl">
            <!-- Global default option (hidden when showDefault === false) -->
            <div
              v-if="showDefault !== false"
              class="wmp-item wmp-item--default"
              :class="{ 'wmp-item--active': !modelValue }"
              @click="select('')"
            >
              <span class="wmp-item__name">{{ props.defaultLabel ?? t('wiki.configPanel.globalDefault') }}</span>
              <svg v-if="!modelValue" class="wmp-item__check" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
            </div>

            <!-- Groups -->
            <template v-for="group in filteredGroups" :key="group.providerName">
              <div class="wmp-group-header">
                <span class="wmp-group-header__label">{{ group.providerName }}</span>
                <span v-if="group.isLocal" class="wmp-group-header__badge">Local</span>
              </div>
              <div
                v-for="opt in group.options"
                :key="opt.id"
                class="wmp-item"
                :class="{ 'wmp-item--active': modelValue === opt.id }"
                @click="select(opt.id)"
              >
                <div class="wmp-item__info">
                  <span class="wmp-item__name">{{ opt.name }}</span>
                  <span v-if="opt.modelId" class="wmp-item__model-id">{{ opt.modelId }}</span>
                </div>
                <svg v-if="modelValue === opt.id" class="wmp-item__check" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
            </template>

            <div v-if="filteredGroups.length === 0 && query" class="wmp-empty">
              {{ t('wiki.configPanel.noModelMatch') }}
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, type CSSProperties } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

export interface ModelOption {
  id: string           // numeric config ID as string
  name: string         // display name
  modelId?: string     // underlying model identifier (e.g. "qwen-max")
  providerName?: string // display name of the provider
  providerId?: string  // internal provider id (used for local detection)
  isLocal?: boolean
  /** false = provider API key not configured; such models are shown disabled */
  available?: boolean
}

const props = defineProps<{
  options: ModelOption[]
  modelValue: string
  disabled?: boolean
  /** Override the "empty / default" label. Default: t('wiki.configPanel.globalDefault') */
  defaultLabel?: string
  /** Whether to show the "follow global default" option. Default: true */
  showDefault?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: string): void
}>()

const open = ref(false)
const query = ref('')
const triggerRef = ref<HTMLElement | null>(null)
const popRef = ref<HTMLElement | null>(null)
const listEl = ref<HTMLElement | null>(null)
const searchEl = ref<HTMLInputElement | null>(null)
const popStyle = ref<CSSProperties>({})

// Available options only (providers with configured API keys)
const availableOptions = computed(() =>
  props.options.filter(o => o.available !== false)
)

// Flat count for search threshold
const flatOptions = computed(() => availableOptions.value)

// Group available options by providerName
interface OptionGroup {
  providerName: string
  isLocal: boolean
  options: ModelOption[]
}
const groups = computed<OptionGroup[]>(() => {
  const map = new Map<string, OptionGroup>()
  for (const opt of availableOptions.value) {
    const key = opt.providerName || t('wiki.configPanel.otherProvider')
    if (!map.has(key)) {
      map.set(key, { providerName: key, isLocal: !!opt.isLocal, options: [] })
    }
    map.get(key)!.options.push(opt)
  }
  const arr = [...map.values()]
  arr.sort((a, b) => Number(a.isLocal) - Number(b.isLocal))
  return arr
})

const filteredGroups = computed<OptionGroup[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return groups.value
  const result: OptionGroup[] = []
  for (const g of groups.value) {
    if (g.providerName.toLowerCase().includes(q)) {
      result.push(g)
      continue
    }
    const matched = g.options.filter(
      o => o.name.toLowerCase().includes(q) || (o.modelId || '').toLowerCase().includes(q)
    )
    if (matched.length > 0) result.push({ ...g, options: matched })
  }
  return result
})

const selectedLabel = computed(() => {
  if (!props.modelValue) {
    if (props.showDefault === false) return t('wiki.configPanel.selectModel')
    return props.defaultLabel ?? t('wiki.configPanel.globalDefault')
  }
  // Search all options (not just available) so a previously-saved selection still shows its name
  const opt = props.options.find(o => o.id === props.modelValue)
  return opt ? opt.name : props.modelValue
})

function position() {
  const el = triggerRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  popStyle.value = {
    position: 'fixed',
    top: `${rect.bottom + 6}px`,
    left: `${rect.left}px`,
    minWidth: `${Math.max(rect.width, 260)}px`,
  }
}

function toggle() {
  if (open.value) { open.value = false; return }
  position()
  open.value = true
}

function select(id: string) {
  open.value = false
  query.value = ''
  emit('update:modelValue', id)
}

watch(open, async (v) => {
  if (v) {
    query.value = ''
    await nextTick()
    searchEl.value?.focus()
    await nextTick()
    if (props.modelValue) {
      listEl.value?.querySelector('.wmp-item--active')?.scrollIntoView({ block: 'nearest' })
    }
  }
})
</script>

<style scoped>
.wmp-wrap { position: relative; display: inline-block; width: 100%; }

.wmp-trigger {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  text-align: left;
}
.wmp-trigger:hover:not(.wmp-trigger--disabled) { border-color: var(--mc-primary); background: var(--mc-bg-muted); }
.wmp-trigger--set { color: var(--mc-text-primary); }
.wmp-trigger--disabled { opacity: 0.5; cursor: not-allowed; }
.wmp-trigger__icon { color: var(--mc-text-tertiary); flex-shrink: 0; }
.wmp-trigger__name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wmp-trigger__chevron { flex-shrink: 0; color: var(--mc-text-tertiary); transition: transform 0.18s; }
.wmp-trigger__chevron.open { transform: rotate(180deg); }
</style>

<style>
/* Teleported elements — must be global */
.wmp-backdrop {
  position: fixed;
  inset: 0;
  z-index: 4000;
}
.wmp-pop {
  z-index: 4001;
  max-width: 380px;
  max-height: 400px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.14);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.wmp-search {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px 8px;
  border-bottom: 1px solid var(--mc-border-light);
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}
.wmp-search__input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  color: var(--mc-text-primary);
  font-size: 13px;
}
.wmp-search__input::placeholder { color: var(--mc-text-tertiary); }
.wmp-search__clear { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 11px; padding: 0; }
.wmp-search__clear:hover { color: var(--mc-text-primary); }
.wmp-list { overflow-y: auto; flex: 1; }
.wmp-group-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 10px 3px;
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  user-select: none;
}
.wmp-group-header__badge {
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
  background: rgba(52,199,89,0.12);
  color: #34c759;
}
.wmp-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.1s;
}
.wmp-item:hover { background: var(--mc-bg-sunken); }
.wmp-item--active { background: var(--mc-primary-bg) !important; }
.wmp-item--default { border-bottom: 1px solid var(--mc-border-light); margin-bottom: 4px; border-radius: 8px 8px 0 0; }
.wmp-item__info { display: flex; flex-direction: column; gap: 1px; overflow: hidden; }
.wmp-item__name { font-size: 13px; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wmp-item__model-id { font-size: 10px; color: var(--mc-text-tertiary); font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wmp-item__check { flex-shrink: 0; color: var(--mc-primary); }
.wmp-empty { padding: 16px 10px; text-align: center; font-size: 13px; color: var(--mc-text-tertiary); }

/* Transitions */
.wmp-fade-enter-active, .wmp-fade-leave-active { transition: opacity 0.15s; }
.wmp-fade-enter-from, .wmp-fade-leave-to { opacity: 0; }

.wmp-pop-enter-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.wmp-pop-leave-active { transition: opacity 0.1s ease, transform 0.1s ease; }
.wmp-pop-enter-from { opacity: 0; transform: translateY(-6px); }
.wmp-pop-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
