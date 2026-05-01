package vip.mate.skill.lessons.event;

/**
 * RFC-090 §14.3 — fires when a skill records a new lesson into its
 * per-skill {@code LESSONS.md} file.
 *
 * <p>Distinct from {@code MemoryWriteEvent} on purpose: lessons are
 * skill-local, agent-scoped notes rather than canonical memory writes.
 * Mixing them into the SOUL summarizer would inflate its recompute
 * count without improving the canonical memory's quality.
 *
 * <p>Default subscriber set is empty. A future "lesson → memory"
 * aggregator can subscribe and decide when (if ever) to promote
 * accumulated lessons up to MEMORY.md / SOUL.md.
 *
 * @param agentId        agent that triggered the skill call (may be null
 *                       for global / non-agent contexts)
 * @param skillId        DB id of the skill the lesson belongs to (may
 *                       be null when the runtime resolved by name only)
 * @param skillName      slug identifier of the skill (always present)
 * @param conversationId conversation in which the lesson was learned;
 *                       may be null for offline / cron-driven flows
 * @param content        lesson text exactly as recorded (caller is
 *                       responsible for any redaction / truncation)
 */
public record SkillLessonWrittenEvent(
        Long agentId,
        Long skillId,
        String skillName,
        String conversationId,
        String content
) {}
