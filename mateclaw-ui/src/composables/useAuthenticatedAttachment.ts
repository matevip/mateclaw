import { ref } from 'vue'
import { fetchAuthenticatedBlob } from '@/api/index'
import type { ChatAttachment } from '@/types'

/**
 * 统一受保护附件 Loader
 *
 * 项目使用 Bearer token 认证（localStorage），<img src> 和 <a href> 不会自动携带
 * Authorization header。此 composable 统一处理鉴权 fetch → blob → ObjectURL。
 *
 * 使用方式：
 * - 图片：loadAllImages() 批量加载 → blobUrls[key] 绑定 <img :src>
 * - 文件：downloadFile() 点击时鉴权下载
 * - 图片点击放大：openImage() 同步开窗避免弹窗拦截
 * - 组件卸载：revokeAll() 释放所有 blob URL
 */
export function useAuthenticatedAttachment() {
  /** 已加载的 blob URL 映射（key = storedName || url） */
  const blobUrls = ref<Record<string, string>>({})

  /** 跟踪所有创建的 blob URL，用于清理 */
  const trackedUrls: string[] = []

  /** 正在加载的 URL 集合，防止重复请求 */
  const loading = new Set<string>()

  /**
   * 加载单个附件的 blob URL
   */
  async function loadBlobUrl(url: string, key: string): Promise<string | null> {
    // 跳过：已加载、正在加载、本地 blob
    if (blobUrls.value[key] || loading.has(key) || url.startsWith('blob:')) return blobUrls.value[key] || url
    loading.add(key)
    try {
      const blob = await fetchAuthenticatedBlob(url)
      const objectUrl = URL.createObjectURL(blob)
      blobUrls.value[key] = objectUrl
      trackedUrls.push(objectUrl)
      return objectUrl
    } catch (e) {
      console.warn('[useAuthenticatedAttachment] Failed to load:', url, e)
      return null
    } finally {
      loading.delete(key)
    }
  }

  /**
   * 批量加载所有图片附件的 blob URL
   */
  async function loadAllImages(attachments: ChatAttachment[]) {
    const imageAtts = attachments.filter(a => a.contentType?.startsWith('image/'))
    for (const att of imageAtts) {
      const key = att.storedName || att.url
      if (!att.previewUrl && att.url && !blobUrls.value[key]) {
        await loadBlobUrl(att.url, key)
      }
    }
  }

  /**
   * 批量加载所有视频附件的 blob URL
   */
  async function loadAllVideos(attachments: ChatAttachment[]) {
    const videoAtts = attachments.filter(a => a.contentType?.startsWith('video/'))
    for (const att of videoAtts) {
      const key = att.storedName || att.url
      if (!att.previewUrl && att.url && !blobUrls.value[key]) {
        await loadBlobUrl(att.url, key)
      }
    }
  }

  /**
   * 批量加载所有音频附件的 blob URL（<audio :src> 同样不带 Authorization 头）
   */
  async function loadAllAudios(attachments: ChatAttachment[]) {
    const audioAtts = attachments.filter(a => a.contentType?.startsWith('audio/'))
    for (const att of audioAtts) {
      const key = att.storedName || att.url
      if (!att.previewUrl && att.url && !blobUrls.value[key]) {
        await loadBlobUrl(att.url, key)
      }
    }
  }

  /**
   * 鉴权下载文件：fetch blob → 创建临时 <a download> → 触发点击
   */
  async function downloadFile(attachment: ChatAttachment) {
    try {
      const blob = await fetchAuthenticatedBlob(attachment.url)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = attachment.name || 'download'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      // 延迟释放，确保下载已启动
      setTimeout(() => URL.revokeObjectURL(url), 10000)
    } catch (e) {
      console.error('[useAuthenticatedAttachment] Download failed:', attachment.name, e)
      // fallback：直接打开 URL（可能 401 但至少给个反馈）
      window.open(attachment.url, '_blank')
    }
  }

  /**
   * 鉴权打开图片到新标签页。
   * 同步 window.open 空白页（绕过弹窗拦截），异步写入 blob URL。
   */
  async function openImage(url: string) {
    if (url.startsWith('blob:')) {
      window.open(url, '_blank')
      return
    }
    // 同步开窗：必须在用户点击的同步调用栈中，否则被浏览器拦截
    const win = window.open('about:blank', '_blank')
    if (!win) return
    try {
      const blob = await fetchAuthenticatedBlob(url)
      const blobUrl = URL.createObjectURL(blob)
      win.location.href = blobUrl
      // 页面关闭后释放
      setTimeout(() => URL.revokeObjectURL(blobUrl), 300000)
    } catch {
      win.location.href = url  // fallback
    }
  }

  /**
   * 获取附件的显示 URL（优先 blob → previewUrl → 原始 url）
   */
  function getDisplayUrl(attachment: ChatAttachment): string {
    const key = attachment.storedName || attachment.url
    return blobUrls.value[key] || attachment.previewUrl || attachment.url || ''
  }

  /**
   * 释放所有跟踪的 blob URL
   */
  function revokeAll() {
    for (const url of trackedUrls) {
      URL.revokeObjectURL(url)
    }
    trackedUrls.length = 0
    blobUrls.value = {}
  }

  return {
    blobUrls,
    loadBlobUrl,
    loadAllImages,
    loadAllVideos,
    loadAllAudios,
    downloadFile,
    openImage,
    getDisplayUrl,
    revokeAll,
  }
}
