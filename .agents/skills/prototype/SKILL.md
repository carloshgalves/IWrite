---
name: prototype
description: Build a disposable IWrite prototype to answer an unresolved behavior or UX question before production implementation.
---

# Prototype

Use when the open question is easier to resolve by reacting to something concrete than by discussing abstractions.

Examples:

- editor/reviewer/beta-reader interaction patterns;
- workspace switching UX;
- invitation acceptance flow;
- offline conflict presentation;
- review/suggestion interactions;
- dashboard information density;
- alternative module/interface shapes that benefit from executable comparison.

## Rules

1. State the decision the prototype is meant to answer.
2. Keep it isolated from production architecture. A prototype is evidence, not implementation.
3. Prefer the cheapest artifact that makes the decision observable: static HTML, local component mock, state-machine sketch, small throwaway script, or multiple clearly labeled variants.
4. Use synthetic data only. Do not use private manuscripts or production credentials.
5. Do not add database migrations, external provider dependencies, or permanent abstractions merely to support a prototype.
6. Compare options against IWrite constraints: clarity for writers/editors, accessibility, responsive behavior, domain vocabulary, authorization expectations, implementation risk, and consistency with existing interaction patterns.
7. Record the selected behavior in the Issue/spec. If the decision is architectural and qualifies for an ADR, record it there separately.
8. Delete or clearly quarantine throwaway code before production implementation unless the user explicitly chooses to preserve it as reference.

## Output

End with:

- decision tested;
- options shown;
- observed trade-offs;
- selected direction or unresolved question;
- what the production spec must preserve.

Never silently promote prototype code into production code without passing through normal specification, testing and review rules.
