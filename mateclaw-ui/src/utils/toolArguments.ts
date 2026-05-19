export function formatToolArguments(args?: string | null): string {
  if (!args) return ''
  const trimmed = args.trim()
  if (!trimmed) return ''

  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return trimmed
  }
}

export function shouldShowMcpToolArguments(toolName?: string | null, args?: string | null): boolean {
  return !!toolName?.startsWith('mcp_') && !!formatToolArguments(args)
}
