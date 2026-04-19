package vip.mate.llm.model;

import lombok.Data;

import java.util.ArrayList;
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
    /** position in the failover chain (0 = excluded, 1..N = priority). */
    private Integer fallbackPriority;
}
