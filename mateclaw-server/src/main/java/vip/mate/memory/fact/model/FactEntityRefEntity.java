package vip.mate.memory.fact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Entity reference for multi-hop graph queries on facts.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_fact_entity_ref")
public class FactEntityRefEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long factId;

    private String entityName;

    /** person, tool, project, concept */
    private String entityType;

    /** subject | object */
    private String role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
