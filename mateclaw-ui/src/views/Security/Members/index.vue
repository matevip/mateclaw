<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.members.title') }}</h2>
        <p class="section-desc">{{ t('security.members.desc') }}</p>
      </div>
      <button class="btn-primary" @click="showAddDialog = true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('security.members.addMember') }}
      </button>
    </div>

    <!-- Members Table -->
    <div class="rules-table-wrapper">
      <div v-if="loading" class="empty-state">{{ t('security.members.loading') }}</div>
      <div v-else-if="members.length === 0" class="empty-state">{{ t('security.members.noMembers') }}</div>
      <table v-else class="rules-table">
        <thead>
          <tr>
            <th>{{ t('security.members.columns.user') }}</th>
            <th>{{ t('security.members.columns.role') }}</th>
            <th>{{ t('security.members.columns.joined') }}</th>
            <th style="width: 80px;">{{ t('security.members.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="member in members" :key="member.id">
            <td>
              <div class="member-info">
                <div class="member-avatar">{{ (member.username || member.userId + '').charAt(0).toUpperCase() }}</div>
                <div class="member-detail">
                  <span class="member-name">{{ member.nickname || member.username || ('User #' + member.userId) }}</span>
                  <span v-if="member.username && member.nickname" class="member-username">@{{ member.username }}</span>
                </div>
              </div>
            </td>
            <td>
              <select
                :value="member.role"
                @change="updateRole(member, ($event.target as HTMLSelectElement).value)"
                :disabled="member.role === 'owner'"
                class="config-select"
              >
                <option value="owner" disabled>{{ t('security.members.roles.owner') }}</option>
                <option value="admin">{{ t('security.members.roles.admin') }}</option>
                <option value="member">{{ t('security.members.roles.member') }}</option>
                <option value="viewer">{{ t('security.members.roles.viewer') }}</option>
              </select>
            </td>
            <td class="date-cell">{{ formatDate(member.createTime) }}</td>
            <td>
              <div class="action-btns">
                <button
                  v-if="member.role !== 'owner'"
                  class="action-btn danger"
                  @click="removeMember(member)"
                  :title="t('security.members.actions.remove')"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                    <path d="M10 11v6"/><path d="M14 11v6"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Add Member Dialog -->
    <Teleport to="body">
      <div v-if="showAddDialog" class="modal-overlay" @click.self="showAddDialog = false">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ t('security.members.addDialog.title') }}</h3>
            <button class="modal-close" @click="showAddDialog = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-grid" style="grid-template-columns: 1fr;">
              <div class="form-group">
                <label>{{ t('security.members.addDialog.userId') }}</label>
                <input v-model.number="newMemberUserId" type="number" class="form-input" :placeholder="t('security.members.addDialog.userIdPlaceholder')" />
              </div>
              <div class="form-group">
                <label>{{ t('security.members.addDialog.role') }}</label>
                <select v-model="newMemberRole" class="form-input">
                  <option value="admin">{{ t('security.members.roles.admin') }}</option>
                  <option value="member">{{ t('security.members.roles.member') }}</option>
                  <option value="viewer">{{ t('security.members.roles.viewer') }}</option>
                </select>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showAddDialog = false">{{ t('security.members.actions.cancel') }}</button>
            <button class="btn-primary" @click="addMember" :disabled="!newMemberUserId">{{ t('security.members.actions.confirm') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { workspaceTeamApi } from '@/api/index'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()

interface Member {
  id: number
  workspaceId: number
  userId: number
  username?: string
  nickname?: string
  role: string
  createTime: string
}

const store = useWorkspaceStore()
const members = ref<Member[]>([])
const loading = ref(false)
const showAddDialog = ref(false)
const newMemberUserId = ref<number | null>(null)
const newMemberRole = ref('member')

onMounted(() => {
  fetchMembers()
})

async function fetchMembers() {
  const wsId = store.currentWorkspaceId
  if (!wsId) return
  loading.value = true
  try {
    const res: any = await workspaceTeamApi.listMembers(wsId)
    members.value = res.data || []
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function addMember() {
  const wsId = store.currentWorkspaceId
  if (!wsId || !newMemberUserId.value) return
  try {
    await workspaceTeamApi.addMember(wsId, { userId: newMemberUserId.value, role: newMemberRole.value })
    ElMessage.success(t('security.members.messages.addSuccess'))
    showAddDialog.value = false
    newMemberUserId.value = null
    newMemberRole.value = 'member'
    fetchMembers()
  } catch (e: any) {
    ElMessage.error(t('security.members.messages.addFailed'))
  }
}

async function updateRole(member: Member, role: string) {
  const wsId = store.currentWorkspaceId
  if (!wsId) return
  try {
    await workspaceTeamApi.updateMemberRole(wsId, member.userId, role)
    member.role = role
    ElMessage.success(t('security.members.messages.updateSuccess'))
  } catch {
    ElMessage.error(t('security.members.messages.updateFailed'))
  }
}

async function removeMember(member: Member) {
  const wsId = store.currentWorkspaceId
  if (!wsId) return
  if (!confirm(t('security.members.messages.removeConfirm'))) return
  try {
    await workspaceTeamApi.removeMember(wsId, member.userId)
    ElMessage.success(t('security.members.messages.removeSuccess'))
    fetchMembers()
  } catch {
    ElMessage.error(t('security.members.messages.removeFailed'))
  }
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString()
}
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
.member-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.member-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(217, 119, 87, 0.12);
  color: var(--mc-primary, #D97757);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 14px;
  flex-shrink: 0;
}

.member-detail {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.member-name {
  font-weight: 500;
  color: var(--mc-text-primary);
  font-size: 14px;
}

.member-username {
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.date-cell {
  color: var(--mc-text-tertiary);
  font-size: 13px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
