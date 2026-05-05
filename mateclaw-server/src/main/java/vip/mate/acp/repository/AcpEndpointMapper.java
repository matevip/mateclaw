package vip.mate.acp.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.acp.model.AcpEndpointEntity;

/**
 * RFC-090 Phase 7 — MyBatis Plus mapper for {@link AcpEndpointEntity}.
 */
@Mapper
public interface AcpEndpointMapper extends BaseMapper<AcpEndpointEntity> {
}
