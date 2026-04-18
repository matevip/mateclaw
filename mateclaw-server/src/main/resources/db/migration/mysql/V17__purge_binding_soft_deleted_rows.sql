-- V17: Purge soft-deleted rows from binding tables.
-- mate_agent_tool / mate_agent_skill previously had @TableLogic on their `deleted`
-- column, but the unique indexes uk_agent_tool(agent_id, tool_name) /
-- uk_agent_skill(agent_id, skill_id) did not include `deleted`. Any rebind after
-- an unbind therefore hit a duplicate-key error because the soft-deleted row
-- still occupied the unique slot. Soft-delete has been removed for these tables
-- (see AgentToolBinding / AgentSkillBinding) — clear residual deleted=1 rows so
-- pre-existing deployments can rebind the affected pairs.
DELETE FROM mate_agent_tool WHERE deleted = 1;
DELETE FROM mate_agent_skill WHERE deleted = 1;
