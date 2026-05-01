package vip.mate.tool.model3d.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.model3d.Model3dCapability;
import vip.mate.tool.model3d.Model3dGenerationProvider;
import vip.mate.tool.model3d.Model3dGenerationRequest;
import vip.mate.tool.model3d.Model3dProviderCapabilities;
import vip.mate.tool.model3d.Model3dSubmitResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tencent Hunyuan 3D Rapid provider — async submit/poll using the
 * {@code ai3d} TencentCloud API service (v2025-05-13).
 *
 * <h3>Credentials</h3>
 * Resolved from {@code mate_model_provider} entry {@code provider_id='hunyuan-3d'}.
 * The {@code api_key} column stores {@code "SecretId:SecretKey"} (colon-joined,
 * mirrors the existing two-key Kling provider convention). The {@code base_url}
 * column may override the API host (default {@code ai3d.tencentcloudapi.com}).
 *
 * <h3>API surface</h3>
 * <ul>
 *   <li>{@code SubmitHunyuanTo3DRapidJob} — accepts {@code Prompt} OR {@code ImageUrl} (mutually exclusive)</li>
 *   <li>{@code QueryHunyuanTo3DRapidJob} — polls Status ∈ {WAIT, RUN, DONE, FAIL}</li>
 *   <li>Region: {@code ap-guangzhou} (the only documented region for ai3d)</li>
 *   <li>Concurrency: 1 task by default — {@link vip.mate.tool.ConcurrencyUnsafe} on the tool ensures the agent doesn't fire two in parallel</li>
 * </ul>
 *
 * <h3>Result delivery contract</h3>
 * On {@code DONE}, {@link #checkStatus} returns
 * {@code TaskPollResult.succeeded(null, null, <json>)} where {@code <json>} is
 * {@code {"modelUrl": "<glb-url>", "format": "glb"}}. The
 * {@code Model3dGenerationService.handleCompletion} parses that out, downloads
 * the asset, and persists a {@code model3d} MessageContentPart.
 */
@Slf4j
@Component
public class HunyuanModel3dProvider implements Model3dGenerationProvider {

    private final ObjectMapper objectMapper;
    private final ModelProviderService modelProviderService;

    public HunyuanModel3dProvider(ObjectMapper objectMapper,
                                   ModelProviderService modelProviderService) {
        this.objectMapper = objectMapper;
        this.modelProviderService = modelProviderService;
    }

    private static final String PROVIDER_ID = "hunyuan-3d";
    private static final String SERVICE = "ai3d";
    private static final String VERSION = "2025-05-13";
    private static final String REGION = "ap-guangzhou";
    private static final String DEFAULT_HOST = "ai3d.tencentcloudapi.com";

    // Two action families exist on the same ai3d service. Model name routes
    // the request to the right one — see V72 migration for the model -> action
    // mapping. We dispatch QueryHunyuanTo3DRapidJob vs Pro the same way: the
    // task entity's `provider_task_id` carries a "rapid:" or "pro:" prefix so
    // checkStatus knows which Action to call without re-reading model config.
    private static final String SUBMIT_RAPID = "SubmitHunyuanTo3DRapidJob";
    private static final String QUERY_RAPID  = "QueryHunyuanTo3DRapidJob";
    private static final String SUBMIT_PRO   = "SubmitHunyuanTo3DProJob";
    private static final String QUERY_PRO    = "QueryHunyuanTo3DProJob";

    private static final String DEFAULT_MODEL = "HY-3D-3.1";

    @Override public String id() { return PROVIDER_ID; }
    @Override public String label() { return "Tencent Hunyuan 3D"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 100; }

    @Override
    public Set<Model3dCapability> capabilities() {
        return Set.of(Model3dCapability.TEXT_TO_3D, Model3dCapability.IMAGE_TO_3D);
    }

    @Override
    public Model3dProviderCapabilities detailedCapabilities() {
        return Model3dProviderCapabilities.builder()
                .modes(capabilities())
                .supportedFormats(List.of("glb"))
                .supportsTexture(true)
                // PBR is supported on the Pro action only (HY-3D-3.0 / HY-3D-3.1);
                // exposed at the capability level so future routing can negotiate.
                .supportsPbr(true)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("HY-3D-3.1", "HY-3D-3.0", "HY-3D-Express"))
                .build();
    }

    /** Whether the requested model variant goes through the Pro action family. */
    private static boolean usePro(String model) {
        if (model == null || model.isBlank()) return true;  // default 3.1 -> Pro
        String upper = model.toUpperCase();
        if (upper.contains("EXPRESS") || upper.contains("RAPID")) return false;
        return true;  // HY-3D-3.x -> Pro
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return resolveCredentials() != null;
    }

    private record Credentials(String secretId, String secretKey, String host) {}

    private Credentials resolveCredentials() {
        try {
            if (!modelProviderService.isProviderConfigured(PROVIDER_ID)) {
                return null;
            }
            ModelProviderEntity entity = modelProviderService.getProviderConfig(PROVIDER_ID);
            String apiKey = entity.getApiKey();
            if (!StringUtils.hasText(apiKey)) return null;
            // api_key column packs both halves as "SecretId:SecretKey"
            String[] parts = apiKey.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                log.warn("[Hunyuan3D] Provider api_key must be \"SecretId:SecretKey\" (colon-joined)");
                return null;
            }
            String host = StringUtils.hasText(entity.getBaseUrl())
                    ? extractHost(entity.getBaseUrl())
                    : DEFAULT_HOST;
            return new Credentials(parts[0].trim(), parts[1].trim(), host);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reduce a configured base_url to bare host (strip scheme + path). */
    private static String extractHost(String baseUrl) {
        try {
            java.net.URI uri = java.net.URI.create(baseUrl.trim());
            if (uri.getHost() != null) return uri.getHost();
        } catch (Exception ignore) {
            // fall through
        }
        return DEFAULT_HOST;
    }

    @Override
    public Model3dSubmitResult submit(Model3dGenerationRequest request, SystemSettingsDTO config) {
        Credentials creds = resolveCredentials();
        if (creds == null) {
            return Model3dSubmitResult.failure(id(),
                    "Hunyuan 3D 凭据未配置（在「模型与凭据」中以 SecretId:SecretKey 格式保存到 hunyuan-3d）");
        }
        try {
            boolean pro = usePro(request.getModel());
            ObjectNode body = objectMapper.createObjectNode();

            // Both Rapid and Pro accept Prompt XOR ImageUrl. Pro additionally
            // takes MultiViewImages and several quality knobs (EnablePBR,
            // FaceCount, GenerateType). Pro also supports Prompt + ImageUrl
            // together when GenerateType="Sketch", but we don't expose Sketch
            // mode at the tool layer yet — keep XOR semantics for now.
            if (StringUtils.hasText(request.getImageUrl())) {
                body.put("ImageUrl", request.getImageUrl());
            } else if (StringUtils.hasText(request.getPrompt())) {
                body.put("Prompt", request.getPrompt());
            } else {
                return Model3dSubmitResult.failure(id(),
                        "缺少必要参数：请提供 prompt（文生 3D）或 imageUrl（图生 3D）");
            }

            if (pro) {
                // Multi-view: map up to 3 extra views to ViewType={back,left,right}.
                // Tencent limits each angle to one image; ordering of imageUrls
                // beyond the primary determines the angle slot.
                List<String> extraViews = request.getImageUrls();
                if (extraViews != null && !extraViews.isEmpty()) {
                    String[] viewTypes = {"back", "left", "right"};
                    com.fasterxml.jackson.databind.node.ArrayNode arr = body.putArray("MultiViewImages");
                    for (int i = 0; i < extraViews.size() && i < viewTypes.length; i++) {
                        String url = extraViews.get(i);
                        if (!StringUtils.hasText(url)) continue;
                        ObjectNode view = arr.addObject();
                        view.put("ViewType", viewTypes[i]);
                        view.put("ViewImageUrl", url);
                    }
                }
                if (Boolean.TRUE.equals(request.getEnablePbr())) {
                    body.put("EnablePBR", true);
                }
                // White-model toggle: when texture is explicitly disabled, ask
                // for the geometry-only generate type.
                if (Boolean.FALSE.equals(request.getEnableTexture())) {
                    body.put("GenerateType", "Geometry");
                }
            }

            String action = pro ? SUBMIT_PRO : SUBMIT_RAPID;
            JsonNode resp = invoke(creds, action, body.toString());
            JsonNode response = resp.path("Response");
            if (response.has("Error")) {
                String code = response.path("Error").path("Code").asText("UnknownError");
                String msg = response.path("Error").path("Message").asText("Unknown error");
                log.warn("[Hunyuan3D] {} failed: {} ({})", action, msg, code);
                return Model3dSubmitResult.failure(id(), msg + " (" + code + ")");
            }
            String jobId = response.path("JobId").asText(null);
            if (!StringUtils.hasText(jobId)) {
                return Model3dSubmitResult.failure(id(), "Tencent 未返回 JobId");
            }
            // Embed the action family in the providerTaskId so checkStatus
            // dispatches to the matching Query action without re-reading the
            // model from the request entity (which is JSON-serialized and
            // would require an extra DB round-trip).
            String taggedJobId = (pro ? "pro:" : "rapid:") + jobId;
            log.info("[Hunyuan3D] {} submitted job: {} (model={})",
                    action, jobId, request.getModel());
            return Model3dSubmitResult.success(taggedJobId, id());
        } catch (Exception e) {
            log.error("[Hunyuan3D] Submit error: {}", e.getMessage(), e);
            return Model3dSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        Credentials creds = resolveCredentials();
        if (creds == null) return TaskPollResult.failed("Hunyuan 3D 凭据未配置");

        try {
            // providerTaskId carries a "rapid:" / "pro:" prefix from submit().
            String queryAction;
            String rawJobId;
            if (providerTaskId != null && providerTaskId.startsWith("pro:")) {
                queryAction = QUERY_PRO;
                rawJobId = providerTaskId.substring(4);
            } else if (providerTaskId != null && providerTaskId.startsWith("rapid:")) {
                queryAction = QUERY_RAPID;
                rawJobId = providerTaskId.substring(6);
            } else {
                // Backward-compat for any unprefixed jobs that may still be in
                // flight from a previous version: default to Rapid.
                queryAction = QUERY_RAPID;
                rawJobId = providerTaskId;
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.put("JobId", rawJobId);

            JsonNode resp = invoke(creds, queryAction, body.toString());
            JsonNode response = resp.path("Response");
            if (response.has("Error")) {
                String msg = response.path("Error").path("Message").asText("Unknown error");
                return TaskPollResult.failed(msg);
            }

            String status = response.path("Status").asText("");
            switch (status) {
                case "DONE":
                    PickedFile picked = pickBestResultFile(response);
                    if (picked == null || !StringUtils.hasText(picked.url())) {
                        return TaskPollResult.failed("Tencent 任务完成但未返回模型 URL");
                    }
                    // Stuff modelUrl + format into resultJson — the service layer parses it.
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("modelUrl", picked.url());
                    result.put("format", picked.type());
                    return TaskPollResult.succeeded(null, null, result.toString());
                case "FAIL":
                    String errCode = response.path("ErrorCode").asText(null);
                    String errMsg = response.path("ErrorMessage").asText("任务失败");
                    return TaskPollResult.failed(errCode != null
                            ? errMsg + " (" + errCode + ")" : errMsg);
                case "RUN":
                    return TaskPollResult.running(null);
                case "WAIT":
                default:
                    return TaskPollResult.pending(null);
            }
        } catch (Exception e) {
            log.error("[Hunyuan3D] Poll error for job {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    private record PickedFile(String url, String type) {}

    /**
     * Pick the best file from {@code ResultFile3Ds[]} for our pipeline.
     * <p>
     * Tencent Pro returns multiple {@code File3D} entries per job — typically
     * one each of GLB / OBJ / FBX (and possibly STL/USDZ). The OBJ entry's
     * {@code Url} actually points at a {@code .zip} bundle (obj + textures +
     * mtl), which {@code <model-viewer>} can't render directly. The GLB entry
     * is a single self-contained binary that <em>is</em> renderable. So we
     * prefer GLB → FBX → OBJ → first-available, ignoring the URL extension.
     */
    private static PickedFile pickBestResultFile(JsonNode response) {
        JsonNode files = response.path("ResultFile3Ds");
        if (!files.isArray() || files.isEmpty()) return null;

        PickedFile glb = null, fbx = null, obj = null, anyFile = null;
        for (JsonNode f : files) {
            String type = f.path("Type").asText("").toLowerCase();
            String url = f.path("Url").asText(null);
            if (!StringUtils.hasText(url)) continue;
            PickedFile entry = new PickedFile(url, type.isBlank() ? "glb" : type);
            if (anyFile == null) anyFile = entry;
            switch (type) {
                case "glb" -> { if (glb == null) glb = entry; }
                case "fbx" -> { if (fbx == null) fbx = entry; }
                case "obj" -> { if (obj == null) obj = entry; }
                default -> { /* stl / usdz / unknown — only used as last resort */ }
            }
        }
        if (glb != null) return glb;
        if (fbx != null) return fbx;
        if (obj != null) return obj;
        return anyFile;
    }

    /**
     * Sign + send a TC3 v3 request. Tencent's gateway returns 200 OK with an
     * {@code Error} body on logical failure, so we return the parsed
     * {@code JsonNode} regardless of status — the caller inspects it.
     */
    private JsonNode invoke(Credentials creds, String action, String payload) throws Exception {
        TencentCloudV3Signer.SignedHeaders sig =
                TencentCloudV3Signer.sign(creds.secretId(), creds.secretKey(), SERVICE, creds.host(), payload);
        Map<String, String> common = TencentCloudV3Signer.commonHeaders(action, VERSION, REGION, sig.timestamp());

        HttpRequest req = HttpRequest.post("https://" + creds.host())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Host", sig.host())
                .header("Authorization", sig.authorization())
                .body(payload)
                .timeout(60_000);
        for (Map.Entry<String, String> e : common.entrySet()) {
            req.header(e.getKey(), e.getValue());
        }
        try (HttpResponse response = req.execute()) {
            return objectMapper.readTree(response.body());
        }
    }
}
