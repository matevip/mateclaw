package vip.mate.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆整合服务
 * <p>
 * 读取近 N 天的 daily notes，提炼反复出现的模式和重要信息，
 * 合并到 MEMORY.md 中。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEmergenceService {

    private final WorkspaceFileService workspaceFileService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final MemoryRecallService recallService;

    /**
     * 执行记忆整合：将 daily notes 中的重复模式提炼到 MEMORY.md
     *
     * @param agentId Agent ID
     */
    public void consolidate(Long agentId) {
        if (!properties.isEmergenceEnabled()) {
            log.debug("[Memory] Emergence is disabled, skipping for agent={}", agentId);
            return;
        }

        // 1. 列出所有 memory/*.md 文件
        List<WorkspaceFileEntity> allFiles = workspaceFileService.listFiles(agentId);
        List<String> dailyFilenames = allFiles.stream()
                .map(WorkspaceFileEntity::getFilename)
                .filter(f -> f.startsWith("memory/") && f.endsWith(".md"))
                .sorted(Comparator.reverseOrder())
                .limit(properties.getEmergenceDayRange())
                .toList();

        if (dailyFilenames.isEmpty()) {
            log.info("[Memory] No daily notes found for agent={}, skipping emergence", agentId);
            return;
        }

        // 2. 读取 daily notes 内容
        StringBuilder dailyNotesBuilder = new StringBuilder();
        for (String filename : dailyFilenames) {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            if (file != null && file.getContent() != null && !file.getContent().isBlank()) {
                dailyNotesBuilder.append("### ").append(filename).append("\n");
                dailyNotesBuilder.append(file.getContent().trim()).append("\n\n");
            }
        }
        String dailyNotes = dailyNotesBuilder.toString().trim();

        if (dailyNotes.isEmpty()) {
            log.info("[Memory] All daily notes are empty for agent={}, skipping emergence", agentId);
            return;
        }

        // 3. 读取现有 MEMORY.md
        String memoryContent = readFileContentSafe(agentId, "MEMORY.md");

        // 4. 计算召回评分（必须在 resetDailyCounts 之前，否则 velocity 信号被清零）
        List<MemoryRecallEntity> scoredCandidates = recallService.computeScores(agentId);
        boolean hasScoredCandidates = !scoredCandidates.isEmpty();

        // 5. 评分快照完成后再重置 dailyCount，为下一轮积累
        recallService.resetDailyCounts(agentId);

        String systemPrompt = PromptLoader.loadPrompt("memory/emergence-system");
        String userPrompt;

        if (hasScoredCandidates) {
            // 使用评分增强的 prompt
            String candidatesText = formatScoredCandidates(scoredCandidates);
            String userTemplate = PromptLoader.loadPrompt("memory/emergence-scored-user");
            userPrompt = userTemplate
                    .replace("{memory}", memoryContent)
                    .replace("{scored_candidates}", candidatesText)
                    .replace("{day_range}", String.valueOf(properties.getEmergenceDayRange()))
                    .replace("{daily_notes}", dailyNotes);
            log.info("[Memory] Emergence with {} scored candidates for agent={}", scoredCandidates.size(), agentId);
        } else {
            // 冷启动：回退到原有纯 LLM 逻辑
            String userTemplate = PromptLoader.loadPrompt("memory/emergence-user");
            userPrompt = userTemplate
                    .replace("{memory}", memoryContent)
                    .replace("{day_range}", String.valueOf(properties.getEmergenceDayRange()))
                    .replace("{daily_notes}", dailyNotes);
        }

        String llmResponse;
        try {
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));
            ChatResponse response = chatModel.call(prompt);
            llmResponse = response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[Memory] Emergence LLM call failed for agent={}: {}", agentId, e.getMessage());
            return;
        }

        // 5. 解析并应用
        try {
            JsonNode root = parseJsonResponse(llmResponse);
            if (root == null || !root.path("should_update").asBoolean(false)) {
                String reason = root != null ? root.path("reason").asText("") : "parse failed";
                log.info("[Memory] No emergence update needed for agent={}: {}", agentId, reason);
                return;
            }

            JsonNode memoryNode = root.path("memory_content");
            if (!memoryNode.isNull() && memoryNode.isTextual()) {
                String newContent = memoryNode.asText().trim();
                if (!newContent.isEmpty()) {
                    workspaceFileService.saveFile(agentId, "MEMORY.md", newContent);
                    String reason = root.path("reason").asText("");
                    log.info("[Memory] Emergence completed for agent={}: {}", agentId, reason);

                    // 逐候选检查：只有内容被 LLM 实际采纳（出现在新 MEMORY.md 中）的才标记为已提升
                    if (hasScoredCandidates) {
                        List<Long> promotedIds = scoredCandidates.stream()
                                .filter(c -> candidateAdoptedInMemory(c, newContent))
                                .map(MemoryRecallEntity::getId)
                                .collect(Collectors.toList());
                        if (!promotedIds.isEmpty()) {
                            recallService.markPromoted(promotedIds);
                        }
                        log.info("[Memory] Promoted {}/{} recall candidates for agent={}",
                                promotedIds.size(), scoredCandidates.size(), agentId);

                        // 写入 DREAMS.md 整合日记
                        appendDreamDiary(agentId, scoredCandidates, promotedIds);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Memory] Failed to parse/apply emergence result for agent={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 将本轮 dreaming 结果追加到 DREAMS.md 整合日记
     */
    private void appendDreamDiary(Long agentId, List<MemoryRecallEntity> allCandidates, List<Long> promotedIds) {
        try {
            java.util.Set<Long> promotedSet = new java.util.HashSet<>(promotedIds);

            List<MemoryRecallEntity> promoted = allCandidates.stream()
                    .filter(c -> promotedSet.contains(c.getId()))
                    .sorted(java.util.Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                    .toList();
            List<MemoryRecallEntity> kept = allCandidates.stream()
                    .filter(c -> !promotedSet.contains(c.getId()))
                    .sorted(java.util.Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                    .toList();

            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            StringBuilder diary = new StringBuilder();
            diary.append("## ").append(timestamp).append(" Dreaming\n\n");
            diary.append(String.format("**评分候选**: %d 条（阈值 %.1f）\n",
                    allCandidates.size(), properties.getEmergenceScoreThreshold()));
            diary.append(String.format("**实际整合**: %d 条\n\n", promoted.size()));

            if (!promoted.isEmpty()) {
                diary.append("### 已整合\n");
                for (MemoryRecallEntity c : promoted) {
                    diary.append(String.format("- `%s` (score=%.2f, recalls=%d)\n",
                            c.getFilename(), c.getScore(), c.getRecallCount()));
                }
                diary.append("\n");
            }

            if (!kept.isEmpty()) {
                diary.append("### 未整合（保留下轮）\n");
                for (MemoryRecallEntity c : kept) {
                    diary.append(String.format("- `%s` (score=%.2f, recalls=%d)\n",
                            c.getFilename(), c.getScore(), c.getRecallCount()));
                }
                diary.append("\n");
            }

            // 读取现有 DREAMS.md，追加新日记
            String existing = readFileContentSafe(agentId, "DREAMS.md");
            String newContent = existing.isBlank()
                    ? "# Dreaming 整合日记\n\n" + diary
                    : existing + "\n" + diary;

            // 防止无限膨胀：超过 20KB 时截断，只保留最近的内容
            if (newContent.length() > 20_000) {
                int cutPoint = newContent.length() - 16_000;
                // 找到下一个 "## " 标记作为安全截断点
                int safePoint = newContent.indexOf("\n## ", cutPoint);
                if (safePoint > 0) {
                    newContent = "# Dreaming 整合日记\n\n> 早期记录已归档\n\n"
                            + newContent.substring(safePoint + 1);
                }
            }

            workspaceFileService.saveFile(agentId, "DREAMS.md", newContent);
            log.info("[Memory] Dream diary appended for agent={}", agentId);
        } catch (Exception e) {
            log.warn("[Memory] Failed to write dream diary for agent={}: {}", agentId, e.getMessage());
        }
    }

    private String formatScoredCandidates(List<MemoryRecallEntity> candidates) {
        StringBuilder sb = new StringBuilder();
        for (MemoryRecallEntity entry : candidates) {
            sb.append(String.format("### %s (score=%.2f, recalls=%d)\n",
                    entry.getFilename(), entry.getScore(), entry.getRecallCount()));
            if (entry.getSnippetPreview() != null) {
                sb.append(entry.getSnippetPreview());
                sb.append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 判断候选片段是否被 LLM 实际采纳到新 MEMORY.md 中。
     * 通过检查片段预览中的关键短语（取前 3 个非空行的前 20 字符）是否出现在新内容中。
     */
    private boolean candidateAdoptedInMemory(MemoryRecallEntity candidate, String newMemoryContent) {
        String preview = candidate.getSnippetPreview();
        if (preview == null || preview.isBlank() || newMemoryContent == null) {
            return false;
        }
        // 从 snippet 提取关键短语进行匹配
        String[] lines = preview.split("\n");
        int matched = 0;
        int checked = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (checked >= 3) break;
            checked++;
            // 取行的核心内容（去掉 markdown 标记），检查是否出现在新 MEMORY.md 中
            String key = trimmed.replaceAll("^[-*>]+\\s*", "");
            if (key.length() > 20) key = key.substring(0, 20);
            if (key.length() >= 5 && newMemoryContent.contains(key)) {
                matched++;
            }
        }
        // 至少有 1 个关键短语命中才算采纳
        return matched > 0;
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel);
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) return null;

        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[Memory] Failed to parse emergence JSON response: {}", e.getMessage());
            return null;
        }
    }

    private String readFileContentSafe(Long agentId, String filename) {
        try {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
