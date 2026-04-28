package vip.mate.workspace.core.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Workspace schema bootstrap.
 * <p>
 * Ensures the default workspace (id=1, slug='default') exists, and performs a
 * <em>first-run-only</em> bootstrap of an admin owner. This is not a
 * reconciliation loop: once the default workspace has any owner, subsequent
 * startups make no membership changes — operators may legitimately remove an
 * admin from the default workspace and that decision must persist across
 * restarts (see issue #29).
 * <p>
 * Runs after {@code DatabaseBootstrapRunner} ({@code @Order(1)}).
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
     * First-run-only bootstrap: if the default workspace has no owner yet, pick
     * the lowest-id active admin and add them as owner. If an owner already
     * exists (or was deliberately removed and re-installed by an operator), do
     * nothing. If no admin exists at all, log a warning and skip — failing
     * startup here would be worse than the recoverable "no owner" state.
     */
    private void ensureDefaultWorkspaceMembership() {
        try {
            Integer ownerCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM mate_workspace_member "
                            + "WHERE workspace_id = 1 AND role = 'owner' AND deleted = 0",
                    Integer.class);
            if (ownerCount != null && ownerCount > 0) {
                return;
            }
            List<Long> adminIds = jdbcTemplate.queryForList(
                    "SELECT id FROM mate_user WHERE deleted = 0 AND role = 'admin' "
                            + "ORDER BY id ASC LIMIT 1",
                    Long.class);
            if (adminIds.isEmpty()) {
                log.warn("Default workspace has no owner and no admin user exists; "
                        + "skipping bootstrap. Operator must assign an owner manually.");
                return;
            }
            Long adminId = adminIds.get(0);
            jdbcTemplate.update(
                    "INSERT INTO mate_workspace_member "
                            + "(id, workspace_id, user_id, role, create_time, update_time, deleted) "
                            + "VALUES (?, 1, ?, 'owner', NOW(), NOW(), 0)",
                    adminId, adminId);
            log.info("Bootstrapped admin user {} as owner of default workspace", adminId);
        } catch (DataAccessException e) {
            // Defensive guard: tables may not exist yet during very early startup,
            // or the insert may collide with a soft-deleted row's primary key.
            log.debug("Skipping default workspace owner bootstrap: {}", e.getMessage());
        }
    }
}
