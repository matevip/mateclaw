package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiChunkEntity;

/**
 * Wiki chunk 数据访问层
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiChunkMapper extends BaseMapper<WikiChunkEntity> {
}
