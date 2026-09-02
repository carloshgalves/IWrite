---
name: implement
description: Implement an approved IWrite Issue/spec/ticket with focused feedback loops, repository-specific validation, and review before any merge decision.
disable-model-invocation: true
---

# Implement

Use only when the behavior is sufficiently specified.

## Before editing

1. Read `AGENTS.md`, `CONTEXT.md`, the originating Issue/spec, relevant ADRs, and quality rules.
2. Inspect current code and tests in the affected area.
3. Identify the stable seams to validate the change.
4. Identify dangerous invariants: authorization, tenant isolation, concurrency, idempotency, migrations, session/cache state, privacy, provider failure, or recovery.
5. If implementation still requires a product/domain decision, stop that branch of work and route the decision to `grill-with-docs`, `research`, or `prototype` instead of guessing.

## Implementation loop

Work in the smallest useful vertical slice:

1. establish a focused failing/verification signal when practical;
2. make the smallest coherent change that delivers the behavior;
3. run focused tests immediately;
4. continue to the next slice only after the previous one is understood and green;
5. refactor only when it improves the current change or removes proven friction, not as speculative cleanup.

Use the `tdd` skill for behavior that benefits from red-green development.

## Database work

For schema changes:

- use Flyway only;
- test critical migrations against real PostgreSQL;
- validate upgrade from the relevant previous version with representative legacy data when risk warrants it;
- consider constraints, indexes, backfills, locking, nullability, duplicate/invalid historical rows, and startup failure modes;
- do not weaken isolation or authorization while an expand/migrate/contract sequence is in progress.

## Frontend work

For identity/workspace/session-sensitive UI:

- account for stale queries and mutations;
- preserve current cache invalidation/reconciliation principles;
- do not use hidden UI controls as the authorization boundary;
- cover loading, empty, error, revoked, and stale-response states when relevant.

## AI/integration work

- keep provider details behind existing seams/adapters;
- preserve optional-provider behavior where currently required;
- do not log or audit private prompt/manuscript content;
- preserve cost/latency/status observability through the existing LLM gateway where applicable.

## Validation

Run focused checks repeatedly, then the broader relevant suite. Typical commands are documented in `AGENTS.md` and `docs/wiki/Quality-and-Review.md`.

If validation creates Docker containers, volumes, or networks, register scoped teardown before startup and follow `docs/agents/docker-test-lifecycle.md`. Completion requires those test-created resources to be removed even when the validation fails; never delete pre-existing developer resources.

Before completion:

- run `git diff --check`;
- inspect the final diff for unrelated changes;
- verify no Docker container or volume created by the validation remains;
- run `iwrite-review` against the originating spec/Issue;
- fix valid findings and add regression tests when appropriate;
- document remaining known risk or explicit out-of-scope work.

## Git behavior

Do not assume that finishing implementation authorizes merge, force-push, history rewrite, or direct writes to the protected/default branch. Follow the user's current instruction and repository policy.
