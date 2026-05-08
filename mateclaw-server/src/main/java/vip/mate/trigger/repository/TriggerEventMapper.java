package vip.mate.trigger.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.trigger.model.TriggerEventEntity;

@Mapper
public interface TriggerEventMapper extends BaseMapper<TriggerEventEntity> {
}
