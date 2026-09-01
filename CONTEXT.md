# IWrite Domain Context

This file is a canonical glossary for agents and contributors. It defines domain language, not implementation details or feature specifications.

## Identity and access

### User
A person with an IWrite account. A User may participate in multiple Workspaces and may have different roles in different Books.

### Persona
A descriptive identity declared by a User, such as writer, editor, reviewer, or beta reader. A Persona expresses how the User works or presents themselves. It never grants authorization.

### Workspace
The user-facing collaboration context that groups membership and resources. A User can own a personal Workspace and may participate in other Workspaces.

### Tenant
The isolation scope used by the system to keep Workspace data separated. In product language prefer Workspace unless discussing isolation semantics. Tenant identity is never chosen authoritatively by the browser.

### Workspace Membership
The relationship that allows a User to participate in a Workspace. Workspace Membership determines workspace-level authority, not permissions inside every Book.

### Workspace Role
The administrative role associated with a Workspace Membership, such as owner or guest. A Workspace Role does not automatically grant a Book Role.

### Book Owner
The User who owns a Book and controls the book-level collaboration relationship. Ownership is explicit and distinct from generic Workspace Membership.

### Book Collaborator
A User who has been granted contextual access to a Book owned by another User or beyond their ownership rights.

### Book Role
The explicit role a User holds in a specific Book, such as author, editor, or reader. A Book Role is contextual and can be revoked.

### Book Authorship Credit
A public or bibliographic attribution identifying a person or party as an author or coauthor of a Book. Book Authorship Credit is distinct from Book Role and from the provenance of Authored Contributions. It grants no Capability and is not inferred automatically from access, contribution volume, comments, reviews, or accepted suggestions.

### Capability
An operation that a User is permitted to perform in a Book, such as reading manuscript content, commenting, suggesting, editing text, changing structure, exporting, or managing collaborators. Authorization should be expressed in terms of the minimum required Capability when practical.

### Book Capability Policy
The centralized domain rule that derives Book-scoped Capabilities from explicit ownership or an accepted Book Role. A Book Capability Policy may establish eligibility for a resource-scoped operation without being sufficient to authorize it; changing an Authored Contribution also requires authority over that contribution. Later collaboration workflows compose with this policy rather than redefining Book access independently.

### Book Role Transition
A change from one accepted Book Role to another for an existing Book Collaborator. A Book Role Transition is distinct from defining the supported roles and their Capability policy, and from revoking access entirely.

### Editorial Specialty
A descriptive area of editorial practice, such as sensitive-content review, research and fact-checking, orthography, continuity, or style. An Editorial Specialty may help an Author choose an Editor for particular work, but it does not grant a Book Role or Capability.

### Editorial Review Service
A category of editorial work requested for a Scene, such as orthographic review, sensitive-content review, continuity review, or research and fact-checking. A requested service may be matched to an Editor with a corresponding Editorial Specialty, but the service itself grants no access.

### Collaboration Invitation
A pending offer from an authorized sender to a specific recipient to receive a Book Role. An invitation does not grant access until the required acceptance flow succeeds.

## Writing domain

### Book
The main writing project. A Book contains manuscript structure, narrative planning, notebook material, progress information, collaboration, and related metadata.

### Manuscript
The ordered written content of a Book. The manuscript is distinct from planning notes, analytics, audit data, and collaboration metadata.

### Manuscript Structure Mutation
The creation, renaming, reordering, movement, or deletion of a Book Section, Chapter, or Scene. A Manuscript Structure Mutation is distinct from changing text or structure inside an Authored Contribution. In the initial assignable-role policy, only the Book Owner may perform it; the non-assignable legacy compatibility role preserves prior access without establishing a capability for new collaborators. Any future authority for Authors requires an additional contextual policy.

### Book Section
A structural grouping inside a Book used to organize Chapters.

### Chapter
An ordered manuscript unit inside a Book Section. A Chapter contains Scenes.

### Scene
The primary editable unit of manuscript content. A Scene may also carry planning information such as point of view, participants, goal, conflict, outcome, and notes.

### Canonical Planning
The Author-controlled narrative planning material of a Book, including Scene planning and related Narrative Entities. In the initial Book Capability Policy, the Book Owner and Authors may change it, Editors may read it while writing their own Editor Planning, and Readers have no access.

### Scene Author
An Author attributed to writing a particular Scene. A Scene may have one or multiple Scene Authors. Scene Authorship is distinct from Book ownership and from merely holding the Author Book Role.

### Authored Contribution
A portion or change set of a Scene whose authorship is attributed to one or multiple Scene Authors. Attribution derives from authenticated authoring activity and cannot be claimed by selecting another Author's text. An Author cannot directly change an Authored Contribution attributed exclusively to another Author. Moving, splitting, joining, or deleting a contribution counts as changing it even when its words remain untouched. Authored Contributions allow editorial work and readiness decisions to target the participating Authors' text without assigning responsibility for unrelated parts of the Scene. Revoking an attributed User's Book access prevents any further access or mutation by that User but does not remove the contribution from the manuscript or erase its historical provenance.

### Contribution Confirmation
An explicit confirmation by every Scene Author of an Authored Contribution that its current state is ready for the requested workflow transition. Authors of unrelated contributions do not confirm it. A subsequent change to the contribution invalidates confirmations tied to its previous state.

### Scene Version
An immutable historical snapshot of a Scene that can be inspected or restored according to the versioning rules. In the initial assignable-role policy, the Book Owner, Authors, and Editors may inspect versions of Scenes they can otherwise access; Readers cannot inspect version history. A Reader Review Release may expose the content of an explicitly released Scene Version without granting access to version history or the current Manuscript. Only the Book Owner may restore a Scene Version in this first partition. Future contribution-aware restoration for Authors must preserve authority over Authored Contributions rather than treating Book Role alone as sufficient.

### Reader Review Release
A Book-scoped, immutable selection of Scene Versions deliberately released for audience feedback. In the initial policy, a Scene Version is eligible only when its canonical Scene is `REVISED` or `FINAL` at release time. A release may contain selected Scenes or Chapters and does not change when the canonical Manuscript is later edited, reopened, reordered, or deleted. Reader Book Role establishes eligibility to participate but does not grant access to the current Manuscript, unreleased structure, version history, or every release; access remains scoped to releases made available to that Reader. Reader comments remain attached to the released context and do not automatically change Scene or Authored Contribution status.

### Editorial Comment
Freeform feedback attached to manuscript context. A comment may contain advice, but it is not a structured manuscript change that the system can accept and apply.

### Editorial Suggestion
A structured proposed textual or structural change, analogous to a tracked change in a word processor, associated with a specific Authored Contribution state. It does not alter the authoritative manuscript until the attributed Author or Authors accept it through the editorial workflow. Acceptance incorporates the result into the existing Authored Contribution without making the proposer its Author. Rejection requires a Suggestion Rejection Rationale. A Book Owner may accept or reject a suggestion only when it affects their own Authored Contribution; Book ownership alone grants no authority over another Author's contribution.

### Suggestion Attribution
The historical association between an Editorial Suggestion and the User who proposed it. Suggestion Attribution remains available after acceptance or rejection for editorial traceability, but it does not create Scene Authorship, contribution authorship, a Book Role, or public authorship credit.

### Suggestion Rejection Rationale
A nonblank, durable message recorded by the attributed Author or Authors when they reject an Editorial Suggestion. It explains the decision to the proposer and remains associated with the suggestion for editorial traceability. A meeting may inform the rationale, but discussion alone does not replace its record in the editorial workflow. Requiring a rationale does not grant the proposer veto power, make the suggestion authoritative, or authorize the system to judge the substantive quality of the explanation automatically.

### Editor Planning
Book-scoped working material in which an Editor develops research, fact-checking evidence, continuity observations, and other editorial planning without changing the Authors' canonical Planning or Notebook. Editor Planning is distinct from an Editorial Comment or Editorial Suggestion until the Editor explicitly submits relevant material into the editorial workflow.

### Editor Planning Submission
An explicit delivery of selected Editor Planning material to one or more Authors for consideration in an author-facing editorial area. A submission does not itself change canonical Planning, Notebook, or manuscript content. A proposed textual or structural manuscript change remains an Editorial Suggestion even when supported by an Editor Planning Submission.

### Editorial Review Cycle
A bounded review initiated by an authorized Scene Author for one or more Authored Contributions. A cycle groups the relevant editorial work and its decisions without granting additional access to the Book or making an Editor responsible for unrelated Scene text. A contribution may pass through multiple cycles while it remains written but not yet revised.

### Editorial Review Submission
The immutable contribution diff, requested Editorial Review Services, assigned Editors, and optional contextual brief submitted into an Editorial Review Cycle. An assigned Editor reviews the submitted diff; existing Book access may allow them to inspect the surrounding Scene, but doing so does not expand the assignment's responsibility.

### Contribution Review Lock
The restriction that prevents the submitting Author from changing an Authored Contribution while its formal editorial review remains active. The lock does not block other Authors' unrelated contributions and can end through completion or explicit withdrawal of the review workflow.

### Editorial Consultation
An asynchronous question from an Author to an Editor that may reference a Book, Scene, or editorial topic without submitting an Authored Contribution for formal review. A consultation does not lock manuscript text and does not substitute for an Editorial Review Submission.

### Editorial Assignment
A request for a specific Editor to provide an Editorial Review Service in an Editorial Review Cycle, usually because of a corresponding Editorial Specialty. An assignment creates an expectation of review and may trigger notifications, but it does not grant access beyond the Editor's existing Book Role. Each assigned Editor returns their own suggestions and Editorial Review Note to the requesting Author.

### Editorial Review Note
A nonblank message in which an assigned Editor records the result of reviewing the submitted contribution, including when no change is necessary. An Editor cannot complete an Editorial Assignment without at least one Editorial Review Note associated with that assignment.

### Contribution Revision Completion
The explicit decision by the attributed Authors that no further Editorial Review Cycle is needed for the current Authored Contribution. Completing an Editor's assignment returns that review to the Author; it does not by itself make the contribution revised. The contribution becomes revised only through Contribution Revision Completion.

### Scene Integration Review
A whole-Scene editorial review concerned with coherence between Authored Contributions, usually performed by a general or continuity Editor. It is required when a Scene contains contributions from multiple Authors and is distinct from the Editors' responsibility for individual submitted diffs.

### Narrative Entity
A structured planning entity associated with a Book, currently including Character, Location, and Item.

### Notebook
Book-scoped canonical supporting material organized independently from the manuscript hierarchy. In the initial Book Capability Policy, the Book Owner and Authors may change it, Editors may read it while writing their own Editor Planning, and Readers have no access.

### Full Book Export
An explicit creation of a portable copy of the complete Manuscript or Notebook for use outside IWrite. Full Book Export is distinct from reading the same material in the product and from an Offline Working Set. In the initial Book Capability Policy, only the Book Owner and Authors may export the Manuscript or Notebook; Editors and Readers cannot. A future export limited to material covered by an Editorial Assignment is a resource-scoped editorial operation, not Full Book Export and not a general capability of the Editor Book Role.

### Offline Working Set
A temporary device-local representation managed by IWrite so that previously authorized work can remain available through a connectivity interruption. An Offline Working Set is not a user-requested portable export and does not expand the Book resources or operations for which the User was authorized. Because a disconnected client cannot observe a server-side revocation immediately, offline authorization needs bounded validity, visible offline state, revalidation, conflict handling, and cleanup rules; reconnecting never permits queued work to bypass current Book Capabilities or contribution authority.

## Progress and history

### Word Count
The authoritative count of manuscript words computed according to backend rules. Client-side counts are not authoritative.

### Word Count Event
An auditable event that records a change relevant to manuscript word-count history.

### Writing Progress
A User-specific record of writing activity for a Book and historical writing date. It is not the same as the Book's current manuscript size.

### Book Contributor Progress
A Book-scoped view of attributable writing activity for a selected current or former Book Collaborator. An authorized current Book Owner, Author, or Editor may inspect that collaborator's Quantitative Contribution Metrics by date or period and the Scenes and Chapters in the same Book from which the activity originated. Historical activity remains visible after its contributor loses Book access, but revocation gives that former collaborator no residual access or mutation Capability. Selecting a collaborator updates only contributor-dependent Book dashboard data; shared manuscript totals and other Book-wide state remain Book-scoped. Book Contributor Progress never reveals the collaborator's other Books, cross-Book totals, Personal Book Writing Goal, or Personal Writing Dashboard. Readers have no access. The server validates both the requester's current Book access and the selected contributor's current or historical relationship to the Book; a browser-supplied contributor identifier is only a filter and cannot enumerate unrelated Users.

### Quantitative Contribution Metrics
Book-scoped factual measures of attributable writing activity, including Productive Writing, Manuscript Adjustments, writing days, distinct Scenes and Chapters with recorded contributions, and their distribution across a selected period. These dimensions are displayed together rather than collapsed into a contribution score or ranking. Scene count, word count, and pace do not by themselves measure narrative quality, effort, importance, professional performance, or public authorship credit. In a multi-Author Scene, metrics must use Authored Contribution provenance and must not attribute the entire Scene or its word count independently to every Author.

### Personal Book Writing Goal
An optional User-owned writing target inside a Book, such as a personal daily word target and planned writing days. A Book Owner or Author may manage only their own Personal Book Writing Goal. It is not shown to other collaborators through Book Contributor Progress. Absence of a word target means no target was chosen, not zero progress, failure, or lower contribution. A Book-wide target remains a separate optional setting controlled by the Book Owner.

### Personal Writing Dashboard
The private User-scoped view that aggregates the authenticated User's own writing activity across Books, including cross-Book totals and personal writing history. It is distinct from Book Contributor Progress and is not exposed to another Book Collaborator merely because they may inspect that User's activity inside a shared Book.

### Productive Writing
Writing activity counted toward productivity according to the domain rules. It is intentionally distinct from net manuscript growth.

### Manuscript Adjustment
A change that affects current manuscript size without necessarily representing productive writing, such as deletion or restoration behavior defined by the domain.

### Writing Streak
A sequence derived from qualifying writing activity. It is distinct from general product/project activity.

## AI and integration

### Writing Assistant
The provider-neutral product capability that performs optional AI-assisted writing analysis. In the initial Book Capability Policy, the Book Owner, Authors, and Editors are eligible to request Scene analysis; Readers are not. Eligibility through Book Role does not guarantee commercial availability, quota, provider availability, or access to the requested resource.

### AI Assistant Type
A user-selectable analysis profile offered for a particular purpose. The catalog includes a General AI Assistant that analyzes a broad set of writing concerns and may include specialized types for context and fact-checking, continuity, orthography, narrative, and other focused work. An AI Assistant Type is distinct from an Editorial Specialty and from a provider or model: a User chooses among entitled assistant types independently of Persona, Book Role, or professional specialty.

### AI Assistant Entitlement
The commercial availability and usage allowance that permits a User to invoke a particular AI Assistant Type, potentially through a subscription, plan, or limited free quota. It neither grants access to a Book nor changes the User's Book Role or Editorial Specialty. An AI-assisted operation requires both the relevant Book Capability and resource access and a valid entitlement with remaining allowance. No User is required to subscribe, and unavailable AI never blocks the corresponding human workflow.

### AI-Assisted Qualitative Contribution Report
An advisory Book-scoped report that may analyze narrative effects, context, and other qualitative nuances of Authored Contributions without treating word or Scene counts as quality. The generated report remains an Owner-only draft unless the Book Owner explicitly approves its distribution to current Authors and Editors. It is distinct from Quantitative Contribution Metrics and cannot automatically determine Book Role, access, public authorship credit, professional reputation, compensation, or acceptance of manuscript changes. Its evidence, uncertainty, fairness, visibility, retention, and distribution rules require a separate product workflow.

### LLM Execution
A single audited invocation of a language model provider, including controlled operational metadata such as status, latency, token usage, and configured cost where available.

### MCP Tool
A controlled external tool surface that exposes an existing IWrite capability through MCP. MCP does not create a second domain model or authorization model.

## Operational language

### Domain Audit Event
A privacy-minimized record that a relevant domain action occurred, who performed it where appropriate, what resource category it affected, and its result. It is not a copy of manuscript content.

### Active Workspace
The Workspace currently selected for an authenticated session when the account can participate in more than one. Selection must be validated by the server.

### Non-enumerable Access
An authorization behavior where inaccessible resources do not reveal whether a guessed identifier exists when the relevant contract requires that protection.

## Language rules

- Do not use `Persona`, `Workspace Role`, and `Book Role` interchangeably.
- Do not use Book Role, Authored Contribution provenance, and Book Authorship Credit interchangeably.
- Do not treat a Book-scoped Capability as sufficient for a mutation whose domain also requires authority over an Authored Contribution.
- Do not use an Editorial Specialty as a Book Role or Capability.
- Do not call every authenticated relationship `membership`; specify Workspace Membership or Book Collaborator when the distinction matters.
- Prefer `Workspace` in user-facing product discussion and `Tenant` when discussing technical isolation semantics.
- Distinguish an Editorial Comment from an Editorial Suggestion that proposes a structured manuscript change.
- Distinguish Suggestion Attribution from authorship of the resulting manuscript contribution.
- Distinguish completion of an Editorial Assignment from Contribution Revision Completion.
- Distinguish current manuscript size, Productive Writing, and Manuscript Adjustment.
- Distinguish Book Contributor Progress from a collaborator's Personal Writing Dashboard and cross-Book activity.
- Do not turn Quantitative Contribution Metrics into a qualitative score, ranking, or claim about creative value.
- Treat a missing Personal Book Writing Goal as an intentional absence of a target, not as zero performance.
- Distinguish Full Book Export from an application-managed Offline Working Set and from any future export scoped to an Editorial Assignment.
- Distinguish a Reader Review Release from the current Manuscript, a dynamic filter of current `REVISED` Scenes, and general Scene Version history.
- Distinguish Book Capability and resource authorization from AI Assistant Entitlement, quota, provider, and model selection.
- Do not infer an AI Assistant Type or subscription from Persona, Book Role, or Editorial Specialty.
- Distinguish an AI-Assisted Qualitative Contribution Report from factual dashboard metrics and require Owner approval before distribution.
- Treat Scene Version as historical state, not as a second editable Scene.
- Treat MCP and LLM providers as integration surfaces, not as owners of domain rules.
