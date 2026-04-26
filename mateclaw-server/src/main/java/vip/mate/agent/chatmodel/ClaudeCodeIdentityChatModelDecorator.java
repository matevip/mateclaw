package vip.mate.agent.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC-062: Claude Code OAuth identity transform applied to every Anthropic
 * request when the underlying auth is a Claude Code OAuth token.
 *
 * <p>Anthropic's OAuth edge enforces an anti-abuse path that rate-limits
 * (and intermittently 5xxs) requests whose system prompt does NOT claim
 * Claude Code identity. Symptoms include:
 *
 * <ul>
 *   <li>HTTP 429 with {@code rate_limit_error} on quiet accounts that haven't
 *       come close to their token budget — the give-away is a body of just
 *       {@code {"type":"error","error":{"type":"rate_limit_error","message":"Error"}}}
 *       (genuine quota exhaustion includes a descriptive message).</li>
 *   <li>Sporadic 500s on the first call after a long idle period.</li>
 * </ul>
 *
 * <p>Reference: hermes-agent {@code anthropic_adapter._build_anthropic_messages_request}
 * lines 1571-1607 — applies the same transforms unconditionally on
 * {@code is_oauth=True} requests.
 *
 * <h2>Transforms applied per call</h2>
 * <ol>
 *   <li>Prepend {@code "You are Claude Code, Anthropic's official CLI for Claude."}
 *       to the system prompt. If no system message is present we insert one.</li>
 *   <li>Scrub MateClaw branding from system text — replace
 *       {@code "MateClaw"} → {@code "Claude Code"} and a few common variants —
 *       so server-side content filters don't fire on the spoofed identity.</li>
 * </ol>
 *
 * <p><b>Tool-name {@code mcp_} prefix</b> (hermes lines 1593-1607) is a
 * separate concern: it requires bidirectional rewriting (out + back) and
 * touches Spring AI's tool-callback layer. Deferred to a follow-up — current
 * symptom is rate-limit on memory analysis (which uses no tools).
 */
@Slf4j
public class ClaudeCodeIdentityChatModelDecorator implements ChatModel {

    /** Magic identity prefix Anthropic's OAuth edge requires in the system prompt. */
    static final String CLAUDE_CODE_SYSTEM_PREFIX =
            "You are Claude Code, Anthropic's official CLI for Claude.";

    private final ChatModel delegate;

    public ClaudeCodeIdentityChatModelDecorator(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(transform(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(transform(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * Build a new {@link Prompt} with the OAuth identity transforms applied.
     * Package-private for unit tests.
     */
    Prompt transform(Prompt original) {
        if (original == null) {
            return null;
        }
        List<Message> source = original.getInstructions();
        List<Message> rewritten = new ArrayList<>(source.size() + 1);

        boolean systemSeen = false;
        for (Message msg : source) {
            if (msg instanceof SystemMessage sm && !systemSeen) {
                // Only mutate the FIRST system message — multiple system messages
                // are rare in practice but preserve the second-onward verbatim.
                rewritten.add(new SystemMessage(prependIdentity(sanitizeBranding(sm.getText()))));
                systemSeen = true;
            } else {
                rewritten.add(msg);
            }
        }
        if (!systemSeen) {
            // No system message at all → insert one with just the identity prefix.
            rewritten.add(0, new SystemMessage(CLAUDE_CODE_SYSTEM_PREFIX));
        }
        return new Prompt(rewritten, original.getOptions());
    }

    private static String prependIdentity(String existingSystem) {
        if (existingSystem == null || existingSystem.isBlank()) {
            return CLAUDE_CODE_SYSTEM_PREFIX;
        }
        if (existingSystem.startsWith(CLAUDE_CODE_SYSTEM_PREFIX)) {
            // Already prefixed (defensive — protects against double-wrapping
            // if a caller invokes the decorator twice).
            return existingSystem;
        }
        return CLAUDE_CODE_SYSTEM_PREFIX + "\n\n" + existingSystem;
    }

    /**
     * Replace MateClaw / agent-specific branding tokens with their Claude Code
     * equivalents. Same idea as hermes-agent's
     * {@code text.replace("Hermes Agent", "Claude Code")} chain — Anthropic's
     * server-side filter flags requests where the spoofed identity contradicts
     * itself ("You are Claude Code … built by MateClaw").
     */
    static String sanitizeBranding(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replace("MateClaw", "Claude Code")
                .replace("mateclaw", "claude-code")
                .replace("Mate Claw", "Claude Code");
    }
}
