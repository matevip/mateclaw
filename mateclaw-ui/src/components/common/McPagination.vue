<template>
  <div v-if="shouldRender" class="mc-pager">
    <span class="mc-pager-total">{{ t('common.pager.total', { n: total }) }}</span>

    <!-- Page-size pill: opens a small native-style dropdown. We don't
         use el-select to avoid pulling EP styling — the whole point of
         this component is to read MateClaw, not Element. -->
    <label class="mc-pager-size">
      <select
        :value="size"
        class="mc-pager-size__native"
        @change="onSizeChange(($event.target as HTMLSelectElement).value)"
      >
        <option v-for="s in sizes" :key="s" :value="s">
          {{ t('common.pager.perPage', { n: s }) }}
        </option>
      </select>
      <span class="mc-pager-size__label">{{ t('common.pager.perPage', { n: size }) }}</span>
      <svg class="mc-pager-size__chev" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </label>

    <button
      class="mc-pager-arrow"
      :disabled="page <= 1"
      :aria-label="t('common.pager.prev')"
      @click="goTo(page - 1)"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="15 18 9 12 15 6"/>
      </svg>
    </button>

    <button
      v-for="(item, idx) in pageItems"
      :key="`${item.type}-${idx}`"
      class="mc-pager-num"
      :class="{ 'mc-pager-num--active': item.type === 'page' && item.value === page, 'mc-pager-num--gap': item.type === 'gap' }"
      :disabled="item.type === 'gap'"
      @click="item.type === 'page' && goTo(item.value!)"
    >
      <span v-if="item.type === 'page'">{{ item.value }}</span>
      <span v-else>…</span>
    </button>

    <button
      class="mc-pager-arrow"
      :disabled="page >= totalPages"
      :aria-label="t('common.pager.next')"
      @click="goTo(page + 1)"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="9 18 15 12 9 6"/>
      </svg>
    </button>

    <!-- Jumper, only useful past a few pages. -->
    <span v-if="totalPages > 5" class="mc-pager-jump">
      <span class="mc-pager-jump__label">{{ t('common.pager.jumpTo') }}</span>
      <input
        :value="jumpDraft"
        class="mc-pager-jump__input"
        type="number"
        min="1"
        :max="totalPages"
        @input="jumpDraft = ($event.target as HTMLInputElement).value"
        @keydown.enter="commitJump"
        @blur="commitJump"
      />
      <span class="mc-pager-jump__suffix">{{ t('common.pager.pageSuffix') }}</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(defineProps<{
  /** Current 1-based page. */
  page: number
  /** Items per page. */
  size: number
  /** Total record count. */
  total: number
  /** Available page sizes. */
  sizes?: number[]
  /** Hide the whole bar when there's only one page. Defaults to false
   *  (we still show "共 N 条" + size selector for transparency). */
  hideOnSinglePage?: boolean
}>(), {
  sizes: () => [10, 20, 50],
  hideOnSinglePage: false,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:size': [value: number]
  /** Convenience event fired whenever either page or size changes. */
  'change': [value: { page: number; size: number }]
}>()

const { t } = useI18n()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const shouldRender = computed(() => {
  if (props.total <= 0) return false
  if (props.hideOnSinglePage && totalPages.value <= 1) return false
  return true
})

/**
 * Build the page-number list with ellipsis collapsing. Strategy:
 *   - always show first + last
 *   - always show ±1 around current
 *   - elide other ranges with a single "…" placeholder
 *
 * Returns alternating page/gap items so the template can render them
 * with stable keys.
 */
type PageItem = { type: 'page'; value: number } | { type: 'gap' }
const pageItems = computed<PageItem[]>(() => {
  const total = totalPages.value
  const cur = props.page
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => ({ type: 'page', value: i + 1 }))
  }
  const items: PageItem[] = [{ type: 'page', value: 1 }]
  const left = Math.max(2, cur - 1)
  const right = Math.min(total - 1, cur + 1)
  if (left > 2) items.push({ type: 'gap' })
  for (let i = left; i <= right; i++) items.push({ type: 'page', value: i })
  if (right < total - 1) items.push({ type: 'gap' })
  items.push({ type: 'page', value: total })
  return items
})

function goTo(p: number) {
  const next = Math.min(Math.max(1, p), totalPages.value)
  if (next === props.page) return
  emit('update:page', next)
  emit('change', { page: next, size: props.size })
}

function onSizeChange(raw: string) {
  const next = Number(raw) || props.size
  if (next === props.size) return
  emit('update:size', next)
  // When the page size changes, snap back to page 1 so the user doesn't
  // land on a now-out-of-range page.
  if (props.page !== 1) emit('update:page', 1)
  emit('change', { page: 1, size: next })
}

const jumpDraft = ref<string>(String(props.page))
watch(() => props.page, (p) => { jumpDraft.value = String(p) })

function commitJump() {
  const target = Number(jumpDraft.value)
  if (!Number.isFinite(target)) {
    jumpDraft.value = String(props.page)
    return
  }
  goTo(target)
  // Reset draft to whatever goTo clamped it to.
  jumpDraft.value = String(Math.min(Math.max(1, Math.floor(target)), totalPages.value))
}
</script>

<style scoped>
.mc-pager {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(14px) saturate(1.1);
  -webkit-backdrop-filter: blur(14px) saturate(1.1);
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.04);
}
:global(html.dark .mc-pager) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}

.mc-pager-total {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-right: 6px;
  font-weight: 500;
}

/* Page-size pill: native <select> overlaid invisibly so the OS picker
 * works for free, with a custom-styled label sitting on top. */
.mc-pager-size {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(123, 88, 67, 0.07);
  font-size: 12px;
  font-weight: 500;
  color: var(--mc-text-primary);
  cursor: pointer;
  transition: background 0.15s ease;
}
.mc-pager-size:hover {
  background: rgba(123, 88, 67, 0.12);
}
:global(html.dark .mc-pager-size) {
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .mc-pager-size:hover) {
  background: rgba(255, 255, 255, 0.14);
}
.mc-pager-size__native {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
  border: 0;
  background: transparent;
  font: inherit;
}
.mc-pager-size__chev {
  color: var(--mc-text-tertiary);
}

.mc-pager-arrow,
.mc-pager-num {
  appearance: none;
  border: 0;
  background: transparent;
  min-width: 28px;
  height: 28px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease, color 0.15s ease;
}
.mc-pager-arrow:hover:not(:disabled),
.mc-pager-num:hover:not(:disabled):not(.mc-pager-num--active) {
  background: rgba(123, 88, 67, 0.10);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-pager-arrow:hover:not(:disabled)),
:global(html.dark .mc-pager-num:hover:not(:disabled):not(.mc-pager-num--active)) {
  background: rgba(255, 255, 255, 0.10);
}
.mc-pager-arrow:disabled,
.mc-pager-num:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.mc-pager-num--active {
  background: var(--mc-primary);
  color: #fff;
  cursor: default;
}
.mc-pager-num--gap {
  color: var(--mc-text-tertiary);
  cursor: default;
  background: transparent;
}
.mc-pager-num--gap:hover {
  background: transparent !important;
}

.mc-pager-jump {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: 4px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}
.mc-pager-jump__input {
  width: 50px;
  height: 26px;
  padding: 0 6px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: rgba(123, 88, 67, 0.07);
  text-align: center;
  font-size: 12px;
  color: var(--mc-text-primary);
  outline: none;
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
  /* Strip native spinners — they're aesthetic noise. */
  appearance: textfield;
  -moz-appearance: textfield;
}
.mc-pager-jump__input::-webkit-outer-spin-button,
.mc-pager-jump__input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.mc-pager-jump__input:focus {
  border-color: rgba(217, 119, 87, 0.4);
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
:global(html.dark .mc-pager-jump__input) {
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .mc-pager-jump__input:focus) {
  background: rgba(255, 255, 255, 0.14);
}
</style>
