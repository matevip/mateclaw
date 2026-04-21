## ADDED Requirements

### Requirement: Converged scope MUST preserve only source-backed delegation fixes
The implementation plan for conversation multi-agent hardening MUST keep active delivery scope limited to defects that are directly observable in the current codebase and reproducible from the current runtime path.

#### Scenario: Source-backed issue enters active scope
- **WHEN** a task is listed as active implementation work in the converged RFC set
- **THEN** the task MUST cite a current source location or runtime path that demonstrates the defect
- **AND** the task MUST have a bounded fix that does not depend on speculative architectural rewrites

### Requirement: Validate-first items MUST remain gated until proof exists
Tasks whose symptom is plausible but whose proposed implementation hook is not yet proven correct MUST remain in a validation bucket rather than active implementation.

#### Scenario: Disconnect cleanup proposal lacks the correct hook
- **WHEN** an RFC proposes fixing parent SSE disconnect behavior through conversation completion callbacks
- **THEN** the task MUST be marked validate-first if the current runtime path actually disconnects through emitter detachment instead of completion
- **AND** the RFC MUST state what proof is required before implementation can start

### Requirement: Converged scope MUST exclude duplicate replay control paths
The converged change MUST NOT introduce a second approval replay control path when the current server flow already performs approval replay in the existing SSE route.

#### Scenario: Proposed replay redesign duplicates existing runtime flow
- **WHEN** a planning document proposes a new replay response contract or dedicated replay-started event
- **THEN** that work MUST be deferred or removed if the current approval route already attaches the SSE stream and starts replay on approval

### Requirement: Converged scope MUST defer stale large-scale refactors
The converged change MUST defer major architectural refactors when the underlying code has already partially implemented the proposed separation and the refactor is not required to fix the current delegation defects.

#### Scenario: AgentGraphBuilder refactor is proposed from stale assumptions
- **WHEN** an RFC proposes extracting model-construction abstractions from `AgentGraphBuilder`
- **THEN** the work MUST be deferred if the repository already routes model construction through `ProviderChatModelFactory` and protocol-specific builders
- **AND** the RFC MUST not treat that refactor as part of the active reliability patch set

### Requirement: Active implementation scope MUST include delegation-focused verification
The converged plan MUST require targeted tests for the delegation path before declaring the reliability work complete.

#### Scenario: Reliability patch set is marked ready
- **WHEN** the converged plan defines the must-do implementation set
- **THEN** it MUST include tests for delegation timeout behavior, delegation context restoration, and at least one end-to-end delegation event sequence
- **AND** completion claims MUST depend on those tests, not only on document edits
