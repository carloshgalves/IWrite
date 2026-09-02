# IWrite Local Skills

Project-specific engineering skills live in `.agents/skills/` and are versioned with the repository. This directory is the canonical source of skill behavior.

## Installed skills

- `domain-modeling` — maintain precise IWrite domain vocabulary and durable decisions;
- `grill-with-docs` — resolve ambiguous product/design branches before implementation;
- `to-spec` — synthesize resolved discussion into a durable GitHub Issue spec;
- `to-tickets` — split large specs into dependency-aware vertical tracer bullets;
- `implement` — implement approved work with IWrite-specific guards and validation;
- `tdd` — red/green development at the seam that actually proves the invariant;
- `iwrite-review` — three-axis review: spec, engineering quality, IWrite semantic invariants;
- `iwrite-migration` — Flyway/PostgreSQL changes validated against legacy states, integrity and operational risk;
- `diagnosing-bugs` — reproduce, minimize, hypothesize, instrument, fix, regress;
- `research` — primary-source research persisted in `docs/research/`;
- `handoff` — durable checkpoint for a fresh agent/context;
- `prototype` — disposable UX/behavior evidence before production implementation;
- `codebase-design` — module/seam design guidance adapted to Spring/Next conventions;
- `wayfinder` — decision mapping for large uncertain initiatives;
- `triage` — Issue/PR triage that preserves the repository's current label taxonomy.

## Claude Code discovery bridges

Claude Code discovers project skills under `.claude/skills/`, while the repository keeps provider-neutral procedures under `.agents/skills/`.

Each `.claude/skills/<name>/SKILL.md` is therefore a thin bridge: it tells Claude Code to read `AGENTS.md` and then load the matching canonical `.agents/skills/<name>/SKILL.md` in full. The bridge must not copy or independently evolve the procedure.

When adding or renaming a canonical skill, add or rename the matching Claude bridge in the same change. If a bridge and the canonical skill ever conflict, `.agents/skills/` is authoritative.

## Not vendored intentionally

The repository does not copy the full upstream `mattpocock/skills` catalog. Vendor-specific, TypeScript-only, exercise-authoring, beta orchestration, and unrelated productivity skills are excluded until a concrete IWrite need justifies them.

## Workflow entry point

Agents should start with `/AGENTS.md` and use `docs/agents/README.md` for the default flows.

Attribution for adapted upstream ideas is recorded in `docs/agents/skills-attribution.md`.
