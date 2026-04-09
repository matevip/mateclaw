/**
 * MateClaw WebChat — Embeddable Chat Widget
 *
 * Usage:
 *   <script src="https://your-server/mateclaw-webchat.umd.js"></script>
 *   <script>
 *     MateClawWebChat.init({ apiKey: 'your-key', server: 'https://your-server' })
 *   </script>
 */

export interface WebChatConfig {
  /** API Key (from MateClaw channel config) */
  apiKey: string
  /** MateClaw server URL (e.g., https://your-server.com) */
  server: string
  /** Widget position */
  position?: 'bottom-right' | 'bottom-left'
  /** Primary color (hex) */
  primaryColor?: string
  /** Widget title */
  title?: string
  /** Placeholder text */
  placeholder?: string
}

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const DEFAULT_CONFIG: Partial<WebChatConfig> = {
  position: 'bottom-right',
  primaryColor: '#409eff',
  title: 'MateClaw',
  placeholder: 'Type a message...',
}

let config: WebChatConfig
let container: HTMLDivElement
let visitorId: string
let messages: Message[] = []
let isOpen = false
let isStreaming = false

export function init(userConfig: WebChatConfig) {
  config = { ...DEFAULT_CONFIG, ...userConfig }
  visitorId = localStorage.getItem('mc-webchat-visitor') || generateId()
  localStorage.setItem('mc-webchat-visitor', visitorId)

  injectStyles()
  createWidget()
}

function generateId(): string {
  return 'v_' + Math.random().toString(36).substring(2, 10) + Date.now().toString(36)
}

function injectStyles() {
  const style = document.createElement('style')
  style.textContent = `
    .mc-webchat-bubble {
      position: fixed;
      ${config.position === 'bottom-left' ? 'left: 20px' : 'right: 20px'};
      bottom: 20px;
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: ${config.primaryColor};
      color: white;
      border: none;
      cursor: pointer;
      box-shadow: 0 4px 12px rgba(0,0,0,0.15);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 99999;
      transition: transform 0.2s;
    }
    .mc-webchat-bubble:hover { transform: scale(1.1); }
    .mc-webchat-panel {
      position: fixed;
      ${config.position === 'bottom-left' ? 'left: 20px' : 'right: 20px'};
      bottom: 88px;
      width: 380px;
      height: 520px;
      background: white;
      border-radius: 12px;
      box-shadow: 0 8px 32px rgba(0,0,0,0.12);
      display: flex;
      flex-direction: column;
      z-index: 99999;
      overflow: hidden;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    }
    .mc-webchat-header {
      padding: 14px 16px;
      background: ${config.primaryColor};
      color: white;
      font-weight: 600;
      font-size: 15px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .mc-webchat-close {
      background: none;
      border: none;
      color: white;
      cursor: pointer;
      font-size: 18px;
      padding: 0 4px;
      opacity: 0.8;
    }
    .mc-webchat-close:hover { opacity: 1; }
    .mc-webchat-messages {
      flex: 1;
      overflow-y: auto;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .mc-webchat-msg {
      max-width: 85%;
      padding: 8px 12px;
      border-radius: 12px;
      font-size: 14px;
      line-height: 1.5;
      word-break: break-word;
      white-space: pre-wrap;
    }
    .mc-webchat-msg--user {
      align-self: flex-end;
      background: ${config.primaryColor};
      color: white;
      border-bottom-right-radius: 4px;
    }
    .mc-webchat-msg--assistant {
      align-self: flex-start;
      background: #f0f2f5;
      color: #333;
      border-bottom-left-radius: 4px;
    }
    .mc-webchat-input-area {
      padding: 10px 12px;
      border-top: 1px solid #eee;
      display: flex;
      gap: 8px;
    }
    .mc-webchat-input {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid #ddd;
      border-radius: 20px;
      font-size: 14px;
      outline: none;
    }
    .mc-webchat-input:focus { border-color: ${config.primaryColor}; }
    .mc-webchat-send {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: ${config.primaryColor};
      color: white;
      border: none;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .mc-webchat-send:disabled { opacity: 0.5; cursor: not-allowed; }
    @media (max-width: 480px) {
      .mc-webchat-panel { width: calc(100vw - 24px); left: 12px; right: 12px; bottom: 80px; height: 60vh; }
    }
  `
  document.head.appendChild(style)
}

function createWidget() {
  container = document.createElement('div')
  container.id = 'mc-webchat-root'

  // Bubble button
  const bubble = document.createElement('button')
  bubble.className = 'mc-webchat-bubble'
  bubble.innerHTML = `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`
  bubble.onclick = () => togglePanel()
  container.appendChild(bubble)

  document.body.appendChild(container)
}

function togglePanel() {
  isOpen = !isOpen
  const existing = container.querySelector('.mc-webchat-panel')
  if (isOpen && !existing) {
    createPanel()
  } else if (!isOpen && existing) {
    existing.remove()
  }
}

function createPanel() {
  const panel = document.createElement('div')
  panel.className = 'mc-webchat-panel'
  panel.innerHTML = `
    <div class="mc-webchat-header">
      <span>${config.title}</span>
      <button class="mc-webchat-close">&times;</button>
    </div>
    <div class="mc-webchat-messages" id="mc-messages"></div>
    <div class="mc-webchat-input-area">
      <input class="mc-webchat-input" placeholder="${config.placeholder}" id="mc-input" />
      <button class="mc-webchat-send" id="mc-send">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
      </button>
    </div>
  `

  panel.querySelector('.mc-webchat-close')!.addEventListener('click', togglePanel)
  const input = panel.querySelector('#mc-input') as HTMLInputElement
  const sendBtn = panel.querySelector('#mc-send') as HTMLButtonElement

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage(input.value)
    }
  })
  sendBtn.addEventListener('click', () => sendMessage(input.value))

  container.appendChild(panel)

  // Render existing messages
  renderMessages()
}

function renderMessages() {
  const el = document.getElementById('mc-messages')
  if (!el) return
  el.innerHTML = ''
  messages.forEach((msg) => {
    const div = document.createElement('div')
    div.className = `mc-webchat-msg mc-webchat-msg--${msg.role}`
    div.textContent = msg.content
    el.appendChild(div)
  })
  el.scrollTop = el.scrollHeight
}

function appendMessage(role: 'user' | 'assistant', content: string) {
  messages.push({ role, content })
  const el = document.getElementById('mc-messages')
  if (!el) return
  const div = document.createElement('div')
  div.className = `mc-webchat-msg mc-webchat-msg--${role}`
  div.textContent = content
  el.appendChild(div)
  el.scrollTop = el.scrollHeight
  return div
}

function updateLastAssistant(content: string) {
  const el = document.getElementById('mc-messages')
  if (!el) return
  const last = el.querySelector('.mc-webchat-msg--assistant:last-child')
  if (last) {
    last.textContent = content
    el.scrollTop = el.scrollHeight
  }
}

async function sendMessage(text: string) {
  text = text.trim()
  if (!text || isStreaming) return

  const input = document.getElementById('mc-input') as HTMLInputElement
  if (input) input.value = ''

  appendMessage('user', text)
  isStreaming = true

  // Create assistant message placeholder
  appendMessage('assistant', '...')
  let fullContent = ''

  try {
    const response = await fetch(`${config.server}/api/v1/channels/webchat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-MC-Key': config.apiKey,
        Accept: 'text/event-stream',
      },
      body: JSON.stringify({ message: text, visitorId }),
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body?.getReader()
    const decoder = new TextDecoder()

    if (!reader) throw new Error('No response body')

    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (!data) continue
          try {
            const parsed = JSON.parse(data)
            if (parsed.text) {
              fullContent += parsed.text
              updateLastAssistant(fullContent)
            }
          } catch {
            // not JSON, might be raw text
          }
        } else if (line.startsWith('event:')) {
          const eventType = line.slice(6).trim()
          if (eventType === 'done') {
            break
          }
        }
      }
    }

    // Update the stored message
    if (messages.length > 0) {
      messages[messages.length - 1].content = fullContent || '(no response)'
    }
  } catch (e: any) {
    updateLastAssistant(`Error: ${e.message}`)
    if (messages.length > 0) {
      messages[messages.length - 1].content = `Error: ${e.message}`
    }
  } finally {
    isStreaming = false
  }
}

// Auto-export for UMD
if (typeof window !== 'undefined') {
  ;(window as any).MateClawWebChat = { init }
}
