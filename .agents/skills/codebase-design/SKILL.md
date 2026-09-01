---
name: codebase-design
description: Evaluate IWrite module seams for locality, leverage and testability without forcing framework-independent terminology onto Spring/Next conventions.
---

# Codebase Design for IWrite

Use as a design reference when a change exposes architectural friction or when a new seam is being considered.

## Goal

Prefer modules whose callers learn a small, stable interface while meaningful complexity remains localized behind it.

This skill adopts the useful principles of deep modules without banning established Spring/Java/Next.js vocabulary such as Controller, Service, Repository, Adapter, API, component, or hook when those names are accurate.

## Questions to ask

### Interface and leverage

- Does the public interface expose only what callers need to know?
- Are callers repeating domain rules that belong behind one seam?
- Would deleting this abstraction remove complexity, or merely scatter the same complexity into many callers?
- Is a new interface justified by real variation or isolation needs, or only hypothetical future flexibility?

### Locality

- Can a domain rule be changed in one place without coordinated edits across unrelated callers?
- Is authorization centralized at the right level, or duplicated inconsistently?
- Are provider-specific details contained in adapters/gateways?
- Are transaction/idempotency/concurrency semantics local enough to reason about?
- Does frontend identity/workspace state have one coherent lifecycle rather than several independent sources of truth?

### Testability

- Can the important behavior be proven through the same stable interface used by callers?
- Are tests reaching through an interface because the module shape hides the only observable proof?
- Would a database seam be more truthful for a database invariant?
- Are mocks replacing the very behavior that matters?

## IWrite guardrails

- Do not split the modular monolith into microservices merely to create cleaner diagrams.
- Do not add adapter/interface layers when only one implementation exists unless they isolate a real external boundary or a hard-to-test dependency.
- Existing examples of legitimate external seams include LLM providers, storage providers when introduced, email/payment providers when introduced, and MCP as an exposure layer over existing services.
- Keep authorization/domain rules out of provider and transport adapters.
- Prefer backend authority for identity/tenant/capabilities and keep UI state derivative of server-confirmed identity.
- Respect ADRs. If a proposed improvement contradicts one, surface concrete evidence before recommending that the ADR be reopened.

## Design twice for high-risk seams

For hard-to-reverse interfaces, sketch at least two materially different designs before choosing. Compare them on:

- caller complexity;
- domain leakage;
- locality of change;
- test seam quality;
- migration cost;
- failure/retry semantics;
- provider coupling;
- security/privacy implications.

Do not refactor simply because one option looks more elegant. Prefer changes supported by active feature pressure, repeated bugs, or observed maintenance friction.
