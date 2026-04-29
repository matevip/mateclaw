import { type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { claudeCodeOAuthApi, oauthApi } from '@/api'
import type { ProviderInfo } from '@/types'

interface FormDeps {
  /** Editing-modal context — rebound after a load so the modal sees fresh OAuth state. */
  editingProvider: Ref<ProviderInfo | null>
}

interface ListDeps {
  loadProviders: () => Promise<void>
  /** Read-only access to the current providers list — used to re-resolve editingProvider after refresh. */
  providers: Ref<ProviderInfo[]>
}

/**
 * RFC-074 PR-1: OAuth flows. Two distinct shapes:
 *   - openai-chatgpt: pop a real authorize window, poll status, refresh on success.
 *   - anthropic-claude-code (RFC-062): credentials live on disk under the user's
 *     Claude Code install — the "Connect" button just re-reads from disk.
 */
export function useProviderOAuth(deps: FormDeps & ListDeps) {
  const { t } = useI18n()

  /** After a load that may have changed OAuth state, keep the editing modal in sync. */
  async function reloadProvidersAndSync() {
    await deps.loadProviders()
    if (deps.editingProvider.value) {
      const updated = deps.providers.value.find(p => p.id === deps.editingProvider.value!.id)
      if (updated) deps.editingProvider.value = updated
    }
  }

  async function handleOAuthLogin(providerId?: string) {
    if (providerId === 'anthropic-claude-code') {
      try {
        const res: any = await claudeCodeOAuthApi.reload()
        if (res.data?.connected && !res.data?.expired) {
          ElMessage.success(t('settings.model.oauthLoginSuccess'))
        } else {
          ElMessage.warning(t('settings.model.claudeCodeOauthInstructions'))
        }
        await reloadProvidersAndSync()
      } catch (e: any) {
        ElMessage.error(e.msg || 'Claude Code OAuth detection failed')
      }
      return
    }
    try {
      const res: any = await oauthApi.authorize()
      const { authorizeUrl } = res.data
      const authWindow = window.open(authorizeUrl, '_blank', 'width=600,height=700')
      const pollInterval = setInterval(async () => {
        try {
          const statusRes: any = await oauthApi.status()
          if (statusRes.data?.connected) {
            clearInterval(pollInterval)
            if (authWindow && !authWindow.closed) authWindow.close()
            ElMessage.success(t('settings.model.oauthLoginSuccess'))
            await reloadProvidersAndSync()
          }
        } catch { /* ignore polling errors */ }
      }, 2000)
      setTimeout(() => clearInterval(pollInterval), 30000)
    } catch (e: any) {
      ElMessage.error(e.msg || 'OAuth login failed')
    }
  }

  async function handleOAuthRevoke(providerId?: string) {
    // Claude Code OAuth credentials live on disk and are owned by the Claude
    // Code app, not MateClaw — direct the user to log out there instead of
    // clobbering their machine-level login.
    if (providerId === 'anthropic-claude-code') {
      ElMessage.info(t('settings.model.claudeCodeOauthRevokeHint'))
      return
    }
    try {
      await oauthApi.revoke()
      ElMessage.success(t('settings.model.oauthRevokeSuccess'))
      await reloadProvidersAndSync()
    } catch (e: any) {
      ElMessage.error(e.msg || 'OAuth revoke failed')
    }
  }

  return {
    handleOAuthLogin,
    handleOAuthRevoke,
  }
}
