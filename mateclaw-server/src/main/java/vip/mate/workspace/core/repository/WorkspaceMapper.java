package vip.mate.workspace.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.core.model.WorkspaceEntity;

/**
 * 工作区 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
}
