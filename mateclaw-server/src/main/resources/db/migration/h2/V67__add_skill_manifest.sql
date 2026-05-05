-- V67: Skill manifest_json column (RFC-090 Phase 2)
-- Stores the full parsed SKILL.md frontmatter as JSON. This becomes the
-- source of truth (RFC-090 §14.6); existing columns (skill_type, icon,
-- version, author) are kept as index projections, written by
-- SkillPackageResolver.projectManifestToColumns after each resolve.
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS manifest_json LONGTEXT;
