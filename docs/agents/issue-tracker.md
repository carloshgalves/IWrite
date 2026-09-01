# GitHub Issue Tracker Conventions

GitHub Issues are the canonical tracker for IWrite planning and implementation work.

## Existing taxonomy

The repository already uses multidimensional labels such as:

- `priority:*` — relative urgency/importance;
- `status:*` — lifecycle state;
- `type:*` — epic, feature, bug, and related work types;
- `area:*` — product/technical area such as collaboration.

Do not replace this taxonomy with a generic third-party label set. Before adding or changing labels, inspect which labels actually exist in the repository and preserve the current conventions.

## Issue hierarchy

Large roadmap/epic Issues may act as canonical specifications. Smaller implementation Issues should reference their parent and preserve the parent's invariants and out-of-scope constraints.

Do not close or rewrite a parent Issue merely because it was decomposed into smaller tickets.

## Agent-ready ticket shape

A small implementation ticket should normally contain:

```markdown
## Parent

Reference to the originating Issue/spec when applicable.

## What to build

The narrow end-to-end behavior this ticket makes work.

## Acceptance criteria

- [ ] Observable criterion 1
- [ ] Observable criterion 2

## Blocked by

- References to genuinely blocking Issues, or `None`.

## Validation

- The behavioral seams/invariants that must be proven.

## Out of scope

- Explicit exclusions when they prevent scope creep.
```

Avoid hard-coding file paths and implementation snippets into tickets unless a specific shape is itself a decided contract. Paths become stale quickly.

## Vertical slicing

Prefer tracer-bullet slices that deliver a narrow complete behavior through all layers the behavior actually needs.

Bad default decomposition:

```text
1. database
2. backend
3. frontend
4. tests
```

Preferred decomposition:

```text
1. one safe user/domain behavior, including its schema/API/UI/tests as needed
2. the next independently verifiable behavior
3. the next behavior
```

Wide mechanical refactors are an exception. For a rename or representation change with a large blast radius, prefer expand -> migrate in safe batches -> contract while keeping the main branch buildable.

## Dependencies

`Blocked by` means the ticket cannot safely start until the referenced work is complete. Do not use dependencies merely to express preferred ordering.

The implementation frontier is the set of open tickets whose true blockers are done.

## Relationship to existing roadmap

When an Issue such as the identity/collaboration roadmap already defines a sequence and invariants, decomposition must respect that sequence instead of inventing a competing plan.

## Triage

Before declaring an Issue ready for autonomous implementation:

1. confirm the behavior is not already implemented;
2. read the full Issue and relevant comments;
3. read `CONTEXT.md` and relevant ADRs;
4. identify unresolved product/domain decisions;
5. identify the testing seams needed to prove the risky invariants;
6. split the Issue if it cannot fit safely in one focused implementation session.

If material decisions are still open, use `grill-with-docs` or `research` before implementation.
