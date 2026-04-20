package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SOUL.md auto-evolution service.
 * <p>
 * Subscribes to {@link MemoryWriteEvent}; after every K writes (configured by
 * soulUpdateInterval), triggers an LLM call to regenerate SOUL.md from the
 * agent's current memory state.
 *
 * <p>When soulUpdateInterval=0, this service is a no-op.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoulSummarizerService {

    private final WorkspaceFileService workspaceFileService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;

    /** Per-agent write counter since last SOUL update */
    private final Map<Long, AtomicInteger> writeCounters = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void onMemoryWrite(MemoryWriteEvent event) {
        int interval = properties.getSoulUpdateInterval();
        if (interval <= 0) return;

        Long agentId = event.agentId();
        AtomicInteger counter = writeCounters.computeIfAbsent(agentId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count < interval) {
            log.debug("[SOUL] Write {}/{} for agent={}, waiting...", count, interval, agentId);
            return;
        }

        // Reset counter and trigger SOUL update
        counter.set(0);
        log.info("[SOUL] Triggering SOUL.md update for agent={} (after {} writes)", agentId, interval);

        try {
            updateSoul(agentId);
        } catch (Exception e) {
            log.warn("[SOUL] Failed to update SOUL.md for agent={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * Regenerate SOUL.md from current agent memory state.
     */
    void updateSoul(Long agentId) {
        // Read current files
        String memoryContent = readSafe(agentId, "MEMORY.md");
        String profileContent = readSafe(agentId, "PROFILE.md");
        String currentSoul = readSafe(agentId, "SOUL.md");

        String systemPrompt = PromptLoader.loadPrompt("memory/soul-summarize");
        String userPrompt = String.format("""
                ## Current SOUL.md
                ```
                %s
                ```

                ## PROFILE.md
                ```
                %s
                ```

                ## MEMORY.md
                ```
                %s
                ```

                Based on the above, regenerate SOUL.md. Keep it concise and personal.
                Output ONLY the new SOUL.md content (no fences, no explanation).
                """, currentSoul, profileContent, memoryContent);

        ChatModel chatModel = buildChatModel();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
        ChatResponse response = chatModel.call(prompt);
        String newSoul = response.getResult().getOutput().getText();

        if (newSoul != null && !newSoul.isBlank() && newSoul.length() > 50) {
            workspaceFileService.saveFile(agentId, "SOUL.md", newSoul.trim());
            log.info("[SOUL] Updated SOUL.md for agent={} ({} chars)", agentId, newSoul.length());
        } else {
            log.debug("[SOUL] LLM returned empty/short response, skipping SOUL update");
        }
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel);
    }

    private String readSafe(Long agentId, String filename) {
        try {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
