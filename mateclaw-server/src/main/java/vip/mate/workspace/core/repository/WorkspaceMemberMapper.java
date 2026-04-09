package vip.mate.workspace.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;

/**
 * 工作区成员 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMemberEntity> {
}
