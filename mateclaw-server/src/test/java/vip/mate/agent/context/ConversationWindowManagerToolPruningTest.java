package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.config.ConversationWindowProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationWindowManagerToolPruningTest {

    @Test
    void prunesOlderToolResultsAndKeepsLatestFullResult() {
        ConversationWindowManager manager = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);
        String oldLarge = "old-result\n".repeat(700);
        String latestLarge = "latest-result\n".repeat(700);
        List<Message> messages = List.of(
                new UserMessage("read earlier file"),
                toolMessage("old-1", "read_file", oldLarge),
                new UserMessage("read latest file"),
                toolMessage("new-1", "read_file", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(messages);

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(1);
        ToolResponseMessage latestToolMessage = (ToolResponseMessage) pruned.get(3);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        String latestData = latestToolMessage.getResponses().getFirst().responseData();

        assertTrue(oldData.contains("previous tool output summarized"));
        assertTrue(oldData.length() < 300);
        assertEquals(latestLarge, latestData);
    }

    @Test
    void olderDuplicateToolResultUsesDuplicatePlaceholder() {
        ConversationWindowManager manager = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);
        String repeated = "same-output\n".repeat(700);
        List<Message> messages = List.of(
                toolMessage("old-1", "read_file", repeated),
                toolMessage("new-1", "read_file", repeated)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(messages);

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.getFirst();
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertTrue(oldData.contains("duplicate tool output omitted"));
    }

    private static ToolResponseMessage toolMessage(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }
}
