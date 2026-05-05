package vip.mate.llm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.llm.model.DiscoverResult;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelInfoDTO;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.TestResult;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelDiscoveryService;

import vip.mate.llm.service.ModelProviderService;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup runner: auto-detect running Ollama instance and register discovered models.
 * <p>
 * On application startup, first checks whether the Ollama provider is enabled in
 * {@code mate_model_provider} — if not, skips immediately without any network
 * traffic. When enabled, pings the Ollama endpoint (default
 * http://127.0.0.1:11434); if online, discovers all pulled models and
 * auto-enables matching pre-configured models. If offline, silently skips
 * (debug log only).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class OllamaAutoDiscoveryRunner implements ApplicationRunner {

    private static final String OLLAMA_PROVIDER_ID = "ollama";

    /**
     * Ollama 模型族里**已知不支持 function calling / tools** 的家族名（冒号前半段）。
     * <p>
     * Agent 默认带 24+ 个 tool callbacks 发请求，这类模型会返回
     * {@code 400 "<model> does not support tools"}。把它们从 auto-activate 默认候选里排除，
     * 仍然允许 enabled（用户可以手工选它做纯文本问答），但不会被意外设为默认。
     * <p>
     * 参考：https://ollama.com/search?c=tools （标了 "Tools" 标签的才支持）。
     * 清单随 Ollama 生态演进，必要时手工维护。
     */
    private static final Set<String> KNOWN_NO_TOOL_FAMILIES = Set.of(
            "deepseek-r1",    // reasoning model, no tool calling
            "gemma", "gemma2", "gemma3",
            "phi3", "phi4",
            "codellama",
            "llama3.2",       // 1b/3b variants lack tools（llama3.1 / llama3.3 支持）
            "qwen2",          // 旧 qwen2 无 tool 支持（qwen2.5 / qwen3 支持）
            "mistral"         // 原始 mistral 无 tools（mistral-nemo / mixtral 支持）
    );

    private final ModelDiscoveryService modelDiscoveryService;
    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;

    @Async
    @Override
    public void run(ApplicationArguments args) {
        try {
            // Skip the network probe entirely when the user hasn't enabled the
            // Ollama provider — there's no point pinging localhost:11434 every
            // boot for a provider that isn't part of the active model config.
            ModelProviderEntity provider;
            try {
                provider = modelProviderService.getProviderConfig(OLLAMA_PROVIDER_ID);
            } catch (Exception e) {
                log.debug("Ollama provider row missing, skipping auto-discovery");
                return;
            }
            if (!Boolean.TRUE.equals(provider.getEnabled())) {
                log.debug("Ollama provider disabled, skipping auto-discovery");
                return;
            }

            // Test connection
            TestResult result = modelDiscoveryService.testConnection(OLLAMA_PROVIDER_ID);
            if (!result.isSuccess()) {
                log.debug("Ollama not running, skipping auto-discovery: {}", result.getErrorMessage());
                return;
            }

            log.info("Ollama detected, discovering models...");

            // Discover and register new models
            DiscoverResult discovered = modelDiscoveryService.discoverModels(OLLAMA_PROVIDER_ID);
            if (discovered.getNewCount() > 0) {
                var newModelIds = discovered.getNewModels().stream()
                        .map(ModelInfoDTO::getId)
                        .toList();
                int added = modelDiscoveryService.batchAddModels(OLLAMA_PROVIDER_ID, newModelIds);
                log.info("Ollama auto-discovery: found {} models, registered {} new", discovered.getTotalDiscovered(), added);
            } else {
                log.info("Ollama auto-discovery: {} models found, all already registered", discovered.getTotalDiscovered());
            }

            // Enable pre-configured Ollama models that match discovered models
            Set<String> discoveredIds = discovered.getDiscoveredModels().stream()
                    .map(ModelInfoDTO::getId)
                    .collect(Collectors.toSet());

            for (ModelConfigEntity model : modelConfigService.listModelsByProvider(OLLAMA_PROVIDER_ID)) {
                if (Boolean.TRUE.equals(model.getEnabled())) {
                    continue;
                }
                String modelTag = model.getModelName();
                String modelBase = modelTag.contains(":") ? modelTag.substring(0, modelTag.indexOf(":")) : modelTag;

                // 精确匹配 / 裸名匹配 —— Ollama 能按原 tag 调用，model_name 无需改写
                if (discoveredIds.contains(modelTag) || discoveredIds.contains(modelBase)) {
                    model.setEnabled(true);
                    modelConfigService.updateModel(model);
                    log.info("Ollama: auto-enabled model '{}'", modelTag);
                    continue;
                }

                // Fuzzy 前缀匹配：种子里的 tag（例如 deepseek-r1:latest）Ollama 没装，
                // 但 base 相同的另一个 tag 存在（例如 deepseek-r1:7b）。
                // 这里必须把 model_name 改写成实际存在的 tag，否则 /v1/chat/completions
                // 会用原 tag 发请求，Ollama 返回 404 "model not found"。
                String actualTag = discoveredIds.stream()
                        .filter(id -> id.startsWith(modelBase + ":"))
                        .findFirst()
                        .orElse(null);
                if (actualTag != null) {
                    log.info("Ollama: rewriting seed tag '{}' → '{}' to match installed model",
                            modelTag, actualTag);
                    model.setModelName(actualTag);
                    model.setEnabled(true);
                    modelConfigService.updateModel(model);
                    log.info("Ollama: auto-enabled model '{}'", actualTag);
                }
            }
            // Auto-activate first Ollama model if no valid default exists
            tryAutoActivateOllamaModel(discoveredIds);
        } catch (Exception e) {
            log.debug("Ollama auto-discovery skipped: {}", e.getMessage());
        }
    }

    private void tryAutoActivateOllamaModel(Set<String> discoveredIds) {
        List<ModelConfigEntity> ollamaModels = modelConfigService.listModelsByProvider(OLLAMA_PROVIDER_ID);
        List<ModelConfigEntity> enabledModels = ollamaModels.stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .toList();
        if (enabledModels.isEmpty()) {
            return;
        }
        boolean hasValidDefault;
        try {
            ModelConfigEntity current = modelConfigService.getDefaultModel();
            boolean providerOk = modelProviderService.isProviderAvailable(current.getProvider());
            // 对 Ollama provider 额外要求：当前默认的 tag 必须实际存在于 Ollama，
            // 否则视为"历史遗留的无效默认"（如之前 auto-activate 把 :latest 设为默认，
            // 但 Ollama 只有 :7b），需要在本轮重新挑一个可用的默认。
            boolean tagOk = !OLLAMA_PROVIDER_ID.equals(current.getProvider())
                    || discoveredIds.contains(current.getModelName());
            hasValidDefault = providerOk && tagOk;
        } catch (Exception e) {
            hasValidDefault = false;
        }
        if (!hasValidDefault) {
            // 挑默认的优先级（逐级 fallback）：
            //   1) tag 实际存在于 Ollama **且** 家族支持 tools（agent 能正常用工具）
            //   2) tag 实际存在于 Ollama（哪怕不支持 tools，总比设个根本不存在的 tag 强）
            //   3) 兜底：enabledModels 第一条（保留原来的行为）
            ModelConfigEntity pick = enabledModels.stream()
                    .filter(m -> discoveredIds.contains(m.getModelName()))
                    .filter(m -> supportsTools(m.getModelName()))
                    .findFirst()
                    .orElseGet(() -> enabledModels.stream()
                            .filter(m -> discoveredIds.contains(m.getModelName()))
                            .findFirst()
                            .orElse(enabledModels.get(0)));
            modelConfigService.setDefaultModel(OLLAMA_PROVIDER_ID, pick.getModelName());
            if (!supportsTools(pick.getModelName())) {
                log.warn("Ollama: auto-activated default model '{}' but its family does not support tool calling; "
                        + "agents that require tools will fail. Consider pulling a tool-capable model "
                        + "(qwen3 / qwen2.5 / llama3.1:8b+ / mistral-nemo).", pick.getModelName());
            } else {
                log.info("Ollama: auto-activated default model '{}'", pick.getModelName());
            }
        }
    }

    /**
     * 粗粒度判断：以 tag 的冒号前半段（base）比对已知无工具支持家族集合。
     * 只用于 default picking；不影响模型是否被 enabled，用户仍可手工选任何模型。
     */
    private static boolean supportsTools(String modelTag) {
        if (modelTag == null || modelTag.isEmpty()) return false;
        int idx = modelTag.indexOf(':');
        String base = idx > 0 ? modelTag.substring(0, idx) : modelTag;
        return !KNOWN_NO_TOOL_FAMILIES.contains(base);
    }
}
