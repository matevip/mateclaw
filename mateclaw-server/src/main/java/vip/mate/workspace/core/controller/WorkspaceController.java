package vip.mate.workspace.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;
import java.util.Map;

/**
 * 工作区管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "工作区管理")
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final AuthService authService;

    // ==================== 工作区 CRUD ====================

    @Operation(summary = "获取当前用户的工作区列表")
    @GetMapping
    public R<List<WorkspaceEntity>> list(Authentication auth) {
        Long userId = resolveUserId(auth);
        return R.ok(workspaceService.listByUserId(userId));
    }

    @Operation(summary = "获取工作区详情")
    @GetMapping("/{id}")
    public R<WorkspaceEntity> get(@PathVariable Long id) {
        return R.ok(workspaceService.getById(id));
    }

    @Operation(summary = "创建工作区")
    @PostMapping
    public R<WorkspaceEntity> create(@RequestBody WorkspaceEntity entity, Authentication auth) {
        Long userId = resolveUserId(auth);
        return R.ok(workspaceService.create(entity, userId));
    }

    @Operation(summary = "更新工作区")
    @PutMapping("/{id}")
    public R<WorkspaceEntity> update(@PathVariable Long id, @RequestBody WorkspaceEntity entity, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "owner");
        entity.setId(id);
        return R.ok(workspaceService.update(entity));
    }

    @Operation(summary = "删除工作区")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "owner");
        workspaceService.delete(id);
        return R.ok();
    }

    // ==================== 成员管理 ====================

    @Operation(summary = "获取工作区成员列表")
    @GetMapping("/{id}/members")
    public R<List<WorkspaceMemberEntity>> listMembers(@PathVariable Long id) {
        List<WorkspaceMemberEntity> members = workspaceService.listMembers(id);
        // 填充用户名/昵称
        for (WorkspaceMemberEntity m : members) {
            UserEntity user = authService.findById(m.getUserId());
            if (user != null) {
                m.setUsername(user.getUsername());
                m.setNickname(user.getNickname());
            }
        }
        return R.ok(members);
    }

    @Operation(summary = "添加工作区成员")
    @PostMapping("/{id}/members")
    public R<WorkspaceMemberEntity> addMember(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body,
                                               Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        Long targetUserId = Long.valueOf(body.get("userId").toString());
        String role = body.containsKey("role") ? body.get("role").toString() : "member";
        return R.ok(workspaceService.addMember(id, targetUserId, role));
    }

    @Operation(summary = "更新成员角色")
    @PutMapping("/{id}/members/{memberId}")
    public R<WorkspaceMemberEntity> updateMemberRole(@PathVariable Long id,
                                                      @PathVariable Long memberId,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        return R.ok(workspaceService.updateMemberRole(id, memberId, body.get("role")));
    }

    @Operation(summary = "移除工作区成员")
    @DeleteMapping("/{id}/members/{memberId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        workspaceService.removeMember(id, memberId);
        return R.ok();
    }

    // ==================== 工具方法 ====================

    private Long resolveUserId(Authentication auth) {
        String username = auth.getName();
        UserEntity user = authService.findByUsername(username);
        if (user == null) {
            throw new MateClawException("用户不存在: " + username);
        }
        return user.getId();
    }
}
