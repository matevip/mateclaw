<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue'

/**
 * Renders a user-authored message as plain text (no markdown processing) and
 * auto-collapses content longer than {@link COLLAPSE_LINE_THRESHOLD} lines.
 *
 * Why no markdown: user input is what the user typed, not a render directive.
 * Markdown rendering surprises people who paste prompts containing #/`/-, and
 * makes the bubble visually compete with assistant output (which IS markdown).
 *
 * Why 8 lines: long pasted prompts (test cases, structured asks, JSON dumps)
 * dominate the scrollback otherwise. 8 lines fits the typical 1-2 paragraph
 * ask without truncating.
 */

const props = defineProps<{
  content: string
}>()

const { t } = useI18n()

const COLLAPSE_LINE_THRESHOLD = 8

const lines = computed(() => (props.content ?? '').split('\n'))
const lineCount = computed(() => lines.value.length)
const needsCollapse = computed(() => lineCount.value > COLLAPSE_LINE_THRESHOLD)

const expanded = ref(false)

const displayText = computed(() => {
  if (!needsCollapse.value || expanded.value) return props.content ?? ''
  return lines.value.slice(0, COLLAPSE_LINE_THRESHOLD).join('\n')
})

function toggle() {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="user-msg-plain">
    <div class="user-msg-plain__text" :class="{ 'is-collapsed': needsCollapse && !expanded }">{{ displayText }}</div>

    <button
      v-if="needsCollapse"
      type="button"
      class="user-msg-plain__toggle"
      @click="toggle"
    >
      <span class="user-msg-plain__toggle-label">
        {{ expanded ? t('chat.collapse') : t('chat.expandLines', { hidden: lineCount - COLLAPSE_LINE_THRESHOLD }) }}
      </span>
      <el-icon class="user-msg-plain__toggle-icon">
        <component :is="expanded ? ArrowUp : ArrowDown" />
      </el-icon>
    </button>
  </div>
</template>

<style scoped>
.user-msg-plain {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/*
 * white-space: pre-wrap preserves the user's newlines and indentation
 * (important for pasted prompts with structure) while still wrapping at the
 * bubble's right edge. word-break:break-word keeps long URLs / paths from
 * overflowing.
 */
.user-msg-plain__text {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  line-height: 1.6;
  color: inherit;
  overflow-wrap: anywhere;
}

.user-msg-plain__text.is-collapsed {
  /*
   * Soft bottom fade so the collapse boundary doesn't look like a hard cut.
   * Uses currentColor so the gradient inherits whatever bubble color is in
   * effect (works on the orange user-bubble and on dark mode alike).
   */
  position: relative;
  -webkit-mask-image: linear-gradient(to bottom, currentColor 70%, rgba(0, 0, 0, 0.35) 100%);
  mask-image: linear-gradient(to bottom, currentColor 70%, rgba(0, 0, 0, 0.35) 100%);
}

.user-msg-plain__toggle {
  align-self: flex-end;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: none;
  background: rgba(255, 255, 255, 0.18);
  color: inherit;
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.user-msg-plain__toggle:hover {
  background: rgba(255, 255, 255, 0.28);
}

.user-msg-plain__toggle-icon {
  font-size: 12px;
}
</style>
