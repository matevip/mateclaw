package vip.mate.tool.guard.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ToolGuard 表 Schema 迁移
 */
@Slf4j
@Component
@Order(100) // 在 ApprovalSchemaMigration 之后
@RequiredArgsConstructor
public class ToolGuardSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        createGuardRuleTable();
        createGuardConfigTable();
        createAuditLogTable();
        migrateGuardConfigAuditColumns();
    }

    private void createGuardRuleTable() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mate_tool_guard_rule (
                    id              BIGINT       NOT NULL PRIMARY KEY,
                    rule_id         VARCHAR(64)  NOT NULL UNIQUE,
                    name            VARCHAR(128) NOT NULL,
                    description     TEXT,
                    tool_name       VARCHAR(128),
                    param_name      VARCHAR(128),
                    category        VARCHAR(64)  NOT NULL,
                    severity        VARCHAR(16)  NOT NULL,
                    decision        VARCHAR(16)  NOT NULL DEFAULT 'NEEDS_APPROVAL',
                    pattern         VARCHAR(512) NOT NULL,
                    exclude_pattern VARCHAR(512),
                    remediation     TEXT,
                    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
                    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
                    priority        INT          NOT NULL DEFAULT 100,
                    create_time     DATETIME     NOT NULL,
                    update_time     DATETIME     NOT NULL,
                    deleted         INT          NOT NULL DEFAULT 0
                )
                """);
            log.info("[ToolGuardSchemaMigration] mate_tool_guard_rule table ready");
        } catch (Exception e) {
            log.warn("[ToolGuardSchemaMigration] Failed to create mate_tool_guard_rule: {}", e.getMessage());
        }
    }

    private void createGuardConfigTable() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mate_tool_guard_config (
                    id                   BIGINT       NOT NULL PRIMARY KEY,
                    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
                    guard_scope          VARCHAR(32)  NOT NULL DEFAULT 'all',
                    guarded_tools_json   TEXT,
                    denied_tools_json    TEXT,
                    file_guard_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
                    sensitive_paths_json TEXT,
                    create_time          DATETIME     NOT NULL,
                    update_time          DATETIME     NOT NULL
                )
                """);
            log.info("[ToolGuardSchemaMigration] mate_tool_guard_config table ready");
        } catch (Exception e) {
            log.warn("[ToolGuardSchemaMigration] Failed to create mate_tool_guard_config: {}", e.getMessage());
        }
    }

    private void createAuditLogTable() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mate_tool_guard_audit_log (
                    id                  BIGINT       NOT NULL PRIMARY KEY,
                    conversation_id     VARCHAR(128),
                    agent_id            VARCHAR(64),
                    user_id             VARCHAR(64),
                    channel_type        VARCHAR(32),
                    tool_name           VARCHAR(128) NOT NULL,
                    tool_params_json    TEXT,
                    decision            VARCHAR(16)  NOT NULL,
                    max_severity        VARCHAR(16),
                    findings_json       TEXT,
                    pending_id          VARCHAR(32),
                    replay_payload_hash VARCHAR(64),
                    create_time         DATETIME     NOT NULL,
                    update_time         DATETIME     NOT NULL,
                    deleted             INT          NOT NULL DEFAULT 0
                )
                """);

            safeExecute("CREATE INDEX IF NOT EXISTS idx_guard_audit_conv ON mate_tool_guard_audit_log(conversation_id)");
            safeExecute("CREATE INDEX IF NOT EXISTS idx_guard_audit_time ON mate_tool_guard_audit_log(create_time)");

            log.info("[ToolGuardSchemaMigration] mate_tool_guard_audit_log table ready");
        } catch (Exception e) {
            log.warn("[ToolGuardSchemaMigration] Failed to create audit log table: {}", e.getMessage());
        }
    }

    /**
     * 为 mate_tool_guard_config 补充审计配置列（向已有表兼容迁移）
     */
    private void migrateGuardConfigAuditColumns() {
        safeAddColumn("mate_tool_guard_config", "audit_enabled", "BOOLEAN NOT NULL DEFAULT TRUE");
        safeAddColumn("mate_tool_guard_config", "audit_min_severity", "VARCHAR(16) NOT NULL DEFAULT 'INFO'");
        safeAddColumn("mate_tool_guard_config", "audit_retention_days", "INT NOT NULL DEFAULT 90");
        log.info("[ToolGuardSchemaMigration] audit columns migration done");
    }

    private void safeAddColumn(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table.toUpperCase(), column.toUpperCase());
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception e) {
            log.debug("[ToolGuardSchemaMigration] Column migration skipped: {}", e.getMessage());
        }
    }

    private void safeExecute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("[ToolGuardSchemaMigration] SQL may already exist: {}", e.getMessage());
        }
    }
}
