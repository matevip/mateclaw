import { ref, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { channelApi } from '@/api'

export type DingTalkRegisterStatus = '' | 'waiting' | 'confirmed' | 'expired' | 'denied'

export interface DingTalkRegisterResult {
  clientId: string
  clientSecret: string
}

/**
 * 钉钉"一键应用注册"前端状态机：
 *  1. POST /dingtalk/register/begin → sessionId（后端起 worker，开始 5s 轮询 /poll）
 *  2. 每 2s 轮询 /dingtalk/register/status → 拿到 qrcode_img 渲染二维码 / 拿到 confirmed 时回调
 *  3. 终态（confirmed / expired / denied）后停止轮询
 *
 * 跟 useFeishuAppRegister 同构，区别仅在 begin 不需要 domain 参数。
 */
export function useDingTalkAppRegister(onConfirmed: (r: DingTalkRegisterResult) => void) {
  const { t } = useI18n()
  const qrcodeUrl = ref('')
  const loading = ref(false)
  const status = ref<DingTalkRegisterStatus>('')

  let pollTimer: ReturnType<typeof setInterval> | null = null
  let confirmedFired = false

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function reset() {
    stopPolling()
    qrcodeUrl.value = ''
    status.value = ''
    confirmedFired = false
  }

  async function start() {
    reset()
    loading.value = true

    let sessionId = ''
    try {
      const res: any = await channelApi.dingtalkRegisterBegin()
      sessionId = res?.data?.session_id || res?.session_id || ''
      if (!sessionId) {
        ElMessage.error(t('channels.dingtalkRegister.startFailed'))
        return
      }
      status.value = 'waiting'
    } catch {
      ElMessage.error(t('channels.dingtalkRegister.startFailed'))
      return
    } finally {
      loading.value = false
    }

    pollTimer = setInterval(async () => {
      try {
        const res: any = await channelApi.dingtalkRegisterStatus(sessionId)
        const data = res?.data || res || {}
        const s = (data.status as DingTalkRegisterStatus) || 'waiting'

        // Prefer the backend-rendered base64 PNG. The raw qrcode_url is the
        // verification URL that needs encoding into a QR image; browsers can't
        // render plain text as an image. Fall back to URL only as a defensive
        // last resort.
        const img = data.qrcode_img || data.qrcode_url
        if (img && qrcodeUrl.value !== img) {
          qrcodeUrl.value = img
        }
        status.value = s

        if (s === 'confirmed') {
          if (confirmedFired) return
          confirmedFired = true
          stopPolling()
          const clientId = data.client_id || ''
          const clientSecret = data.client_secret || ''
          if (clientId && clientSecret) {
            onConfirmed({ clientId, clientSecret })
            ElMessage.success(t('channels.dingtalkRegister.confirmed'))
          }
          return
        }

        if (s === 'expired') {
          stopPolling()
          ElMessage.warning(t('channels.dingtalkRegister.expired'))
        } else if (s === 'denied') {
          stopPolling()
          ElMessage.warning(t('channels.dingtalkRegister.denied'))
        }
      } catch {
        // Silent — transient network errors should not abort the loop.
      }
    }, 2000)
  }

  onBeforeUnmount(stopPolling)

  return { qrcodeUrl, loading, status, start, reset, stopPolling }
}
