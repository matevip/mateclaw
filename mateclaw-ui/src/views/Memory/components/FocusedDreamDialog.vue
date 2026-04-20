<template>
  <el-dialog
    v-model="visible"
    :title="t('memory.focused.title')"
    width="420px"
    :close-on-click-modal="false"
  >
    <div class="focused-form">
      <p class="focused-desc">{{ t('memory.focused.desc') }}</p>
      <el-input
        v-model="topic"
        :placeholder="t('memory.focused.placeholder')"
        :rows="3"
        type="textarea"
        maxlength="200"
        show-word-limit
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">{{ t('memory.hil.cancel') }}</el-button>
      <el-button type="primary" :loading="running" :disabled="!topic.trim()" @click="trigger">
        {{ t('memory.focused.trigger') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { http } from '@/api'

const props = defineProps<{ agentId: number }>()
const emit = defineEmits<{ triggered: []; 'update:modelValue': [val: boolean] }>()
const { t } = useI18n()

const visible = defineModel<boolean>({ default: false })
const topic = ref('')
const running = ref(false)

async function trigger() {
  if (!topic.value.trim()) return
  running.value = true
  try {
    const res = await http.post(`/memory/${props.agentId}/dreaming/focused`, {
      topic: topic.value.trim(),
    })
    if (res.data?.status === 'SUCCESS') {
      ElMessage.success(t('memory.focused.success'))
    } else {
      ElMessage.info(t('memory.focused.skipped'))
    }
    emit('triggered')
    visible.value = false
    topic.value = ''
  } catch (e: any) {
    ElMessage.error(e.message || 'Focused dream failed')
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.focused-form {
  padding: 4px 0;
}
.focused-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
