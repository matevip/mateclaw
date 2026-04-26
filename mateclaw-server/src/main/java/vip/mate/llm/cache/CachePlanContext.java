package vip.mate.llm.cache;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.llm.model.ModelProtocol;

import java.util.List;

/**
 * 策略评估缓存方案时所需的只读上下文。
 *
 * <p>不持有 {@link Prompt} 引用以外的可变状态；所有派生指标（system 长度、tool 数等）按需懒计算
 * 并由 record 字段缓存，避免重复扫描。</p>
 */
public record CachePlanContext(
        Prompt prompt,
        ModelProtocol protocol,
        int totalPromptTokens,
        int turnCount,
        int minPromptTokens,
        int maxBreakpoints,
        boolean includeToolsBlock,
        CacheTtl ttl) {

    public CachePlanContext {
        if (prompt == null)   throw new IllegalArgumentException("prompt must not be null");
        if (protocol == null) throw new IllegalArgumentException("protocol must not be null");
        if (ttl == null)      ttl = CacheTtl.DEFAULT_5M;
        if (maxBreakpoints <= 0) maxBreakpoints = 4;
    }

    /** 消息列表（不可变视图；上层不应修改）。 */
    public List<Message> messages() {
        return prompt.getInstructions();
    }

    /** 是否存在 system 消息（用于决定是否打 SYSTEM_TAIL 断点）。 */
    public boolean hasSystemMessage() {
        for (Message m : messages()) {
            if (m.getMessageType() == MessageType.SYSTEM) return true;
        }
        return false;
    }

    /** 系统消息的字符长度总和，用于估算是否值得缓存 system。 */
    public int systemCharLen() {
        int n = 0;
        for (Message m : messages()) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                String t = m.getText();
                if (t != null) n += t.length();
            }
        }
        return n;
    }

    /** 协议是否原生支持 cache_control 标记。DashScope/Ollama/Gemini 自有缓存机制，无需接入。 */
    public boolean protocolSupportsCacheControl() {
        return switch (protocol) {
            // Claude Code OAuth (RFC-062) tunnels through the same Messages API,
            // so it inherits Anthropic-native cache_control support.
            case ANTHROPIC_MESSAGES, ANTHROPIC_CLAUDE_CODE, OPENAI_CHATGPT -> true;
            case OPENAI_COMPATIBLE, DASHSCOPE_NATIVE, GEMINI_NATIVE -> false;
        };
    }
}
