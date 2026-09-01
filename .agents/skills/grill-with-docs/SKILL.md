---
name: grill-with-docs
description: Resolve an unclear IWrite design through focused questioning while keeping domain vocabulary and durable decisions synchronized.
disable-model-invocation: true
---

# Grill With Docs

Use when a feature, workflow, or architecture direction is still ambiguous enough that implementation would force the agent to invent product decisions.

## Process

1. Read `CONTEXT.md`, the relevant Issue/roadmap, current architecture, relevant ADRs, and the code that constrains the decision.
2. Identify the smallest unresolved decision tree. Do not ask questions whose answers are already in the repository or conversation.
3. Ask focused questions in small rounds. Prefer concrete scenarios over abstract preference questions.
4. As terminology becomes precise, apply the `domain-modeling` rules and update `CONTEXT.md` when a canonical term actually changed or was added.
5. When a real architectural trade-off becomes settled and qualifies for durable recording, propose/update an ADR rather than hiding the decision in chat.
6. Keep explicit lists of:
   - decided;
   - still unresolved;
   - out of scope;
   - assumptions that require research or prototype evidence.
7. Stop grilling when the remaining work can be described without the implementation agent needing to make product/domain decisions.

## IWrite-specific pressure tests

When relevant, probe:

- who owns the resource;
- which Workspace is active;
- which Book Role/Capability is required;
- what happens after revocation;
- what happens under retry or duplicate submission;
- what happens under concurrent edits/acceptance;
- whether a browser-supplied identifier is being trusted;
- whether cross-tenant existence can leak;
- what is persisted historically versus recalculated;
- whether logs/analytics/LLM/audit receive private content;
- what happens when an optional provider is unavailable;
- what happens to old data during a migration.

## Output

Do not implement. Produce a decision-ready conversation/state that can feed `to-spec`, a targeted `research` note, or a disposable `prototype`.
