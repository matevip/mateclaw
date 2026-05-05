package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;

/**
 * Mapper for the SHA-256 keyed image caption cache.
 *
 * <p>Read path goes through unique key {@code image_sha256} (uses
 * {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#selectList}
 * with a query wrapper from the service layer). Write path uses
 * {@link #bumpHitCount(String)} to increment the hit counter without
 * round-tripping the row through Java memory.
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiImageCaptionCacheMapper extends BaseMapper<WikiImageCaptionCacheEntity> {

    /**
     * Atomically increments {@code hit_count} for the row matching the
     * given SHA. Returns the number of rows affected (0 when no row
     * exists or the row is soft-deleted).
     */
    @Update("UPDATE mate_wiki_image_caption_cache "
          + "SET hit_count = hit_count + 1 "
          + "WHERE image_sha256 = #{sha256} AND deleted = 0")
    int bumpHitCount(@Param("sha256") String sha256);
}
