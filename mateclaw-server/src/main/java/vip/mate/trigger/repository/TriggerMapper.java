package vip.mate.trigger.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.trigger.model.TriggerEntity;

@Mapper
public interface TriggerMapper extends BaseMapper<TriggerEntity> {
}
