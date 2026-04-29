package vip.mate.llm.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置实体
 */
@Data
@TableName("mate_model_config")
public class ModelConfigEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String provider;

    private String modelName;

    private String description;

    private Double temperature;

    private Integer maxTokens;

    /** 模型最大输入 token 数（上下文窗口），0 或 null 表示使用全局默认 */
    private Integer maxInputTokens;

    private Double topP;

    private Boolean enableSearch;

    private String searchStrategy;

    private Boolean builtin;

    private Boolean enabled;

    private Boolean isDefault;

    /**
     * 模型类型：chat（默认，LLM 对话） / embedding（文本向量化）
     * <p>
     * 参考 Dify 的 ModelType 抽象：允许同一 Provider 下同时管理 chat 和 embedding 两类模型，
     * API Key 共用（存于 mate_model_provider）。
     */
    private String modelType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
