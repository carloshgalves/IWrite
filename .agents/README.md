# IWrite Local Skills

Project-specific engineering skills live in `.agents/skills/` and are versioned with the repository.

## Installed skills

- `domain-modeling` — maintain precise IWrite domain vocabulary and durable decisions;
- `grill-with-docs` — resolve ambiguous product/design branches before implementation;
- `to-spec` — synthesize resolved discussion into a durable GitHub Issue spec;
- `to-tickets` — split large specs into dependency-aware vertical tracer bullets;
- `implement` — implement approved work with IWrite-specific guards and validation;
- `tdd` — red/green development at the seam that actually proves the invariant;
- `iwrite-review` — three-axis review: spec, engineering quality, IWrite semantic invariants;
- `diagnosing-bugs` — reproduce, minimize, hypothesize, instrument, fix, regress;
- `research` — primary-source research persisted in `docs/research/`;
- `handoff` — durable checkpoint for a fresh agent/context;
- `prototype` — disposable UX/behavior evidence before production implementation;
- `codebase-design` — module/seam design guidance adapted to Spring/Next conventions;
- `wayfinder` — decision mapping for large uncertain initiatives;
- `triage` — Issue/PR triage that preserves the repository's current label taxonomy.

## Not vendored intentionally

The repository does not copy the full upstream `mattpocock/skills` catalog. Vendor-specific, TypeScript-only, exercise-authoring, beta orchestration, and unrelated productivity skills are excluded until a concrete IWrite need justifies them.

## Workflow entry point

Agents should start with `/AGENTS.md` and use `docs/agents/README.md` for the default flows.

Attribution for adapted upstream ideas is recorded in `docs/agents/skills-attribution.md`.
