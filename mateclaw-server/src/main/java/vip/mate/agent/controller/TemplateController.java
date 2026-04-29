package vip.mate.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.agent.service.TemplateService;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/**
 * Agent 模板接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent Templates")
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;
    private final AuthService authService;

    @Operation(summary = "获取模板列表")
    @GetMapping
    public R<List<TemplateDTO>> list() {
        return R.ok(templateService.listTemplates());
    }

    @Operation(summary = "应用模板创建Agent")
    @PostMapping("/{id}/apply")
    @RequireWorkspaceRole("member")
    public R<AgentEntity> apply(
            @PathVariable String id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        Long userId = resolveUserId(auth);
        return R.ok(templateService.applyTemplate(id, wsId, userId));
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "Not authenticated");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", 401, "User not found: " + auth.getName());
        }
        return user.getId();
    }
}
