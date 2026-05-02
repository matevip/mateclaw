package vip.mate.system.featureflag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.system.featureflag.FeatureFlagEntity;

/**
 * MyBatis Plus mapper for {@link FeatureFlagEntity}.
 *
 * <p>Mapper interface lives under a {@code repository} sub-package as required
 * by the application-wide {@code @MapperScan("vip.mate.**.repository")}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface FeatureFlagMapper extends BaseMapper<FeatureFlagEntity> {
}
