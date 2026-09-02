package com.iwrite.book.authorization;

import com.iwrite.book.entity.BookRole;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.iwrite.book.authorization.BookCapabilityDecision.CONTEXTUAL;
import static com.iwrite.book.authorization.BookCapabilityDecision.DENIED;
import static com.iwrite.book.authorization.BookCapabilityDecision.GRANTED;

/**
 * The single Book Capability Policy (#145): the centralized, parameterized derivation of Book-scoped
 * capabilities from explicit ownership or an accepted Book Role.
 *
 * <p>Later collaboration workflows compose with this policy instead of redefining Book access. They
 * add resource-scoped predicates on top of a {@link BookCapabilityDecision#CONTEXTUAL} result; they
 * never build a parallel matrix and never turn a nominal role into authority.
 *
 * <p>Persona, Workspace Role, Editorial Specialty, AI Assistant Type, subscription and quota add no
 * capability here.
 */
@Component
public class BookCapabilityPolicy {

    private static final Map<BookCapability, Map<BookRole, BookCapabilityDecision>> COLLABORATOR_MATRIX =
            new EnumMap<>(BookCapability.class);
    private static final Map<BookCapability, BookCapabilityDecision> OWNER_MATRIX =
            new EnumMap<>(BookCapability.class);

    static {
        //     capability                        owner      AUTHOR     EDITOR   READER      LEGACY_COLLABORATOR
        define(BookCapability.READ_MANUSCRIPT, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.EDIT_AUTHORED_CONTRIBUTION, CONTEXTUAL, CONTEXTUAL, DENIED, DENIED, GRANTED);
        define(BookCapability.MUTATE_MANUSCRIPT_STRUCTURE, GRANTED, DENIED, DENIED, DENIED, GRANTED);
        define(BookCapability.READ_CANONICAL_PLANNING, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.EDIT_CANONICAL_PLANNING, GRANTED, GRANTED, DENIED, DENIED, GRANTED);
        define(BookCapability.READ_NOTEBOOK, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.EDIT_NOTEBOOK, GRANTED, GRANTED, DENIED, DENIED, GRANTED);
        define(BookCapability.VIEW_BOOK_CONTRIBUTOR_PROGRESS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL, GRANTED, GRANTED, DENIED, DENIED, GRANTED);
        define(BookCapability.EDIT_BOOK_SETTINGS, GRANTED, DENIED, DENIED, DENIED, GRANTED);
        define(BookCapability.CREATE_EDITORIAL_COMMENT, GRANTED, GRANTED, GRANTED, CONTEXTUAL, DENIED);
        define(BookCapability.CREATE_EDITORIAL_SUGGESTION, GRANTED, GRANTED, GRANTED, DENIED, DENIED);
        define(BookCapability.RESOLVE_EDITORIAL_SUGGESTION, CONTEXTUAL, CONTEXTUAL, DENIED, DENIED, DENIED);
        define(BookCapability.READ_SCENE_VERSIONS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.RESTORE_SCENE_VERSION, GRANTED, DENIED, DENIED, DENIED, GRANTED);
        define(BookCapability.EXPORT_MANUSCRIPT, GRANTED, GRANTED, DENIED, DENIED, GRANTED);
        define(BookCapability.EXPORT_NOTEBOOK, GRANTED, GRANTED, DENIED, DENIED, GRANTED);
        define(BookCapability.REQUEST_SCENE_AI_ANALYSIS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED);
        define(BookCapability.READ_READER_REVIEW_RELEASE, DENIED, DENIED, DENIED, CONTEXTUAL, DENIED);
        define(BookCapability.MANAGE_COLLABORATORS, GRANTED, DENIED, DENIED, DENIED, DENIED);
        define(BookCapability.DELETE_BOOK, GRANTED, DENIED, DENIED, DENIED, DENIED);

        if (OWNER_MATRIX.size() != BookCapability.values().length) {
            throw new IllegalStateException("Book Capability Policy must decide every capability.");
        }
    }

    private static void define(
            BookCapability capability,
            BookCapabilityDecision owner,
            BookCapabilityDecision author,
            BookCapabilityDecision editor,
            BookCapabilityDecision reader,
            BookCapabilityDecision legacyCollaborator
    ) {
        OWNER_MATRIX.put(capability, owner);
        Map<BookRole, BookCapabilityDecision> byRole = new EnumMap<>(BookRole.class);
        byRole.put(BookRole.AUTHOR, author);
        byRole.put(BookRole.EDITOR, editor);
        byRole.put(BookRole.READER, reader);
        byRole.put(BookRole.LEGACY_COLLABORATOR, legacyCollaborator);
        COLLABORATOR_MATRIX.put(capability, byRole);
    }

    /**
     * Decides one capability for a relationship resolved by the backend.
     *
     * @param role the accepted Book Role of a collaborator; must be {@code null} for the Book Owner,
     *             whose authority comes from ownership and not from a role
     */
    public BookCapabilityDecision decide(BookRelationship relationship, BookRole role, BookCapability capability) {
        if (relationship == null || capability == null) {
            throw new IllegalArgumentException("Relationship and capability are required to decide a Book capability.");
        }
        if (relationship == BookRelationship.OWNER) {
            if (role != null) {
                throw new IllegalArgumentException("The Book Owner relationship is not derived from a Book Role.");
            }
            return OWNER_MATRIX.get(capability);
        }
        if (role == null) {
            throw new IllegalArgumentException("A Book Collaborator relationship requires an accepted Book Role.");
        }
        return COLLABORATOR_MATRIX.get(capability).get(role);
    }

    /**
     * Derives the effective access context of a relationship already resolved from persistence.
     * The policy owns the capability derivation; resolving who the User is, which Workspace is active
     * and which Book Role is persisted stays in the authorization boundary that reads them.
     */
    public BookAccessContext contextFor(
            UUID bookId,
            UUID tenantId,
            UUID userId,
            BookRelationship relationship,
            BookRole role
    ) {
        return new BookAccessContext(
                bookId,
                tenantId,
                userId,
                relationship,
                role,
                grantedCapabilities(relationship, role),
                contextualCapabilities(relationship, role)
        );
    }

    /** Capabilities whose Book-scoped authorization is sufficient on its own. */
    public Set<BookCapability> grantedCapabilities(BookRelationship relationship, BookRole role) {
        return capabilitiesWith(relationship, role, GRANTED);
    }

    /** Capabilities the context is eligible for but which still require a resource-scoped predicate. */
    public Set<BookCapability> contextualCapabilities(BookRelationship relationship, BookRole role) {
        return capabilitiesWith(relationship, role, CONTEXTUAL);
    }

    private Set<BookCapability> capabilitiesWith(
            BookRelationship relationship,
            BookRole role,
            BookCapabilityDecision decision
    ) {
        Set<BookCapability> capabilities = EnumSet.noneOf(BookCapability.class);
        for (BookCapability capability : BookCapability.values()) {
            if (decide(relationship, role, capability) == decision) {
                capabilities.add(capability);
            }
        }
        return Collections.unmodifiableSet(capabilities);
    }
}
