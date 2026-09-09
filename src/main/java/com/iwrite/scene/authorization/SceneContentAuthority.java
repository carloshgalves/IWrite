package com.iwrite.scene.authorization;

import com.iwrite.book.authorization.BookAccessContext;
import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.authorization.BookRelationship;

/**
 * The resource-scoped predicate that {@link BookCapability#EDIT_AUTHORED_CONTRIBUTION} still requires
 * after the Book Capability Policy has answered {@code CONTEXTUAL} (#145, #207).
 *
 * <p>Until Authored Contributions exist (#184), a Scene carries no contribution provenance, so the
 * only User who can be shown to have authority over the whole canonical text of a Scene is the Book
 * Owner. Holding the AUTHOR Book Role makes a User eligible and nothing more: the role alone must not
 * become broad authority over text whose authorship the system cannot yet attribute.
 *
 * <p>The legacy compatibility role never reaches this predicate — the policy grants it the capability
 * outright to preserve the surface it had before Book Roles existed — and no new role inherits that.
 *
 * <p>#184 replaces the body of this predicate with real contribution attribution. It exists as one
 * named rule so that change has a single site instead of being spread through the Scene surfaces.
 */
public final class SceneContentAuthority {

    private SceneContentAuthority() {
    }

    /** Whether this access may change the canonical content of a Scene of the Book it was resolved for. */
    public static boolean canEditSceneContent(BookAccessContext access) {
        if (access.isGranted(BookCapability.EDIT_AUTHORED_CONTRIBUTION)) {
            return true;
        }
        return access.isEligible(BookCapability.EDIT_AUTHORED_CONTRIBUTION)
                && access.relationship() == BookRelationship.OWNER;
    }
}
