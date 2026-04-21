## 1. Converge RFC scope

- [ ] 1.1 Rewrite `docs/rfcs/202604/01-conversation-multiagent-scheduling-review.md` so active findings are grouped into must-do, validate-first, and defer buckets.
- [ ] 1.2 Rewrite `docs/rfcs/202604/02-dev-plan-overview.md` so only source-backed must-do tasks remain in the active implementation lane.
- [ ] 1.3 Update `docs/rfcs/202604/03-lane-a-reliability-patches.md` to keep A-1 and any proven fixes, and to remove or reclassify A-4 if it duplicates the existing approval replay path.
- [ ] 1.4 Update `docs/rfcs/202604/04-lane-b-delegate-quality.md` to keep bounded timeout and context-restoration work active, while marking relay cleanup and stop propagation as validate-first until the disconnect hook is proven.
- [ ] 1.5 Update `docs/rfcs/202604/05-lane-c-refactor-tests.md` to remove `AgentGraphBuilder` large-scale refactor work from the active change and keep only the delegation-focused tests needed by the must-do fixes.

## 2. Lock scope with explicit rationale

- [ ] 2.1 Add a short rationale section to the converged docs explaining why A-4 replay redesign is deferred or dropped.
- [ ] 2.2 Add a short rationale section explaining why `AgentGraphBuilder` decomposition is deferred because builder extraction already exists in current code.
- [ ] 2.3 Add a short rationale section explaining why retention / archival work is outside the current delegation hardening change.

## 3. Define the delivery-ready implementation set

- [ ] 3.1 Produce the final must-do list: malformed `content_parts` visibility, delegation timeout bound, `DelegationContext` restoration, and delegation-focused tests.
- [ ] 3.2 Produce the final validate-first list: relay cleanup hook and USER_STOP propagation investigation.
- [ ] 3.3 Ensure every active task in the converged RFC set cites its current source file and runtime path.

## 4. Verify the convergence result

- [ ] 4.1 Re-read the rewritten RFC set and confirm no deferred task is still described as active implementation work.
- [ ] 4.2 Re-run source cross-checks for approval replay, delegation timeout, `DelegationContext`, and model-builder extraction to verify the convergence rationale still matches the code.
- [ ] 4.3 Confirm the converged docs no longer claim Lane A/B/C are conflict-free parallel lanes when they share files and test dependencies.
