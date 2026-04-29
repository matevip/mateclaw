package vip.mate.workspace.core.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区实体
 * <p>
 * 工作区是资源隔离的基本单元，Agent、Channel、Wiki、Conversation 都归属于某个工作区。
 * 系统自动创建 id=1 的默认工作区（default），单人部署无需感知。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_workspace")
public class WorkspaceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工作区名称 */
    private String name;

    /** 工作区标识（URL 友好，唯一） */
    private String slug;

    /** 描述 */
    private String description;

    /** 拥有者用户 ID */
    private Long ownerId;

    /** 工作区活动目录（限制文件工具的访问范围，为空不限制） */
    private String basePath;

    /** 工作区级配置（JSON） */
    @TableField(value = "settings_json", updateStrategy = FieldStrategy.ALWAYS)
    private String settingsJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
