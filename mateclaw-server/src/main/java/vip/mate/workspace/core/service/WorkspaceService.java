package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

import java.util.List;

/**
 * 工作区业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    /** 默认工作区 slug */
    public static final String DEFAULT_SLUG = "default";

    // ==================== 工作区 CRUD ====================

    public List<WorkspaceEntity> listAll() {
        return workspaceMapper.selectList(
                new LambdaQueryWrapper<WorkspaceEntity>().orderByAsc(WorkspaceEntity::getCreateTime));
    }

    /**
     * 查询用户可见的工作区列表（用户是其成员的所有工作区）
     */
    public List<WorkspaceEntity> listByUserId(Long userId) {
        List<WorkspaceMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        if (memberships.isEmpty()) {
            // 至少返回默认工作区
            WorkspaceEntity defaultWs = getBySlug(DEFAULT_SLUG);
            return defaultWs != null ? List.of(defaultWs) : List.of();
        }
        List<Long> wsIds = memberships.stream().map(WorkspaceMemberEntity::getWorkspaceId).toList();
        return workspaceMapper.selectBatchIds(wsIds);
    }

    public WorkspaceEntity getById(Long id) {
        WorkspaceEntity entity = workspaceMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("工作区不存在: " + id);
        }
        return entity;
    }

    public WorkspaceEntity getBySlug(String slug) {
        return workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceEntity>()
                        .eq(WorkspaceEntity::getSlug, slug));
    }

    @Transactional
    public WorkspaceEntity create(WorkspaceEntity entity, Long creatorUserId) {
        // 验证 slug 唯一
        if (getBySlug(entity.getSlug()) != null) {
            throw new MateClawException("工作区标识已存在: " + entity.getSlug());
        }
        entity.setOwnerId(creatorUserId);
        workspaceMapper.insert(entity);

        // 创建者自动成为 owner
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(entity.getId());
        member.setUserId(creatorUserId);
        member.setRole("owner");
        memberMapper.insert(member);

        log.info("Created workspace: {} (slug={}, owner={})", entity.getName(), entity.getSlug(), creatorUserId);
        return entity;
    }

    public WorkspaceEntity update(WorkspaceEntity entity) {
        WorkspaceEntity existing = getById(entity.getId());
        // 不允许修改默认工作区的 slug
        if (DEFAULT_SLUG.equals(existing.getSlug()) && !DEFAULT_SLUG.equals(entity.getSlug())) {
            throw new MateClawException("不能修改默认工作区的标识");
        }
        // 验证 slug 唯一性（如果修改了 slug）
        if (entity.getSlug() != null && !entity.getSlug().equals(existing.getSlug())) {
            if (getBySlug(entity.getSlug()) != null) {
                throw new MateClawException("工作区标识已存在: " + entity.getSlug());
            }
        }
        workspaceMapper.updateById(entity);
        return entity;
    }

    public void delete(Long id) {
        WorkspaceEntity existing = getById(id);
        if (DEFAULT_SLUG.equals(existing.getSlug())) {
            throw new MateClawException("不能删除默认工作区");
        }
        workspaceMapper.deleteById(id);
        log.info("Deleted workspace: {} (id={})", existing.getName(), id);
    }

    // ==================== 成员管理 ====================

    public List<WorkspaceMemberEntity> listMembers(Long workspaceId) {
        return memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(WorkspaceMemberEntity::getCreateTime));
    }

    public WorkspaceMemberEntity getMembership(Long workspaceId, Long userId) {
        return memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMemberEntity::getUserId, userId));
    }

    @Transactional
    public WorkspaceMemberEntity addMember(Long workspaceId, Long userId, String role) {
        // 验证工作区存在
        getById(workspaceId);
        // 检查是否已是成员
        WorkspaceMemberEntity existing = getMembership(workspaceId, userId);
        if (existing != null) {
            throw new MateClawException("用户已经是该工作区的成员");
        }
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role != null ? role : "member");
        memberMapper.insert(member);
        log.info("Added member to workspace: userId={}, workspaceId={}, role={}", userId, workspaceId, member.getRole());
        return member;
    }

    public WorkspaceMemberEntity updateMemberRole(Long workspaceId, Long userId, String role) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("不能修改工作区拥有者的角色");
        }
        member.setRole(role);
        memberMapper.updateById(member);
        return member;
    }

    public void removeMember(Long workspaceId, Long userId) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("不能移除工作区拥有者");
        }
        memberMapper.deleteById(member.getId());
        log.info("Removed member from workspace: userId={}, workspaceId={}", userId, workspaceId);
    }

    // ==================== 权限检查 ====================

    /**
     * 检查用户是否有指定工作区的最低角色权限
     *
     * @param workspaceId 工作区 ID
     * @param userId      用户 ID
     * @param minRole     最低角色要求：owner > admin > member > viewer
     * @return true 如果用户有足够权限
     */
    public boolean hasPermission(Long workspaceId, Long userId, String minRole) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            return false;
        }
        return roleLevel(member.getRole()) >= roleLevel(minRole);
    }

    /**
     * 断言用户有指定权限，否则抛异常
     */
    public void requirePermission(Long workspaceId, Long userId, String minRole) {
        if (!hasPermission(workspaceId, userId, minRole)) {
            throw new MateClawException("权限不足：需要 " + minRole + " 或更高角色");
        }
    }

    private int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
