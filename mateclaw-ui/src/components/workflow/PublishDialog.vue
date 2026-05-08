<template>
  <ElDialog
    v-model="visible"
    :title="t('workflows.dialogs.publishTitle')"
    width="480px"
    align-center
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <p class="publish-hint">{{ t('workflows.dialogs.publishHint') }}</p>
    <ElForm :model="form" label-position="top" @submit.prevent="handleSubmit">
      <ElFormItem :label="t('workflows.dialogs.publishNote')">
        <ElInput
          v-model="form.note"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          :placeholder="t('workflows.dialogs.publishNotePlaceholder')"
          autofocus
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="handleClose">{{ t('common.cancel') }}</ElButton>
      <ElButton type="primary" :loading="loading" @click="handleSubmit">
        {{ t('workflows.actions.publish') }}
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
  (e: 'submit', payload: { note: string }): void
}>()

const { t } = useI18n()

const visible = ref(props.modelValue)
const form = reactive({ note: '' })

watch(
  () => props.modelValue,
  (open) => {
    visible.value = open
    if (open) form.note = ''
  }
)
watch(visible, (v) => emit('update:modelValue', v))

function handleClose() {
  visible.value = false
}
function handleSubmit() {
  emit('submit', { note: form.note.trim() })
}
</script>

<style scoped>
.publish-hint {
  margin: 0 0 12px;
  font-size: 12px;
  opacity: 0.75;
}
</style>
