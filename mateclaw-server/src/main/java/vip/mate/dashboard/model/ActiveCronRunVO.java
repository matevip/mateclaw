package vip.mate.dashboard.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Read-model for the "is a cron job currently running in this conversation?"
 * UI poll. Returned by {@code GET /api/v1/cron-jobs/active-runs} so the
 * chat console can render a placeholder bubble between T1 (run row inserted)
 * and T2 (assistant message persisted) — see CronJobLifecycleService for the
 * three-segment transactional flow.
 */
@Data
public class ActiveCronRunVO {
    private Long runId;
    private Long jobId;
    private String jobName;
    private String triggerType;
    private String conversationId;
    private LocalDateTime startedAt;
}
