package vip.mate.workflow.draftgen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.service.WorkflowService;

/**
 * Agent-callable workflow drafting tool.
 *
 * <p>Lets a user say in chat «帮我把每周一汇总销售这件事做成 workflow»
 * and have the agent compose a draft + persist it as a fresh
 * {@link WorkflowEntity} row + return a short natural-language summary
 * the user can act on. The created workflow stays as a draft (no
 * publish, no triggers wired) — same v0 safety contract as the
 * controller endpoint.
 *
 * <p>Workspace is taken from {@link ChatOrigin} on the active
 * {@link ToolContext}, so the tool can never write into a foreign
 * workspace even if the agent prompt tried to forge one.
 */
@Slf4j
@Component
public class WorkflowAuthoringTool {

    private final WorkflowDraftGenerator generator;
    private final WorkflowService workflowService;

    public WorkflowAuthoringTool(WorkflowDraftGenerator generator,
                                 WorkflowService workflowService) {
        this.generator = generator;
        this.workflowService = workflowService;
    }

    @Tool(description = "把用户描述的业务流程转换成一个 MateClaw workflow 草稿并保存到当前 workspace。"
            + "适用场景：用户说「把 X 这件事做成 workflow / 自动化 / 流程」、「每周一让 X 员工 ...」、"
            + "「客户消息进来时让 X 应对」。工具会输出 workflowId + 简短摘要，前端会自动在 workflow 编辑器里打开。"
            + "不会自动发布，不会自动启用 trigger — 用户需要在编辑器里 review 后再 publish。")
    public String workflow_draft_generate(
            @ToolParam(description = "用户对业务流程的自然语言描述，越具体越好；可以包含触发条件、参与员工、是否要审批、要发到哪个渠道。")
            String description,
            // ChatOrigin-scoped workspace lookup; never trust the LLM to pass workspaceId.
            @Nullable ToolContext ctx) {

        Long workspaceId = ctx == null ? null : ChatOrigin.from(ctx).workspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            return "无法确定当前 workspace，工具放弃执行。请在 workspace 上下文里调用我。";
        }

        GeneratedWorkflowDraft draft;
        try {
            draft = generator.generate(description, workspaceId);
        } catch (Exception e) {
            log.warn("[workflow_draft_generate] generation failed for ws={}: {}",
                    workspaceId, e.getMessage());
            return "生成失败：" + e.getMessage();
        }

        // Persist as a draft. No publish, no triggers — that's a separate
        // user action via the editor / approve flow. We name it from the
        // generator output so the editor surfaces something useful in
        // the list immediately.
        WorkflowEntity wf = new WorkflowEntity();
        wf.setName(draft.name());
        wf.setDescription(draft.description());
        wf.setEnabled(true);
        wf.setWorkspaceId(workspaceId);
        WorkflowEntity created;
        try {
            created = workflowService.create(wf);
            workflowService.saveDraft(created.getId(), workspaceId, draft.draftJson(), null);
        } catch (Exception e) {
            log.warn("[workflow_draft_generate] persist failed: {}", e.getMessage());
            return "草稿生成成功但保存失败：" + e.getMessage();
        }

        StringBuilder out = new StringBuilder();
        out.append("已生成 workflow 草稿 ").append(draft.name())
           .append("（id=").append(created.getId()).append("）。\n");
        if (draft.compileOk()) {
            out.append("✓ 编译预校验通过。\n");
        } else {
            out.append("⚠ 编译预校验未通过 (").append(draft.compileErrors().size()).append(" 处)，需在编辑器里修正。\n");
        }
        if (draft.missingFields() != null && !draft.missingFields().isEmpty()) {
            out.append("缺失字段：");
            for (int i = 0; i < draft.missingFields().size(); i++) {
                if (i > 0) out.append("；");
                out.append(draft.missingFields().get(i));
            }
            out.append("\n");
        }
        if (draft.warnings() != null && !draft.warnings().isEmpty()) {
            out.append("警告：");
            for (int i = 0; i < draft.warnings().size(); i++) {
                if (i > 0) out.append("；");
                out.append(draft.warnings().get(i));
            }
            out.append("\n");
        }
        if (draft.triggerDrafts() != null && !draft.triggerDrafts().isEmpty()) {
            out.append("建议触发器：").append(draft.triggerDrafts().size()).append(" 个 (默认未启用，需在编辑器里确认后创建)。\n");
        }
        out.append("请到 workflow 编辑器查看并继续完善。");
        return out.toString();
    }
}
