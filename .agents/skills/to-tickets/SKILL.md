---
name: to-tickets
description: Break an IWrite spec or large Issue into dependency-aware vertical tracer-bullet GitHub Issues sized for focused implementation sessions.
disable-model-invocation: true
---

# To Tickets

Use when a spec/Issue is too large to implement safely in one focused session.

## 1. Read the source completely

Read the parent Issue/spec, comments, `CONTEXT.md`, relevant ADRs, architecture, quality rules, and enough code to understand existing seams and prior art.

Do not decompose a stale mental model.

## 2. Prefer vertical tracer bullets

Each ticket should deliver a narrow, observable behavior through every layer it actually requires.

A good slice is independently demoable or verifiable and can land without leaving the main branch intentionally broken.

Avoid default horizontal slices such as:

```text
database -> backend -> frontend -> tests
```

Instead prefer behavior slices such as:

```text
recipient can safely preview a pending invitation
recipient can accept a valid invitation atomically
accepted book appears in the shared library
owner can revoke access and the user immediately loses capability
```

Each slice may include migration, backend, frontend and tests if that behavior needs them.

## 3. Preserve dangerous invariants

When splitting work, never create an early ticket that temporarily weakens:

- tenant/book isolation;
- authorization;
- idempotency;
- optimistic concurrency;
- migration compatibility;
- audit/privacy rules;
- session/cache safety.

If an expand/migrate/contract sequence is needed, make the compatibility strategy explicit.

## 4. Size tickets by context and blast radius

A ticket should be understandable and implementable from a fresh context after reading its parent and repository guidance.

Split when one ticket combines unrelated decisions, large schema change + unrelated UX, multiple independently risky concurrency models, or too many acceptance paths.

Do not split merely to produce more Issues.

## 5. Declare true blockers

For each ticket list only work that genuinely prevents safe implementation. Distinguish a blocker from preferred ordering.

Work the frontier: tickets whose blockers are all complete can proceed.

## 6. Wide refactors

A mechanical change with a large blast radius may use:

```text
expand -> migrate safe batches -> contract
```

Keep compatibility during migration batches whenever practical. Do not force a repository-wide rename into artificial vertical product slices.

## 7. Ticket template

Use `docs/agents/issue-tracker.md`. Every generated implementation ticket should include:

- Parent;
- What to build;
- Acceptance criteria;
- Blocked by;
- Validation;
- Out of scope when useful.

Avoid brittle file paths and speculative code snippets.

## 8. Approval and publishing

If the user is actively collaborating, show the proposed breakdown before publishing when meaningful judgment is involved. If the parent Issue already dictates the decomposition clearly, do not re-ask decisions that are settled.

Publish GitHub Issues using existing repository label conventions. Do not modify or close the parent Issue as a side effect of decomposition.
