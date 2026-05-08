package vip.mate.workflow.draftgen;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Small in-process library of canonical workflow shapes. Used as
 * few-shot exemplars in the system prompt AND as "apply template"
 * entries operators / agents can drop in directly. Kept as code
 * constants rather than a DB table so the templates version with the
 * runtime that interprets them — a template that references modes the
 * runtime doesn't support yet should never ship.
 *
 * <p>Templates are intentionally minimal: 5-7 shapes that cover what
 * the v0 reviewer flagged as the actual customer use cases (weekly
 * summary, approval-and-notify, customer-message routing, chained
 * workflow, daily memory write). New shapes only get added when the
 * customer evidence is in.
 */
@Component
public class WorkflowDraftTemplateLibrary {

    private final List<WorkflowDraftTemplate> templates = List.of(
            weeklySummary(),
            approvalAndNotify(),
            customerMessageRouting(),
            chainedWorkflow(),
            dailyMemoryWrite(),
            parallelAnalysis(),
            channelAlertOnFailure()
    );

    public List<WorkflowDraftTemplate> all() {
        return templates;
    }

    /** Look up a template by id; returns null when no match. */
    public WorkflowDraftTemplate byId(String id) {
        if (id == null) return null;
        return templates.stream()
                .filter(t -> id.equals(t.id()))
                .findFirst().orElse(null);
    }

    // ===== template definitions =====

    private static WorkflowDraftTemplate weeklySummary() {
        return new WorkflowDraftTemplate(
                "weekly-summary",
                "周报汇总 / Weekly summary",
                "每周固定时间让数字员工汇总数据，再发到群里。常见于销售周报、运营日报。",
                List.of("每周", "周报", "周一", "weekly", "summary", "汇总"),
                """
                {"steps":[
                  {"name":"collect-data","agentName":"TODO_DATA_AGENT","mode":{"type":"sequential"},
                   "promptTemplate":"汇总本周的{{ inputs.topic }}并输出 JSON","outputVar":"summary","outputContentType":"json"},
                  {"name":"notify-group",
                   "mode":{"type":"dispatch_channel","channels":["TODO_SELECT_CHANNEL"],
                           "targets":{"TODO_SELECT_CHANNEL":"TODO_TARGET_ID"},
                           "content":"本周汇总：{{ outputs.summary }}"}}
                ]}""",
                """
                [{"name":"weekly-summary-cron","patternType":"cron","enabled":false,
                  "patternJson":{"cron":"0 0 9 ? * MON","timezone":"Asia/Shanghai"},
                  "targetType":"workflow",
                  "payloadTemplate":"{\\"topic\\":\\"销售\\"}"}]"""
        );
    }

    private static WorkflowDraftTemplate approvalAndNotify() {
        return new WorkflowDraftTemplate(
                "approval-and-notify",
                "审批后通知 / Approval then notify",
                "数字员工出方案 → 老板审批 → 通过后发到群里。常见于费用申请、采购、合同。",
                List.of("审批", "确认", "老板", "approval", "approve", "确认通过"),
                """
                {"steps":[
                  {"name":"draft-proposal","agentName":"TODO_DRAFTER","mode":{"type":"sequential"},
                   "promptTemplate":"为 {{ inputs.topic }} 起草一个方案","outputVar":"proposal","outputContentType":"text"},
                  {"name":"manager-approve",
                   "mode":{"type":"await_approval","approvalKind":"manager",
                           "approverChannels":["web"],
                           "approvalMessage":"请审批方案：{{ outputs.proposal }}",
                           "timeoutSecs":86400}},
                  {"name":"notify-group",
                   "mode":{"type":"dispatch_channel","channels":["TODO_SELECT_CHANNEL"],
                           "targets":{"TODO_SELECT_CHANNEL":"TODO_TARGET_ID"},
                           "content":"方案已通过：{{ outputs.proposal }}"}}
                ]}""",
                "[]"
        );
    }

    private static WorkflowDraftTemplate customerMessageRouting() {
        return new WorkflowDraftTemplate(
                "customer-message-routing",
                "客户消息路由 / Customer message routing",
                "渠道里出现关键词时，让客服员工应对，并把结果记到员工记忆。",
                List.of("客户", "客服", "关键词", "customer", "support", "回复"),
                """
                {"steps":[
                  {"name":"answer-customer","agentName":"TODO_SUPPORT_AGENT","mode":{"type":"sequential"},
                   "promptTemplate":"客户说：{{ inputs.content }}。请用礼貌的语气回复。",
                   "outputVar":"reply","outputContentType":"text"},
                  {"name":"send-reply",
                   "mode":{"type":"dispatch_channel","channels":["TODO_SELECT_CHANNEL"],
                           "targets":{"TODO_SELECT_CHANNEL":"{{ inputs.sender }}"},
                           "content":"{{ outputs.reply }}"}},
                  {"name":"remember-issue",
                   "mode":{"type":"write_memory","employeeId":"TODO_SUPPORT_AGENT",
                           "file":"customer-issues.md","mergeStrategy":"append",
                           "content":"### {{ inputs.sender }}\\n{{ inputs.content }}\\n回复：{{ outputs.reply }}\\n"}}
                ]}""",
                """
                [{"name":"customer-keyword","patternType":"channel_message","enabled":false,
                  "patternJson":{"channelType":"TODO_SELECT_CHANNEL","contentContains":"发票"},
                  "targetType":"workflow",
                  "payloadTemplate":"{\\"content\\":\\"{{ event.content }}\\",\\"sender\\":\\"{{ event.senderId }}\\"}"}]"""
        );
    }

    private static WorkflowDraftTemplate chainedWorkflow() {
        return new WorkflowDraftTemplate(
                "chained-workflow",
                "上游完成后接力 / Chained on upstream completion",
                "上游 workflow 跑完后自动接一段处理：常见于 ETL 接出报表、运营接审计。",
                List.of("接力", "上游", "完成后", "chained", "after"),
                """
                {"steps":[
                  {"name":"post-process","agentName":"TODO_AGENT","mode":{"type":"sequential"},
                   "promptTemplate":"上游 run {{ inputs.sourceWorkflowId }} 已完成（state={{ inputs.state }}），请处理后续。",
                   "outputVar":"summary","outputContentType":"text"}
                ]}""",
                """
                [{"name":"after-upstream","patternType":"workflow_completion","enabled":false,
                  "patternJson":{"sourceWorkflowId":"TODO_WORKFLOW_ID","stateFilter":"succeeded"},
                  "targetType":"workflow",
                  "payloadTemplate":"{\\"sourceWorkflowId\\":\\"{{ event.sourceWorkflowId }}\\",\\"state\\":\\"{{ event.state }}\\"}"}]"""
        );
    }

    private static WorkflowDraftTemplate dailyMemoryWrite() {
        return new WorkflowDraftTemplate(
                "daily-memory-write",
                "每日记入员工记忆 / Daily memory append",
                "每天定时让员工写一段记忆，作为后续对话的上下文。",
                List.of("每天", "daily", "记忆", "写入", "memory"),
                """
                {"steps":[
                  {"name":"summarize-day","agentName":"TODO_AGENT","mode":{"type":"sequential"},
                   "promptTemplate":"用一段话总结今天的{{ inputs.topic }}。",
                   "outputVar":"summary","outputContentType":"text"},
                  {"name":"persist-memory",
                   "mode":{"type":"write_memory","employeeId":"TODO_EMPLOYEE_ID",
                           "file":"daily-log.md","mergeStrategy":"append",
                           "content":"### {{ inputs.date }}\\n{{ outputs.summary }}\\n"}}
                ]}""",
                """
                [{"name":"daily-memory-cron","patternType":"cron","enabled":false,
                  "patternJson":{"cron":"0 0 22 * * ?","timezone":"Asia/Shanghai"},
                  "targetType":"workflow",
                  "payloadTemplate":"{\\"topic\\":\\"工作\\",\\"date\\":\\"{{ event.firedAt }}\\"}"}]"""
        );
    }

    private static WorkflowDraftTemplate parallelAnalysis() {
        return new WorkflowDraftTemplate(
                "parallel-analysis",
                "并行多角度分析 / Parallel multi-angle analysis",
                "三个不同员工同时从不同角度分析同一份输入，最后由 collect 汇合。",
                List.of("分别", "并行", "多角度", "parallel", "fan_out"),
                """
                {"steps":[
                  {"name":"angle-finance","agentName":"TODO_FINANCE_AGENT","mode":{"type":"fan_out"},
                   "promptTemplate":"从财务角度分析：{{ inputs.topic }}",
                   "outputVar":"finance","outputContentType":"text"},
                  {"name":"angle-operations","agentName":"TODO_OPS_AGENT","mode":{"type":"fan_out"},
                   "promptTemplate":"从运营角度分析：{{ inputs.topic }}",
                   "outputVar":"ops","outputContentType":"text"},
                  {"name":"angle-customer","agentName":"TODO_CUSTOMER_AGENT","mode":{"type":"fan_out"},
                   "promptTemplate":"从客户角度分析：{{ inputs.topic }}",
                   "outputVar":"customer","outputContentType":"text"},
                  {"name":"merge-views","mode":{"type":"collect"}}
                ]}""",
                "[]"
        );
    }

    private static WorkflowDraftTemplate channelAlertOnFailure() {
        return new WorkflowDraftTemplate(
                "channel-alert-on-failure",
                "上游失败时报警 / Alert on upstream failure",
                "上游 workflow 跑失败时立即推送到值班渠道，常见于关键 ETL / 自动化作业的兜底。",
                List.of("失败", "报警", "alert", "failure", "失败时"),
                """
                {"steps":[
                  {"name":"alert-oncall",
                   "mode":{"type":"dispatch_channel","channels":["TODO_SELECT_CHANNEL"],
                           "targets":{"TODO_SELECT_CHANNEL":"TODO_ONCALL_TARGET"},
                           "content":"⚠ 上游 workflow run {{ inputs.runId }} 失败：{{ inputs.errorMessage }}"}}
                ]}""",
                """
                [{"name":"upstream-failure","patternType":"workflow_completion","enabled":false,
                  "patternJson":{"sourceWorkflowId":"TODO_WORKFLOW_ID","stateFilter":"failed"},
                  "targetType":"workflow",
                  "payloadTemplate":"{\\"runId\\":\\"{{ event.runId }}\\",\\"errorMessage\\":\\"{{ event.errorMessage }}\\"}"}]"""
        );
    }
}
