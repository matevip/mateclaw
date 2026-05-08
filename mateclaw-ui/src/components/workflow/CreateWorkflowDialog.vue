<template>
  <ElDialog
    v-model="visible"
    :title="t('workflows.dialogs.createTitle')"
    width="480px"
    align-center
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <ElForm :model="form" label-position="top" @submit.prevent="handleSubmit">
      <ElFormItem :label="t('workflows.dialogs.fieldName')" required>
        <ElInput
          v-model="form.name"
          :placeholder="t('workflows.dialogs.namePlaceholder')"
          maxlength="128"
          show-word-limit
          autofocus
          @keyup.enter="handleSubmit"
        />
      </ElFormItem>
      <ElFormItem :label="t('workflows.dialogs.fieldDescription')">
        <ElInput
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="1024"
          show-word-limit
          :placeholder="t('workflows.dialogs.descriptionPlaceholder')"
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="handleClose">{{ t('common.cancel') }}</ElButton>
      <ElButton type="primary" :loading="loading" :disabled="!form.name.trim()" @click="handleSubmit">
        {{ t('workflows.dialogs.createSubmit') }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElDialog, ElForm, ElFormItem, ElInput, ElButton } from 'element-plus'

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

// Reset form whenever the dialog re-opens so a stale value from the
// last create attempt doesn't leak in.
watch(
  () => props.modelValue,
  (open) => {
    visible.value = open
    if (open) {
      form.name = ''
      form.description = ''
    }
  }
)
watch(visible, (v) => emit('update:modelValue', v))

function handleClose() {
  visible.value = false
}

function handleSubmit() {
  const trimmed = form.name.trim()
  if (!trimmed) return
  emit('submit', { name: trimmed, description: form.description.trim() })
}
</script>
