package vip.mate.auth.pat.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.auth.pat.PersonalAccessTokenEntity;

/**
 * RFC-03 Lane I1 — MyBatis Plus mapper for {@link PersonalAccessTokenEntity}.
 *
 * <p>Located under {@code repository} so {@code @MapperScan("vip.mate.**.repository")}
 * (declared on {@code MateClawApplication}) discovers it; outside that
 * package Spring won't auto-register the mapper bean and constructor
 * injection into {@code PersonalAccessTokenService} fails at startup.
 *
 * <p>Lookup queries live in the service layer via {@code LambdaQueryWrapper};
 * the only custom requirement is uniqueness on {@code token_hash}, which is
 * enforced by the database (UNIQUE index in V76 migration).
 */
@Mapper
public interface PersonalAccessTokenMapper extends BaseMapper<PersonalAccessTokenEntity> {
}
