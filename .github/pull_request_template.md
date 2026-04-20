## Dream v2 PR Checklist

- [ ] Only modifies current Phase scope; no future Phase undecided designs introduced
- [ ] Feature flag defaults to off
- [ ] `scripts/rfc-lint.sh` passes locally
- [ ] New Flyway migrations exist in both h2/ and mysql/ directories
- [ ] New `@Scheduled` cron expressions documented in application.yml with stagger rationale
- [ ] Phase 1 PR: `recall_count / daily_count` regression test passes
- [ ] Phase 3 PR: `mate_fact` two-write-path SQL-level guard test passes

Related RFC: <!-- rfc-035 P1-S{x} / rfc-037 P1-S{x} / ... -->
