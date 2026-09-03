package com.iwrite.book.authorization;

import com.iwrite.book.entity.BookRole;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The effective Book access of one authenticated User, resolved entirely by the backend from the
 * session identity, the active Workspace, the persisted collaboration and the Book itself.
 *
 * <p>The browser never asserts any of these values. Projecting the context lets a consumer show only
 * the actions it may attempt; it never becomes the authorization boundary, and a request that echoes
 * a capability back is not authority.
 *
 * @param role the accepted Book Role, or {@code null} when the relationship is ownership
 * @param capabilities capabilities authorized by Book scope alone
 * @param contextualCapabilities capabilities the User is eligible for, each still subject to the
 *                               resource-scoped predicate of the operation
 */
public record BookAccessContext(
        UUID bookId,
        UUID tenantId,
        UUID userId,
        BookRelationship relationship,
        BookRole role,
        Set<BookCapability> capabilities,
        Set<BookCapability> contextualCapabilities
) {

    public BookAccessContext {
        capabilities = immutableCopy(capabilities);
        contextualCapabilities = immutableCopy(contextualCapabilities);
    }

    /** Whether Book-scoped authorization alone is sufficient for the capability. */
    public boolean isGranted(BookCapability capability) {
        return capabilities.contains(capability);
    }

    /** Whether the capability is granted or contextually available to this access context. */
    public boolean isEligible(BookCapability capability) {
        return isGranted(capability) || contextualCapabilities.contains(capability);
    }

    private static Set<BookCapability> immutableCopy(Set<BookCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Collections.unmodifiableSet(EnumSet.noneOf(BookCapability.class));
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }
}
