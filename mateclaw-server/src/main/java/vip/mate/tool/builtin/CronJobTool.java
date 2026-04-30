package vip.mate.tool.builtin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.cron.model.CronJobDTO;
import vip.mate.cron.service.CronJobService;

import java.util.List;

/**
 * Built-in tool: scheduled task (cron job) management via chat.
 * <p>
 * Allows agents to create, list, toggle, and delete cron jobs through natural language.
 * The agent_id is automatically bound to the current agent. LLM generates cron expressions
 * from natural language (e.g. "every day at 9am" → "0 9 * * *").
 *
 * @author MateClaw Team
 * @see vip.mate.cron.service.CronJobService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronJobTool {

    private final CronJobService cronJobService;

    @vip.mate.tool.ConcurrencyUnsafe("cron job creation persists to mate_cron_job; concurrent creates can race on name")
    @Tool(description = "Create a scheduled task that asks the agent to do something at a specific time — "
            + "the trigger message is sent to the LLM, which can use tools (search, weather, etc.) to produce the answer. "
            + "Use this for queries like 'every morning give me a weather report' or 'daily news summary'. "
            + "DO NOT use this for plain reminders where the user already wrote the exact text they want delivered — "
            + "use create_reminder instead, otherwise the LLM will rephrase or echo the reminder. "
            + "Use 5-field cron expressions: minute hour day month weekday. "
            + "Examples: '0 9 * * *' = daily at 9am, '0 9 * * 1-5' = weekdays at 9am, '*/30 * * * *' = every 30 minutes.")
    public String create_cron_job(
            @ToolParam(description = "Task name, e.g. 'Daily AI News Summary'") String name,
            @ToolParam(description = "5-field cron expression: minute hour day month weekday") String cronExpression,
            @ToolParam(description = "Message to send when the task triggers, e.g. 'Search for the latest AI news and summarize'") String triggerMessage,
            @ToolParam(description = "Timezone, default Asia/Shanghai. Examples: UTC, America/New_York", required = false) String timezone,
            // RFC-063r §2.4: ToolContext is *not* exposed to the LLM —
            // JsonSchemaGenerator skips it (Spring AI 1.1 framework convention)
            @Nullable ToolContext ctx) {

        try {
            // RFC-063r §2.5: the ChatOrigin must carry agentId — buildInitialState
            // injects it from the agent that owns the StateGraph. If it's missing
            // here, something upstream broke (no holder set, KeyStrategyFactory
            // dropped CHAT_ORIGIN, etc.) — fail loudly rather than silently
            // binding to agent #1, which could be disabled / non-existent /
            // user-renamed and would surface as "scheduled but never runs".
            ChatOrigin origin = ChatOrigin.from(ctx);
            String conversationId = origin.conversationId() != null && !origin.conversationId().isEmpty()
                    ? origin.conversationId()
                    : ToolExecutionContext.conversationId();
            Long agentId = origin.agentId();
            if (agentId == null) {
                log.warn("[CronJobTool] create_cron_job invoked without an agentId in ChatOrigin " +
                        "(conv={}); refusing to silently bind to a default agent.", conversationId);
                return errorResult("Cannot create cron job: agent context unavailable. " +
                        "This is an internal wiring bug — the originating agent id was not threaded " +
                        "through ToolContext. Re-issue the request; if it persists, see RFC-063r §2.5.");
            }

            CronJobDTO dto = new CronJobDTO();
            dto.setName(name);
            dto.setCronExpression(cronExpression);
            dto.setTriggerMessage(triggerMessage);
            dto.setTimezone(timezone != null && !timezone.isBlank() ? timezone : "Asia/Shanghai");
            dto.setAgentId(agentId);
            dto.setTaskType("text");
            dto.setEnabled(true);

            // RFC-063r §2.4 / PR-2: when the originating context carries a
            // channelId, the cron job inherits the binding so its results can
            // be delivered back to the same channel. Fields are wired via
            // reflection until PR-2 adds them to CronJobDTO + CronJobEntity.
            propagateChannelBinding(dto, origin);

            // RFC-083: stamp workspace from the originating ChatOrigin so the
            // cron job is created in the agent's current workspace; fall back
            // to the default workspace when origin is unscoped (legacy paths).
            Long workspaceId = origin.workspaceId() != null ? origin.workspaceId() : 1L;
            CronJobDTO created = cronJobService.create(dto, workspaceId);

            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("jobId", created.getId());
            result.set("name", created.getName());
            result.set("cronExpression", created.getCronExpression());
            result.set("timezone", created.getTimezone());
            result.set("nextRunTime", created.getNextRunTime() != null ? created.getNextRunTime().toString() : "");
            result.set("enabled", created.getEnabled());
            return JSONUtil.toJsonPrettyStr(result);

        } catch (Exception e) {
            log.error("[CronJobTool] create failed: {}", e.getMessage());
            return errorResult("Failed to create cron job: " + e.getMessage());
        }
    }

    @vip.mate.tool.ConcurrencyUnsafe("reminder creation persists to mate_cron_job; concurrent creates can race on name")
    @Tool(description = "Create a scheduled REMINDER. The reminder text is delivered to the user verbatim at the "
            + "scheduled time — no LLM call, no rephrasing, no token cost. "
            + "Use this when the user wants a notification with specific content (e.g. 'remind me at 3pm to leave for the meeting' → "
            + "reminder text 'It's time to leave for the meeting'). "
            + "DO NOT use this if the message requires the agent to compute or look something up — use create_cron_job for that. "
            + "Use 5-field cron expressions: minute hour day month weekday. "
            + "Examples: '0 15 * * *' = every day at 3pm, '0 9 * * 1' = every Monday at 9am.")
    public String create_reminder(
            @ToolParam(description = "Reminder name, e.g. 'Meeting at Room 6'") String name,
            @ToolParam(description = "5-field cron expression: minute hour day month weekday") String cronExpression,
            @ToolParam(description = "The exact text to deliver to the user when the reminder fires, e.g. "
                    + "'⏰ It's time to leave for the meeting at Room 6.'") String reminderText,
            @ToolParam(description = "Timezone, default Asia/Shanghai. Examples: UTC, America/New_York", required = false) String timezone,
            @Nullable ToolContext ctx) {

        try {
            ChatOrigin origin = ChatOrigin.from(ctx);
            String conversationId = origin.conversationId() != null && !origin.conversationId().isEmpty()
                    ? origin.conversationId()
                    : ToolExecutionContext.conversationId();
            Long agentId = origin.agentId();
            if (agentId == null) {
                log.warn("[CronJobTool] create_reminder invoked without an agentId in ChatOrigin " +
                        "(conv={}); refusing to silently bind to a default agent.", conversationId);
                return errorResult("Cannot create reminder: agent context unavailable.");
            }

            CronJobDTO dto = new CronJobDTO();
            dto.setName(name);
            dto.setCronExpression(cronExpression);
            dto.setTriggerMessage(reminderText);
            dto.setTimezone(timezone != null && !timezone.isBlank() ? timezone : "Asia/Shanghai");
            dto.setAgentId(agentId);
            dto.setTaskType("reminder");
            dto.setEnabled(true);

            propagateChannelBinding(dto, origin);

            Long workspaceId = origin.workspaceId() != null ? origin.workspaceId() : 1L;
            CronJobDTO created = cronJobService.create(dto, workspaceId);

            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("jobId", created.getId());
            result.set("name", created.getName());
            result.set("taskType", "reminder");
            result.set("cronExpression", created.getCronExpression());
            result.set("timezone", created.getTimezone());
            result.set("nextRunTime", created.getNextRunTime() != null ? created.getNextRunTime().toString() : "");
            result.set("enabled", created.getEnabled());
            return JSONUtil.toJsonPrettyStr(result);

        } catch (Exception e) {
            log.error("[CronJobTool] create_reminder failed: {}", e.getMessage());
            return errorResult("Failed to create reminder: " + e.getMessage());
        }
    }

    @Tool(description = "List all scheduled tasks (cron jobs) for the current agent. "
            + "Returns task name, cron expression, next run time, enabled status, and last run time.")
    public String list_cron_jobs(@Nullable ToolContext ctx) {
        try {
            // RFC-083: scope to the originating workspace so an agent only
            // sees the cron jobs of the workspace it's running in.
            Long workspaceId = workspaceFromContext(ctx);
            List<CronJobDTO> jobs = cronJobService.list(workspaceId);
            JSONArray arr = new JSONArray();
            for (CronJobDTO job : jobs) {
                JSONObject obj = new JSONObject();
                obj.set("jobId", job.getId());
                obj.set("name", job.getName());
                obj.set("cronExpression", job.getCronExpression());
                obj.set("timezone", job.getTimezone());
                obj.set("enabled", job.getEnabled());
                obj.set("nextRunTime", job.getNextRunTime() != null ? job.getNextRunTime().toString() : "");
                obj.set("lastRunTime", job.getLastRunTime() != null ? job.getLastRunTime().toString() : "");
                obj.set("agentName", job.getAgentName());
                arr.add(obj);
            }
            JSONObject result = new JSONObject();
            result.set("totalJobs", jobs.size());
            result.set("jobs", arr);
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception e) {
            log.error("[CronJobTool] list failed: {}", e.getMessage());
            return errorResult("Failed to list cron jobs: " + e.getMessage());
        }
    }

    @vip.mate.tool.ConcurrencyUnsafe("toggles row state in mate_cron_job; serialize to keep enabled/disabled deterministic")
    @Tool(description = "Enable or disable a scheduled task by its job ID. "
            + "Use list_cron_jobs first to find the job ID.")
    public String toggle_cron_job(
            @ToolParam(description = "Job ID (number)") Long jobId,
            @ToolParam(description = "true to enable, false to disable") Boolean enabled,
            @Nullable ToolContext ctx) {
        try {
            // RFC-083: scope toggle to the originating workspace.
            Long workspaceId = workspaceFromContext(ctx);
            cronJobService.toggle(jobId, enabled, workspaceId);
            CronJobDTO updated = cronJobService.getById(jobId, workspaceId);
            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("jobId", jobId);
            result.set("name", updated.getName());
            result.set("enabled", updated.getEnabled());
            result.set("nextRunTime", updated.getNextRunTime() != null ? updated.getNextRunTime().toString() : "");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception e) {
            log.error("[CronJobTool] toggle failed: {}", e.getMessage());
            return errorResult("Failed to toggle cron job: " + e.getMessage());
        }
    }

    @vip.mate.tool.ConcurrencyUnsafe("destructive — removes row from mate_cron_job")
    @Tool(description = "Delete a scheduled task by its job ID. This action requires user approval. "
            + "Use list_cron_jobs first to find the job ID.")
    public String delete_cron_job(
            @ToolParam(description = "Job ID (number) to delete") Long jobId,
            @Nullable ToolContext ctx) {
        try {
            // RFC-083: scope delete to the originating workspace.
            Long workspaceId = workspaceFromContext(ctx);
            CronJobDTO job = cronJobService.getById(jobId, workspaceId);
            String jobName = job.getName();
            cronJobService.delete(jobId, workspaceId);
            JSONObject result = new JSONObject();
            result.set("success", true);
            result.set("deleted", jobName);
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception e) {
            log.error("[CronJobTool] delete failed: {}", e.getMessage());
            return errorResult("Failed to delete cron job: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        JSONObject result = new JSONObject();
        result.set("success", false);
        result.set("error", message);
        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * RFC-083: resolve the workspace ID from the originating ChatOrigin so
     * cron-tool reads/writes are scoped to the agent's current workspace.
     * Falls back to the default workspace (1) when origin is unscoped — same
     * behaviour as the controller-layer {@code resolve()} helper.
     */
    private Long workspaceFromContext(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        return origin != null && origin.workspaceId() != null ? origin.workspaceId() : 1L;
    }

    /**
     * RFC-063r §2.4: propagate the originating channel binding into the cron
     * job DTO so PR-3's delivery dispatcher can route results back to the
     * originating channel.
     */
    private void propagateChannelBinding(CronJobDTO dto, ChatOrigin origin) {
        if (origin == null || origin.channelId() == null) return;
        dto.setChannelId(origin.channelId());
        if (origin.channelTarget() != null) {
            // Carry the requesterId (= IM senderId) so CronConversationResolver
            // can match (channelId, senderId) instead of (channelId, targetId).
            // Adapters that use a replyToken (DingTalk sessionWebhook etc.)
            // store it as session.targetId, which never equals the cron's own
            // chatId/senderId-derived targetId — the senderId match is the
            // stable common key.
            dto.setDeliveryConfig(vip.mate.cron.model.DeliveryConfig.from(
                    origin.channelTarget(), origin.requesterId()));
        }
    }
}
