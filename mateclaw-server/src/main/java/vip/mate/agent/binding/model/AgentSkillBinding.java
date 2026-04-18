package vip.mate.agent.binding.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_agent_skill")
public class AgentSkillBinding {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long agentId;
    private Long skillId;
    private Boolean enabled;
    private String configJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}
