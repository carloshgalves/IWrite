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
The explicit role a User holds in a specific Book, such as author/coauthor, editor, reviewer, beta reader, or viewer. A Book Role is contextual and can be revoked.

### Capability
An operation that a User is permitted to perform in a Book, such as reading manuscript content, commenting, suggesting, editing text, changing structure, exporting, or managing collaborators. Authorization should be expressed in terms of the minimum required Capability when practical.

### Collaboration Invitation
A pending offer from an authorized sender to a specific recipient to receive a Book Role. An invitation does not grant access until the required acceptance flow succeeds.

## Writing domain

### Book
The main writing project. A Book contains manuscript structure, narrative planning, notebook material, progress information, collaboration, and related metadata.

### Manuscript
The ordered written content of a Book. The manuscript is distinct from planning notes, analytics, audit data, and collaboration metadata.

### Book Section
A structural grouping inside a Book used to organize Chapters.

### Chapter
An ordered manuscript unit inside a Book Section. A Chapter contains Scenes.

### Scene
The primary editable unit of manuscript content. A Scene may also carry planning information such as point of view, participants, goal, conflict, outcome, and notes.

### Scene Version
An immutable historical snapshot of a Scene that can be inspected or restored according to the versioning rules.

### Narrative Entity
A structured planning entity associated with a Book, currently including Character, Location, and Item.

### Notebook
Book-scoped freeform supporting material organized independently from the manuscript hierarchy.

## Progress and history

### Word Count
The authoritative count of manuscript words computed according to backend rules. Client-side counts are not authoritative.

### Word Count Event
An auditable event that records a change relevant to manuscript word-count history.

### Writing Progress
A User-specific record of writing activity for a Book and historical writing date. It is not the same as the Book's current manuscript size.

### Productive Writing
Writing activity counted toward productivity according to the domain rules. It is intentionally distinct from net manuscript growth.

### Manuscript Adjustment
A change that affects current manuscript size without necessarily representing productive writing, such as deletion or restoration behavior defined by the domain.

### Writing Streak
A sequence derived from qualifying writing activity. It is distinct from general product/project activity.

## AI and integration

### Writing Assistant
The product capability that performs optional AI-assisted writing analysis while keeping provider-specific details outside the domain contract.

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
- Do not call every authenticated relationship `membership`; specify Workspace Membership or Book Collaborator when the distinction matters.
- Prefer `Workspace` in user-facing product discussion and `Tenant` when discussing technical isolation semantics.
- Distinguish current manuscript size, Productive Writing, and Manuscript Adjustment.
- Treat Scene Version as historical state, not as a second editable Scene.
- Treat MCP and LLM providers as integration surfaces, not as owners of domain rules.
