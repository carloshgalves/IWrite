-- Expand phase of the Book Role foundation (#205, parent #145).
--
-- Every collaboration relationship becomes an explicit, revocable role. Legacy rows are backfilled
-- to LEGACY_COLLABORATOR, the non-assignable compatibility role that reproduces exactly the effective
-- surface a generic collaborator already had. No AUTHOR, EDITOR or READER is inferred here: persona,
-- activity, ownership, email or any other heuristic would silently elevate or reduce access.
--
-- The column is added with a constant default, so PostgreSQL fills the existing rows from the catalog
-- without rewriting the table and without a window in which a row has no role. The default is also the
-- rollout compatibility path: an application instance that predates this migration keeps inserting
-- usable collaborator rows, and they land on the legacy surface instead of a new policy. #213 removes
-- the default once every surface is behind capabilities and new grants are role-aware.
alter table book_collaborators
    add column role varchar(32) not null default 'LEGACY_COLLABORATOR';

-- Closed catalog of persistable roles. AUTHOR, EDITOR and READER are the assignable product roles;
-- LEGACY_COLLABORATOR exists only for relationships that predate the role and can never be requested.
alter table book_collaborators
    add constraint chk_book_collaborators_role check (
        role in ('AUTHOR', 'EDITOR', 'READER', 'LEGACY_COLLABORATOR')
    );

-- Invitations move to the assignable roles. Already persisted COLLABORATOR invitations are preserved
-- as legacy state: they remain auditable and revocable, but they are not converted by inference into
-- an AUTHOR, EDITOR or READER grant. The explicit acceptance lifecycle belongs to #147.
alter table book_collaboration_invitations
    drop constraint chk_book_collaboration_invitations_role;

alter table book_collaboration_invitations
    add constraint chk_book_collaboration_invitations_role check (
        requested_role in ('AUTHOR', 'EDITOR', 'READER', 'COLLABORATOR')
    );
