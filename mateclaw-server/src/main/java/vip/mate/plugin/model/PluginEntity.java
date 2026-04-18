package vip.mate.plugin.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Plugin entity — persists plugin state in mate_plugin table.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_plugin")
public class PluginEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Plugin name (unique identifier from manifest) */
    private String name;

    /** Plugin version */
    private String version;

    /** Plugin type: tool / provider / channel / memory */
    private String pluginType;

    /** Display name */
    private String displayName;

    /** Description */
    private String description;

    /** Author */
    private String author;

    /** Fully qualified entrypoint class name */
    private String entrypoint;

    /** JAR file path on disk */
    private String jarPath;

    /** Plugin configuration as JSON */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String configJson;

    /** Whether the plugin is enabled */
    private Boolean enabled;

    /** Runtime status: LOADED / ENABLED / DISABLED / ERROR */
    private String status;

    /** Error message if loading failed */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
