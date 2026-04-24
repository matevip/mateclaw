import axios from 'axios'
import { handleAuthFailure, updateTokenFromHeader } from '@/utils/auth'

// Axios 实例
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

// 请求拦截器：注入 Token + Workspace ID
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const workspaceId = localStorage.getItem('mc-workspace-id')
  if (workspaceId) {
    config.headers['X-Workspace-Id'] = workspaceId
  }
  return config
})

// 响应拦截器：适配后端 R<T> { code, msg, data } 格式
http.interceptors.response.use(
  (res) => {
    // 滑动窗口续期：后端在 Token 接近过期时通过响应头下发新 Token
    updateTokenFromHeader(res.headers)

    const data = res.data
    // 后端统一响应格式 R<T>: { code: number, msg: string, data: T }
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code === 200) return data
      if (data.code === 401 || data.code === 403) {
        handleAuthFailure()
        return Promise.reject(new Error(data.msg || 'Unauthorized'))
      }
      return Promise.reject(new Error(data.msg || 'Request failed'))
    }
    return data
  },
  (err) => {
    if (err.response?.status === 401 || err.response?.status === 403) {
      handleAuthFailure()
    }
    return Promise.reject(err.response?.data?.msg || err.message)
  }
)

// ==================== 受保护文件访问 ====================

/**
 * 使用 JWT 认证 fetch 受保护文件，返回 Blob。
 * 用于 <img> 和 <a download> 无法自动携带 Authorization header 的场景。
 * 使用原生 fetch（不走 axios），避免 baseURL 拼接和 R<T> 拦截器干扰。
 */
export async function fetchAuthenticatedBlob(fileUrl: string): Promise<Blob> {
  const token = localStorage.getItem('token')
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(fileUrl, { headers })
  if (!response.ok) throw new Error(`Fetch failed: ${response.status}`)
  return response.blob()
}

// ==================== Auth ====================
export const authApi = {
  login: (data: { username: string; password: string }) =>
    http.post('/auth/login', data),
  listUsers: () => http.get('/auth/users'),
  createUser: (data: any) => http.post('/auth/users', data),
  changePassword: (id: number, oldPassword: string, newPassword: string) =>
    http.put(`/auth/users/${id}/password`, null, { params: { oldPassword, newPassword } }),
}

// ==================== Agent ====================
export const agentApi = {
  list: () => http.get('/agents'),
  get: (id: string | number) => http.get(`/agents/${id}`),
  create: (data: any) => http.post('/agents', data),
  update: (id: string | number, data: any) => http.put(`/agents/${id}`, data),
  delete: (id: string | number) => http.delete(`/agents/${id}`),
  chat: (id: string | number, data: any) => http.post(`/agents/${id}/chat`, data),
  execute: (id: string | number, data: any) => http.post(`/agents/${id}/execute`, data),
  getState: (id: string | number) => http.get(`/agents/${id}/state`),
}

// ==================== Templates ====================
export const templateApi = {
  list: () => http.get('/templates'),
  apply: (id: string) => http.post(`/templates/${id}/apply`),
}

// ==================== Chat ====================
export const chatApi = {
  uploadFile: async (conversationId: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('conversationId', conversationId)
    return http.post('/chat/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    })
  },
  stream: (data: any, signal?: AbortSignal) => {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'Cache-Control': 'no-cache',
    }
    const token = localStorage.getItem('token')
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    return fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
      signal,
    })
  },
  stop: (conversationId: string) =>
    http.post<{ stopped: boolean }>(`/chat/${conversationId}/stop`),
  approve: (conversationId: string, data: { pendingId: string; decision: string }) =>
    http.post(`/chat/${conversationId}/approve`, data),
  getPendingApprovals: (conversationId: string) =>
    http.get(`/chat/${conversationId}/pending-approvals`),
}

// ==================== Conversation ====================
export const conversationApi = {
  list: () => http.get('/conversations'),
  listMessages: (conversationId: string, params?: { beforeId?: number; limit?: number }) =>
    http.get(`/conversations/${conversationId}/messages`, { params }),
  getStatus: (conversationId: string) =>
    http.get(`/conversations/${conversationId}/status`),
  delete: (conversationId: string) =>
    http.delete(`/conversations/${conversationId}`),
  clearMessages: (conversationId: string) =>
    http.delete(`/conversations/${conversationId}/messages`),
  rename: (conversationId: string, title: string) =>
    http.put(`/conversations/${conversationId}/title`, { title }),
}

// ==================== Skill ====================
export const skillApi = {
  /** RFC-042 §2.1 — paginated skill listing with search/type/enabled/scanStatus filters */
  page: (params: {
    page?: number
    size?: number
    keyword?: string
    skillType?: string
    enabled?: boolean
    /** 'PASSED' / 'FAILED' — filters by security_scan_status (RFC-042 §2.3.5) */
    scanStatus?: string
  } = {}) => http.get('/skills', { params }),
  /** Tab count aggregate — returns { all, builtin, mcp, dynamic } */
  counts: () => http.get('/skills/counts'),
  /** RFC-042 §2.3.4 — manually rescan a single skill's security */
  rescan: (id: string | number) => http.post(`/skills/${id}/rescan`),
  listEnabled: () => http.get('/skills/enabled'),
  get: (id: string | number) => http.get(`/skills/${id}`),
  create: (data: any) => http.post('/skills', data),
  update: (id: string | number, data: any) => http.put(`/skills/${id}`, data),
  delete: (id: string | number) => http.delete(`/skills/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/skills/${id}/toggle?enabled=${enabled}`),
  getActiveSkills: () => http.get('/skills/runtime/active'),
  getRuntimeStatus: () => http.get('/skills/runtime/status'),
  refreshRuntime: () => http.post('/skills/runtime/refresh'),
  exportWorkspace: (id: string | number) => http.post(`/skills/${id}/export-workspace`),
  getWorkspaceInfo: (id: string | number) => http.get(`/skills/${id}/workspace`),
}

// ==================== Skill Install ====================
export const skillInstallApi = {
  searchHub: (q: string, limit = 20) =>
    http.get('/skills/install/hub/search', { params: { q, limit } }),
  startInstall: (data: { bundleUrl: string; version?: string; enable?: boolean; targetName?: string; overwrite?: boolean }) =>
    http.post('/skills/install/start', data),
  getStatus: (taskId: string) =>
    http.get(`/skills/install/status/${taskId}`),
  cancelInstall: (taskId: string) =>
    http.post(`/skills/install/cancel/${taskId}`),
  uninstall: (skillName: string) =>
    http.delete(`/skills/install/${skillName}`),
  uploadZip: (file: File, options?: { enable?: boolean; overwrite?: boolean; targetName?: string }) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/skills/install/upload', formData, {
      params: options,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

// ==================== Datasource ====================
export const datasourceApi = {
  list: () => http.get('/datasources'),
  get: (id: string | number) => http.get(`/datasources/${id}`),
  create: (data: any) => http.post('/datasources', data),
  update: (id: string | number, data: any) => http.put(`/datasources/${id}`, data),
  delete: (id: string | number) => http.delete(`/datasources/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/datasources/${id}/toggle?enabled=${enabled}`),
  test: (id: string | number) => http.post(`/datasources/${id}/test`),
}

// ==================== Tool ====================
export const toolApi = {
  list: () => http.get('/tools'),
  listEnabled: () => http.get('/tools/enabled'),
  get: (id: string | number) => http.get(`/tools/${id}`),
  create: (data: any) => http.post('/tools', data),
  update: (id: string | number, data: any) => http.put(`/tools/${id}`, data),
  delete: (id: string | number) => http.delete(`/tools/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/tools/${id}/toggle?enabled=${enabled}`),
}

// ==================== Channel ====================
export const channelApi = {
  list: () => http.get('/channels'),
  get: (id: string | number) => http.get(`/channels/${id}`),
  create: (data: any) => http.post('/channels', data),
  update: (id: string | number, data: any) => http.put(`/channels/${id}`, data),
  delete: (id: string | number) => http.delete(`/channels/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/channels/${id}/toggle?enabled=${enabled}`),
  status: () => http.get('/channels/status'),
  // 微信 iLink Bot QR 码登录
  weixinQrcode: () => http.get('/channels/webhook/weixin/qrcode'),
  weixinQrcodeStatus: (qrcode: string) =>
    http.get(`/channels/webhook/weixin/qrcode/status?qrcode=${encodeURIComponent(qrcode)}`),
}

// ==================== MCP Server ====================
export const mcpApi = {
  list: () => http.get('/mcp/servers'),
  get: (id: string | number) => http.get(`/mcp/servers/${id}`),
  create: (data: any) => http.post('/mcp/servers', data),
  update: (id: string | number, data: any) => http.put(`/mcp/servers/${id}`, data),
  delete: (id: string | number) => http.delete(`/mcp/servers/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/mcp/servers/${id}/toggle?enabled=${enabled}`),
  test: (id: string | number) => http.post(`/mcp/servers/${id}/test`),
  refresh: () => http.post('/mcp/servers/refresh'),
}

// ==================== Plan ====================
export const planApi = {
  listByAgent: (agentId: string) => http.get(`/plans?agentId=${agentId}`),
  get: (id: string | number) => http.get(`/plans/${id}`),
}

// ==================== Model ====================
export const modelApi = {
  listProviders: () => http.get('/models'),
  listEnabled: () => http.get('/models/enabled'),
  get: (id: string | number) => http.get(`/models/${id}`),
  getDefault: () => http.get('/models/default'),
  create: (data: any) => http.post('/models', data),
  update: (id: string | number, data: any) => http.put(`/models/${id}`, data),
  delete: (id: string | number) => http.delete(`/models/${id}`),
  setDefault: (id: string | number) => http.post(`/models/${id}/default`),
  updateProviderConfig: (providerId: string, data: any) =>
    http.put(`/models/${providerId}/config`, data),
  createCustomProvider: (data: any) => http.post('/models/custom-providers', data),
  deleteCustomProvider: (providerId: string) =>
    http.delete(`/models/custom-providers/${providerId}`),
  addProviderModel: (providerId: string, data: any) =>
    http.post(`/models/${providerId}/models`, data),
  removeProviderModel: (providerId: string, modelId: string) =>
    http.delete(`/models/${providerId}/models/${encodeURIComponent(modelId)}`),
  getActive: () => http.get('/models/active'),
  setActive: (data: { providerId: string; model: string }) =>
    http.put('/models/active', data),
  // 模型发现与连接测试
  discoverModels: (providerId: string) =>
    http.post(`/models/${providerId}/discover`),
  applyDiscoveredModels: (providerId: string, modelIds: string[]) =>
    http.post(`/models/${providerId}/discover/apply`, { modelIds }),
  testConnection: (providerId: string) =>
    http.post(`/models/${providerId}/test-connection`),
  testModel: (providerId: string, modelId: string) =>
    http.post(`/models/${providerId}/models/${encodeURIComponent(modelId)}/test`),

  // ==================== Embedding Model (RFC Embedding UI) ====================
  listByType: (modelType: 'chat' | 'embedding') =>
    http.get('/models/by-type', { params: { modelType } }),
  testEmbedding: (modelId: string | number) =>
    http.post(`/models/embedding/${modelId}/test`),
  getDefaultEmbedding: () => http.get('/models/embedding/default'),
  setDefaultEmbedding: (modelId: string | number | '') =>
    http.post('/models/embedding/default', { modelId }),
}

// ==================== Provider Pool (RFC-009 Phase 4) ====================
export interface ProviderPoolEntry {
  providerId: string
  providerName: string
  inPool: boolean
  removalSource: string | null
  removalMessage: string | null
  removedAtMs: number | null
  inCooldown: boolean
  cooldownRemainingMs: number
  consecutiveFailures: number
}

export interface ReprobeResult {
  providerId: string
  success: boolean
  latencyMs: number
  errorMessage: string | null
  inPool: boolean
}

export const providerPoolApi = {
  snapshot: () => http.get<ProviderPoolEntry[]>('/llm/provider-pool'),
  reprobe: (providerId: string) =>
    http.post<ReprobeResult>(`/llm/provider-pool/${encodeURIComponent(providerId)}/reprobe`),
}

// ==================== OAuth ====================
export const oauthApi = {
  authorize: () => http.get('/oauth/openai/authorize'),
  status: () => http.get('/oauth/openai/status'),
  refresh: () => http.post('/oauth/openai/refresh'),
  revoke: () => http.delete('/oauth/openai/revoke'),
}

// ==================== Setup ====================
export const setupApi = {
  onboardingStatus: () => http.get('/setup/onboarding-status'),
}

// ==================== Settings ====================
export const settingsApi = {
  get: () => http.get('/settings'),
  update: (data: any) => http.put('/settings', data),
  getLanguage: () => http.get('/settings/language'),
  updateLanguage: (language: string) => http.put('/settings/language', { language }),
}

// ==================== Workspace ====================
const encodeFilePath = (filename: string) =>
  filename.split('/').map(encodeURIComponent).join('/')

export const agentContextApi = {
  listFiles: (agentId: string | number) =>
    http.get(`/agents/${agentId}/workspace/files`),
  getFile: (agentId: string | number, filename: string) =>
    http.get(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`),
  saveFile: (agentId: string | number, filename: string, content: string) =>
    http.put(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`, { content }),
  deleteFile: (agentId: string | number, filename: string) =>
    http.delete(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`),
  getPromptFiles: (agentId: string | number) =>
    http.get(`/agents/${agentId}/workspace/prompt-files`),
  setPromptFiles: (agentId: string | number, files: string[]) =>
    http.put(`/agents/${agentId}/workspace/prompt-files`, { files }),
}

// ==================== Security ====================
export const securityApi = {
  getGuardConfig: () => http.get('/security/guard/config'),
  updateGuardConfig: (data: any) => http.put('/security/guard/config', data),
  getFileGuardConfig: () => http.get('/security/guard/config/file-guard'),
  updateFileGuardConfig: (data: any) => http.put('/security/guard/config/file-guard', data),
  listRules: (params?: any) => http.get('/security/guard/rules', { params }),
  listBuiltinRules: (params?: any) => http.get('/security/guard/rules/builtin', { params }),
  createRule: (data: any) => http.post('/security/guard/rules', data),
  updateRule: (ruleId: string, data: any) => http.put(`/security/guard/rules/${ruleId}`, data),
  toggleRule: (ruleId: string, enabled: boolean) =>
    http.put(`/security/guard/rules/${ruleId}/toggle?enabled=${enabled}`),
  deleteRule: (ruleId: string) => http.delete(`/security/guard/rules/${ruleId}`),
  listAuditLogs: (params?: any) => http.get('/security/audit/logs', { params }),
  getAuditStats: () => http.get('/security/audit/stats'),
  listApprovals: (params?: any) => http.get('/security/approvals', { params }),
}

// ==================== Token Usage ====================
export const tokenUsageApi = {
  getSummary: (params?: { startDate?: string; endDate?: string; modelName?: string; providerId?: string }) =>
    http.get('/token-usage', { params }),
}

// ==================== CronJob ====================
export const cronJobApi = {
  list: () => http.get('/cron-jobs'),
  get: (id: string | number) => http.get(`/cron-jobs/${id}`),
  create: (data: any) => http.post('/cron-jobs', data),
  update: (id: string | number, data: any) => http.put(`/cron-jobs/${id}`, data),
  delete: (id: string | number) => http.delete(`/cron-jobs/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/cron-jobs/${id}/toggle`, null, { params: { enabled } }),
  runNow: (id: string | number) => http.post(`/cron-jobs/${id}/run`),
}

// ==================== Wiki Knowledge Base ====================
export const wikiApi = {
  // Knowledge Base
  listKBs: () => http.get('/wiki/knowledge-bases'),
  getKB: (id: number) => http.get(`/wiki/knowledge-bases/${id}`),
  listKBsByAgent: (agentId: number) => http.get(`/wiki/knowledge-bases/agent/${agentId}`),
  createKB: (data: { name: string; description?: string; agentId?: number }) =>
    http.post('/wiki/knowledge-bases', data),
  updateKB: (id: number, data: { name?: string; description?: string; agentId?: number; embeddingModelId?: string | number | null }) =>
    http.put(`/wiki/knowledge-bases/${id}`, data),
  deleteKB: (id: number) => http.delete(`/wiki/knowledge-bases/${id}`),
  getConfig: (id: number) => http.get(`/wiki/knowledge-bases/${id}/config`),
  updateConfig: (id: number, content: string) =>
    http.put(`/wiki/knowledge-bases/${id}/config`, { content }),

  // Directory Scan
  setSourceDirectory: (id: number, path: string) =>
    http.put(`/wiki/knowledge-bases/${id}/source-directory`, { path }),
  scanDirectory: (id: number) => http.post(`/wiki/knowledge-bases/${id}/scan`),

  // Raw Materials
  listRaw: (kbId: number) => http.get(`/wiki/knowledge-bases/${kbId}/raw`),
  addRawText: (kbId: number, data: { title: string; content: string }) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/text`, data),
  uploadRaw: (kbId: number, formData: FormData, onProgress?: (pct: number) => void) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/upload`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress
        ? (e) => { if (e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
        : undefined,
    }),
  deleteRaw: (kbId: number, rawId: number) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/raw/${rawId}`),
  reprocessRaw: (kbId: number, rawId: number) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/${rawId}/reprocess`),

  // Wiki Pages
  listPages: (kbId: number, rawId?: number) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages`, rawId != null ? { params: { rawId } } : undefined),
  getPage: (kbId: number, slug: string) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`),
  updatePage: (kbId: number, slug: string, content: string) =>
    http.put(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`, { content }),
  deletePage: (kbId: number, slug: string) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`),
  batchDeletePages: (kbId: number, slugs: string[]) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/pages/batch`, { data: slugs }),
  getBacklinks: (kbId: number, slug: string) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}/backlinks`),

  // Processing
  processKB: (kbId: number) => http.post(`/wiki/knowledge-bases/${kbId}/process`),
  getProcessingStatus: (kbId: number) => http.get(`/wiki/knowledge-bases/${kbId}/processing-status`),

  // RFC-029: Relations
  getRelatedPages: (kbId: number, slug: string, topK = 5) =>
    http.get(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/related`, { params: { topK } }),
  explainRelation: (kbId: number, slugA: string, slugB: string) =>
    http.get(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slugA)}/relation/${encodeURIComponent(slugB)}`),
  getPageCitations: (kbId: number, pageId: number) =>
    http.get(`/wiki/kb/${kbId}/pages/${pageId}/citations`),

  // RFC-030: Jobs
  getWikiJobs: (kbId: number, rawId: number) =>
    http.get(`/wiki/kb/${kbId}/jobs`, { params: { rawId } }),
  getKBStats: (kbId: number) =>
    http.get(`/wiki/kb/${kbId}/stats`),

  // RFC-031: Enrichment & Repair
  enrichPage: (kbId: number, slug: string) =>
    http.post(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/enrich`),
  repairPage: (kbId: number, slug: string) =>
    http.post(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/repair`),

  // RFC-032: Search preview
  searchPreview: (kbId: number, data: { query: string; mode?: string; topK?: number }) =>
    http.post(`/wiki/kb/${kbId}/search-preview`, data),
}

// ==================== Workspace (Team) ====================
export const workspaceTeamApi = {
  list: () => http.get('/workspaces'),
  get: (id: string | number) => http.get(`/workspaces/${id}`),
  create: (data: any) => http.post('/workspaces', data),
  update: (id: string | number, data: any) => http.put(`/workspaces/${id}`, data),
  delete: (id: string | number) => http.delete(`/workspaces/${id}`),
  listMembers: (id: string | number) => http.get(`/workspaces/${id}/members`),
  addMember: (id: string | number, data: { username: string; password?: string; nickname?: string; role?: string }) =>
    http.post(`/workspaces/${id}/members`, data),
  updateMemberRole: (id: string | number, memberId: string | number, role: string) =>
    http.put(`/workspaces/${id}/members/${memberId}`, { role }),
  removeMember: (id: string | number, memberId: string | number) =>
    http.delete(`/workspaces/${id}/members/${memberId}`),
}

// ==================== Agent Binding ====================
export const agentBindingApi = {
  listSkills: (agentId: string | number) => http.get(`/agents/${agentId}/skills`),
  setSkills: (agentId: string | number, skillIds: number[]) => http.put(`/agents/${agentId}/skills`, skillIds),
  bindSkill: (agentId: string | number, skillId: number) => http.post(`/agents/${agentId}/skills/${skillId}`),
  unbindSkill: (agentId: string | number, skillId: number) => http.delete(`/agents/${agentId}/skills/${skillId}`),
  listTools: (agentId: string | number) => http.get(`/agents/${agentId}/tools`),
  setTools: (agentId: string | number, toolNames: string[]) => http.put(`/agents/${agentId}/tools`, toolNames),
  // RFC-009 PR-3: per-agent provider preference order. Empty list = use global chain order.
  listProviderPreferences: (agentId: string | number) =>
    http.get(`/agents/${agentId}/provider-preferences`),
  setProviderPreferences: (agentId: string | number, providerIds: string[]) =>
    http.put(`/agents/${agentId}/provider-preferences`, providerIds),
}

// ==================== Dashboard ====================
export const dashboardApi = {
  overview: () => http.get('/dashboard/overview'),
  trend: (days = 30) => http.get('/dashboard/trend', { params: { days } }),
  agentRanking: (days = 7, topN = 10) => http.get('/dashboard/agent-ranking', { params: { days, topN } }),
  cronJobRuns: (cronJobId: string | number, limit = 20) => http.get(`/dashboard/cron-runs/${cronJobId}`, { params: { limit } }),
  recentRuns: (limit = 20) => http.get('/dashboard/cron-runs', { params: { limit } }),
}

// ==================== Plugins ====================
export const pluginApi = {
  list: () => http.get('/plugins'),
  get: (name: string) => http.get(`/plugins/${name}`),
  disable: (name: string) => http.post(`/plugins/${name}/disable`),
  enable: (name: string) => http.post(`/plugins/${name}/enable`),
  updateConfig: (name: string, config: Record<string, any>) => http.put(`/plugins/${name}/config`, config),
}

// ==================== Audit Events ====================
export const auditApi = {
  listEvents: (params: {
    action?: string
    resourceType?: string
    startTime?: string
    endTime?: string
    page?: number
    size?: number
  }) => http.get('/audit/events', { params }),
}
