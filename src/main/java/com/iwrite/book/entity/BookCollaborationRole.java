package com.iwrite.book.entity;

import java.util.Optional;

/**
 * Role catalog persistable on a Collaboration Invitation.
 *
 * <p>AUTHOR, EDITOR and READER are the assignable Book Roles a new invitation may request; each
 * carries the {@link BookRole} an acceptance flow would grant. COLLABORATOR is preserved only
 * because invitations created before Book Roles existed carry it: such a row stays auditable and
 * revocable, but it grants no Book Role by inference ({@link #grantedBookRole()} is empty), it is
 * never converted into an assignable role, and it can never be requested again. The explicit
 * acceptance lifecycle belongs to #147.
 */
public enum BookCollaborationRole {

    AUTHOR(BookRole.AUTHOR),
    EDITOR(BookRole.EDITOR),
    READER(BookRole.READER),
    COLLABORATOR(null);

    private final BookRole grantedBookRole;

    BookCollaborationRole(BookRole grantedBookRole) {
        this.grantedBookRole = grantedBookRole;
    }

    /**
     * The assignable Book Role an acceptance flow would grant for this invitation, or empty for a
     * legacy COLLABORATOR invitation. A legacy invitation has no conversion into a grantable role, so
     * it can never be inferred into a usable grant.
     */
    public Optional<BookRole> grantedBookRole() {
        return Optional.ofNullable(grantedBookRole);
    }

    /** Whether accepting this invitation may produce a Book Role grant. */
    public boolean isAssignable() {
        return grantedBookRole != null;
    }
}
