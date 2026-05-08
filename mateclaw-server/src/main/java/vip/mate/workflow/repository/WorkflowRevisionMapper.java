package vip.mate.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workflow.model.WorkflowRevisionEntity;

@Mapper
public interface WorkflowRevisionMapper extends BaseMapper<WorkflowRevisionEntity> {
}
