package vip.mate.channel.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.approval.PendingApproval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批通知服务
 * <p>
 * 统一构建审批通知内容，替代各处硬编码的字符串拼接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalNotificationService {

    private final ObjectMapper objectMapper;

    /**
     * 从 PendingApproval 构建通知数据
     */
    public ApprovalNotice buildNotice(PendingApproval pending) {
        String argsPreview = pending.getToolArguments();
        if (argsPreview != null && argsPreview.length() > 300) {
            argsPreview = argsPreview.substring(0, 300) + "...";
        }

        List<Map<String, Object>> findings = parseFindings(pending.getFindingsJson());

        // 在审批命令中包含 shortId，支持群聊多审批并发场景下精确定位
        String shortId = pending.getPendingId().substring(0, Math.min(6, pending.getPendingId().length()));
        return new ApprovalNotice(
                pending.getPendingId(),
                pending.getToolName(),
                pending.getSummary(),
                argsPreview,
                pending.getMaxSeverity(),
                findings,
                "/approve " + shortId,
                "/deny " + shortId
        );
    }

    /**
     * 构建 IM 渠道友好的文本通知（替代 ChannelMessageRouter.buildApprovalNotice）
     */
    public String buildApprovalText(PendingApproval pending) {
        ApprovalNotice notice = buildNotice(pending);
        return buildApprovalText(notice);
    }

    /**
     * 从 ApprovalNotice 构建文本（实例方法，保留供历史调用方使用）
     */
    public String buildApprovalText(ApprovalNotice notice) {
        return staticBuildText(notice);
    }

    /**
     * Static text renderer — used by the {@code AbstractChannelAdapter}
     * default {@code sendApprovalNotice} implementation, which has no
     * Spring-managed reference to the service instance.
     *
     * <p>Logic is identical to {@link #buildApprovalText(ApprovalNotice)};
     * the instance method delegates here so the two paths can never
     * drift.
     */
    public static String staticBuildText(ApprovalNotice notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 **工具需要审批**\n\n");
        sb.append("**工具名称**: ").append(notice.toolName()).append("\n");

        // Risk severity
        if (notice.maxSeverity() != null) {
            sb.append("**风险等级**: ").append(staticSeverityLabel(notice.maxSeverity())).append("\n");
        }

        // Summary
        if (notice.summary() != null && !notice.summary().isEmpty()) {
            sb.append("**摘要**: ").append(notice.summary()).append("\n");
        }

        // Args preview
        if (notice.argumentsPreview() != null && !notice.argumentsPreview().isEmpty()) {
            sb.append("**参数**: `").append(notice.argumentsPreview()).append("`\n");
        }

        // Findings (top 3)
        if (notice.findings() != null && !notice.findings().isEmpty()) {
            sb.append("\n**发现的问题**:\n");
            int shown = 0;
            for (Map<String, Object> finding : notice.findings()) {
                if (shown >= 3) {
                    sb.append("  ... 还有 ").append(notice.findings().size() - 3).append(" 条\n");
                    break;
                }
                String title = String.valueOf(finding.getOrDefault("title", ""));
                String severity = String.valueOf(finding.getOrDefault("severity", ""));
                sb.append("  • [").append(severity).append("] ").append(title).append("\n");
                shown++;
            }
        }

        sb.append("\n输入 `").append(notice.approveCommand()).append("` 批准执行，或 `")
                .append(notice.denyCommand()).append("` 拒绝。");
        return sb.toString();
    }

    private static String staticSeverityLabel(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "CRITICAL" -> "🔴 CRITICAL";
            case "HIGH" -> "🟠 HIGH";
            case "MEDIUM" -> "🟡 MEDIUM";
            case "LOW" -> "🔵 LOW";
            case "INFO" -> "⚪ INFO";
            default -> severity;
        };
    }

    /**
     * 构建 Web SSE 事件数据
     */
    public Map<String, Object> buildWebEventData(ApprovalNotice notice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingId", notice.pendingId());
        data.put("toolName", notice.toolName());
        data.put("argumentsPreview", notice.argumentsPreview());
        data.put("maxSeverity", notice.maxSeverity());
        data.put("summary", notice.summary());
        data.put("findings", notice.findings());
        data.put("approveCommand", notice.approveCommand());
        data.put("denyCommand", notice.denyCommand());
        return data;
    }

    private String severityLabel(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "CRITICAL" -> "🔴 CRITICAL";
            case "HIGH" -> "🟠 HIGH";
            case "MEDIUM" -> "🟡 MEDIUM";
            case "LOW" -> "🔵 LOW";
            case "INFO" -> "⚪ INFO";
            default -> severity;
        };
    }

    private List<Map<String, Object>> parseFindings(String findingsJson) {
        if (findingsJson == null || findingsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(findingsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ApprovalNotification] Failed to parse findings: {}", e.getMessage());
            return List.of();
        }
    }
}
