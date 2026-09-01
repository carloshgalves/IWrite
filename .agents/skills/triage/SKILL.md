---
name: triage
description: Triage IWrite Issues and PRs using the repository's existing priority/status/type/area taxonomy and produce implementation-ready briefs without replacing current labels.
disable-model-invocation: true
---

# Triage for IWrite

Use to evaluate reported bugs, proposed enhancements, stale Issues, or PRs that need a clear next state.

## Preserve repository taxonomy

IWrite already uses multidimensional labels such as `priority:*`, `status:*`, `type:*`, and `area:*`. Inspect current labels before changing anything. Do not import a foreign label state machine wholesale.

## Gather context

For an Issue:

- read the full body and comments;
- inspect labels, dependencies, parent roadmaps and related closed/open work;
- search code by domain concept to see whether the behavior already exists;
- read `CONTEXT.md`, relevant ADRs and quality rules.

For a PR, also inspect the diff, originating Issue/spec, CI status and review discussion.

## Classify the situation

Determine whether the item is:

- already implemented;
- reproducible bug;
- valid enhancement with clear behavior;
- duplicate/absorbed by a canonical Issue;
- blocked by missing information or product decision;
- too large and needs decomposition;
- out of scope for the current roadmap;
- ready for focused implementation/review.

Do not close as duplicate or already implemented without evidence and a pointer to the canonical implementation/Issue.

## Verify before recommending implementation

For bugs, reproduce or build the strongest available feedback signal using `diagnosing-bugs` principles.

For enhancements, check whether unresolved decisions would force an agent to guess. Route those to `grill-with-docs`, `research`, `prototype`, or `wayfinder` as appropriate.

## Agent-ready brief

When an Issue is ready for focused implementation, ensure it clearly states:

- the behavior to build;
- parent/spec relationship;
- acceptance criteria;
- true blockers;
- validation seams and risky invariants;
- explicit out-of-scope boundaries where necessary.

Use `to-tickets` if the item is too large for one safe session.

## PR triage

A PR can be technically green and still not be merge-ready. Use `iwrite-review` to distinguish:

- spec mismatch;
- engineering-quality findings;
- IWrite semantic-invariant findings.

Do not treat raw coverage percentage or green CI as the complete review result.

## Comments and automation

When posting AI-generated triage conclusions, make it clear that the note was generated/assisted by an AI agent and provide evidence links so a maintainer can verify the recommendation.
