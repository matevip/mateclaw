import type { RawStep, RawWorkflow } from './useWorkflowGraph'

/**
 * Surgical helpers for editing the workflow draft JSON without going
 * through a serialize-deserialize round-trip on every keystroke.
 *
 * Each helper preserves unknown fields (e.g. `outputVar`, `timeoutSecs`,
 * future schema additions) by deep-cloning the original object and only
 * overwriting the fields the caller specifies — vs. constructing a
 * fresh step shape that would silently drop anything we forgot to copy.
 */

export interface DraftDoc {
  /** Parsed `{ steps: [...] }` JSON. Always has a `steps` array,
   *  even when the source was empty / malformed. */
  raw: RawWorkflow
  /** True when the source string parsed cleanly; callers should treat
   *  a false here as "do not write back, surface the parse error". */
  ok: boolean
  /** Parser error message when `ok === false`; else null. */
  error: string | null
}

export function parseDraft(json: string): DraftDoc {
  const fallback: RawWorkflow = { steps: [] }
  if (!json || !json.trim()) return { raw: fallback, ok: true, error: null }
  try {
    const parsed = JSON.parse(json) as RawWorkflow
    if (!parsed || typeof parsed !== 'object') {
      return { raw: fallback, ok: false, error: 'workflow root must be an object' }
    }
    if (!Array.isArray(parsed.steps)) parsed.steps = []
    return { raw: parsed, ok: true, error: null }
  } catch (e) {
    return { raw: fallback, ok: false, error: (e as Error).message }
  }
}

export function serializeDraft(doc: RawWorkflow): string {
  // Pretty-printed with 2 spaces matches the templates dropdown output
  // and what the operator sees in the JSON tab — keeps the diffs clean
  // when a single field changes.
  return JSON.stringify(doc, null, 2)
}

/** Returns a deep clone so the caller can mutate without affecting the
 *  original. Falls back to a shallow strategy if structuredClone isn't
 *  available (very old browsers). */
function clone<T>(value: T): T {
  if (typeof structuredClone === 'function') return structuredClone(value)
  return JSON.parse(JSON.stringify(value))
}

/**
 * Merge a sparse patch into one step at {@code index}, preserving the
 * step's unknown fields. Returns a new draft string (always re-serialised
 * even when the patch was a no-op so callers can safely write back).
 */
export function updateStepAtIndex(
  json: string,
  index: number,
  patch: Partial<RawStep>
): string {
  const doc = parseDraft(json)
  if (!doc.ok || !Array.isArray(doc.raw.steps) || index < 0 || index >= doc.raw.steps.length) {
    return json
  }
  const next = clone(doc.raw)
  const cur = next.steps![index] as RawStep
  // Mode field is the only one where a sub-patch should also merge
  // (e.g. just changing `expression` shouldn't drop `type`). For the
  // shared scalar fields a flat overwrite is correct.
  if (patch.mode && cur.mode) {
    next.steps![index] = {
      ...cur,
      ...patch,
      mode: { ...cur.mode, ...patch.mode },
    }
  } else {
    next.steps![index] = { ...cur, ...patch }
  }
  return serializeDraft(next)
}

/** Insert a fresh step right after the one at {@code afterIndex}. Pass
 *  -1 to prepend at position 0. Returns a new draft string. */
export function insertStepAfter(json: string, afterIndex: number, step: RawStep): string {
  const doc = parseDraft(json)
  if (!doc.ok) return json
  const next = clone(doc.raw)
  const arr = Array.isArray(next.steps) ? next.steps! : (next.steps = [], next.steps!)
  const insertAt = afterIndex < 0 ? 0 : Math.min(arr.length, afterIndex + 1)
  arr.splice(insertAt, 0, step)
  return serializeDraft(next)
}

/** Drop one step. Returns the new draft (or the original on bounds
 *  violations / parse failures). */
export function deleteStepAtIndex(json: string, index: number): string {
  const doc = parseDraft(json)
  if (!doc.ok || !Array.isArray(doc.raw.steps)
      || index < 0 || index >= doc.raw.steps.length) {
    return json
  }
  const next = clone(doc.raw)
  next.steps!.splice(index, 1)
  return serializeDraft(next)
}

/** Duplicate one step right after itself, suffixing the name with
 *  `-copy` so the editor's de-dup-on-name UK doesn't immediately fail. */
export function duplicateStepAtIndex(json: string, index: number): string {
  const doc = parseDraft(json)
  if (!doc.ok || !Array.isArray(doc.raw.steps)
      || index < 0 || index >= doc.raw.steps.length) {
    return json
  }
  const original = doc.raw.steps![index] as RawStep
  const copy: RawStep = clone(original)
  if (typeof copy.name === 'string') copy.name = `${copy.name}-copy`
  return insertStepAfter(json, index, copy)
}

/** Resolve the step at {@code index} in the given JSON without
 *  mutating it. Returns null on bounds / parse error. */
export function readStepAtIndex(json: string, index: number): RawStep | null {
  const doc = parseDraft(json)
  if (!doc.ok || !Array.isArray(doc.raw.steps)
      || index < 0 || index >= doc.raw.steps.length) {
    return null
  }
  return doc.raw.steps![index] as RawStep
}
