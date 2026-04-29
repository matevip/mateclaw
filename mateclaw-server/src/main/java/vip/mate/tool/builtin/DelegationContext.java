package vip.mate.tool.builtin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Tracks Agent delegation call context to prevent infinite recursion and carry parent session info.
 * <p>
 * Uses a ThreadLocal stack so that nested delegations correctly restore the previous layer's
 * parentConversationId and childDeniedTools on exit.
 * Each {@link DelegateAgentTool} delegation calls enter() before and exit() after execution.
 *
 * @author MateClaw Team
 */
public final class DelegationContext {

    /**
     * Snapshot of one delegation layer's state.
     */
    private record Frame(String parentConversationId, Set<String> childDeniedTools) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private DelegationContext() {}

    /** Current delegation depth (0 = top-level call, not inside any delegation) */
    public static int currentDepth() {
        return STACK.get().size();
    }

    /** Parent conversation ID for event relay (from the current frame) */
    public static String parentConversationId() {
        Frame top = STACK.get().peek();
        return top != null ? top.parentConversationId : null;
    }

    /** Denied tools set for the child Agent (from the current frame) */
    public static Set<String> childDeniedTools() {
        Frame top = STACK.get().peek();
        return top != null && top.childDeniedTools != null ? top.childDeniedTools : Set.of();
    }

    /** Enter the next delegation layer (with parent conversation ID and child tool restrictions) */
    public static void enter(String parentConversationId, Set<String> deniedTools) {
        STACK.get().push(new Frame(parentConversationId, deniedTools));
    }

    /** Enter the next delegation layer (backward-compatible overload) */
    public static void enter() {
        enter(null, null);
    }

    /** Exit the current delegation layer, restoring the previous layer's context */
    public static void exit() {
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // Clean up ThreadLocal entirely when the stack is empty to prevent memory leaks
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }
}
