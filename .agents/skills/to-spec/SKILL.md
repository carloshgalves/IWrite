---
name: to-spec
description: Turn resolved IWrite discussion and repository context into a durable GitHub Issue specification without re-interviewing settled decisions.
disable-model-invocation: true
---

# To Spec

Use after design/product questions are sufficiently resolved. This skill synthesizes existing decisions; it does not restart the interview.

## Gather context

Read:

- the current conversation/decision record;
- `CONTEXT.md`;
- relevant code;
- relevant parent roadmap/Issues and comments;
- relevant ADRs and quality rules.

If a material decision is still unresolved, route that question to `grill-with-docs`, `research`, or `prototype` instead of burying a guess in the spec.

## Testing seams

Before finalizing the spec, identify the stable seams that can prove its risky behavior. Use the highest stable seam that can actually prove the invariant, not the highest possible layer by dogma.

Examples:

- UI journey -> Playwright/behavior test;
- API authorization -> controller/integration seam;
- domain transaction/concurrency -> service/integration seam;
- Flyway/constraint/lock/index semantics -> real PostgreSQL seam.

## Spec shape

```markdown
## Problem Statement

The user/domain problem, not a technical task list.

## Solution

The intended behavior from the product/user perspective.

## User Stories

Numbered stories covering the actors and important states.

## Domain and Authorization Decisions

Canonical terms, ownership, Workspace/Book Role/Capability rules, revocation, privacy and non-enumerability where relevant.

## Implementation Decisions

Modules/contracts/schema/API interactions and settled technical trade-offs. Avoid brittle file-path instructions unless a path is itself part of the contract.

## Data, Concurrency and Failure Semantics

Transactions, idempotency, retries, conflicts, migrations/backfills, provider failure, or recovery behavior when relevant.

## Testing Decisions

The seams and risky invariants that must be proven, with references to existing test patterns when known.

## Observability and Privacy

Required audit/telemetry signals and data that must never be exported.

## Out of Scope

Explicit exclusions.

## Dependencies

Real blockers and related Issues.

## Further Notes

Anything durable that does not fit above.
```

## Publish

Publish as a GitHub Issue using the repository's current label taxonomy. Do not invent labels merely because an upstream workflow used them. If the work belongs under an existing roadmap/spec, link the parent.

A spec may remain one Issue if it fits one focused implementation session. Otherwise feed it to `to-tickets`.
