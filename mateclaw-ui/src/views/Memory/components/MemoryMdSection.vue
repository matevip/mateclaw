<template>
  <div class="memory-section">
    <div class="section-header">
      <h4>{{ title }}</h4>
      <div class="section-actions">
        <el-button size="small" text type="success" @click="onConfirm">
          {{ t('memory.hil.confirm') }}
        </el-button>
        <el-button size="small" text type="primary" @click="startEdit">
          {{ t('memory.hil.edit') }}
        </el-button>
      </div>
    </div>

    <div v-if="!editing" class="section-content" v-html="renderedContent" />

    <div v-else class="section-edit">
      <el-input
        v-model="editText"
        type="textarea"
        :rows="6"
        :placeholder="t('memory.hil.editPlaceholder')"
      />
      <div class="edit-actions">
        <el-button size="small" @click="cancelEdit">{{ t('memory.hil.cancel') }}</el-button>
        <el-button size="small" type="primary" @click="saveEdit" :loading="saving">
          {{ t('memory.hil.save') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { http } from '@/api'

const props = defineProps<{
  agentId: number
  reportId: string
  sectionKey: string
  title: string
  content: string
}>()

const emit = defineEmits<{ confirmed: []; edited: [newContent: string] }>()
const { t } = useI18n()

const editing = ref(false)
const editText = ref('')
const saving = ref(false)

const renderedContent = computed(() => {
  // Simple markdown-to-HTML for display (just paragraphs and lists)
  return props.content
    .split('\n')
    .map(line => {
      if (line.startsWith('- ')) return `<li>${line.slice(2)}</li>`
      if (line.trim() === '') return '<br/>'
      return `<p>${line}</p>`
    })
    .join('')
})

function onConfirm() {
  http.post(`/memory/${props.agentId}/dream/reports/${props.reportId}/entries/${props.sectionKey}/confirm`)
    .then(() => {
      ElMessage.success(t('memory.hil.confirmed'))
      emit('confirmed')
    })
    .catch(() => ElMessage.error('Confirm failed'))
}

function startEdit() {
  editText.value = props.content
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editText.value = ''
}

async function saveEdit() {
  if (!editText.value.trim()) return
  saving.value = true
  try {
    await http.post(
      `/memory/${props.agentId}/dream/reports/${props.reportId}/entries/${props.sectionKey}/edit`,
      { content: editText.value }
    )
    ElMessage.success(t('memory.hil.saved'))
    emit('edited', editText.value)
    editing.value = false
  } catch {
    ElMessage.error('Save failed')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.memory-section {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 12px;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.section-header h4 {
  margin: 0;
  font-size: 14px;
}
.section-content {
  margin-top: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
.section-edit {
  margin-top: 8px;
}
.edit-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
