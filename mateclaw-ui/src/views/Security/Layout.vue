<template>
  <div class="settings-layout">
    <div class="settings-nav">
      <h2 class="nav-title">{{ t('security.title') }}</h2>
      <router-link
        v-for="section in sections"
        :key="section.id"
        :to="section.path"
        class="nav-item"
        :class="{ active: isActive(section.path) }"
      >
        <span class="nav-icon" v-html="section.icon"></span>
        {{ section.label }}
      </router-link>
    </div>

    <div class="settings-content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const { t } = useI18n()

const sections = computed(() => [
  {
    id: 'toolGuard',
    path: '/security/tool-guard',
    label: t('security.sections.toolGuard'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
  },
  {
    id: 'fileGuard',
    path: '/security/file-guard',
    label: t('security.sections.fileGuard'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="15" x2="15" y2="15"/></svg>',
  },
  {
    id: 'auditLogs',
    path: '/security/audit-logs',
    label: t('security.sections.auditLogs'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>',
  },
  {
    id: 'members',
    path: '/security/members',
    label: t('security.sections.members', 'Members'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
  },
])

function isActive(path: string) {
  return route.path === path
}
</script>

<style scoped>
.settings-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.settings-nav {
  width: 200px;
  min-width: 200px;
  border-right: 1px solid var(--mc-border-light);
  padding: 20px 12px;
  overflow-y: auto;
}

.nav-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0 0 16px 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 14px;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  text-decoration: none;
  margin-bottom: 2px;
}

.nav-item:hover { background: var(--mc-bg-hover); color: var(--mc-text-primary); }
.nav-item.active { background: var(--mc-sidebar-active); color: var(--mc-text-primary); font-weight: 500; }
.nav-icon { display: flex; align-items: center; flex-shrink: 0; }

.settings-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
}
</style>
