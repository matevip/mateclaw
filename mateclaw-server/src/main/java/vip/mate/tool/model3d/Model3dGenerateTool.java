package vip.mate.tool.model3d;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.ConcurrencyUnsafe;
import vip.mate.tool.builtin.ToolExecutionContext;

/**
 * @Tool wrapper that lets the agent invoke 3D-model generation.
 * Mirrors {@link vip.mate.tool.music.MusicGenerateTool} — submit immediately,
 * the worker pipeline pushes the resulting model URL via SSE
 * {@code async_task_completed} when generation finishes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Model3dGenerateTool {

    private final Model3dGenerationService model3dGenerationService;

    @ConcurrencyUnsafe("creates async tasks and persists generated 3D assets; provider rate limits also forbid parallel calls")
    @Tool(description = "生成 3D 模型 (.glb)。支持文生 3D 与图生 3D，目前 Provider 为腾讯混元 3D（HY-3D-3.1 / HY-3D-3.0 走 Pro 接口，HY-3D-Express 走 Rapid 极速接口）。任务异步执行（约 1-3 分钟），工具立即返回任务 ID，前端会在生成完成时自动接收 SSE 事件并把 3D 模型推到对话中（带 <model-viewer> 预览），无需用户手动刷新。")
    public String model3d_generate(
            @ToolParam(description = "3D 模型描述，如：'一个可爱的卡通机械爪子吉祥物，金属质感，蓝橙色调'") String prompt,
            @ToolParam(description = "参考图片 URL（可选，给定后走 image-to-3d 模式）") String imageUrl,
            @ToolParam(description = "模型版本：HY-3D-3.1（默认/最高精度）/ HY-3D-3.0 / HY-3D-Express（极速）") String model,
            @ToolParam(description = "是否生成纹理，默认 true（false 走 GenerateType=Geometry 白模，仅 Pro 支持）") Boolean enableTexture,
            @ToolParam(description = "是否生成 PBR 材质（更逼真，但更慢），默认 false。仅 Pro 接口支持（HY-3D-Express 忽略）") Boolean enablePbr,
            @Nullable ToolContext ctx) {

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null) {
            return "无法获取会话 ID";
        }
        String username = ToolExecutionContext.username(ctx);

        Model3dGenerationRequest request = Model3dGenerationRequest.builder()
                .prompt(prompt)
                .imageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl)
                .model(model == null || model.isBlank() ? null : model)
                // outputFormat is currently fixed to glb upstream — keep field on
                // the request so the service-layer normalize step still runs.
                .outputFormat("glb")
                .enableTexture(enableTexture == null ? Boolean.TRUE : enableTexture)
                .enablePbr(enablePbr == null ? Boolean.FALSE : enablePbr)
                .build();

        Model3dGenerationResult result = model3dGenerationService.submitGeneration(
                request, conversationId, username != null ? username : "system");

        if (result.isSubmitted()) {
            return result.getMessage();
        } else {
            return "3D 模型生成失败：" + result.getMessage();
        }
    }
}
