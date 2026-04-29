/**
 * Two-way translation between the high-level "protocol" the user picks in the
 * provider form (openai-compatible / anthropic-messages / gemini-native /
 * dashscope-native) and the Spring AI ChatModel class name persisted on the
 * provider row.
 *
 * Default for unknown values is OpenAI-compatible — that's the protocol most
 * third-party providers (Kimi, DeepSeek, Zhipu, OpenRouter, OpenCode, etc.)
 * speak, so falling back there is safer than throwing.
 */
export function protocolToChatModel(protocol: string): string {
  if (protocol === 'anthropic-messages') return 'AnthropicChatModel'
  if (protocol === 'gemini-native') return 'GeminiChatModel'
  if (protocol === 'dashscope-native') return 'DashScopeChatModel'
  return 'OpenAIChatModel'
}

export function chatModelToProtocol(chatModel?: string): string {
  if (chatModel === 'AnthropicChatModel') return 'anthropic-messages'
  if (chatModel === 'GeminiChatModel') return 'gemini-native'
  if (chatModel === 'DashScopeChatModel') return 'dashscope-native'
  return 'openai-compatible'
}
