<template>
  <Teleport to="body">
    <Transition name="mc-confirm-fade">
      <div
        v-if="current"
        class="mc-confirm-overlay"
        @click.self="onCancel"
        @keydown.esc="onCancel"
      >
        <div
          class="mc-confirm-panel"
          role="alertdialog"
          aria-modal="true"
          :aria-labelledby="`mc-confirm-title-${seq}`"
        >
          <div class="mc-confirm-head">
            <span class="mc-confirm-icon" :class="`mc-confirm-icon--${current.tone || 'default'}`">
              <svg v-if="current.tone === 'danger'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 9v4"/><path d="M12 17h.01"/>
                <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
              </svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 8v4"/><path d="M12 16h.01"/>
              </svg>
            </span>
            <h3 :id="`mc-confirm-title-${seq}`" class="mc-confirm-title">
              {{ current.title || t('common.confirm') }}
            </h3>
          </div>
          <p class="mc-confirm-message">{{ current.message }}</p>
          <div class="mc-confirm-actions">
            <button
              ref="cancelBtnRef"
              type="button"
              class="mc-confirm-btn mc-confirm-btn--ghost"
              @click="onCancel"
            >
              {{ current.cancelText || t('common.cancel') }}
            </button>
            <button
              type="button"
              class="mc-confirm-btn mc-confirm-btn--primary"
              :class="`mc-confirm-btn--${current.tone || 'default'}`"
              @click="onConfirm"
            >
              {{ current.confirmText || t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { activeConfirm, resolveConfirm } from './useConfirm'

const { t } = useI18n()
const current = computed(() => activeConfirm.value)
const cancelBtnRef = ref<HTMLButtonElement | null>(null)

// Stable id seed so the aria-labelledby can point at a unique title even
// if two prompts open in close succession.
let seqCounter = 0
const seq = ref(0)

watch(current, async (next) => {
  if (next) {
    seq.value = ++seqCounter
    // Focus the cancel button on open. Cancel-as-default matches the
    // safety-first stance: if the user hits Enter twice on a destructive
    // prompt by mistake, nothing destructive happens.
    await nextTick()
    cancelBtnRef.value?.focus()
  }
})

function onConfirm() {
  resolveConfirm(true)
}
function onCancel() {
  resolveConfirm(false)
}
</script>

<style scoped>
.mc-confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
:global(html.dark .mc-confirm-overlay) {
  background: rgba(0, 0, 0, 0.55);
}

.mc-confirm-panel {
  width: 100%;
  max-width: 420px;
  background: rgba(255, 250, 245, 0.88);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 18px;
  padding: 22px 24px 18px;
  box-shadow:
    0 24px 60px rgba(25, 14, 8, 0.22),
    0 2px 6px rgba(25, 14, 8, 0.08);
  /* iOS-style spring on entry — small overshoot, settles fast. */
  animation: mc-confirm-pop 0.28s cubic-bezier(0.32, 0.72, 0, 1.2);
}
:global(html.dark .mc-confirm-panel) {
  background: rgba(32, 26, 22, 0.88);
  border-color: rgba(255, 255, 255, 0.10);
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.6),
    0 2px 6px rgba(0, 0, 0, 0.4);
}

@keyframes mc-confirm-pop {
  from { opacity: 0; transform: scale(0.94) translateY(6px); }
  to   { opacity: 1; transform: scale(1) translateY(0); }
}

.mc-confirm-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.mc-confirm-icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.mc-confirm-icon--default {
  background: rgba(123, 88, 67, 0.10);
  color: var(--mc-text-secondary);
}
.mc-confirm-icon--primary {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.mc-confirm-icon--danger {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
}
:global(html.dark .mc-confirm-icon--danger) {
  background: rgba(248, 113, 113, 0.18);
  color: #fca5a5;
}

.mc-confirm-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
}

.mc-confirm-message {
  margin: 0 0 22px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--mc-text-secondary);
}

.mc-confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mc-confirm-btn {
  appearance: none;
  border: 0;
  padding: 9px 18px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  letter-spacing: 0.01em;
  transition: background 0.15s ease, color 0.15s ease, transform 0.1s ease, box-shadow 0.15s ease;
}
.mc-confirm-btn:active {
  transform: scale(0.98);
}
.mc-confirm-btn--ghost {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
.mc-confirm-btn--ghost:hover {
  background: rgba(123, 88, 67, 0.14);
}
:global(html.dark .mc-confirm-btn--ghost) {
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .mc-confirm-btn--ghost:hover) {
  background: rgba(255, 255, 255, 0.14);
}

.mc-confirm-btn--primary {
  background: var(--mc-primary);
  color: #fff;
  box-shadow: 0 1px 3px rgba(217, 119, 87, 0.25);
}
.mc-confirm-btn--primary:hover {
  background: var(--mc-primary-hover);
}
.mc-confirm-btn--primary.mc-confirm-btn--danger {
  background: #dc2626;
  box-shadow: 0 1px 3px rgba(220, 38, 38, 0.3);
}
.mc-confirm-btn--primary.mc-confirm-btn--danger:hover {
  background: #b91c1c;
}
.mc-confirm-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.3);
}
.mc-confirm-btn--danger:focus-visible {
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.3);
}

.mc-confirm-fade-enter-active,
.mc-confirm-fade-leave-active {
  transition: opacity 0.18s ease;
}
.mc-confirm-fade-enter-from,
.mc-confirm-fade-leave-to {
  opacity: 0;
}
</style>
