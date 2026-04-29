/**
 * 聊天错误分类与结构化信息
 * 将 HTTP 状态码和网络异常映射为用户可理解的错误类别
 */

export type ChatErrorCategory =
  | 'rate_limit'          // 429
  | 'auth_expired'        // user-side session expired (our backend 401) — triggers /login redirect
  | 'provider_auth_error' // LLM provider 401 (e.g. invalid Kimi/OpenAI API key) — unrelated to user login
  | 'forbidden'           // 403
  | 'bad_request'         // 400
  | 'server_error'        // 500
  | 'service_unavailable' // 503
  | 'timeout'             // 请求超时
  | 'network'             // 网络不可达
  | 'unknown'

export interface ChatErrorInfo {
  category: ChatErrorCategory
  httpStatus?: number
  requestId?: string
  rawMessage?: string
  retryable: boolean
  timestamp: number
}

/**
 * 根据 HTTP 状态码分类错误
 */
export function classifyHttpError(status: number, body?: any): ChatErrorInfo {
  const base: ChatErrorInfo = {
    category: 'unknown',
    httpStatus: status,
    rawMessage: body?.msg || body?.message || undefined,
    requestId: body?.requestId || undefined,
    retryable: false,
    timestamp: Date.now(),
  }

  switch (true) {
    case status === 429:
      return { ...base, category: 'rate_limit', retryable: true }
    case status === 401:
      return { ...base, category: 'auth_expired', retryable: false }
    case status === 403:
      return { ...base, category: 'forbidden', retryable: false }
    case status === 400:
      return { ...base, category: 'bad_request', retryable: false }
    case status === 503:
      return { ...base, category: 'service_unavailable', retryable: true }
    case status >= 500:
      return { ...base, category: 'server_error', retryable: true }
    default:
      return { ...base, retryable: true }
  }
}

/**
 * 后端 ErrorType 枚举值 → 前端 ChatErrorCategory 映射
 * 后端通过 SSE error 事件的 errorType 字段传递
 */
const BACKEND_ERROR_TYPE_MAP: Record<string, { category: ChatErrorCategory; retryable: boolean }> = {
  RATE_LIMIT:           { category: 'rate_limit',          retryable: true },
  SERVER_ERROR:         { category: 'server_error',        retryable: true },
  PROMPT_TOO_LONG:      { category: 'bad_request',         retryable: false },
  // RFC fix: backend AUTH_ERROR comes from the LLM provider (e.g. Kimi 401),
  // NOT from the user's own session expiring. Map to provider_auth_error so
  // the UI shows "model authentication failed" instead of "session expired /
  // redirecting to login".
  AUTH_ERROR:           { category: 'provider_auth_error',  retryable: false },
  // 后端 ErrorType.CLIENT_ERROR 对应 HTTP 400 类错误（比如模型不支持 tools、参数格式错误）。
  // 归类到 bad_request，配合 MessageBubble 优先展示 rawMessage，
  // 让后端 extractUserFriendlyError 返回的具体中文提示能真正显示出来。
  CLIENT_ERROR:         { category: 'bad_request',         retryable: false },
  THINKING_BLOCK_ERROR: { category: 'bad_request',         retryable: true },
  UNKNOWN:              { category: 'unknown',             retryable: true },
}

/**
 * 根据后端 SSE error 事件数据构建 ChatErrorInfo
 * 后端 payload: { message, conversationId, errorType }
 */
export function classifyBackendError(data: {
  message?: string
  errorType?: string
  conversationId?: string
}): ChatErrorInfo {
  const mapped = BACKEND_ERROR_TYPE_MAP[data.errorType || '']
  return {
    category: mapped?.category || 'unknown',
    rawMessage: data.message,
    retryable: mapped?.retryable ?? true,
    timestamp: Date.now(),
  }
}

/**
 * 从持久化的消息内容中重建 ChatErrorInfo
 * 后端将错误存为 "[错误] LLM 调用失败: 请求频率过高，请稍后重试" 格式的文本。
 * 页面刷新后从数据库加载时 errorInfo 丢失，需要根据文本模式重建。
 */
// Order matters: the narrow auth_expired pattern MUST come before the broader
// provider_auth_error pattern, otherwise legitimate session-expiry messages
// would be misclassified as a model auth issue.
//
// auth_expired = our own backend's session expired (token invalid, will redirect to /login)
// provider_auth_error = LLM provider returned 401 (e.g. Kimi API key invalid; user stays logged in)
const ERROR_TEXT_PATTERNS: Array<{ pattern: RegExp; category: ChatErrorCategory; retryable: boolean }> = [
  { pattern: /频率|rate.?limit|too.?many|quota|429/i,       category: 'rate_limit',          retryable: true },
  // Narrow: only fire on explicit signals that the user's own session is gone.
  { pattern: /HTTP 401|登录已过期|session.?expired|凭证.*失效/i, category: 'auth_expired',  retryable: false },
  // Broad: any other 401-ish wording is treated as a model-side auth failure.
  { pattern: /unauthorized|401|invalid.?api.?key|api.?key.*expired|认证失败/i, category: 'provider_auth_error', retryable: false },
  { pattern: /权限|forbidden|403/i,                          category: 'forbidden',           retryable: false },
  { pattern: /过长|too.?long|context.?length|prompt/i,       category: 'bad_request',         retryable: false },
  { pattern: /超时|timeout/i,                                category: 'timeout',             retryable: true },
  { pattern: /不可用|unavailable|503|502|504|过载|overload/i, category: 'service_unavailable', retryable: true },
  { pattern: /服务器|server.?error|500|internal/i,           category: 'server_error',        retryable: true },
]

export function reconstructErrorInfo(text: string): ChatErrorInfo | null {
  if (!text || !text.startsWith('[错误]')) return null
  const rawMessage = text.replace(/^\[错误]\s*/, '')
  for (const { pattern, category, retryable } of ERROR_TEXT_PATTERNS) {
    if (pattern.test(rawMessage)) {
      return { category, rawMessage, retryable, timestamp: 0 }
    }
  }
  return { category: 'unknown', rawMessage, retryable: true, timestamp: 0 }
}

/**
 * 根据网络层异常分类错误
 */
export function classifyNetworkError(error: Error): ChatErrorInfo {
  const isTimeout = error.name === 'TimeoutError'
    || error.message?.includes('timeout')
    || error.message?.includes('Timeout')

  return {
    category: isTimeout ? 'timeout' : 'network',
    rawMessage: error.message,
    retryable: true,
    timestamp: Date.now(),
  }
}
