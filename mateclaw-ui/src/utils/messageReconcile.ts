/**
 * 消息 reconcile 工具 — 防止 poorer DB 快照覆盖 local rich message。
 *
 * 问题场景：流结束后 onStreamEnd → refreshCurrentConversationMessages() 从 DB 拉消息，
 * 但此时后端 API 返回的 metadata 可能因 JSON 编码问题丢失 segments，
 * 整表替换会把本地包含完整 segments 的 rich message 覆盖为 poorer 版本。
 *
 * 解决：逐条按 id 比较"丰富度"，只接受更完整的版本。
 */
import type { Message } from '@/types'

/** 安全 parse metadata（处理 string / double-encoded / object / null） */
function safeParseMeta(metadata: any): Record<string, any> {
  if (!metadata) return {}
  if (typeof metadata === 'object') return metadata
  if (typeof metadata === 'string') {
    try {
      let parsed = JSON.parse(metadata)
      if (typeof parsed === 'string') {
        try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
      }
      return typeof parsed === 'object' && parsed !== null ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

/**
 * 计算消息丰富度分数。分数越高，消息包含的展示信息越完整。
 * 只对 assistant 消息有意义。
 */
export function messageRichness(msg: Message): number {
  if (msg.role !== 'assistant') return 0
  let score = 0
  const meta = safeParseMeta(msg.metadata)

  // segments 是分段渲染的权威数据源，权重最高
  const segs = Array.isArray(meta.segments) ? meta.segments : []
  score += segs.length * 10

  // toolCalls
  const tcs = Array.isArray(meta.toolCalls) ? meta.toolCalls : []
  score += tcs.length * 5

  // contentParts 中的 thinking / tool_call
  const parts = Array.isArray(msg.contentParts) ? msg.contentParts : []
  if (parts.some(p => p.type === 'thinking')) score += 20
  score += parts.filter(p => p.type === 'tool_call').length * 5

  // content 文本长度（capped，避免长文本主导）
  score += Math.min((msg.content?.length || 0), 500)

  return score
}

/**
 * 逐条 reconcile：对每条消息按 id 匹配，只接受更丰富的版本。
 *
 * - 新消息（fetched 有但 local 没有）：接受 fetched
 * - 非 assistant 消息：直接用 fetched
 * - assistant 消息：比较 richness，取更高分的版本
 * - 本地有但 fetched 没有的 assistant 消息：保留（防止 lagging snapshot 丢消息）
 */
export function reconcileMessages(local: Message[], fetched: Message[]): Message[] {
  if (!local.length) return fetched
  if (!fetched.length) return local

  const localMap = new Map<string, Message>()
  for (const m of local) {
    localMap.set(String(m.id), m)
  }

  const matchedLocalIds = new Set<string>()
  const result: Message[] = []

  for (const fm of fetched) {
    const fid = String(fm.id)
    const lm = localMap.get(fid)

    if (!lm) {
      result.push(fm)
    } else if (fm.role !== 'assistant') {
      result.push(fm)
      matchedLocalIds.add(fid)
    } else {
      // assistant 消息：比较丰富度，取更高分的
      const lr = messageRichness(lm)
      const fr = messageRichness(fm)
      result.push(fr >= lr ? fm : lm)
      matchedLocalIds.add(fid)
    }
  }

  // 保留 fetched 中不存在的本地 assistant 消息（防止 lagging snapshot 丢弃刚完成的消息）
  for (const lm of local) {
    const lid = String(lm.id)
    if (!matchedLocalIds.has(lid) && lm.role === 'assistant') {
      // 检查是否是 fetched 末尾之后的消息（刚完成，DB 还没返回）
      const lastFetchedTime = result.length > 0 ? result[result.length - 1].createTime : ''
      if (!lastFetchedTime || (lm.createTime && lm.createTime >= lastFetchedTime)) {
        result.push(lm)
      }
    }
  }

  return result
}

/**
 * 统一解析 API 响应中的消息列表。
 * 后端有两种返回模式：
 * - 不传 limit：R.ok(List<MessageVO>) → res.data 是数组
 * - 传 limit：R.ok({messages, hasMore}) → res.data 是对象
 */
export function extractMessages(res: any): { messages: any[], hasMore: boolean } {
  const data = res?.data
  if (Array.isArray(data)) {
    return { messages: data, hasMore: false }
  }
  if (data && Array.isArray(data.messages)) {
    return { messages: data.messages, hasMore: !!data.hasMore }
  }
  return { messages: [], hasMore: false }
}
