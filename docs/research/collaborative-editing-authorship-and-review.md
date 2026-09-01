# Collaborative editing, authorship, and editorial review

Date: 2026-09-01
Related Issues: #145, #65, #72, #184, #198

## Question

Which established mechanisms can attribute changes to Authors, represent editorial suggestions, and handle concurrent edits without letting one Author directly change another Author's contribution? Which mechanisms fit IWrite's authoritative backend, current Tiptap editor, and revision-based Scene saves?

## Findings

### Coauthoring does not imply per-range ownership

Google Docs suggestion mode preserves the current text while proposed insertions, deletions, and replacements await acceptance or rejection. Microsoft Word's review mode similarly limits a reviewer to comments and tracked changes while a document owner or full editor controls canonical changes. These are strong precedents for IWrite Editors proposing textual changes instead of editing the manuscript directly. Neither product documents ordinary coauthoring as an authorization model in which each author exclusively owns and may edit particular text ranges. [Google Docs suggestion mode](https://support.google.com/docs/answer/6033474?co=GENIE.Platform%3DDesktop&hl=en-EN), [Microsoft Word review mode](https://support.microsoft.com/en-US/Word/review-mode-in-word)

Google Docs version history can attribute visible changes to editors, although Google notes that revisions may be merged and that the more granular “show editors” feature has edition limits. Attribution is therefore useful evidence and presentation, but it is not equivalent to a durable rule that authorizes edits to a specific contribution. [Google Docs version history](https://support.google.com/docs/answer/190843)

### A shared document still needs convergence and conflict rules

The Google Docs API exposes two distinct revision strategies: a stale `requiredRevisionId` rejects a write, while a recent `targetRevisionId` lets the server apply a client's operations against collaborator changes and resolve conflicts. This demonstrates that a single shared document can avoid Git-style branches, but it still needs a defined policy for concurrent operations. [Google Docs API `documents.batchUpdate`](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate)

ProseMirror represents edits as transformation steps. Its collaboration model sends steps through a central authority that orders them and requires clients to rebase unconfirmed steps over concurrent changes. Rebasing can fail when a concurrent change removes the structure targeted by a step. Operation-based collaboration therefore handles convergence, but does not by itself establish IWrite's business ownership or authorization rules. [ProseMirror guide](https://prosemirror.net/docs/guide/)

Yjs relative positions remain attached to shared content as remote edits change ordinary numeric positions, which makes them useful anchors for comments and suggestions. They can become unresolvable if the referenced shared type is deleted, so stable anchors do not remove the need to define deletion, split, move, and boundary behavior. [Yjs relative positions](https://docs.yjs.dev/api/relative-positions)

### Tiptap has useful mechanisms, but they are implementation options

Tiptap's Tracked Changes extension records insertions, deletions, replacements, formatting changes, block splits, and other proposals with creator, timestamp, and identifier metadata. It supports accept/reject workflows, nested suggestions, comments, and Yjs collaboration. It is a paid add-on, so its behavior is useful evidence for the product model but must not become an unstated domain or licensing dependency. [Tiptap Tracked Changes](https://tiptap.dev/docs/tracked-changes/getting-started/overview)

Tiptap's collaboration stack uses Yjs updates for real-time convergence. Its documented server authentication can distinguish authenticated users and read-only access at document scope. The reviewed documentation does not provide an off-the-shelf authorization rule for “this authenticated Author may mutate only these ranges.” Enforcing Authored Contribution boundaries over raw collaborative updates would therefore require IWrite-specific validation or a different mutation boundary. The last sentence is an inference from the documented extension points, not a vendor guarantee. [Tiptap Collaboration overview](https://tiptap.dev/docs/collaboration/getting-started/overview), [Hocuspocus authentication](https://tiptap.dev/docs/hocuspocus/guides/authentication), [Hocuspocus server hooks](https://tiptap.dev/docs/hocuspocus/server/hooks)

## Implications for IWrite

Forbidding an Author from directly editing another Author's contribution removes the need for a Git-like merge of independent manuscript copies. It does **not** eliminate all integration work. The shared Scene still needs deterministic behavior when an Author inserts at a contribution boundary, splits or joins a paragraph, moves a block, changes Scene structure, or acts on a stale revision.

IWrite treats moving, splitting, joining, or deleting another Author's contribution as changing that contribution even if no word is rewritten. Such an operation cannot be applied directly by an unrelated Author; it must remain a structured proposal until accepted by the attributed Author or Authors.

The current whole-document Scene replacement contract cannot safely prove that every changed range belongs to the authenticated Author. Comparing two complete snapshots after the fact may identify a textual diff, but does not establish durable ownership when rich-text structure changes, concurrent saves occur, or blocks are moved. Contribution-level authorization therefore needs a mutation model that carries authenticated provenance and stable contribution identity.

Editor suggestions and Author contributions solve different problems:

- an Editor uses a structured Editorial Suggestion that cannot mutate canonical text until accepted;
- an Author directly changes only an Authored Contribution attributed to them;
- a Book Owner accepts or rejects suggestions only for Authored Contributions attributed to them, because ownership does not bypass contribution authorship;
- accepting a suggestion incorporates its result into the existing contribution while retaining separate historical attribution to the proposer; it does not make the proposer a coauthor;
- another Author who sees a needed change uses a proposal or explicit handoff rather than silently editing that contribution;
- a Scene Integration Review examines the assembled Scene across contribution boundaries.

This preserves one authoritative Scene instead of creating per-Author manuscript clones. It also keeps concurrency mechanics separate from editorial status and Book Role authorization.

## Options

### 1. Git-like per-Author copies

Each Author edits a private Scene copy and later merges it. This gives clear isolated diffs, but introduces branch selection, merge conflicts, duplicate Scene state, and an assembly workflow that conflicts with IWrite's intended simultaneous shared manuscript. It is not recommended for #145.

### 2. One Scene with stable contribution blocks and server-authorized operations

The Scene remains authoritative and shared, while stable block or contribution identifiers define the smallest directly editable ownership boundary. Authenticated operations carry actor and base revision; the backend rejects stale or cross-contribution mutations. Cross-author changes become suggestions or explicit ownership transfers. This fits the product rule best, but paragraph split/join, reorder, and shared-contribution behavior require a prototype before the schema and API are fixed.

Block-level ownership is a safer initial boundary than arbitrary character ranges because it gives the backend a durable object to authorize. It is less flexible when two Authors truly collaborate inside one paragraph, so that case may need an explicitly shared contribution rather than implicit range ownership.

### 3. Whole-Scene Yjs/CRDT collaboration with custom range authorization

This provides strong real-time convergence and aligns with Tiptap's packaged collaboration features. It also introduces a new persistence and synchronization model, custom semantic validation of collaborative updates, and likely overlap with the broader real-time collaboration work tracked by #72. It should not be adopted solely to deliver Book Roles in #145.

## Recommendation

Use a single authoritative Scene and do not introduce Git-like manuscript branches. Treat “merge” as two separate concerns:

1. **Content convergence:** stale and concurrent operations still need rejection, rebase, or another deterministic policy.
2. **Editorial integration:** a required Scene Integration Review for multi-Author Scenes checks continuity across independently owned contributions.

Prototype option 2 in #184 before making contribution provenance part of #145's implementation contract. The prototype should use stable contribution or block identifiers, authenticated operation provenance, backend-enforced direct-edit authority, and structured proposals for cross-author changes. It should explicitly test inserts at boundaries, paragraph split/join, block movement, deletion, stale revisions, retries, and a contribution shared by multiple Authors.

Do not create an ADR yet. An ADR becomes warranted after #184 produces evidence choosing among operation-based HTTP updates, ProseMirror collaboration, or Yjs/CRDT persistence.

## Open questions

- What is the minimum stable ownership unit: block, paragraph, explicitly selected range, or an operation-derived change set?
- When several Authors deliberately work inside the same contribution, which mutations invalidate their readiness confirmations?
- At which transition must the required Scene Integration Review for a multi-Author Scene be complete?

## Sources

- [Google Docs: suggest edits](https://support.google.com/docs/answer/6033474?co=GENIE.Platform%3DDesktop&hl=en-EN)
- [Google Docs: version history](https://support.google.com/docs/answer/190843)
- [Google Docs API: `documents.batchUpdate`](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate)
- [ProseMirror guide](https://prosemirror.net/docs/guide/)
- [Tiptap Tracked Changes](https://tiptap.dev/docs/tracked-changes/getting-started/overview)
- [Tiptap Collaboration overview](https://tiptap.dev/docs/collaboration/getting-started/overview)
- [Hocuspocus authentication](https://tiptap.dev/docs/hocuspocus/guides/authentication)
- [Hocuspocus server hooks](https://tiptap.dev/docs/hocuspocus/server/hooks)
- [Yjs relative positions](https://docs.yjs.dev/api/relative-positions)
- [Microsoft Word review mode](https://support.microsoft.com/en-US/Word/review-mode-in-word)
