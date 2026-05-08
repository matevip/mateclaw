package vip.mate.workflow.draftgen;

import java.util.List;

/**
 * One named exemplar in the workflow template library.
 *
 * <p>Templates serve two purposes:
 * <ol>
 *   <li>As few-shot examples inside the system prompt — the LLM sees
 *       "here are five canonical shapes; pick the closest and adapt the
 *       fields" instead of inventing structure from scratch. RFC v0
 *       authors should never see anything more exotic than these
 *       shapes.</li>
 *   <li>As "apply template" entries the UI or the
 *       workflow_draft_generate tool can drop in directly when the
 *       user's description matches a canonical pattern (saves a
 *       generation roundtrip and stays cheaper / faster).</li>
 * </ol>
 *
 * <p>{@code matchHints} is a small bag of natural-language phrases the
 * tool can use to short-circuit to a template before calling the LLM —
 * if the user says "周一汇总" or "weekly summary" we already know which
 * shape they mean.
 */
public record WorkflowDraftTemplate(
        /** Stable kebab-case id; surfaces in the API response. */
        String id,
        /** Short bilingual label; the UI's "apply template" picker shows this. */
        String label,
        /** One-sentence description in user-facing prose. */
        String description,
        /** Natural-language phrases that should bias toward this template. */
        List<String> matchHints,
        /** Workflow draft JSON; placeholders like TODO_AGENT_ID stay
         *  in the body until the UI / tool fills them. */
        String draftJson,
        /** Trigger drafts attached to this template, if any. Stored as
         *  serialised JSON arrays so the prompt doesn't have to know
         *  about Java types. */
        String triggerDraftsJson
) {
}
