/**
 * Strict JSON object parser used by config / settings forms where the user
 * types raw JSON. Rejects arrays and primitives so downstream `Object.assign`
 * / spread paths are always safe.
 *
 * Throws an Error with a stable message on either parse failure or non-object
 * payload — callers typically display it via toast.
 */
export function safeParseJson(value: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('Generate config must be a JSON object')
    }
    return parsed as Record<string, unknown>
  } catch {
    throw new Error('Invalid JSON format')
  }
}
