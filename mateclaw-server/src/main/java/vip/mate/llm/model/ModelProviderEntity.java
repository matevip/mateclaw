package vip.mate.llm.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_model_provider")
public class ModelProviderEntity {

    @TableId
    private String providerId;

    private String name;

    private String apiKeyPrefix;

    private String chatModel;

    private String apiKey;

    private String baseUrl;

    private String generateKwargs;

    private Boolean isCustom;

    private Boolean isLocal;

    private Boolean supportModelDiscovery;

    private Boolean supportConnectionCheck;

    private Boolean freezeUrl;

    private Boolean requireApiKey;

    private String authType;

    private String oauthAccessToken;

    private String oauthRefreshToken;

    private Long oauthExpiresAt;

    private String oauthAccountId;

    /**
     * RFC-009: position of this provider in the fallback chain.
     * {@code 0} (default) means "not in the chain"; positive values are tried
     * in ascending order after the primary model exhausts its retries.
     */
    private Integer fallbackPriority;

    /**
     * RFC-074: explicit user-enabled flag. {@code FALSE} means the provider is
     * known to the catalog but hidden from the dropdown / chat fallback chain
     * — the user must opt in via the "Add Provider" drawer. Default {@code FALSE}
     * for fresh installs; V55 migration backfills {@code TRUE} for rows with
     * evidence of prior use (real api_key, OAuth token, recent chat usage,
     * or current default model).
     */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
