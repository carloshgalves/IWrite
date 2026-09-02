package com.iwrite.book.service;

import com.iwrite.book.authorization.BookAccessContext;
import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.authorization.BookCapabilityPolicy;
import com.iwrite.book.authorization.BookRelationship;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.AccessibleBookWithRole;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The authorization boundary for Book-scoped access.
 *
 * <p>Identity, active Workspace, Workspace Membership, relationship and Book Role are resolved here
 * from the session and from persistence; capabilities are then derived by the single
 * {@link BookCapabilityPolicy}. Nothing in a request participates in that derivation.
 *
 * <p>Every denial — absent session, unknown Book, another Book, another Workspace, no Book Role,
 * revoked access, or a capability this context does not hold — raises the same public
 * {@code Book not found}, so a caller cannot enumerate identifiers it is not authorized for.
 */
@Service
public class BookAccessService {

    private final BookRepository bookRepository;
    private final BookCapabilityPolicy capabilityPolicy;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;

    public BookAccessService(
            BookRepository bookRepository,
            BookCapabilityPolicy capabilityPolicy,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService
    ) {
        this.bookRepository = bookRepository;
        this.capabilityPolicy = capabilityPolicy;
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
    }

    /**
     * Resolves the effective access context of the current User for a Book, or fails with the public
     * not-found semantics when there is no accessible relationship at all.
     */
    @Transactional(readOnly = true)
    public BookAccessContext resolveAccessContext(UUID bookId) {
        return resolveAccessibleBook(bookId).access();
    }

    /**
     * Resolves the Book together with the effective access of the current User, so a caller that needs
     * both does not read the Book twice.
     */
    @Transactional(readOnly = true)
    public AccessibleBook resolveAccessibleBook(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return accessibleBook(bookId, currentUserProvider.tenantId(), userId);
    }

    /**
     * Requires that Book scope alone authorizes the capability, and returns the Book.
     *
     * <p>An operation whose domain also depends on a resource-scoped predicate must use
     * {@link #requireCapabilityEligibility(UUID, BookCapability)} and evaluate that predicate itself.
     */
    @Transactional(readOnly = true)
    public Book requireCapability(UUID bookId, BookCapability capability) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        AccessibleBook accessible = accessibleBook(bookId, currentUserProvider.tenantId(), userId);
        requireGranted(accessible.access(), capability, bookId);
        return accessible.book();
    }

    /**
     * Mutation variant: proves the capability, takes the Book row lock so a concurrent change
     * serializes on the same Book, and then proves it again under the lock.
     *
     * <p>The proof comes first because the row lock belongs to authorized mutations: a caller this
     * Book never authorizes must be denied without queueing on a lock that an authorized mutation is
     * holding, and without interacting with the resource before its access is proven. The second proof
     * is what preserves revocation: a revocation committed while this transaction waited for the lock
     * is visible to the statements that run after it, so a stale preflight is never trusted.
     */
    @Transactional
    public Book requireCapabilityForUpdate(UUID bookId, BookCapability capability) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        requireGranted(accessibleBook(bookId, tenantId, userId).access(), capability, bookId);

        Book lockedBook = bookRepository.findByIdAndTenantIdForUpdate(bookId, tenantId)
                .orElseThrow(() -> bookNotFound(bookId));
        requireGranted(accessibleBook(bookId, tenantId, userId).access(), capability, bookId);
        return lockedBook;
    }

    /**
     * Requires Book-scoped eligibility for a capability whose domain also needs a resource-scoped
     * predicate, and returns the resolved context so the resource service can compose its own rule.
     * Eligibility is never sufficient on its own.
     */
    @Transactional(readOnly = true)
    public BookAccessContext requireCapabilityEligibility(UUID bookId, BookCapability capability) {
        BookAccessContext context = resolveAccessContext(bookId);
        if (!context.isEligible(capability)) {
            throw bookNotFound(bookId);
        }
        return context;
    }

    /**
     * Legacy generic read guard. It still answers "is this Book accessible at all" and remains in place
     * only while the surfaces it protects are migrated to their minimum capability (#206 to #212).
     */
    @Transactional(readOnly = true)
    public Book requireBookReadAccess(UUID bookId) {
        return requireAccessibleBook(bookId);
    }

    /** Legacy generic edit guard, semantically equivalent to the read guard until the same migration. */
    @Transactional(readOnly = true)
    public Book requireBookEditAccess(UUID bookId) {
        return requireAccessibleBook(bookId);
    }

    /**
     * Legacy generic mutation guard. It follows the same order as
     * {@link #requireCapabilityForUpdate(UUID, BookCapability)}: prove access, lock the Book, prove it
     * again under the lock.
     */
    @Transactional
    public Book requireBookEditAccessForUpdate(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        accessibleBook(bookId, tenantId, userId);

        Book lockedBook = bookRepository.findByIdAndTenantIdForUpdate(bookId, tenantId)
                .orElseThrow(() -> bookNotFound(bookId));
        accessibleBook(bookId, tenantId, userId);
        return lockedBook;
    }

    /**
     * Derives the access context of an already loaded Book for a User of the same Workspace, without
     * re-reading the Book. Ownership is checked first: it is the explicit relationship, never a role.
     */
    @Transactional(readOnly = true)
    public BookAccessContext accessContextFor(Book book, UUID userId, BookRole role) {
        UUID tenantId = book.getTenant().getId();
        if (book.getOwner().getId().equals(userId)) {
            return capabilityPolicy.contextFor(book.getId(), tenantId, userId, BookRelationship.OWNER, null);
        }
        if (role == null) {
            throw bookNotFound(book.getId());
        }
        return capabilityPolicy.contextFor(book.getId(), tenantId, userId, BookRelationship.COLLABORATOR, role);
    }

    /**
     * Resolves the Book and the Book Role behind the User's access with the single accessible-Book
     * query, so an unknown Book and an inaccessible one are denied by the same statement.
     */
    private AccessibleBook accessibleBook(UUID bookId, UUID tenantId, UUID userId) {
        AccessibleBookWithRole accessible = bookRepository
                .findAccessibleWithRoleByIdAndTenantIdAndUserId(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
        Book book = accessible.book();
        return new AccessibleBook(book, accessContextFor(book, userId, accessible.role()));
    }

    private void requireGranted(BookAccessContext context, BookCapability capability, UUID bookId) {
        if (!context.isGranted(capability)) {
            throw bookNotFound(bookId);
        }
    }

    private Book requireAccessibleBook(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return accessibleBook(bookId, currentUserProvider.tenantId(), userId).book();
    }

    private ResourceNotFoundException bookNotFound(UUID bookId) {
        return new ResourceNotFoundException("Book not found: " + bookId);
    }

    /** A Book the current User may access, paired with the access that was resolved for it. */
    public record AccessibleBook(Book book, BookAccessContext access) {
    }
}
