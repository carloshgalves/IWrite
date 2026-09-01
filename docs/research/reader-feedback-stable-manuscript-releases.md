# Reader feedback and stable manuscript releases

Date: 2026-09-01
Related Issues: #145, #65, #148
Decision status: confirmed for the #145 capability foundation on 2026-09-01; detailed release lifecycle remains in #148.

## Question

Should an IWrite Reader see the live current Manuscript, only Scenes whose current status is `REVISED`, or a stable review copy released deliberately by an Author? The decision must preserve useful comments when Authors later delete or rewrite text, without eliminating the Reader role used to share work with trusted friends, relatives, or an author's ideal reader.

## Findings

### General-purpose collaborative documents expose mutable current state

Google Docs separates Viewer, Commenter, and Editor permissions. A Commenter can comment and suggest changes without directly changing content, but the permission applies to the shared document rather than to an independently released reading copy. This is a useful capability precedent, but it does not by itself keep a reader's manuscript state stable while Editors or Authors continue changing the document. [Google Docs access levels](https://support.google.com/docs/answer/16722399?co=GENIE.Platform%3DDesktop&hl=En)

### Writing products distinguish live collaboration from reader-facing copies

Dabble gives beta readers a separate, isolated review copy rather than the live manuscript. The Author may scope that copy to selected parts. Beta readers can read and comment but cannot edit, and their comments remain associated with the copy until the Author ends access and brings the feedback into the main project. Dabble also archives a merged review copy as a read-only record. [Dabble beta reader guide](https://www.dabblewriter.com/docs/collaboration-sharing/beta-reader-guide), [Dabble review copies](https://www.dabblewriter.com/docs/collaboration-sharing/review-copies)

Dabble's model demonstrates that stable reader feedback does not require waiting until the complete Book is finished: the Author can release only selected documents. It also demonstrates a separation between the collaborator's enduring relationship or role and the particular copy and scope released for one reading pass.

Reedsy Studio exposes a read-only preview that may contain selected chapters or the entire draft. Its current official FAQ says preview readers cannot yet comment. Reedsy therefore supports deliberate, scoped presentation to beta readers, but its preview feature is not evidence for the complete feedback workflow IWrite needs. [Reedsy Studio](https://reedsy.com/studio/), [Reedsy Studio FAQ](https://reedsy.com/studio/resources/book-writing-software-faq/)

BetaReader.io requires the Author to publish a book before approved readers can read it and allows the Author to return it to an unpublished state while preserving reading data. It supports inline comments, surveys, reading progress, invitations, and access revocation. Its public documentation reviewed here does not establish whether edits silently replace text already being read, so it is evidence for an explicit availability lifecycle, not for version stability. [BetaReader.io overview](https://betareader.io/), [BetaReader.io sharing](https://betareader.io/help/how-to-share-your-work)

StoryOrigin can require feedback or questionnaire responses before a reader unlocks the next chapter. This is evidence that beta reading may progress incrementally by chapter rather than require an already complete Book, but the reviewed public page does not specify manuscript version semantics. [StoryOrigin](https://storyoriginapp.com/)

### The ideal reader is a relationship, not live-manuscript authority

Stephen King's official site describes Tabitha King as his Ideal Reader: the one-person audience he has in mind while writing a first draft. The same official reading guide asks when writers should seek critical feedback and highlights King's warning about seeking reader response too early or too frequently. The Ideal Reader concept supports preserving a trusted Reader relationship during creation, but it does not imply that this person must see every transient manuscript state. [Stephen King, *On Writing* twentieth-anniversary reading guide](https://stephenking.com/p/20-years-on-writing/index.html)

## Implications for IWrite

Mapping the Reader Book Role directly to the current Manuscript makes an in-progress reading unstable. Text can disappear or move between visits, comments can lose their intended reading context, and different Readers may unknowingly respond to different current states while appearing to participate in the same pass.

Filtering the live Manuscript dynamically to Scenes whose status is currently `REVISED` improves content maturity but does not solve stability. If an Author reopens or changes a Scene, that Scene can disappear or change for a Reader who has already started it. Status is a workflow fact about current canonical content; it is not a durable publication boundary.

A Reader's feedback also should not automatically reverse editorial readiness. Reader feedback represents audience response, while `WRITTEN` and `REVISED` describe the Author and Editor workflow. A comment can remain attached to the released Scene Version; an attributed Author can then explicitly decide whether to reopen the canonical contribution for further writing or review.

The existing immutable Scene Version model is sufficient as a source for a stable reading surface. IWrite does not need Git-like manuscript branches merely to let Readers comment on a stable pass. A release can reference immutable versions without making them editable copies.

## Options

### 1. Reader sees the live current Manuscript

This is simple and resembles a Google Docs Commenter, but it permits text to shift beneath an active reading pass. Version-anchored comments mitigate broken anchors but do not preserve the reading sequence or context the Reader experienced. Not recommended as the default Reader capability.

### 2. Reader dynamically sees current `REVISED` Scenes

This prevents access to early drafting and permits incremental reading, but it still changes the Reader's visible Book whenever current status or content changes. A Scene that is reopened can disappear after it was read. Better than unrestricted live access, but not sufficient by itself.

### 3. Reader receives a stable release assembled from revised Scene Versions

An authorized Author deliberately creates a reader-facing release from immutable Scene Versions whose canonical Scenes were `REVISED` or `FINAL` at release time. The release may contain selected Scenes, a Chapter, or the available Book, so reading can begin before the whole Book is complete. Existing released content never changes silently. Later Scenes or revised replacements require another explicit release or reading pass.

Reader comments attach to the released Scene Version and remain meaningful even if the canonical Scene later changes. Feedback does not automatically change Scene or contribution status. An attributed Author may keep the current state, reopen the affected contribution for writing, or initiate another editorial cycle according to the workflow rules.

## Recommendation

Preserve READER as a Book Role, but do not grant it a general `read current manuscript` capability. Introduce a resource-scoped reader release or reading pass whose content is an immutable set of released Scene Versions. Only versions captured when their canonical Scenes are `REVISED` or `FINAL` should initially be eligible.

This is not the same as Dabble's editable branch. IWrite needs only immutable version references plus Reader comments and access lifecycle. The canonical Manuscript remains singular and authoritative.

The #145 foundation should distinguish eligibility to participate as a Reader from authorization to read a particular released version. The #148 Reader experience can later implement creation, incremental releases, progress, completion, and feedback presentation without replacing the role or capability foundation.

## Open questions

- Whether a later release appends newly revised Scenes to an active pass or always creates a new pass.
- Whether replacing a previously released Scene requires explicit Reader acknowledgement before their view changes.
- Whether comments from different Readers are private to Authors by default, visible to all Readers in the same pass, or configurable.
- Which attributed Authors must approve releasing a multi-Author Scene.
- Whether reopening because of Reader feedback returns a contribution to `DRAFT`, `WRITTEN`, or offers both depending on whether authoring work is needed.
- Reader access expiry, completion, revocation, offline retention, and notification behavior.

## Sources

- [Google Docs: access levels](https://support.google.com/docs/answer/16722399?co=GENIE.Platform%3DDesktop&hl=En)
- [Dabble: read and comment as a beta reader](https://www.dabblewriter.com/docs/collaboration-sharing/beta-reader-guide)
- [Dabble: work with review copies](https://www.dabblewriter.com/docs/collaboration-sharing/review-copies)
- [Reedsy Studio](https://reedsy.com/studio/)
- [Reedsy Studio FAQ](https://reedsy.com/studio/resources/book-writing-software-faq/)
- [BetaReader.io](https://betareader.io/)
- [BetaReader.io: how to share your work](https://betareader.io/help/how-to-share-your-work)
- [StoryOrigin](https://storyoriginapp.com/)
- [Stephen King: *On Writing* twentieth-anniversary reading guide](https://stephenking.com/p/20-years-on-writing/index.html)
