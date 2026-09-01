---
name: domain-modeling
description: Sharpen IWrite domain vocabulary, reconcile it with code and Issues, and update CONTEXT.md or ADRs only when warranted.
---

# Domain Modeling for IWrite

Use this skill when a feature discussion introduces, changes, or confuses domain concepts.

## First read

Read:

- `CONTEXT.md`;
- the relevant GitHub Issue and comments;
- `docs/wiki/Architecture.md`;
- relevant sections of `docs/wiki/Architectural-Decisions.md`;
- code that currently implements the concept.

## Rules

1. **Challenge ambiguous language.** If a discussion says `role`, determine whether it means Persona, Workspace Role, Book Role, or Capability. If it says `workspace`, do not silently substitute Tenant unless technical isolation is the subject.
2. **Cross-check code.** When the conversation claims the domain behaves one way and the code behaves another, surface the mismatch before documenting either as truth.
3. **Stress-test relationships with concrete scenarios.** Especially test ownership, revocation, multi-workspace access, invitation acceptance, cross-tenant isolation, concurrency, and historical writing/progress semantics when relevant.
4. **Keep `CONTEXT.md` implementation-independent.** It is a glossary, not a schema, class map, endpoint list, or feature spec.
5. **Update terms when they become settled.** Avoid batching glossary fixes until the end of a long design session.
6. **Use ADRs sparingly.** Record or propose an ADR only when the decision is hard to reverse, surprising without context, and the result of a real trade-off.
7. **Do not rewrite settled ADRs for style.** Reopen them only when new evidence makes the existing decision materially wrong or costly.

## IWrite concepts that must remain distinct

- User vs Persona;
- Workspace vs Tenant isolation;
- Workspace Membership vs Book Collaborator;
- Workspace Role vs Book Role;
- Book Role vs Capability;
- Book Owner vs collaborator;
- invitation offer vs accepted access;
- current manuscript size vs Productive Writing vs Manuscript Adjustment;
- Scene vs Scene Version;
- domain rules vs MCP/LLM provider adapters.

## Completion

A domain-modeling pass is complete when:

- the relevant terms have one precise meaning;
- code, Issue language, and `CONTEXT.md` no longer contradict each other without an explicitly documented migration/transition;
- any newly required hard-to-reverse trade-off has been identified for ADR treatment;
- no implementation detail has leaked into the glossary merely because it is convenient today.
