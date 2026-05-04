package vip.mate.skill.usage;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_skill_usage_stat")
public class SkillUsageStatEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String skillName;
    private Long skillId;
    private Long agentId;
    private String conversationId;
    private Long loadCount;
    private LocalDateTime lastLoadedAt;
    private String lastFilePath;
    private Integer lastTokenEstimate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
