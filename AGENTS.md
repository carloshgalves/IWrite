# IWrite Agent Guide

This file is the entry point for coding agents working in IWrite.

## Source of truth

The current branch code is the source of truth for implemented behavior. Current product documentation lives in `README.md` and `docs/wiki/`. Academic delivery documents are historical evidence only and must not be treated as current architecture, infrastructure, or roadmap requirements.

Before making a non-trivial change, read the relevant parts of:

1. `CONTEXT.md` for canonical domain vocabulary;
2. `docs/wiki/Architecture.md` for the current system shape;
3. `docs/wiki/Architectural-Decisions.md` for settled trade-offs;
4. `docs/wiki/Quality-and-Review.md` for testing and semantic review rules;
5. the originating GitHub Issue, including comments and dependencies.

Do not reopen an architectural decision merely because another design is fashionable. Revisit an ADR only when new evidence creates concrete friction or invalidates an assumption.

## Product and architecture invariants

- IWrite is a modular monolith. Do not introduce microservices without a separately approved architectural decision.
- The backend is authoritative for identity, active tenant/workspace context, authorization, word count, progress, and persistent mutations.
- The browser must never become authoritative for `userId`, `tenantId`, workspace membership, book role, or effective capability.
- Persona is descriptive. It never grants access.
- Workspace role and book role are different concepts.
- Book access is contextual and revocable.
- Cross-tenant and cross-book access must not leak resource existence when the current contract requires non-enumerability.
- Mutations that can be retried must preserve idempotency semantics where the domain requires them.
- Concurrency-sensitive flows must not silently overwrite newer state.
- Flyway is the only schema evolution mechanism. Never mutate production schema ad hoc.
- Critical database invariants belong in PostgreSQL constraints when practical, not only in application code.
- OpenTelemetry, Grafana/Tempo/Loki/Mimir, Umami, Spring AI, MCP, k6, auditing, and health checks are current product capabilities. Old academic accounts or hosts are not dependencies.
- MCP must remain a thin layer over existing domain services and authorization.
- AI providers are adapters. Product behavior must remain provider-neutral where the existing architecture requires it.

## Privacy and secrets

Never put manuscript text, full prompts/responses, passwords, cookies, raw invitation tokens, API keys, or secrets into logs, traces, analytics, audit records, Issues, PR descriptions, test snapshots, or research notes unless a narrowly scoped test fixture explicitly requires synthetic content.

When showing diagnostic output, redact credentials and private content.

## Working from Issues

GitHub Issues are the canonical tracker. Respect parent/child scope, dependencies, acceptance criteria, and explicit out-of-scope sections.

For large work, prefer vertical tracer-bullet tickets: each ticket should deliver a narrow end-to-end behavior through every layer it needs, instead of splitting work into disconnected `backend`, `frontend`, `database`, and `tests` tickets.

Do not silently expand an Issue. If implementation reveals additional work, keep the current change focused and record the follow-up separately.

See `docs/agents/issue-tracker.md`.

## Testing rule

Test at the highest stable seam that can actually prove the invariant.

Examples:

- UI/user journey: Playwright or frontend behavior test;
- HTTP contract/authorization: controller/integration test;
- domain rule: service/integration test through a stable interface;
- Flyway migration, FK, unique constraint, lock, query plan, or PostgreSQL-specific behavior: real PostgreSQL at the database seam.

Do not force a database invariant through HTTP merely to claim the test is more end-to-end. Conversely, do not test internal implementation details when a stable public seam proves the same behavior.

For defects, add a regression test at the seam that reproduces the real bug pattern whenever such a seam exists.

## Docker resources created for tests

Any Docker container, volume, or network created specifically for a test, review, benchmark, migration validation, or other temporary verification is ephemeral and must be removed from the developer machine when that verification finishes.

- Register cleanup before creating the resource so the failure path is covered too; use `trap`/`finally`/test-framework teardown as appropriate.
- For Docker Compose test stacks, use an isolated project name when practical and finish with the matching `docker compose ... down -v --remove-orphans`.
- For direct `docker run`, prefer `--rm` for containers and explicitly remove any named or anonymous volume created for the test.
- Never use broad cleanup such as `docker system prune` as a substitute for scoped teardown.
- Never delete a container or volume that existed before the verification. Scope cleanup using the compose project, labels, or exact resource IDs/names captured when the test creates them.
- Reusing an already-running developer service is allowed; because the test did not create it, the test must not remove it.
- Before declaring Docker-backed validation complete, verify that no container or volume created by that validation remains.

Detailed examples and failure-safe patterns live in `docs/agents/docker-test-lifecycle.md`.

## Required validation before completion

Run the smallest relevant feedback loop throughout implementation, then the appropriate broader checks before declaring work complete.

Typical checks include:

```text
Backend:   ./mvnw -s .mvn/local-settings.xml test
Frontend:  cd web && npm test
Build:     cd web && npm run build
Lint:      cd web && npm run lint   (when relevant)
Diff:      git diff --check
```

Use PostgreSQL-backed migration/integration tests for schema work. Use E2E for critical identity, collaboration, cache/session, offline/concurrency, and other user-visible cross-layer flows when risk justifies it.

A green suite is necessary but not sufficient. Review semantic invariants using `docs/wiki/Quality-and-Review.md` and the `iwrite-review` skill.

## Git discipline

- Work on a feature/fix branch unless the user explicitly requests otherwise.
- Do not force-push, reset destructively, or rewrite shared history without explicit authorization.
- Do not merge merely because tests pass.
- A coding skill may prepare commits/PRs, but repository writes must still follow the active user instruction and repository policy.

## Local skills

Project-specific and adapted engineering skills live under `.agents/skills/`. This directory is the canonical source of skill behavior.

Claude Code discovers thin provider-native bridges under `.claude/skills/`. Those bridge files must only load the matching canonical `.agents/skills/<name>/SKILL.md`; do not duplicate the procedure there. If a bridge and its canonical skill ever disagree, `.agents/skills/` wins.

Recommended flows:

```text
unclear design -> grill-with-docs -> to-spec -> to-tickets -> implement -> iwrite-review
clear issue    -> to-tickets (if too large) -> implement -> iwrite-review
bug            -> diagnosing-bugs -> implement/fix -> iwrite-review
external fact  -> research -> decision/spec
uncertain UI   -> prototype -> spec/tickets
session handoff -> handoff
```

These skills are guidance for engineering work. They do not replace the architecture, GitHub Issues, tests, ADRs, or human product decisions.
