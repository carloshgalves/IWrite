package com.iwrite.book.entity;

/**
 * Closed catalog of roles persistable on a Book Collaborator (#145).
 *
 * <p>AUTHOR, EDITOR and READER are the assignable product roles. LEGACY_COLLABORATOR is not a fourth
 * product role: it preserves the effective surface of relationships created before roles existed and
 * can never be chosen by UI, API, a new grant or a new invitation.
 *
 * <p>A Book Role is contextual and revocable, and it is distinct from Persona, Workspace Role,
 * Editorial Specialty and Book Authorship Credit. None of those grant or elevate it.
 */
public enum BookRole {

    AUTHOR(true),
    EDITOR(true),
    READER(true),
    LEGACY_COLLABORATOR(false);

    private final boolean assignable;

    BookRole(boolean assignable) {
        this.assignable = assignable;
    }

    /**
     * Whether the role may be requested for a new grant or invitation. The compatibility role is
     * persistable but never assignable.
     */
    public boolean isAssignable() {
        return assignable;
    }
}
