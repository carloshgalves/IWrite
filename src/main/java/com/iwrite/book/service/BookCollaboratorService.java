package com.iwrite.book.service;

import com.iwrite.book.dto.BookCollaboratorResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BookCollaboratorService {

    private final BookAccessService bookAccessService;
    private final BookRepository bookRepository;
    private final BookCollaboratorRepository collaboratorRepository;
    private final TenantMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;

    public BookCollaboratorService(
            BookAccessService bookAccessService,
            BookRepository bookRepository,
            BookCollaboratorRepository collaboratorRepository,
            TenantMembershipRepository membershipRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService
    ) {
        this.bookAccessService = bookAccessService;
        this.bookRepository = bookRepository;
        this.collaboratorRepository = collaboratorRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
    }

    @Transactional(readOnly = true)
    public List<BookCollaboratorResponse> list(UUID bookId) {
        Book book = bookAccessService.requireBookOwnerAccess(bookId);
        return collaboratorRepository.findByBook_IdAndTenant_IdOrderByUser_DisplayNameAscUser_IdAsc(
                        book.getId(),
                        book.getTenant().getId()
                )
                .stream()
                .map(BookCollaboratorResponse::fromEntity)
                .toList();
    }

    @Transactional
    public BookCollaboratorResponse add(UUID bookId, UUID targetUserId) {
        Book book = bookAccessService.requireBookOwnerAccessForUpdate(bookId);
        UUID grantorUserId = currentUserMembershipService.requireCurrentUserMemberId();
        BookCollaboratorGrantResult result = grantLocked(book, targetUserId, grantorUserId);
        if (result == BookCollaboratorGrantResult.ALREADY_GRANTED) {
            throw new ConflictException("Book collaborator already exists.");
        }
        return collaboratorRepository.findByBook_IdAndTenant_IdAndUser_Id(
                        book.getId(),
                        book.getTenant().getId(),
                        targetUserId
                )
                .map(BookCollaboratorResponse::fromEntity)
                .orElseThrow(() -> collaboratorTargetNotFound());
    }

    @Transactional
    public void remove(UUID bookId, UUID userId) {
        Book lockedBook = bookAccessService.requireBookOwnerAccessForUpdate(bookId);
        BookCollaborator collaborator = collaboratorRepository.findByBook_IdAndTenant_IdAndUser_Id(
                        lockedBook.getId(),
                        lockedBook.getTenant().getId(),
                        userId
                )
                .orElseThrow(() -> new ResourceNotFoundException("Book collaborator not found: " + userId));
        collaboratorRepository.delete(collaborator);
        collaboratorRepository.flush();
    }

    @Transactional
    public BookCollaboratorGrantResult grantInternal(UUID bookId, UUID targetUserId, UUID grantorUserId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found: " + bookId));
        Book lockedBook = lockBookForGrant(book.getId(), book.getTenant().getId(), grantorUserId);
        return grantLocked(lockedBook, targetUserId, grantorUserId);
    }

    private BookCollaboratorGrantResult grantLocked(Book lockedBook, UUID targetUserId, UUID grantorUserId) {
        if (lockedBook.getOwner().getId().equals(targetUserId)) {
            throw new ConflictException("Book owner cannot be added as a collaborator.");
        }

        UUID tenantId = lockedBook.getTenant().getId();
        if (!membershipRepository.existsByTenant_IdAndUser_Id(tenantId, targetUserId)) {
            throw collaboratorTargetNotFound();
        }
        if (collaboratorRepository.existsByBook_IdAndTenant_IdAndUser_Id(lockedBook.getId(), tenantId, targetUserId)) {
            return BookCollaboratorGrantResult.ALREADY_GRANTED;
        }

        BookCollaborator collaborator = new BookCollaborator();
        collaborator.setTenant(lockedBook.getTenant());
        collaborator.setBook(lockedBook);
        collaborator.setUser(userRepository.getReferenceById(targetUserId));
        collaborator.setCreatedBy(userRepository.getReferenceById(grantorUserId));
        // Compatibility phase (#205): grants still reproduce the previous generic surface. No public flow
        // offers AUTHOR, EDITOR or READER while the remaining surfaces are guarded by the legacy checks,
        // so a new grant must not elevate access. #213 makes new grants role-aware and drops this default.
        collaborator.setRole(BookRole.LEGACY_COLLABORATOR);

        try {
            collaboratorRepository.saveAndFlush(collaborator);
            return BookCollaboratorGrantResult.GRANTED;
        } catch (DataIntegrityViolationException exception) {
            if (collaboratorRepository.existsByBook_IdAndTenant_IdAndUser_Id(lockedBook.getId(), tenantId, targetUserId)) {
                return BookCollaboratorGrantResult.ALREADY_GRANTED;
            }
            throw collaboratorTargetNotFound();
        }
    }

    private Book lockBookForGrant(UUID bookId, UUID tenantId, UUID grantorUserId) {
        return bookRepository.findOwnedByIdAndTenantIdAndUserIdForUpdate(bookId, tenantId, grantorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found: " + bookId));
    }

    private ResourceNotFoundException collaboratorTargetNotFound() {
        return new ResourceNotFoundException("Book collaborator target not found");
    }
}
