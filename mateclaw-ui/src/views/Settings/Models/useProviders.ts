import { useProviderList } from './composables/useProviderList'
import { useProviderForm } from './composables/useProviderForm'
import { useProviderDiscovery } from './composables/useProviderDiscovery'
import { useProviderOAuth } from './composables/useProviderOAuth'
import { useProviderPool } from './composables/useProviderPool'
import { useProviderEnablement } from './composables/useProviderEnablement'

/**
 * RFC-074 PR-1: composition facade. Each sub-composable owns one slice of
 * the model-settings surface; this entry point wires them together so
 * `index.vue` can keep its single `const { ... } = useProviders()` pattern.
 *
 * Cross-composable refs flow via dep-injection arguments (not module-level
 * state), so each composable stays independently testable.
 *
 * Owned slices:
 * - useProviderList       — providers / activeModels / currentProvider, status pill, icons
 * - useProviderForm       — provider create/edit modal + form state, save/delete
 * - useProviderDiscovery  — manage-models modal, model discovery, connection / model tests
 * - useProviderOAuth      — OAuth flows (openai-chatgpt + claude-code)
 * - useProviderPool       — manual reprobe trigger (most pool surface inlined in RFC-073)
 * - useProviderEnablement — RFC-074 PR-2: catalog + enable/disable + Add Provider drawer
 */
export function useProviders() {
  const list = useProviderList()
  const form = useProviderForm({
    loadProviders: list.loadProviders,
    loadActiveModel: list.loadActiveModel,
  })
  const discovery = useProviderDiscovery({
    currentProvider: list.currentProvider,
    refreshCurrentProvider: list.refreshCurrentProvider,
  })
  const oauth = useProviderOAuth({
    editingProvider: form.editingProvider,
    loadProviders: list.loadProviders,
    providers: list.providers,
  })
  const pool = useProviderPool({
    loadProviders: list.loadProviders,
  })
  const enablement = useProviderEnablement({
    loadProviders: list.loadProviders,
    loadActiveModel: list.loadActiveModel,
  })

  return {
    ...list,
    ...form,
    ...discovery,
    ...oauth,
    ...pool,
    ...enablement,
  }
}
