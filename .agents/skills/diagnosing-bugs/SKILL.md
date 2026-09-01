---
name: diagnosing-bugs
description: Diagnose IWrite bugs and regressions with a reproducible feedback loop before changing production code.
---

# Diagnosing Bugs in IWrite

Use for defects, flaky behavior, failed tests, regressions, races, incorrect data, or performance degradation.

## Rule zero

Do not begin with a code-change guess. First build the tightest practical feedback loop that can detect the user's exact symptom.

Read `CONTEXT.md`, relevant ADRs, the originating Issue/report, recent changes in the affected area, and `docs/wiki/Quality-and-Review.md`.

Redact secrets and private manuscript/user content from all diagnostic artifacts.

## Phase 1 — Reproduce

Prefer, in order appropriate to the bug:

- a focused failing test;
- controller/service/integration invocation;
- Playwright/user-flow reproduction;
- real PostgreSQL reproduction for schema/lock/query behavior;
- a minimal HTTP/CLI harness;
- deterministic concurrency harness;
- comparison against a known-good commit/configuration.

The loop must detect the actual reported symptom, not merely any nearby exception.

For flaky/race bugs, improve the reproduction rate with deterministic barriers/latches, repeated triggers, controlled timing, or isolated state. Avoid using arbitrary sleeps as the main proof mechanism.

## Phase 2 — Minimize

Reduce the reproduction to the smallest scenario that still fails. Remove inputs, actors, data, integrations, and timing conditions one at a time so the remaining factors are load-bearing.

## Phase 3 — Hypotheses

Create a short ranked list of falsifiable hypotheses. For each one state what observation would support or refute it.

Pay special attention to historical IWrite bug classes:

- late autosave/stale callback;
- stale React Query/session/workspace state;
- cross-tab reconciliation;
- timezone/history reinterpretation;
- idempotency without payload identity;
- concurrency/locking/duplicate creation;
- CORS/proxy/environment mismatch;
- provider/MCP dependency cycles;
- telemetry data/cardinality leakage;
- superficial health checks;
- Flyway/backfill/constraint behavior that differs on real PostgreSQL.

## Phase 4 — Instrument narrowly

Instrument only where a measurement distinguishes hypotheses. Prefer debugger/test state or targeted structured diagnostics to broad logging.

Never log manuscript text, tokens, credentials, cookies, API keys, full prompts/responses, or unrelated user data.

Temporary debug instrumentation must be clearly identifiable and removed before completion.

For performance issues, establish a measurable baseline before changing code. Use query plans, timings, traces, or load scenarios appropriate to the suspected layer.

## Phase 5 — Regression proof and fix

When a correct stable seam exists:

1. turn the minimized reproduction into a failing regression test;
2. confirm it fails for the intended reason;
3. implement the smallest safe fix;
4. confirm the regression test passes;
5. rerun the original reproduction, not only the minimized version.

If no correct seam can reproduce the real failure pattern, record that as an architectural/testability finding instead of adding a false-green test.

## Phase 6 — Semantic review

Run `iwrite-review` on the final diff. A local bug fix must not introduce a tenant leak, retry duplication, migration regression, privacy leak, or unrelated refactor.

Remove temporary artifacts and summarize the confirmed root cause and regression proof in the PR/Issue context.
