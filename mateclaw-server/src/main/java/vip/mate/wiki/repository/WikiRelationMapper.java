package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.wiki.model.WikiRelationEntity;

/**
 * Mapper for the page-to-page wiki relation cache.
 *
 * <p>Read paths fetch top-K rows by ({@code kb_id}, {@code page_a_id}) ordered
 * by {@code total_score DESC}; write paths upsert (insert + on-duplicate-key
 * update) keyed by ({@code kb_id}, {@code page_a_id}, {@code page_b_id}).
 *
 * <p>Cache invalidation paths use soft-delete (set {@code deleted=1}) keyed
 * by either {@code page_id} or {@code kb_id}; the consuming services
 * provide thin wrappers via custom SQL when needed.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiRelationMapper extends BaseMapper<WikiRelationEntity> {
}
