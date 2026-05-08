package vip.mate.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workflow.model.WorkflowRunStepEntity;

@Mapper
public interface WorkflowRunStepMapper extends BaseMapper<WorkflowRunStepEntity> {
}
