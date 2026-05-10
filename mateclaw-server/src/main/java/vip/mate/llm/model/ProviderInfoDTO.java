package vip.mate.llm.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ProviderInfoDTO {
    private String id;
    private String name;
    private String protocol;
    private String apiKeyPrefix;
    private String chatModel;
    private List<ModelInfoDTO> models = new ArrayList<>();
    private List<ModelInfoDTO> extraModels = new ArrayList<>();
    private Boolean isCustom;
    private Boolean isLocal;
    private Boolean supportModelDiscovery;
    private Boolean supportConnectionCheck;
    private Boolean freezeUrl;
    private Boolean requireApiKey;
    private Boolean configured;
    private Boolean available;
    private String apiKey;
    private String baseUrl;
    private Map<String, Object> generateKwargs;
    private String authType;
    private Boolean oauthConnected;
    private Long oauthExpiresAt;
    /** RFC-009 P3.5: position in the failover chain (0 = excluded, 1..N = priority). */
    private Integer fallbackPriority;
    /** RFC-073: combined runtime state — UI source of truth for "is this provider usable right now". */
    private Liveness liveness;
    /** Human-readable reason populated only when liveness ∈ {REMOVED, COOLDOWN}. */
    private String unavailableReason;
    /** Epoch ms of the most recent removal, populated only when liveness == REMOVED. */
    private Long lastProbedAtMs;
    /** Remaining cooldown window in ms, populated only when liveness == COOLDOWN. */
    private Long cooldownRemainingMs;
    /** RFC-074: whether the user has explicitly enabled this provider. False = lives in the catalog drawer only. */
    private Boolean enabled;

    // Issue #81: derived fields powering the chat-console liveness-aware popup.
    // All six are computed from existing columns; none are persisted.

    /** Credential status: CONFIGURED / MISSING / NOT_REQUIRED / OAUTH_PENDING. */
    private String authStatus;

    /** Base URL completeness: null when not applicable; true/false when applicable. */
    private Boolean baseUrlComplete;

    /** Comma-joined missing field names ("apiKey", "baseUrl"); empty when nothing missing. */
    private String missingFields;

    /**
     * Machine-readable next-step key. Switches the chat popup's primary button text + handler.
     * Values: fill_base_url / fill_api_key / start_oauth / configure_required_fields /
     *         test_connection / pull_model / wait_cooldown / reprobe / none.
     */
    private String suggestedAction;

    /**
     * i18n key for an actionable hint (e.g. "provider.hint.llamacppBaseUrlExample").
     * Frontend renders via t(key, args). Null when no hint applies.
     */
    private String suggestedActionHintKey;

    /**
     * Template parameters for {@link #suggestedActionHintKey}. Frontend passes
     * this directly to vue-i18n. Empty map means no parameters.
     */
    private Map<String, Object> suggestedActionHintArgs = new LinkedHashMap<>();
}
