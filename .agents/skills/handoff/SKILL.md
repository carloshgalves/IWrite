---
name: handoff
description: Create a durable IWrite engineering checkpoint so another agent or fresh context can continue without rediscovering decisions.
disable-model-invocation: true
---

# Handoff

Use when work must continue in another context window, coding agent, or session.

A handoff is not a transcript summary. It is a compact continuation artifact tied to repository sources of truth.

## Include

```markdown
# Handoff: <work item>

## Goal
What this session is trying to deliver.

## Canonical sources
- originating Issue/spec;
- relevant parent roadmap;
- relevant ADRs/docs;
- branch/PR/commit state.

## Current state
What is already implemented, changed, verified, or intentionally untouched.

## Decisions made
Only decisions that are not already obvious from the linked sources, with links/pointers to where durable decisions were recorded.

## Invariants at risk
Authorization, tenant isolation, concurrency, idempotency, migration, session/cache, privacy, provider behavior, etc. that the next agent must preserve.

## Validation performed
Commands/tests actually run and their result.

## Open findings / blockers
Confirmed remaining problems, unresolved external facts, or decisions that still require human input.

## Next safe step
The smallest concrete next action that can proceed without inventing a new product decision.

## Do not redo
Investigations or approaches already disproven, with the evidence that ruled them out.
```

## Rules

- Point to `CONTEXT.md`, Issues and ADRs instead of copying large portions of them.
- Distinguish verified repository state from inference.
- Never include secrets, raw invitation tokens, private manuscript content, cookies, API keys, or sensitive logs.
- If an unresolved item requires product judgment, say so explicitly; do not frame it as an implementation TODO for the next agent to guess.
- If temporary debug/prototype artifacts remain, identify them clearly and state whether they must be removed.

Store a handoff only when persistence is useful. Prefer an Issue/PR comment or a clearly scoped repository note according to the active workflow; do not create permanent documents for every routine session.
