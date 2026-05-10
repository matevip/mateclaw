package vip.mate.llm.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Caption an image attachment using a configured vision model so a text-only
 * primary model can still reason about it.
 *
 * <p>The service is the execution arm of the sidecar strategy chosen by
 * {@link MultimodalRouter}: pick a vision-capable model, send a single
 * structured prompt with the image attached, return the description text.
 *
 * <p>v1 has no caching layer — every call hits the vision model. The cache
 * (keyed by {@code sha256(file_bytes) + visionModelId + locale}) is reserved
 * for the next iteration; the API shape exposes {@code cacheHit} so callers
 * already record the field in routing metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaCaptionService {

    private final ProviderChatModelFactory chatModelFactory;
    private final RetryTemplate retryTemplate;

    public CaptionResult caption(ModelConfigEntity visionModel, MessageContentPart imagePart, Locale locale) {
        if (visionModel == null || imagePart == null) {
            return CaptionResult.failure(0, new IllegalArgumentException("vision model or image part is null"));
        }
        Path mediaPath = resolveMediaPath(imagePart);
        if (mediaPath == null) {
            return CaptionResult.failure(0, new IllegalStateException(
                    "Image file not found for attachment: " + imagePart.getFileName()));
        }
        String contentType = imagePart.getContentType();
        if (contentType == null || "image/*".equals(contentType)) {
            contentType = "image/jpeg";
        }
        long start = System.currentTimeMillis();
        try {
            ChatModel chatModel = chatModelFactory.buildFor(visionModel, retryTemplate);
            ChatClient client = ChatClient.create(chatModel);
            UserMessage userMessage = UserMessage.builder()
                    .text(buildPrompt(locale, imagePart.getFileName()))
                    .media(List.of(new Media(MimeType.valueOf(contentType), new FileSystemResource(mediaPath))))
                    .build();
            String description = client.prompt()
                    .messages(userMessage)
                    .call()
                    .content();
            long elapsed = System.currentTimeMillis() - start;
            String trimmed = description == null ? "" : description.trim();
            if (trimmed.isEmpty()) {
                return CaptionResult.failure(elapsed,
                        new IllegalStateException("Vision model returned empty description"));
            }
            return CaptionResult.success(trimmed, elapsed, false);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Caption call failed for {} via {}/{}: {}",
                    imagePart.getFileName(), visionModel.getProvider(), visionModel.getModelName(),
                    e.getMessage());
            return CaptionResult.failure(elapsed, e);
        }
    }

    /**
     * Locale-aware prompt. Defaults to Chinese when the locale is null or unrecognized
     * (matches the primary user base) but switches to English so vision-model output
     * matches the chat language and avoids polluting English-only contexts.
     */
    private String buildPrompt(Locale locale, String fileName) {
        boolean english = locale != null && Locale.ENGLISH.getLanguage().equalsIgnoreCase(locale.getLanguage());
        String fileHint = (fileName == null || fileName.isBlank()) ? "" : " (" + fileName + ")";
        if (english) {
            return "Describe this image" + fileHint
                    + " concisely: list the main objects, scene, any visible text (OCR), "
                    + "and notable actions or emotions. Keep the answer under 300 words. "
                    + "Reply with the description only — no preamble.";
        }
        return "请用一段简洁的中文描述这张图片" + fileHint
                + "：列出主要物体、场景、画面中可见的文字（OCR）、以及人物动作或情绪。"
                + "不超过 300 字。直接给出描述，不要寒暄。";
    }

    /**
     * Mirrors {@code BaseAgent.resolveImagePath} but standalone — caption service
     * is reused outside the agent context (e.g. tests, future preflight endpoint).
     */
    private Path resolveMediaPath(MessageContentPart part) {
        Path resolved = tryResolve(part.getPath());
        if (resolved != null) return resolved;
        return tryResolve(part.getMediaId());
    }

    private Path tryResolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path path = Paths.get(relativePath);
        if (path.isAbsolute() && Files.exists(path)) return path;
        Path workdir = Paths.get(System.getProperty("user.dir")).resolve(relativePath);
        if (Files.exists(workdir)) return workdir;
        return null;
    }

    public record CaptionResult(
            String description,
            boolean cacheHit,
            long elapsedMs,
            Throwable failure
    ) {
        public boolean isFailure() {
            return failure != null;
        }

        public static CaptionResult success(String description, long elapsedMs, boolean cacheHit) {
            return new CaptionResult(description, cacheHit, elapsedMs, null);
        }

        public static CaptionResult failure(long elapsedMs, Throwable failure) {
            return new CaptionResult(null, false, elapsedMs, failure);
        }
    }
}
