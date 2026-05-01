<template>
  <!-- RFC-090 Phase 4: Activity 提升到顶层路由 /activity，但本组件
       既保留 settings-section 内层结构以便旧 Settings 引用兼容，
       又用 mc-page-shell 包一层在顶层路由下显示完整页框。 -->
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner activity-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('nav.activity') }}</div>
            <h1 class="mc-page-title">{{ t('security.activity.title') }}</h1>
            <p class="mc-page-desc">{{ t('security.activity.desc') }}</p>
          </div>
        </div>
        <div class="settings-section activity-inner mc-surface-card">
    <!-- Filters -->
    <div class="filter-row">
      <select v-model="filters.action" class="filter-select" @change="loadEvents">
        <option value="">{{ t('security.activity.allActions') }}</option>
        <option value="CREATE">CREATE</option>
        <option value="UPDATE">UPDATE</option>
        <option value="DELETE">DELETE</option>
        <option value="ENABLE">ENABLE</option>
        <option value="DISABLE">DISABLE</option>
      </select>
      <select v-model="filters.resourceType" class="filter-select" @change="loadEvents">
        <option value="">{{ t('security.activity.allResources') }}</option>
        <option value="AGENT">Agent</option>
        <option value="CHANNEL">Channel</option>
        <option value="SKILL">Skill</option>
        <option value="WIKI">Wiki</option>
        <option value="MEMBER">Member</option>
        <option value="WORKSPACE">Workspace</option>
      </select>
      <button class="btn-secondary" @click="loadEvents">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="1 4 1 10 7 10"/>
          <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
        </svg>
      </button>
    </div>

    <!-- Event Timeline -->
    <div class="rules-table-wrapper">
      <table class="rules-table">
        <thead>
          <tr>
            <th>{{ t('security.activity.columns.time') }}</th>
            <th>{{ t('security.activity.columns.user') }}</th>
            <th>{{ t('security.activity.columns.action') }}</th>
            <th>{{ t('security.activity.columns.resource') }}</th>
            <th>{{ t('security.activity.columns.name') }}</th>
            <th>{{ t('security.activity.columns.ip') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in events" :key="event.id">
            <td class="cell-time">{{ formatTime(event.createTime) }}</td>
            <td>
              <span class="user-tag">{{ event.username }}</span>
            </td>
            <td>
              <span class="action-tag" :class="'action-' + event.action?.toLowerCase()">
                {{ event.action }}
              </span>
            </td>
            <td>
              <span class="resource-tag">{{ event.resourceType }}</span>
            </td>
            <td class="cell-name">{{ event.resourceName || event.resourceId || '-' }}</td>
            <td class="cell-ip">{{ event.ipAddress || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">{{ t('security.activity.loading') }}</div>
      <div v-else-if="!events.length" class="empty-state">{{ t('security.activity.noEvents') }}</div>
    </div>

    <!-- Pagination -->
    <div v-if="total > pageSize" class="pagination">
      <button class="btn-secondary btn-sm" :disabled="page <= 1" @click="page--; loadEvents()">&laquo;</button>
      <span class="page-info">{{ page }} / {{ Math.ceil(total / pageSize) }}</span>
      <button class="btn-secondary btn-sm" :disabled="page >= Math.ceil(total / pageSize)" @click="page++; loadEvents()">&raquo;</button>
    </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { auditApi } from '@/api'

const { t } = useI18n()

const events = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const filters = reactive({ action: '', resourceType: '' })

async function loadEvents() {
  loading.value = true
  try {
    const res: any = await auditApi.listEvents({
      action: filters.action || undefined,
      resourceType: filters.resourceType || undefined,
      page: page.value,
      size: pageSize,
    })
    events.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    events.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(dateStr: string) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleString()
}

onMounted(() => {
  loadEvents()
})
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
/* RFC-090 Phase 4 — top-level standalone shell */
.activity-page { gap: 18px; }
.activity-inner { padding: 18px; }

.filter-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-select {
  padding: 6px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 13px;
}

.cell-time { font-size: 12px; color: var(--mc-text-tertiary); white-space: nowrap; }
.cell-name { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-ip { font-size: 12px; color: var(--mc-text-tertiary); font-family: 'SF Mono', monospace; }

.user-tag {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.action-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
}

.action-create { background: rgba(16, 185, 129, 0.12); color: #10b981; }
.action-update { background: rgba(59, 130, 246, 0.12); color: #3b82f6; }
.action-delete { background: rgba(239, 68, 68, 0.12); color: #ef4444; }
.action-enable { background: rgba(16, 185, 129, 0.12); color: #10b981; }
.action-disable { background: rgba(245, 158, 11, 0.12); color: #f59e0b; }
.action-login { background: rgba(139, 92, 246, 0.12); color: #8b5cf6; }
.action-logout { background: rgba(107, 114, 128, 0.12); color: #6b7280; }

.resource-tag {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  font-family: 'SF Mono', 'Fira Code', monospace;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 16px;
}

.page-info { font-size: 13px; color: var(--mc-text-tertiary); }
</style>
