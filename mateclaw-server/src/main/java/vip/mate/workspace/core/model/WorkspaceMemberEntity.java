package vip.mate.workspace.core.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区成员实体
 * <p>
 * 关联用户与工作区，定义成员角色。
 * 角色：owner（全部权限）/ admin（管理资源）/ member（使用资源）/ viewer（只读）
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_workspace_member")
public class WorkspaceMemberEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工作区 ID */
    private Long workspaceId;

    /** 用户 ID */
    private Long userId;

    /** 角色：owner / admin / member / viewer */
    private String role;

    /** 用户名（非持久化，API 返回用） */
    @TableField(exist = false)
    private String username;

    /** 昵称（非持久化，API 返回用） */
    @TableField(exist = false)
    private String nickname;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
