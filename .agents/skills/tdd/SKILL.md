---
name: tdd
description: Use red-green development for IWrite at the highest stable seam that can actually prove the behavior or invariant.
---

# TDD for IWrite

TDD is useful when a precise observable behavior can be made red before implementation. It is not a rule that every change must begin with an HTTP or UI test.

## Choose the proof seam first

Pick the highest stable seam that can actually prove the invariant:

- Playwright/user flow for critical cross-layer UX;
- frontend behavior tests for component/query/cache behavior;
- controller/integration tests for HTTP contracts and authorization;
- service/integration tests for domain transactions and concurrency;
- real PostgreSQL for migrations, constraints, locks, indexes, and SQL-specific invariants.

Do not mock away the behavior you are trying to prove. Do not query implementation internals from a high-level test merely to assert that a private method ran.

## Red -> green -> clarify

For each narrow behavior slice:

1. Write or adapt one test/reproduction that fails for the intended reason.
2. Run it and confirm the signal is actually red.
3. Implement only enough coherent behavior to make it pass.
4. Run the focused test again.
5. Keep the code readable and remove duplication introduced by the slice when doing so does not expand scope.
6. Move to the next behavior.

Avoid writing a large imagined test suite before learning from the first implementation slice.

## Docker-backed tests

Database-backed or Docker-backed test runs must provision fresh isolated infrastructure for that run. Never reuse an already-running developer database, PostgreSQL container, Compose stack, Docker container, or pre-existing test volume.

- Create the required database/container stack before the test starts; CI service containers or Testcontainers are valid when they are fresh for the run.
- Do not enable reusable Testcontainers for test-only infrastructure.
- Isolate test-created containers/volumes/networks from every pre-existing developer resource.
- Register cleanup before starting the resources so red tests and command failures still tear them down.
- Remove every container and volume created for the test before considering the run complete.
- Follow `docs/agents/docker-test-lifecycle.md`; never use broad prune commands to satisfy this rule.

A green assertion that ran on shared/pre-existing database state or leaked test containers/volumes is not a complete green cycle.

## Good tests

Prefer tests that:

- express user/domain behavior and invariants;
- survive internal refactoring;
- use independent expected values rather than recomputing the implementation;
- exercise the real authorization/tenant/concurrency path when that is the risk;
- use deterministic synchronization for races rather than sleeps when practical;
- use representative legacy data for risky migrations;
- prove privacy by using synthetic canary secrets/private values where appropriate.

## IWrite regression priorities

When relevant, explicitly cover:

- authorized vs unauthorized vs other-tenant vs nonexistent resource;
- revocation;
- duplicate/retry behavior;
- stale revision/lost-update behavior;
- concurrent acceptance/update;
- cross-tab/session/cache reconciliation;
- migration from prior schema state;
- audit/log/trace/analytics data minimization;
- provider disabled/unavailable behavior.

## When not to force TDD

Do not invent a brittle test seam for:

- pure documentation changes;
- exploratory prototypes that will be discarded;
- mechanical changes already fully enforced by tooling;
- external configuration steps that cannot be reproduced locally.

For a bug, if no correct seam can reproduce the actual failure pattern, treat the missing seam as an architectural finding rather than adding a false-green test.
