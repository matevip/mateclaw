package vip.mate.tool.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music generation service.
 * <p>
 * Music providers (MiniMax, Lyria) are synchronous from the upstream API
 * perspective — a single HTTP call blocks for ~120s and returns the audio
 * bytes. Running that on the chat SSE thread freezes the conversation's
 * event stream for two minutes; instead we register an
 * {@link AsyncTaskEntity} for state + SSE bookkeeping and dispatch the actual
 * provider call to a dedicated virtual-thread worker. The chat thread returns
 * with a taskId immediately.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MusicGenerationService {

    private final SystemSettingService systemSettingService;
    private final MusicProviderRegistry providerRegistry;
    private final AsyncTaskService asyncTaskService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");
    private static final String TASK_TYPE = "music_generation";

    /** Dedicated virtual-thread worker. Music generation blocks on a single
     *  upstream HTTP call (~120s) — keeping it off the polling pool and the
     *  chat SSE thread is the whole point of P0b. */
    private final ExecutorService musicWorker = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("music-gen-", 0).factory());

    /**
     * Submit a music generation request. Returns immediately with a taskId;
     * actual generation runs on {@link #musicWorker} and completion is
     * broadcast via {@link AsyncTaskService#broadcastTaskEvent}.
     */
    public Map<String, Object> submitGeneration(String conversationId,
                                                 MusicGenerationRequest request,
                                                 String createdBy) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getMusicEnabled())) {
            return Map.of("success", false, "error", "音乐生成功能未启用");
        }

        MusicGenerationProvider primary = providerRegistry.resolve(config);
        if (primary == null) {
            return Map.of("success", false, "error", "没有可用的音乐 Provider");
        }

        AsyncTaskEntity task;
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String localProviderTaskId = "music_" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 12);
            task = asyncTaskService.createTask(
                    TASK_TYPE, conversationId, null,
                    primary.id(),
                    localProviderTaskId,
                    requestJson, createdBy);
        } catch (Exception e) {
            log.error("[Music] Failed to create async task: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "创建任务失败: " + e.getMessage());
        }

        AsyncTaskEntity finalTask = task;
        musicWorker.execute(() -> doGenerate(finalTask, request, config, conversationId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("taskId", task.getTaskId());
        response.put("providerName", primary.id());
        return response;
    }

    // ==================== Worker thread ====================

    private void doGenerate(AsyncTaskEntity task, MusicGenerationRequest request,
                             SystemSettingsDTO config, String conversationId) {
        try {
            asyncTaskService.updateStatus(task.getTaskId(), "running", null, null, null);

            MusicGenerationResult result = generateWithFallback(request, config);
            if (!result.isSuccess()) {
                asyncTaskService.updateStatus(task.getTaskId(), "failed", null, null,
                        result.getErrorMessage());
                asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                        false, Map.of(), result.getErrorMessage());
                return;
            }

            String audioUrl = persistAudio(conversationId, task.getTaskId(), result);

            saveAssistantMessage(conversationId, audioUrl, result);

            ObjectNode resultJson = objectMapper.createObjectNode();
            resultJson.put("audioUrl", audioUrl);
            resultJson.put("format", result.getFormat());
            if (result.getLyrics() != null) {
                resultJson.put("lyrics", result.getLyrics());
            }
            asyncTaskService.updateStatus(task.getTaskId(), "succeeded", 100,
                    resultJson.toString(), null);

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("audioUrl", audioUrl);
            extra.put("format", result.getFormat());
            if (result.getLyrics() != null) {
                extra.put("lyrics", result.getLyrics());
            }
            asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                    true, extra, null);

            log.info("[Music] Task {} succeeded, audio at {}", task.getTaskId(), audioUrl);
        } catch (Exception e) {
            log.error("[Music] Task {} worker failed: {}", task.getTaskId(), e.getMessage(), e);
            asyncTaskService.updateStatus(task.getTaskId(), "failed", null, null,
                    "音乐生成异常: " + e.getMessage());
            asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                    false, Map.of(), "音乐生成异常: " + e.getMessage());
        }
    }

    private String persistAudio(String conversationId, String taskId,
                                  MusicGenerationResult result) throws IOException {
        Path dir = UPLOAD_ROOT.resolve(conversationId);
        Files.createDirectories(dir);
        String fileName = "music_" + taskId + "." + result.getFormat();
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, result.getAudioData());
        return "/api/v1/chat/files/" + conversationId + "/" + fileName;
    }

    private void saveAssistantMessage(String conversationId, String audioUrl,
                                        MusicGenerationResult result) {
        MessageContentPart audioPart = MessageContentPart.audio(null,
                audioUrl.substring(audioUrl.lastIndexOf('/') + 1));
        audioPart.setFileUrl(audioUrl);
        audioPart.setContentType(result.getContentType());

        StringBuilder content = new StringBuilder("音乐生成完成");
        if (result.getLyrics() != null && !result.getLyrics().isBlank()) {
            content.append("\n\n歌词:\n").append(result.getLyrics());
        }
        conversationService.saveMessage(conversationId, "assistant",
                content.toString(), List.of(audioPart), "completed");
    }

    private MusicGenerationResult generateWithFallback(MusicGenerationRequest request,
                                                         SystemSettingsDTO config) {
        MusicGenerationProvider primary = providerRegistry.resolve(config);
        if (primary == null) return MusicGenerationResult.failure("没有可用的音乐 Provider");

        MusicGenerationResult result = primary.generate(request, config);
        if (result.isSuccess()) return result;

        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getMusicFallbackEnabled())) {
            for (MusicGenerationProvider fb : providerRegistry.fallbackCandidates(config, primary.id())) {
                log.info("[Music] Trying fallback provider: {}", fb.id());
                result = fb.generate(request, config);
                if (result.isSuccess()) return result;
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        return MusicGenerationResult.failure("所有音乐 Provider 均失败\n" + String.join("\n", errors));
    }

    @PreDestroy
    public void shutdown() {
        musicWorker.shutdown();
    }
}
