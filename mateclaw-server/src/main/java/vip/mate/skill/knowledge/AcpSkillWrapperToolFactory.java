package vip.mate.skill.knowledge;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.service.AcpDelegationService;
import vip.mate.acp.service.AcpEndpointService;
import vip.mate.skill.manifest.SkillManifest;

import java.util.List;
import java.util.Locale;

/**
 * RFC-090 §14.4 (parallel) — wrapper tool factory for {@code type=acp}
 * skills.
 *
 * <p>For every {@code type=acp} skill, registers exactly one tool:
 * {@code acp_<endpoint>_<skill>_prompt(prompt)}. The wrapper closes
 * over the resolved endpoint id so the LLM never has to (and can't
 * accidentally) target a different endpoint.
 *
 * <p>The wrapper is synchronous: it sends the prompt to the upstream
 * ACP agent, accumulates streamed text, and returns the joined reply.
 * No multi-turn session caching; each call is fresh. See
 * {@link AcpDelegationService} for the rationale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpSkillWrapperToolFactory {

    private final AcpEndpointService endpointService;
    private final AcpDelegationService delegationService;
    private final ObjectMapper objectMapper;

    /**
     * Resolve a manifest's {@code acp.endpoint} (slug) to a row id.
     * Returns null when the slug doesn't match any registered endpoint.
     */
    public Long resolveEndpointId(String endpointName) {
        if (endpointName == null || endpointName.isBlank()) return null;
        AcpEndpointEntity ep = endpointService.findByName(endpointName.trim());
        return ep == null ? null : ep.getId();
    }

    /**
     * Build the wrapper callback for one ACP skill. Returns an empty
     * list when the manifest doesn't declare an ACP binding.
     */
    public List<ToolCallback> buildWrappers(SkillManifest manifest) {
        if (manifest == null || manifest.getAcp() == null
                || manifest.getAcp().getEndpoint() == null
                || manifest.getAcp().getEndpoint().isBlank()) {
            return List.of();
        }
        String slug = sanitize(manifest.getName());
        String endpointSlug = sanitize(manifest.getAcp().getEndpoint());
        if (slug.isBlank() || endpointSlug.isBlank()) return List.of();

        String name = "acp_" + endpointSlug + "_" + slug + "_prompt";
        String displayName = manifest.getName() != null ? manifest.getName() : slug;
        String desc = String.format(
                "Delegate a prompt to the '%s' coding agent (skill: %s). "
                + "Send a single-string instruction; receive the agent's final reply.",
                manifest.getAcp().getEndpoint(), displayName);
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"prompt\":{\"type\":\"string\",\"description\":\"the instruction or question to send\"}"
                + "},"
                + "\"required\":[\"prompt\"]"
                + "}";

        String endpointName = manifest.getAcp().getEndpoint();
        String cwd = manifest.getAcp().getCwd();
        String systemPrefix = manifest.getAcp().getSystemPrefix();

        return List.of(new SkillScopedToolCallback(name, desc, schema, input -> {
            try {
                JsonNode args = input == null || input.isBlank()
                        ? objectMapper.createObjectNode()
                        : objectMapper.readTree(input);
                String userPrompt = args.path("prompt").asText("").trim();
                if (userPrompt.isEmpty()) return errorJson("prompt is required");

                String composedPrompt = systemPrefix == null || systemPrefix.isBlank()
                        ? userPrompt
                        : systemPrefix.trim() + "\n\n" + userPrompt;
                String reply = delegationService.prompt(endpointName, composedPrompt, cwd);
                JSONObject resp = new JSONObject()
                        .set("endpoint", endpointName)
                        .set("skill", manifest.getName())
                        .set("reply", reply);
                return JSONUtil.toJsonStr(resp);
            } catch (Exception e) {
                log.warn("acp wrapper '{}' failed: {}", name, e.getMessage());
                return errorJson(e.getMessage() == null ? "delegation failed" : e.getMessage());
            }
        }));
    }

    /**
     * Names the wrappers a manifest *would* produce, without actually
     * building them. Used by {@link vip.mate.skill.runtime.SkillPackageResolver}
     * to populate {@code manifest.allowedTools} so
     * {@link vip.mate.skill.runtime.model.ResolvedSkill#getEffectiveAllowedTools()}
     * surfaces the wrapper name even before registration.
     */
    public List<String> wrapperNames(SkillManifest manifest) {
        if (manifest == null || manifest.getAcp() == null
                || manifest.getAcp().getEndpoint() == null
                || manifest.getName() == null) {
            return List.of();
        }
        String slug = sanitize(manifest.getName());
        String endpointSlug = sanitize(manifest.getAcp().getEndpoint());
        if (slug.isBlank() || endpointSlug.isBlank()) return List.of();
        return List.of("acp_" + endpointSlug + "_" + slug + "_prompt");
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String errorJson(String msg) {
        return JSONUtil.createObj().set("error", msg).toString();
    }
}
