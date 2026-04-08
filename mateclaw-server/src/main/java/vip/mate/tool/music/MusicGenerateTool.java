package vip.mate.tool.music;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    @Tool(description = "生成音乐或歌曲。支持文字描述生成音乐、歌词谱曲、纯音乐等模式。支持 Google Lyria 和 MiniMax Music 等 Provider。")
    public String music_generate(
            @ToolParam(description = "音乐风格/场景描述，如：'轻快的钢琴爵士乐'、'史诗电影配乐'、'欢快的流行歌曲'") String prompt,
            @ToolParam(description = "歌词文本（可选，不填则由 AI 生成或生成纯音乐）") String lyrics,
            @ToolParam(description = "是否生成纯音乐（无人声），默认 false") Boolean instrumental) {

        String conversationId = ToolExecutionContext.conversationId();
        if (conversationId == null) {
            return "无法获取会话 ID";
        }

        MusicGenerationRequest request = MusicGenerationRequest.builder()
                .prompt(prompt)
                .lyrics(lyrics)
                .instrumental(instrumental != null ? instrumental : false)
                .build();

        Map<String, Object> result = musicGenerationService.generate(conversationId, request);

        if (Boolean.TRUE.equals(result.get("success"))) {
            StringBuilder sb = new StringBuilder("音乐生成完成！\n");
            sb.append("播放链接: ").append(result.get("audioUrl"));
            if (result.containsKey("lyrics") && result.get("lyrics") != null) {
                sb.append("\n\n歌词:\n").append(result.get("lyrics"));
            }
            return sb.toString();
        } else {
            return "音乐生成失败: " + result.get("error");
        }
    }
}
