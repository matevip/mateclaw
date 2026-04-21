## Context

The current repository already contains a functioning `DelegateAgentTool`, approval replay flow, and partial builder extraction around chat model construction. The reviewed RFC set correctly identifies several concrete issues in this area, but it also proposes changes that either duplicate existing runtime behavior or assume an older architecture than the one now present in `dev`.

The convergence work is therefore a scoping exercise first, not a pure implementation exercise. The design must separate:

1. defects that are observable in the current code,
2. hypotheses that still need validation, and
3. refactors that are not required to fix the current behavior.

## Goals / Non-Goals

**Goals:**
- Preserve the fixes that directly address demonstrated defects in the current delegation and conversation flow.
- Prevent speculative or stale architectural work from entering the active implementation queue.
- Create a single execution-ready task list that future work can follow without reopening the scope debate.
- Require proof before shipping fixes for areas where the reviewed RFCs identified the right symptom but proposed the wrong hook.

**Non-Goals:**
- Re-architect the entire conversation or agent graph subsystem.
- Introduce new approval replay APIs or additional replay event families.
- Perform retention / archival governance work in the same change as delegation hardening.
- Force a full `AgentGraphBuilder` decomposition as part of this convergence.

## Decisions

### 1. Split the RFC bundle into three buckets: must-do, validate-first, and defer

The review found that not all proposed tasks have the same evidence level. The converged scope therefore uses three delivery classes:

- **Must-do:** work items with direct source-backed defects and bounded fixes.
- **Validate-first:** tasks where the symptom is plausible, but the proposed implementation hook is wrong or incomplete.
- **Defer:** tasks that are either stale, duplicative, or unrelated to the current delegation defect cluster.

**Alternatives considered**
- Keep the lane structure and only reprioritize tasks. Rejected because it still implies all lane items belong to the same implementation program.
- Drop the RFC set entirely. Rejected because several identified defects are real and worth fixing.

### 2. Keep the active implementation scope minimal

The active implementation scope is limited to:

- malformed `content_parts` surfacing (`parse_error`)
- reducing delegation parallel timeout to a sane bound
- fixing `DelegationContext.exit()` restoration semantics
- adding focused unit/integration/E2E coverage around delegation behavior

This keeps the change aligned with current runtime risk instead of speculative cleanup.

**Alternatives considered**
- Include relay cleanup and USER_STOP propagation immediately. Rejected because the current RFC text binds cleanup to `complete()` semantics, while the actual disconnect path goes through emitter detachment and needs validation first.
- Include data retention work. Rejected because it is governance-oriented and not required to make delegation reliable.

### 3. Mark approval replay redesign and AgentGraphBuilder refactor as explicit non-goals

The repository already contains an approval replay path in `ChatController`, and model construction has already been partially extracted into `ProviderChatModelFactory` and protocol-specific builders. Continuing to plan a new replay API and a fresh factory/binder abstraction in this change would recreate control planes that already exist.

**Alternatives considered**
- Keep them as phase-2 tasks in the same change. Rejected because they would keep implementation pressure alive for work that has not earned a place in the current reliability patch set.

## Risks / Trade-offs

- **[Risk] Validate-first tasks may still hide a real production issue** → Mitigation: keep them in the change as gated investigations with concrete proof requirements instead of dropping them silently.
- **[Risk] Teams may interpret “defer” as “never revisit”** → Mitigation: record explicit defer reasons in the converged RFC set so future reactivation requires new evidence.
- **[Risk] Test work may uncover additional defects and expand scope** → Mitigation: allow bug fixes discovered by the new tests only when they are required to make the must-do tasks pass.

## Migration Plan

1. Rewrite the five RFC documents so they share one converged scope statement.
2. Move tasks into the three buckets:
   - must-do
   - validate-first
   - defer/drop
3. Update implementation sequencing so only the must-do items are considered active delivery work.
4. Add tests alongside the must-do fixes.
5. Re-evaluate validate-first tasks only after the bounded fixes and tests are merged.

Rollback is documentation-only at this stage: revert the converged RFC edits if the team rejects the narrower scope.

## Open Questions

- What concrete telemetry or repro will be used to prove whether parent SSE disconnect should cancel child agent execution, not just stop relay forwarding?
- Should the validate-first relay cleanup investigation target subscriber detachment, stop propagation, or both?
- Do we want one converged RFC summary document in addition to the rewritten lane docs, or are edits to the five existing docs sufficient?
