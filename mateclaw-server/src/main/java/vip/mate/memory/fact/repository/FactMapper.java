package vip.mate.memory.fact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.memory.fact.model.FactEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fact mapper — limited write operations enforce the core invariant:
 * - Derived columns: only FactProjectionBuilder may write
 * - Accumulated columns: only bumpUseCount may write
 *
 * @author MateClaw Team
 */
@Mapper
public interface FactMapper extends BaseMapper<FactEntity> {

    /**
     * Upsert derived columns by (agent_id, source_ref).
     * Preserves accumulated columns (last_used_at, use_count).
     */
    @Update("""
        MERGE INTO mate_fact (agent_id, source_ref, category, subject, predicate, object_value,
                              confidence, trust, extracted_by, create_time, update_time, deleted)
        KEY (agent_id, source_ref)
        VALUES (#{agentId}, #{sourceRef}, #{category}, #{subject}, #{predicate}, #{objectValue},
                #{confidence}, #{trust}, #{extractedBy}, #{createTime}, #{updateTime}, 0)
        """)
    void upsertDerivedH2(@Param("agentId") Long agentId,
                         @Param("sourceRef") String sourceRef,
                         @Param("category") String category,
                         @Param("subject") String subject,
                         @Param("predicate") String predicate,
                         @Param("objectValue") String objectValue,
                         @Param("confidence") Double confidence,
                         @Param("trust") Double trust,
                         @Param("extractedBy") String extractedBy,
                         @Param("createTime") LocalDateTime createTime,
                         @Param("updateTime") LocalDateTime updateTime);

    /**
     * Bump use_count and last_used_at for the given fact IDs.
     * This is the ONLY path that writes accumulated columns.
     */
    @Update("""
        <script>
        UPDATE mate_fact
        SET use_count = use_count + 1, last_used_at = #{now}, update_time = #{now}
        WHERE id IN
        <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
        AND deleted = 0
        </script>
        """)
    void bumpUseCount(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Soft-delete facts whose source_ref is no longer in the canonical set.
     * Used during full rebuild to remove stale projections.
     */
    @Update("""
        <script>
        UPDATE mate_fact SET deleted = 1, update_time = #{now}
        WHERE agent_id = #{agentId} AND deleted = 0
        AND source_ref NOT IN
        <foreach item='ref' collection='keepSet' open='(' separator=',' close=')'>#{ref}</foreach>
        </script>
        """)
    void deleteByAgentIdAndSourceRefNotIn(@Param("agentId") Long agentId,
                                          @Param("keepSet") List<String> keepSet,
                                          @Param("now") LocalDateTime now);
}
