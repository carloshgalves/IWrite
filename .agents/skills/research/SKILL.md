---
name: research
description: Research an external technical question for IWrite using primary sources and persist durable cited findings under docs/research/.
---

# Research for IWrite

Use when a design or implementation decision depends on facts outside the repository: standards, provider APIs, library behavior, browser/platform constraints, security guidance, legal/operational requirements, or competing technical approaches.

## Process

1. State the decision/question the research must inform.
2. Read the relevant IWrite Issue, `CONTEXT.md`, architecture and ADRs so the investigation answers the product's actual constraint rather than a generic technology question.
3. Prefer primary sources:
   - official documentation;
   - standards/specifications;
   - first-party source repositories/release notes;
   - provider API/security documentation;
   - authoritative legal/regulatory sources when the question is legal/compliance-related.
4. Cross-check important or surprising claims when practical.
5. Separate verified facts from assumptions, recommendations, and unknowns.
6. Record version/date constraints for facts that may change.
7. Write the result as a Markdown note in `docs/research/` following `docs/research/README.md`.

Parallel/background agents may be used when the current coding environment supports them, but they are an optimization, not a requirement of this skill.

## IWrite evaluation lens

Always consider, where relevant:

- compatibility with the current modular-monolith architecture;
- Java 21 / Spring Boot / Spring AI implications;
- Next.js/React/TipTap implications;
- PostgreSQL/Flyway implications;
- authorization and multi-tenant isolation;
- privacy and data residency;
- idempotency/retries and failure recovery;
- observability and cost;
- provider lock-in and adapter boundaries;
- local development/testability;
- migration/rollback implications;
- whether a new dependency duplicates an existing capability.

## Do not

- copy secondary blog conclusions without tracing them to owning sources;
- turn research into an ADR automatically;
- put API keys, private customer/user data, or manuscript content in research notes;
- treat a vendor recommendation as a product requirement;
- hide unresolved trade-offs behind a single confident recommendation.

Research is complete when a future contributor can understand the external facts, their date/version, their impact on IWrite, and which decisions still require human/product judgment.
