---
name: wayfinder
description: Map a large uncertain IWrite initiative as decision Issues, resolving uncertainty before producing implementation tickets.
disable-model-invocation: true
---

# Wayfinder for IWrite

Use only when the destination is important but the route still contains multiple unresolved decisions that cannot fit safely into one design session.

Good candidates include RAG/consistency architecture, offline synchronization, realtime collaboration, production topology, or other initiatives where early decisions change which later questions even exist.

Do not use Wayfinder for work whose implementation sequence is already clear. Existing roadmaps such as identity/collaboration should not be replaced by a new map merely for process consistency.

## 1. Define the destination

State what will be true when planning is complete. The destination is normally a decision-ready spec or architecture direction, not the finished feature.

Read `CONTEXT.md`, architecture, ADRs, related Issues and existing research before creating new decision work.

## 2. Separate decisions from implementation

A Wayfinder ticket asks a decision/investigation question such as:

- Which retrieval/indexing strategy can satisfy #66 and #71 without two redundant search systems?
- What offline conflict model preserves `contentRevision`, versions and idempotent operations?
- Which production topology satisfies backup/restore, observability and release constraints at current scale?

It is not an implementation ticket such as `add pgvector dependency` or `create websocket endpoint` unless that work is a disposable experiment needed to answer a decision.

## 3. Use evidence types deliberately

A decision may require:

- `research` for external facts;
- `prototype` for behavior/UX/interface evidence;
- `grill-with-docs` for product/domain trade-offs;
- a small throwaway technical experiment when feasibility itself is unknown.

Persist evidence in the appropriate durable location and link it from the decision Issue.

## 4. Fog of war

Do not pre-create dozens of speculative Issues. Keep not-yet-formulatable questions in a `Not yet specified` section of the map. Turn them into decision Issues only when upstream answers make the question precise.

## 5. Map shape

Use one parent GitHub Issue containing:

```markdown
## Destination

## Notes / constraints

## Decisions so far
- links to resolved decision Issues with one-line outcomes

## Not yet specified

## Out of scope
```

Create child/related decision Issues using the repository's normal label taxonomy. Express true blocking relationships explicitly.

## 6. Resolve incrementally

Work only on currently unblocked decisions. When a decision resolves:

- record the evidence and outcome in its Issue;
- update the map with a concise pointer;
- promote newly clear questions out of fog;
- remove/reframe decisions invalidated by the result.

## 7. Exit

Stop Wayfinder when the path to implementation is clear enough that no material product/domain/architecture decision must be invented by the implementation agent.

Then create/update the canonical spec and use `to-tickets` for implementation slices.
