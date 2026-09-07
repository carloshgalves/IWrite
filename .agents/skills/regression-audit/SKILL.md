---
name: regression-audit
description: Audit whether IWrite's green test suite actually proves the important guarantees claimed by a PR or implementation, using targeted negative controls and regression tests.
---

# Regression Audit for IWrite

Use this skill after an implementation or fix is already green and before a final merge/release decision when the change makes important claims about authorization, concurrency, idempotency, history, migrations, frontend state, privacy, or third-party library behavior.

This is not TDD and it is not a second general code review. Its question is narrower:

> If someone weakens or removes this guarantee, does the suite fail for the intended reason?

## Relationship to other skills

- `tdd` starts from a behavior that should be red and drives implementation red -> green.
- `iwrite-review` looks for spec, engineering, and semantic defects in the diff.
- `regression-audit` starts from an already-green implementation and tests the **signal quality** of the regression suite.

If the audit discovers a real production defect, stop treating it as a test-only gap: report the finding and route the correction through `tdd` at the correct seam.

## 1. Establish the claims to prove

Read the originating Issue/spec, PR body, review comments, changed code, relevant ADRs, `AGENTS.md`, `CONTEXT.md`, and `docs/wiki/Quality-and-Review.md`.

Build a small claim ledger for the risky behavior changed by the PR:

- claim/invariant;
- risk if it regresses;
- highest stable proof seam;
- existing test(s) that allegedly prove it;
- targeted negative control that should turn those tests red.

Do not audit every line mechanically. Prioritize claims whose regression could create authorization drift, data loss, stale writes, duplicate effects, migration breakage, privacy leakage, or misleading user-visible state.

## 2. Prove the baseline is genuinely green

Run the smallest relevant baseline first and capture the exit code from the actual test/build process, not from `tail`, `tee`, a pipe, or another wrapper command unless pipe failure propagation is explicitly enabled.

Record the exact baseline that was green before mutation.

For Docker/database-backed tests, follow `docs/agents/docker-test-lifecycle.md`: fresh isolated infrastructure only, cleanup registered before creation, and no test-created container/volume/network left after the run.

## 3. Apply one narrow negative control at a time

Temporarily weaken the guarantee in the smallest possible way that simulates a plausible regression. Examples:

### Authorization and tenancy

- treat contextual eligibility as effective authority;
- skip a role/capability check;
- trust a browser-supplied identity/tenant/role;
- relax non-enumerable denial behavior;
- remove the post-lock authorization re-check after a revocable preflight.

### Concurrency, retry and history

- remove an optimistic revision comparison;
- reuse a stale preflight after acquiring a lock;
- weaken idempotency/operation fingerprint checks;
- allow a retry to duplicate a ledger/audit/external effect;
- remove deterministic synchronization that protects a race.

### Frontend and session state

- adopt a new revision without its coherent state snapshot;
- allow editing during conflict reconciliation;
- let a late async response repopulate stale identity/workspace/scene state;
- turn a transport/loading failure into an authorization denial;
- let a library state transition emit a synthetic user change.

### Database and migrations

- remove/relax the relevant constraint or lock;
- test only an empty/current schema instead of the prior supported state;
- weaken a backfill predicate;
- remove the index/constraint whose presence is part of the claim.

### Privacy and observability

- use synthetic canary private values and temporarily weaken redaction/minimization;
- confirm the regression suite detects the value reaching a forbidden sink.

The negative control is temporary evidence. **Never commit it.** Revert it completely before proceeding.

## 4. Interpret the result

A useful negative control must fail for the intended reason.

### Expected red

If the targeted test goes red for the claimed invariant, restore production code and record that the claim is pinned.

### False green

If the suite stays green after the guarantee is weakened, the claim is not proven.

Add or strengthen a regression test at the highest stable seam that can observe the actual failure pattern. Then repeat the negative control and require red before accepting the new test.

Do not add assertions against private implementation details merely to force red. If the stable architecture exposes no seam that can reproduce the claim, report that as an architectural/testability finding.

### Real defect discovered

If the unmodified implementation itself fails the new reproduction, stop the audit for that claim and report a normal finding with evidence, impact, and the smallest safe correction. Use `tdd` for the fix rather than silently mixing production changes into the audit.

## 5. Negative-control quality rules

Prefer controls that are:

- one semantic weakening at a time;
- small enough that the reason for red is unambiguous;
- behavior-oriented rather than implementation-coupled;
- deterministic for races (hooks/latches/barriers, not sleeps when practical);
- executed against the real framework/library when its behavior is the claim (for example TipTap, Jackson, PostgreSQL, browser/query cache semantics);
- isolated from unrelated tests so one broad break does not masquerade as proof.

Avoid brute-force mutation testing of the whole repository. This skill is risk-driven, not mutation-score driven.

## 6. IWrite regression priorities

When affected, explicitly audit the suite's ability to catch regressions in:

- authorized vs unauthorized vs other-tenant vs nonexistent resources;
- revocation during an active session or mutation window;
- contextual eligibility vs effective resource-scoped authority;
- stale revision/lost-update behavior;
- duplicate/retry/idempotency behavior;
- lock ordering and preflight-vs-under-lock authorization;
- cross-tab/session/cache reconciliation;
- frontend loading/error/denial distinctions;
- third-party editor/library transitions that can synthesize mutations;
- migration from the previous real schema/data state;
- immutable history and word-count/progress ledgers;
- audit/log/trace/analytics minimization;
- optional provider disabled/unavailable behavior.

## 7. Completion criteria

Before declaring the audit complete:

1. restore every temporary production mutation;
2. ensure `git diff` contains only intended regression tests/docs, if any;
3. rerun the relevant green suite with the real process exit code;
4. run `git diff --check`;
5. verify all test-created Docker resources are gone;
6. report any claims that remain unproven instead of calling the audit green.

## Output

Report:

- baseline verified;
- claims audited;
- negative controls and which tests they turned red;
- regression tests added or strengthened;
- claims still unproven;
- real findings discovered and handed to `tdd`/review;
- final validation and Docker cleanup.

Do not say a claim is covered merely because a test with a similar name passes. Coverage here means the test demonstrably fails when the claimed guarantee is weakened.
