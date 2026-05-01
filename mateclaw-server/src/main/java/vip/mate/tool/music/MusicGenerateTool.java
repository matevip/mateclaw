package vip.mate.tool.music;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.util.Map;

/**
 * 音乐生成工具 — Agent 可通过 @Tool 注解调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MusicGenerateTool {

    private final MusicGenerationService musicGenerationService;

    @Tool(description = "生成音乐或歌曲。支持文字描述生成音乐、歌词谱曲、纯音乐等模式。支持 Google Lyria 和 MiniMax Music 等 Provider。任务异步执行（约 1-3 分钟），工具立即返回任务 ID，前端会在生成完成时自动接收 SSE 事件并把音频推到对话中。无需用户手动刷新。")
    public String music_generate(
            @ToolParam(description = "音乐风格/场景描述，如：'轻快的钢琴爵士乐'、'史诗电影配乐'、'欢快的流行歌曲'") String prompt,
            @ToolParam(description = "歌词文本（可选，不填则由 AI 生成或生成纯音乐）") String lyrics,
            @ToolParam(description = "是否生成纯音乐（无人声），默认 false") Boolean instrumental,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator.
            @Nullable ToolContext ctx) {

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null) {
            return "无法获取会话 ID";
        }
        String username = ToolExecutionContext.username(ctx);

        MusicGenerationRequest request = MusicGenerationRequest.builder()
                .prompt(prompt)
                .lyrics(lyrics)
                .instrumental(instrumental != null ? instrumental : false)
                .build();

        Map<String, Object> result = musicGenerationService.submitGeneration(
                conversationId, request, username);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return "音乐生成任务已提交（taskId=" + result.get("taskId")
                    + ", provider=" + result.get("providerName")
                    + "）。生成需要约 1-2 分钟，完成后会自动推送到对话。";
        } else {
            return "音乐生成失败: " + result.get("error");
        }
    }
}
