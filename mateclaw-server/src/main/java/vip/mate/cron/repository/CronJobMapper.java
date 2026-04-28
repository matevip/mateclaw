package vip.mate.cron.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.cron.model.CronJobEntity;

import java.util.List;

/**
 * 定时任务 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface CronJobMapper extends BaseMapper<CronJobEntity> {

    /**
     * RFC-063r §2.14: list cron jobs together with their most-recent
     * delivery status / error (subquery against {@code mate_cron_job_run}).
     *
     * <p>Both H2 and MySQL accept {@code LIMIT 1} inside a correlated
     * subquery, so the same SQL is portable across the two profiles. Index
     * coverage: {@code mate_cron_job_run(cron_job_id, started_at)} (created
     * by V1 baseline migration) makes the subquery cheap.
     *
     * <p>Filters out logically-deleted rows and orders by create_time DESC
     * to mirror the existing {@code list()} ordering.
     */
    @Select("""
            SELECT j.*,
                   (SELECT r.delivery_status FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_status,
                   (SELECT r.delivery_error FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_error
            FROM mate_cron_job j
            WHERE j.deleted = 0
            ORDER BY j.create_time DESC
            """)
    List<CronJobEntity> selectListWithDeliveryStatus();

    /**
     * RFC-063r §2.14: per-job variant for the detail page. Same subquery
     * pattern, restricted to a single id.
     */
    @Select("""
            SELECT j.*,
                   (SELECT r.delivery_status FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_status,
                   (SELECT r.delivery_error FROM mate_cron_job_run r
                    WHERE r.cron_job_id = j.id
                    ORDER BY r.started_at DESC LIMIT 1) AS last_delivery_error
            FROM mate_cron_job j
            WHERE j.id = #{id} AND j.deleted = 0
            """)
    CronJobEntity selectByIdWithDeliveryStatus(@Param("id") Long id);
}
