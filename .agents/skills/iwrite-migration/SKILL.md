---
name: iwrite-migration
description: Design, implement, and verify IWrite Flyway/PostgreSQL schema changes against real legacy states, invariants, concurrency, and operational safety.
---

# IWrite Migration

Use for any non-trivial Flyway migration, schema constraint change, backfill, data-shape transition, index change, or persistence migration.

Read `AGENTS.md`, `CONTEXT.md`, `docs/wiki/Database-Migrations.md`, relevant ADRs, the originating Issue, and current entity/repository/schema code first.

## 1. State the invariant and transition

Before SQL, write down:

- the domain invariant being introduced or changed;
- the current persisted states that may exist;
- the desired final persisted states;
- whether application versions before/after the migration must coexist during rollout;
- what invalid or ambiguous legacy data could block the change.

Do not create a migration merely to mirror a Java refactor that does not require persisted-schema change.

## 2. Inspect legacy reality

A migration must be reasoned about from the relevant previous schema/data state, not only from a clean database.

Check:

- nulls and duplicates allowed by the previous schema;
- rows created by historical features/backfills;
- cross-tenant relationships and ownership assumptions;
- enum/string values already persisted;
- indexes and constraints already present;
- table size/growth characteristics when lock duration matters.

## 3. Prefer safe transitions

For risky representation/nullability/constraint changes, consider expand -> backfill/migrate -> constrain/contract instead of one destructive step.

Backfills must be deterministic. Do not pick arbitrary duplicate rows or rely on unspecified row order.

Do not silently elevate or reduce user permissions while translating legacy collaboration/role data.

## 4. Database invariants

Use PostgreSQL constraints where practical for invariants that must remain true even if application code has a bug, especially:

- tenant/book relationship integrity;
- uniqueness under concurrency;
- valid state combinations;
- ownership/collaboration references;
- required relationships after backfill.

Application validation remains useful for UX/errors, but it does not replace durable database integrity.

## 5. Concurrency and operations

Consider:

- table/row locks and expected duration;
- index creation strategy;
- concurrent writes during migration if production topology allows them;
- startup failure behavior;
- transaction size;
- rollback/recovery strategy even when Flyway is forward-only;
- whether a release must be staged around the migration.

Do not assume an empty or tiny database merely because development data is small.

## 6. Required tests

For critical migrations:

1. provision a fresh isolated PostgreSQL instance/container for this validation run; never reuse a pre-existing developer database or Docker container;
2. migrate that fresh PostgreSQL database to the relevant previous version;
3. insert representative valid legacy data plus edge cases;
4. apply the new migration;
5. verify transformed data;
6. verify new constraints/indexes;
7. attempt invalid direct SQL writes that the database should now reject;
8. verify the current application can start/use the migrated schema as appropriate.

Also verify a separate fresh database can migrate from zero to head.

Use deterministic concurrency tests where races/uniqueness/locks are part of the invariant.

If PostgreSQL or supporting infrastructure is started in Docker for validation, it must be newly created for that run with fresh test-owned storage. Register cleanup before startup, do not use reusable Testcontainers or an existing Compose/dev stack, and remove all test-created containers and volumes when validation finishes. Follow `docs/agents/docker-test-lifecycle.md`; the cleanup requirement applies on failing migration tests too.

## 7. Review

Before completion run `iwrite-review` with special attention to:

- cross-tenant integrity;
- privilege preservation;
- deterministic backfill;
- nullability/default semantics;
- indexes and query effects;
- migration from prior state;
- production lock/startup risk;
- forward recovery plan;
- no pre-existing database/container reused for validation;
- no Docker container or volume created for validation left behind.

Document operationally significant migration behavior in the Issue/PR and update migration documentation/ADR only when the change introduces durable new rules.
