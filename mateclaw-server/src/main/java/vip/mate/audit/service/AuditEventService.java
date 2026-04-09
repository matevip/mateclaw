package vip.mate.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.audit.model.AuditEventEntity;
import vip.mate.audit.repository.AuditEventMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;

import java.time.LocalDateTime;

/**
 * 操作审计服务
 * <p>
 * 异步记录用户对资源的 CRUD 操作，不阻塞业务请求。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventMapper auditEventMapper;
    private final AuthService authService;

    /**
     * 异步记录审计事件。
     * <p>
     * 在调用线程（请求线程）中捕获完整上下文，然后交给异步线程写库。
     * 这样避免了 SecurityContext/RequestContext 在异步线程中丢失的问题。
     */
    public void record(String action, String resourceType, String resourceId,
                       String resourceName, String detailJson) {
        // 在请求线程中构建事件（可以访问 SecurityContext 和 RequestContext）
        AuditEventEntity event = buildEvent(action, resourceType, resourceId, resourceName, detailJson);
        if (event != null) {
            insertAsync(event);
        }
    }

    @Async
    void insertAsync(AuditEventEntity event) {
        try {
            auditEventMapper.insert(event);
        } catch (Exception e) {
            log.warn("Failed to insert audit event: {}/{}", event.getAction(), event.getResourceType(), e);
        }
    }

    /**
     * 同步记录（用于必须确保落库的场景，如登录/登出）
     */
    public void recordSync(String action, String resourceType, String resourceId,
                           String resourceName, String detailJson) {
        AuditEventEntity event = buildEvent(action, resourceType, resourceId, resourceName, detailJson);
        if (event != null) {
            auditEventMapper.insert(event);
        }
    }

    /**
     * 分页查询审计事件
     */
    public IPage<AuditEventEntity> listEvents(Long workspaceId, String action, String resourceType,
                                               LocalDateTime startTime, LocalDateTime endTime,
                                               int page, int size) {
        LambdaQueryWrapper<AuditEventEntity> wrapper = new LambdaQueryWrapper<>();
        if (workspaceId != null) {
            wrapper.eq(AuditEventEntity::getWorkspaceId, workspaceId);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditEventEntity::getAction, action);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            wrapper.eq(AuditEventEntity::getResourceType, resourceType);
        }
        if (startTime != null) {
            wrapper.ge(AuditEventEntity::getCreateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(AuditEventEntity::getCreateTime, endTime);
        }
        wrapper.orderByDesc(AuditEventEntity::getCreateTime);
        return auditEventMapper.selectPage(new Page<>(page, size), wrapper);
    }

    private AuditEventEntity buildEvent(String action, String resourceType, String resourceId,
                                         String resourceName, String detailJson) {
        AuditEventEntity event = new AuditEventEntity();
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setResourceName(resourceName);
        event.setDetailJson(detailJson);
        event.setCreateTime(LocalDateTime.now());

        // 从 SecurityContext 获取用户信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String username = auth.getName();
            event.setUsername(username);
            UserEntity user = authService.findByUsername(username);
            if (user != null) {
                event.setUserId(user.getId());
            } else {
                event.setUserId(0L);
            }
        } else {
            event.setUsername("system");
            event.setUserId(0L);
        }

        // 从 Request 获取 IP 和 User-Agent
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                event.setIpAddress(getClientIp(request));
                event.setUserAgent(truncate(request.getHeader("User-Agent"), 256));

                // 从 header 获取 workspace ID
                String wsHeader = request.getHeader("X-Workspace-Id");
                if (wsHeader != null && !wsHeader.isBlank()) {
                    try {
                        event.setWorkspaceId(Long.parseLong(wsHeader.trim()));
                    } catch (NumberFormatException ignored) {
                        event.setWorkspaceId(1L);
                    }
                } else {
                    event.setWorkspaceId(1L);
                }
            }
        } catch (Exception ignored) {
            // 异步上下文可能无法获取 request
        }

        return event;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return truncate(ip, 64);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
