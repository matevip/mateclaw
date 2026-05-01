import { shallowRef } from 'vue'

/**
 * MateClaw confirm dialog — imperative API.
 *
 * Usage:
 *   const ok = await mcConfirm({
 *     title: '确认',
 *     message: '确定删除这个会话？此操作不可恢复。',
 *     tone: 'danger',
 *   })
 *   if (!ok) return
 *
 * Resolves with `true` on confirm, `false` on cancel / overlay click /
 * Esc. Never rejects — that pattern (which `ElMessageBox.confirm` uses)
 * forces every caller to add a noop `.catch`, and turns ordinary user
 * cancellation into a console-looking "error".
 */
export type ConfirmTone = 'default' | 'primary' | 'danger'

export interface ConfirmOptions {
  /** Header text. Defaults to a generic "Confirm" string. */
  title?: string
  /** Body copy. Required — this is the question being asked. */
  message: string
  /** Confirm button label. Defaults to common.confirm. */
  confirmText?: string
  /** Cancel button label. Defaults to common.cancel. */
  cancelText?: string
  /** Visual tone of the icon + confirm button. */
  tone?: ConfirmTone
}

export interface ActiveConfirm extends ConfirmOptions {
  resolve: (ok: boolean) => void
}

/**
 * Singleton slot. The host component watches this and renders one dialog
 * at a time. We use `shallowRef` because the resolve fn is non-reactive
 * and the options blob is replaced wholesale, never mutated.
 */
export const activeConfirm = shallowRef<ActiveConfirm | null>(null)

export function mcConfirm(opts: ConfirmOptions): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    // If a previous prompt is somehow still open (unlikely — clicks
    // close it before resolving), cancel it so we don't leak a never-
    // settled promise.
    if (activeConfirm.value) {
      activeConfirm.value.resolve(false)
    }
    activeConfirm.value = { ...opts, resolve }
  })
}

export function resolveConfirm(ok: boolean) {
  const current = activeConfirm.value
  if (!current) return
  activeConfirm.value = null
  current.resolve(ok)
}
