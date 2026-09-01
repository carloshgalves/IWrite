# Technical Research Notes

This directory stores durable technical research that informs IWrite decisions.

Research notes are not automatically architectural decisions. They capture evidence so a later Issue, spec, or ADR can make a decision without rediscovering the same external facts.

## Rules

- Prefer primary sources: official documentation, specifications, standards, source repositories, provider API references, and first-party security/legal guidance where applicable.
- Include the research date because external services and libraries change.
- Separate verified facts from recommendations and open questions.
- Cite every material external claim with a stable source link when possible.
- Do not include secrets, credentials, private manuscript content, user data, or copied proprietary material.
- Record provider-specific findings without making the domain depend directly on that provider unless an explicit decision approves the coupling.

## Suggested shape

```markdown
# Topic

Date: YYYY-MM-DD
Related Issues: #...

## Question

What decision or implementation uncertainty is this research meant to inform?

## Findings

Verified facts with primary-source citations.

## Implications for IWrite

How the findings interact with current architecture and ADRs.

## Options

Viable alternatives and trade-offs.

## Recommendation

A recommendation when evidence is sufficient.

## Open questions

What still requires product, legal, security, operational, or implementation judgment?

## Sources

Primary sources consulted.
```
