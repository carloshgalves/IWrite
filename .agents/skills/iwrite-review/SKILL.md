---
name: iwrite-review
description: Review an IWrite diff against the originating spec, repository engineering standards, and IWrite-specific semantic invariants.
---

# IWrite Review

Use for a PR, branch, or completed implementation before merge/release decisions.

This review has three independent axes. Do not let a pass on one axis hide a failure on another.

## 1. Establish the comparison and source spec

Identify:

- fixed point / merge base;
- commits and changed files;
- originating Issue/spec and parent roadmap when applicable;
- relevant `AGENTS.md`, `CONTEXT.md`, ADRs, architecture and `docs/wiki/Quality-and-Review.md`.

If there is no spec, say so explicitly rather than inventing one after implementation.

## Axis A — Spec fidelity

Check:

- acceptance criteria missing or only partially implemented;
- behavior that contradicts the Issue;
- scope creep not requested by the Issue;
- explicit out-of-scope work accidentally included;
- dependency assumptions violated;
- UX states or actors described by the spec but omitted;
- implementation that appears complete but changes the requested semantics.

For each finding, cite the spec/Issue requirement and the relevant changed behavior.

## Axis B — Engineering quality

Check the diff for maintainability and test quality, including:

- duplicated or scattered domain logic;
- abstractions that merely pass through and add no leverage;
- provider/environment coupling that should remain behind an adapter;
- implementation details leaking through interfaces;
- tests coupled to internals instead of behavior;
- tautological assertions or false-green mocks;
- unnecessary speculative generality;
- unrelated refactors hidden in feature work;
- missing error/loading/empty states;
- avoidable N+1 or obviously expensive critical-path work;
- missing documentation when behavior/configuration changed materially.

Repository conventions and ADRs override generic style preferences.

## Axis C — IWrite semantic invariants

This axis is mandatory for any affected surface.

### Identity, tenancy and authorization

- browser-controlled `userId`/`tenantId`/role/capability trusted as authority;
- Persona accidentally used as authorization;
- Workspace Role confused with Book Role;
- book access granted by generic membership without the required contextual relationship;
- revoked users retaining access;
- cross-tenant/cross-book enumeration or data leakage;
- frontend hiding a control without backend enforcement.

### Concurrency, retry and history

- silent lost update or stale revision acceptance;
- retry duplicating mutation, ledger, invitation, notification, audit or external effect;
- `operationId`/fingerprint semantics weakened where they apply;
- locks acquired in unsafe/inconsistent order;
- concurrent invitation/registration/workspace transitions producing duplicates;
- immutable historical state accidentally reinterpreted from current timezone/configuration;
- restoration/versioning corrupting current manuscript/history semantics.

### Database and migrations

- invariant enforced only in Java when PostgreSQL can and should protect it;
- missing/unsafe FK, unique/check constraint or index;
- migration that works only on empty databases;
- nondeterministic or unsafe backfill;
- destructive/nullability change without compatibility plan;
- migration/lock that can block startup or production traffic unexpectedly;
- missing real-PostgreSQL test for PostgreSQL-specific behavior.

### Session and frontend state

- stale query/mutation repopulating old identity/workspace data;
- unsafe workspace switch ordering;
- cross-tab state diverging after login/logout/workspace change;
- late autosave or asynchronous callback acting on the wrong Scene/User/Workspace;
- optimistic UI presenting success before server authorization/transaction outcome is safely known.

### Privacy, AI and observability

- manuscript/private title/email/raw IDs/secrets entering analytics unexpectedly;
- full prompt/response/manuscript stored in audit or logs;
- API key/token/cookie/password leaked to logs, traces, tests, Issues, or errors;
- free-text/high-cardinality values exported to controlled telemetry dimensions;
- handled errors exporting sensitive stack traces contrary to policy;
- provider-specific behavior leaking through the Writing Assistant/domain contract;
- optional provider disabled mode breaking application startup unnecessarily;
- LLM execution bypassing existing authorization/audit/cost/latency controls where applicable.

### MCP and integrations

- MCP duplicating domain rules instead of reusing services;
- MCP bypassing book/tenant authorization;
- unsafe transport/configuration assumptions widened silently;
- external effects without idempotency/retry/failure semantics where required.

## Severity

Use the repository severity baseline:

- **Blocker**: data loss/corruption, cross-tenant access, fatal migration, exposed secret, unusable primary flow;
- **High**: authorization bypass, lost update, duplicate external/domain effect, severe session race, missing essential constraint, meaningful private-data leak;
- **Medium**: contract inconsistency, relevant test gap, normal-use performance issue, stale UI state, operationally dangerous docs/config;
- **Low**: concrete improvement with limited functional risk.

## Output

Report findings grouped under:

1. `Spec`;
2. `Engineering`;
3. `IWrite invariants`.

For each finding include severity, location, evidence, impact, and the smallest safe correction.

Do not merge the axes into a single vague score. A change can be well-written and still implement the wrong thing, or satisfy the Issue while violating tenant isolation.

If no findings exist on an axis, state that explicitly and mention what was checked.
