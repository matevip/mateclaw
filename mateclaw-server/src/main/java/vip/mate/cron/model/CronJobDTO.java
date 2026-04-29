package vip.mate.cron.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务 DTO
 *
 * @author MateClaw Team
 */
@Data
public class CronJobDTO {

    private Long id;
    /** Out-only: workspace ID stamped by the server from X-Workspace-Id (RFC-083). */
    private Long workspaceId;
    private String name;
    private String cronExpression;
    private String timezone;
    private Long agentId;
    /** 只读展示字段 */
    private String agentName;
    private String taskType;
    private String triggerMessage;
    private String requestBody;
    private Boolean enabled;
    private LocalDateTime nextRunTime;
    private LocalDateTime lastRunTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** RFC-063r §2.9: originating channel binding (null = web-origin cron). */
    private Long channelId;

    /**
     * Read-only display name for the bound channel — populated by
     * {@code CronJobService.list()} via a batch lookup so the UI can show
     * "钉钉 / 飞书 / 微信" alongside the cron row without an extra request.
     */
    private String channelName;

    /** RFC-063r §2.9: delivery target detail (targetId / threadId / accountId). */
    private DeliveryConfig deliveryConfig;

    /**
     * RFC-063r §2.14: read-model field surfaced by CronJobMapper#selectListWithDeliveryStatus
     * (PR-3). One of NONE / PENDING / DELIVERED / NOT_DELIVERED, taken from
     * the most-recent run row. Out-only — never accepted on create/update.
     */
    private String lastDeliveryStatus;

    /** RFC-063r §2.14: out-only error detail for the most-recent delivery attempt. */
    private String lastDeliveryError;

    public static CronJobDTO from(CronJobEntity entity) {
        CronJobDTO dto = new CronJobDTO();
        dto.setId(entity.getId());
        dto.setWorkspaceId(entity.getWorkspaceId());
        dto.setName(entity.getName());
        dto.setCronExpression(entity.getCronExpression());
        dto.setTimezone(entity.getTimezone());
        dto.setAgentId(entity.getAgentId());
        dto.setTaskType(entity.getTaskType());
        dto.setTriggerMessage(entity.getTriggerMessage());
        dto.setRequestBody(entity.getRequestBody());
        dto.setEnabled(entity.getEnabled());
        dto.setNextRunTime(entity.getNextRunTime());
        dto.setLastRunTime(entity.getLastRunTime());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        dto.setChannelId(entity.getChannelId());
        dto.setDeliveryConfig(entity.getDeliveryConfig());
        // RFC-063r §2.14: surface the latest-run delivery snapshot when the
        // entity was loaded via selectListWithDeliveryStatus / selectByIdWithDeliveryStatus.
        // Default "NONE" when no run has ever been recorded so the UI can
        // render a neutral badge instead of a blank cell.
        dto.setLastDeliveryStatus(entity.getLastDeliveryStatus() != null
                ? entity.getLastDeliveryStatus() : "NONE");
        dto.setLastDeliveryError(entity.getLastDeliveryError());
        return dto;
    }

    public static CronJobDTO from(CronJobEntity entity, String agentName) {
        CronJobDTO dto = from(entity);
        dto.setAgentName(agentName);
        return dto;
    }

    public CronJobEntity toEntity() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(this.id);
        entity.setName(this.name);
        entity.setCronExpression(this.cronExpression);
        entity.setTimezone(this.timezone);
        entity.setAgentId(this.agentId);
        entity.setTaskType(this.taskType);
        entity.setTriggerMessage(this.triggerMessage);
        entity.setRequestBody(this.requestBody);
        entity.setEnabled(this.enabled);
        entity.setChannelId(this.channelId);
        entity.setDeliveryConfig(this.deliveryConfig);
        return entity;
    }
}
