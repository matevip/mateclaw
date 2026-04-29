package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

import java.util.List;
import java.util.Optional;

/**
 * RFC-030: Wiki processing job mapper.
 */
@Mapper
public interface WikiProcessingJobMapper extends BaseMapper<WikiProcessingJobEntity> {

    @Select("SELECT * FROM mate_wiki_processing_job " +
            "WHERE kb_id = #{kbId} AND status = 'queued' AND deleted = 0 " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<WikiProcessingJobEntity> listQueued(@Param("kbId") Long kbId, @Param("limit") int limit);

    @Select("SELECT * FROM mate_wiki_processing_job " +
            "WHERE raw_id = #{rawId} AND deleted = 0 ORDER BY create_time DESC LIMIT 1")
    Optional<WikiProcessingJobEntity> findLatestByRawId(@Param("rawId") Long rawId);

    /**
     * List all non-deleted jobs for a KB (for stats/dashboard queries).
     */
    @Select("SELECT * FROM mate_wiki_processing_job " +
            "WHERE kb_id = #{kbId} AND deleted = 0 " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<WikiProcessingJobEntity> listByKbId(@Param("kbId") Long kbId, @Param("limit") int limit);

    /**
     * Recover stuck jobs on startup: reset routing/*_running stages back to queued.
     */
    @Update("UPDATE mate_wiki_processing_job " +
            "SET status = 'queued', " +
            "    stage  = COALESCE(resume_from_stage, 'queued'), " +
            "    update_time = CURRENT_TIMESTAMP(3) " +
            "WHERE (stage = 'routing' OR stage LIKE '%_running') AND deleted = 0")
    int recoverStuckJobs();
}
