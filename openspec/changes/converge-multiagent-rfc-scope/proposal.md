## Why

The April 21 multi-agent RFC bundle mixes real reliability defects with speculative refactors and duplicated control paths. That makes it hard to tell which work is required to stabilize the current delegation flow and which work is architectural cleanup that should be deferred.

We need a single scoped change that preserves the must-fix items, explicitly marks validate-first items, and removes work that is no longer justified by the current codebase.

## What Changes

- Converge the five RFC documents into a single scoped implementation boundary for conversation delegation hardening.
- Keep only the fixes that address demonstrated runtime defects in the current code:
  - malformed `content_parts` visibility
  - bounded delegation timeout
  - `DelegationContext` parent context restoration
  - missing delegation-focused tests
- Reclassify relay cleanup and USER_STOP propagation as validate-first work items that require source-backed proof before implementation is approved.
- Remove or defer work that is currently overdesigned or stale relative to the codebase:
  - approval replay API / SSE redesign
  - `AgentGraphBuilder` large-scale interface extraction
  - retention / archival governance unrelated to the delegation defects

## Capabilities

### New Capabilities
- `conversation-delegation-hardening`: Defines the minimum reliability and verification requirements for the current multi-agent delegation flow, including explicit non-goals for over-scoped refactors.

### Modified Capabilities
- None.

## Impact

- Affects RFC and implementation planning under `docs/rfcs/202604/`.
- Affects future code changes in `mateclaw-server` conversation, delegation, and test modules.
- Does not introduce new runtime APIs by itself; it narrows scope and sets implementation gates.
