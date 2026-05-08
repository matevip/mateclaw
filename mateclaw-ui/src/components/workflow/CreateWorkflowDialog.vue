<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click.self="close">
      <div class="modal" role="dialog" aria-modal="true">
        <div class="modal-header">
          <h3>{{ t('workflows.dialogs.createTitle') }}</h3>
          <button class="modal-close" @click="close" aria-label="close">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label for="wf-create-name">{{ t('workflows.dialogs.fieldName') }}</label>
            <input
              id="wf-create-name"
              ref="nameInput"
              v-model="form.name"
              class="form-input"
              :placeholder="t('workflows.dialogs.namePlaceholder')"
              maxlength="128"
              spellcheck="false"
              @keyup.enter="handleSubmit"
            />
          </div>
          <div class="form-group">
            <label for="wf-create-desc">{{ t('workflows.dialogs.fieldDescription') }}</label>
            <textarea
              id="wf-create-desc"
              v-model="form.description"
              class="form-input form-textarea"
              :placeholder="t('workflows.dialogs.descriptionPlaceholder')"
              maxlength="1024"
              rows="2"
            />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="close">{{ t('common.cancel') }}</button>
          <button
            class="btn-primary"
            :disabled="loading || !form.name.trim()"
            @click="handleSubmit"
          >
            {{ loading ? t('common.loading') : t('workflows.dialogs.createSubmit') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  modelValue: boolean
  loading?: boolean
}
const props = withDefaults(defineProps<Props>(), { loading: false })
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', payload: { name: string; description: string }): void
}>()

const { t } = useI18n()

const visible = ref(props.modelValue)
const form = reactive({ name: '', description: '' })
const nameInput = ref<HTMLInputElement | null>(null)

watch(
  () => props.modelValue,
  async (open) => {
    visible.value = open
    if (open) {
      form.name = ''
      form.description = ''
      // Defer focus to after the Teleport mounts the DOM, otherwise the
      // input ref isn't available on the first tick.
      await nextTick()
      nameInput.value?.focus()
      // Esc → close, scoped to the dialog lifetime.
      document.addEventListener('keydown', onKey)
    } else {
      document.removeEventListener('keydown', onKey)
    }
  }
)
watch(visible, (v) => emit('update:modelValue', v))

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && visible.value) close()
}

function close() {
  visible.value = false
}

function handleSubmit() {
  const trimmed = form.name.trim()
  if (!trimmed) return
  emit('submit', { name: trimmed, description: form.description.trim() })
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 10, 8, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 2100;
  animation: fadeIn 0.15s ease;
}
.modal {
  width: 480px;
  max-width: 100%;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  box-shadow: 0 16px 48px rgba(25, 14, 8, 0.18);
  overflow: hidden;
  animation: slideUp 0.2s ease;
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}
.modal-close {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: background 0.15s;
}
.modal-close:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}
.modal-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-group label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--mc-text-secondary);
}
.form-input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 13.5px;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
  font-family: inherit;
}
.form-input:focus {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
.form-textarea {
  resize: vertical;
  font-family: inherit;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px 16px;
  border-top: 1px solid var(--mc-border-light);
}
.btn-primary,
.btn-secondary {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.15s, border-color 0.15s;
}
.btn-primary {
  background: var(--mc-primary);
  color: var(--mc-text-inverse, #ffffff);
  border-color: var(--mc-primary);
}
.btn-primary:hover:not(:disabled) {
  background: var(--mc-primary-hover, var(--mc-primary));
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-secondary {
  background: transparent;
  color: var(--mc-text-secondary);
  border-color: var(--mc-border);
}
.btn-secondary:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes slideUp {
  from { transform: translateY(8px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
</style>
