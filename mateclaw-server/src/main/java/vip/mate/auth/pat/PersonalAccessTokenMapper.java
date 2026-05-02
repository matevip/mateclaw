package vip.mate.auth.pat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * RFC-03 Lane I1 — MyBatis Plus mapper for {@link PersonalAccessTokenEntity}.
 *
 * <p>Lookup queries live in the service layer via {@code LambdaQueryWrapper};
 * the only custom requirement is uniqueness on {@code token_hash}, which is
 * enforced by the database (UNIQUE index in V76 migration).
 */
@Mapper
public interface PersonalAccessTokenMapper extends BaseMapper<PersonalAccessTokenEntity> {
}
