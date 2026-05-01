import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/layout/MainLayout.vue'),
      redirect: '/chat',
      children: [
        // ==================== Core ====================
        {
          path: 'chat',
          name: 'Chat',
          component: () => import('@/views/ChatConsole.vue'),
          meta: { title: 'Chat' },
        },
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/Dashboard.vue'),
          meta: { title: 'Dashboard' },
        },
        {
          path: 'agents',
          name: 'Agents',
          component: () => import('@/views/Agents.vue'),
          meta: { title: 'Agents' },
        },
        {
          path: 'wiki',
          name: 'Wiki',
          component: () => import('@/views/Wiki/index.vue'),
          meta: { title: 'Wiki' },
        },
        {
          path: 'memory',
          name: 'Memory',
          component: () => import('@/views/Memory/index.vue'),
          meta: { title: 'Memory' },
        },
        // ==================== Connect ====================
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('@/views/Channels.vue'),
          // keepAlive: cache the component instance so navigating away and
          // back doesn't re-mount + re-fetch the list. Channels.vue must
          // pause polling in onDeactivated to avoid a leaked timer.
          meta: { title: 'Channels', keepAlive: true },
        },
        {
          path: 'skills',
          name: 'Skills',
          component: () => import('@/views/SkillMarket.vue'),
          meta: { title: 'Skills' },
        },
        // Tools 顶层入口已降级到 Settings ▸ Tools (Catalog) (RFC-090 Phase 1)
        // 旧路径 /tools 由下方 redirect 兼容
        {
          path: 'activity',
          name: 'Activity',
          component: () => import('@/views/Security/Activity/index.vue'),
          meta: { title: 'Activity' },
        },
        {
          path: 'plugins',
          name: 'Plugins',
          component: () => import('@/views/Plugins.vue'),
          meta: { title: 'Plugins' },
        },
        // ==================== Settings (absorbs advanced pages) ====================
        {
          path: 'settings',
          component: () => import('@/views/Settings/Layout.vue'),
          redirect: '/settings/models',
          children: [
            {
              path: 'models',
              name: 'SettingsModels',
              component: () => import('@/views/Settings/Models/index.vue'),
              meta: { title: 'Settings - Models' },
            },
            {
              path: 'system',
              name: 'SettingsSystem',
              component: () => import('@/views/Settings/System/index.vue'),
              meta: { title: 'Settings - System' },
            },
            {
              path: 'image',
              name: 'SettingsImage',
              component: () => import('@/views/Settings/Image/index.vue'),
              meta: { title: 'Settings - Image' },
            },
            {
              path: 'tts',
              name: 'SettingsTts',
              component: () => import('@/views/Settings/Tts/index.vue'),
              meta: { title: 'Settings - TTS' },
            },
            {
              path: 'stt',
              name: 'SettingsStt',
              component: () => import('@/views/Settings/Stt/index.vue'),
              meta: { title: 'Settings - STT' },
            },
            {
              path: 'music',
              name: 'SettingsMusic',
              component: () => import('@/views/Settings/Music/index.vue'),
              meta: { title: 'Settings - Music' },
            },
            {
              path: 'video',
              name: 'SettingsVideo',
              component: () => import('@/views/Settings/Video/index.vue'),
              meta: { title: 'Settings - Video' },
            },
            // Workspace management
            {
              path: 'workspaces',
              name: 'SettingsWorkspaces',
              component: () => import('@/views/Security/Workspaces/index.vue'),
              meta: { title: 'Settings - Workspaces' },
            },
            {
              path: 'members',
              name: 'SettingsMembers',
              component: () => import('@/views/Security/Members/index.vue'),
              meta: { title: 'Settings - Members' },
            },
            // RFC-090 Phase 4: Activity 提升到顶层 /activity（下方 children-out
            // 的 settings/activity redirect 兼容旧链接，此处不再注册子路由）
            // Advanced (absorbed from top-level nav)
            {
              path: 'agent-context',
              name: 'SettingsAgentContext',
              component: () => import('@/views/AgentContext.vue'),
              meta: { title: 'Settings - Agent Context' },
            },
            {
              path: 'cron-jobs',
              name: 'SettingsCronJobs',
              component: () => import('@/views/CronJobs.vue'),
              meta: { title: 'Settings - Cron Jobs' },
            },
            {
              path: 'datasources',
              name: 'SettingsDatasources',
              component: () => import('@/views/Datasources.vue'),
              meta: { title: 'Settings - Datasources' },
            },
            {
              path: 'mcp-servers',
              name: 'SettingsMcpServers',
              component: () => import('@/views/McpServers.vue'),
              meta: { title: 'Settings - MCP Connections' },
            },
            {
              path: 'tools',
              name: 'SettingsTools',
              component: () => import('@/views/Tools.vue'),
              meta: { title: 'Settings - Tools Catalog' },
            },
            {
              path: 'token-usage',
              name: 'SettingsTokenUsage',
              component: () => import('@/views/TokenUsage.vue'),
              meta: { title: 'Settings - Token Usage' },
            },
            {
              path: 'about',
              name: 'SettingsAbout',
              component: () => import('@/views/Settings/About/index.vue'),
              meta: { title: 'Settings - About' },
            },
          ],
        },
        // ==================== Security ====================
        {
          path: 'security',
          component: () => import('@/views/Security/Layout.vue'),
          redirect: '/security/tool-guard',
          children: [
            {
              path: 'tool-guard',
              name: 'SecurityToolGuard',
              component: () => import('@/views/Security/ToolGuard/index.vue'),
              meta: { title: 'Security - Tool Guard' },
            },
            {
              path: 'file-guard',
              name: 'SecurityFileGuard',
              component: () => import('@/views/Security/FileGuard/index.vue'),
              meta: { title: 'Security - File Guard' },
            },
            {
              path: 'audit-logs',
              name: 'SecurityAuditLogs',
              component: () => import('@/views/Security/AuditLogs/index.vue'),
              meta: { title: 'Security - Audit Logs' },
            },
          ],
        },
        // ==================== Redirects (backward compatibility) ====================
        { path: 'sessions', redirect: '/chat' },
        { path: 'workspace', redirect: '/settings/agent-context' },
        { path: 'security/workspaces', redirect: '/settings/workspaces' },
        { path: 'security/members', redirect: '/settings/members' },
        // RFC-090 Phase 4: Activity 提升到顶层
        { path: 'security/activity', redirect: '/activity' },
        { path: 'settings/activity', redirect: '/activity' },
        { path: 'cron-jobs', redirect: '/settings/cron-jobs' },
        { path: 'datasources', redirect: '/settings/datasources' },
        { path: 'mcp-servers', redirect: '/settings/mcp-servers' },
        { path: 'token-usage', redirect: '/settings/token-usage' },
        // RFC-090 Phase 1: Tools 顶层降级到 Settings
        { path: 'tools', redirect: '/settings/tools' },
      ],
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/chat',
    },
  ],
})

// 路由守卫：未登录跳转到登录页（开发环境可通过 VITE_SKIP_AUTH=true 跳过）
router.beforeEach((to, _from, next) => {
  if (import.meta.env.VITE_SKIP_AUTH === 'true') {
    next()
    return
  }
  const token = localStorage.getItem('token')
  if (to.name === 'Login' && token) {
    // Already logged in — skip login page
    next({ path: '/' })
  } else if (to.name !== 'Login' && !token) {
    next({ name: 'Login' })
  } else {
    next()
  }
})

export default router
