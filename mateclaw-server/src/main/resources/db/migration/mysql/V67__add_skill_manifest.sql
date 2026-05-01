-- V67: Skill manifest_json column (RFC-090 Phase 2)
-- Stores the full parsed SKILL.md frontmatter as JSON. This becomes the
-- source of truth (RFC-090 §14.6); existing columns (skill_type, icon,
-- version, author) are kept as index projections, written by
-- SkillPackageResolver.projectManifestToColumns after each resolve.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND COLUMN_NAME = 'manifest_json');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill ADD COLUMN manifest_json LONGTEXT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
