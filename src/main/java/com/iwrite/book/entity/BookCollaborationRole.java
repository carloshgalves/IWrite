package com.iwrite.book.entity;

/**
 * Role catalog persistable on a Collaboration Invitation.
 *
 * <p>AUTHOR, EDITOR and READER are the assignable Book Roles a new invitation may request.
 * COLLABORATOR is preserved only because invitations created before Book Roles existed carry it: such
 * a row stays auditable and revocable, but it is never converted by inference into an assignable role,
 * and it can never be requested again. The explicit acceptance lifecycle belongs to #147.
 */
public enum BookCollaborationRole {

    AUTHOR(BookRole.AUTHOR),
    EDITOR(BookRole.EDITOR),
    READER(BookRole.READER),
    COLLABORATOR(BookRole.LEGACY_COLLABORATOR);

    private final BookRole bookRole;

    BookCollaborationRole(BookRole bookRole) {
        this.bookRole = bookRole;
    }

    /** The Book Role this invitation would grant once an acceptance flow exists. */
    public BookRole bookRole() {
        return bookRole;
    }

    public boolean isAssignable() {
        return bookRole.isAssignable();
    }
}
