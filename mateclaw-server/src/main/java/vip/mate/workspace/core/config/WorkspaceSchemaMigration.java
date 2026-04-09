package vip.mate.workspace.core.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作区 Schema 迁移
 * <p>
 * 确保默认工作区（id=1, slug='default'）存在。
 * 在 DatabaseBootstrapRunner (@Order(1)) 之后执行。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class WorkspaceSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultWorkspace();
        ensureDefaultWorkspaceMembership();
    }

    /**
     * 确保默认工作区存在
     */
    private void ensureDefaultWorkspace() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM mate_workspace WHERE slug = 'default' AND deleted = 0",
                    Integer.class);
            if (count != null && count > 0) {
                return;
            }
        } catch (DataAccessException e) {
            log.debug("mate_workspace table may not exist yet: {}", e.getMessage());
            return;
        }

        try {
            jdbcTemplate.update("""
                    INSERT INTO mate_workspace (id, name, slug, description, owner_id, create_time, update_time, deleted)
                    VALUES (1, 'Default', 'default', '默认工作区', NULL, NOW(), NOW(), 0)
                    """);
            log.info("Created default workspace (id=1, slug='default')");
        } catch (DataAccessException e) {
            // 可能已存在（并发或 ID 冲突），忽略
            log.debug("Default workspace may already exist: {}", e.getMessage());
        }
    }

    /**
     * 确保所有现有用户都是默认工作区的成员
     */
    private void ensureDefaultWorkspaceMembership() {
        try {
            // 查找不在默认工作区中的用户
            int inserted = jdbcTemplate.update("""
                    INSERT INTO mate_workspace_member (id, workspace_id, user_id, role, create_time, update_time, deleted)
                    SELECT u.id, 1, u.id, u.role, NOW(), NOW(), 0
                    FROM mate_user u
                    WHERE u.deleted = 0
                      AND NOT EXISTS (
                          SELECT 1 FROM mate_workspace_member wm
                          WHERE wm.workspace_id = 1 AND wm.user_id = u.id AND wm.deleted = 0
                      )
                    """);
            if (inserted > 0) {
                log.info("Added {} existing user(s) to default workspace", inserted);
            }
        } catch (DataAccessException e) {
            log.debug("Skipping default workspace membership init: {}", e.getMessage());
        }
    }
}
