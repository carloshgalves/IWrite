# Agent Engineering Workflow

IWrite keeps project-specific agent instructions in the repository so coding sessions do not have to rediscover the same domain vocabulary, architectural constraints, and review rules.

## Entry points

- `AGENTS.md` — mandatory working rules and source-of-truth hierarchy;
- `CONTEXT.md` — canonical domain glossary;
- `docs/agents/issue-tracker.md` — GitHub Issue conventions for agent work;
- `.agents/skills/` — reusable engineering procedures;
- `docs/wiki/Architecture.md` — current system architecture;
- `docs/wiki/Architectural-Decisions.md` — settled architectural decisions;
- `docs/wiki/Quality-and-Review.md` — IWrite test/review baseline.

## Default flows

### New feature with unresolved design

```text
grill-with-docs
    -> to-spec
    -> to-tickets (when the spec is larger than one safe implementation slice)
    -> implement
    -> iwrite-review
```

### Existing well-specified Issue

```text
read issue + code + context/ADRs
    -> to-tickets if needed
    -> implement
    -> iwrite-review
```

### Bug or performance regression

```text
diagnosing-bugs
    -> regression test at the correct seam
    -> fix
    -> iwrite-review
```

### External technical decision

```text
research
    -> persist evidence in docs/research/
    -> decision/spec/ADR only if warranted
```

### UX behavior is unclear

```text
prototype
    -> compare behavior/options
    -> discard prototype implementation
    -> spec/tickets for production code
```

### Context-window or agent handoff

```text
handoff
    -> durable checkpoint
    -> next session reloads issue + CONTEXT + ADRs + checkpoint
```

## Principles

1. **Do not automate ambiguity.** Resolve domain/product decisions before asking an agent to implement them.
2. **Prefer vertical slices.** A ticket should produce a narrow but complete behavior, not merely modify one technical layer.
3. **Use the correct proof seam.** HTTP/UI tests are not automatically better than database tests. Pick the highest stable seam that can prove the actual invariant.
4. **Review behavior against both the Issue and IWrite invariants.** Passing tests do not prove tenant isolation, idempotency, concurrency safety, privacy, or migration correctness.
5. **Keep provider choice outside the domain.** Agent tooling may vary; repository guidance should remain useful across Codex, Claude, and other compatible coding agents.
6. **Persist durable knowledge, not chat history.** Glossary terms belong in `CONTEXT.md`; hard-to-reverse trade-offs belong in ADRs; external technical evidence belongs in `docs/research/`; implementation work belongs in Issues/PRs and code.

## Skill policy

The local skills are adapted to IWrite rather than vendored unchanged. They intentionally differ from their inspiration where the product requires stronger database testing, semantic security review, provider neutrality, or safer Git behavior.

See `docs/agents/skills-attribution.md` for attribution and licensing notes.
