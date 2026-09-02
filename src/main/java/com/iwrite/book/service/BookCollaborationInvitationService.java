package com.iwrite.book.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.dto.BookCollaborationInvitationCreationResult;
import com.iwrite.book.dto.BookCollaborationInvitationRequest;
import com.iwrite.book.dto.BookCollaborationInvitationResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborationInvitation;
import com.iwrite.book.entity.BookCollaborationInvitationStatus;
import com.iwrite.book.entity.BookCollaborationRole;
import com.iwrite.book.repository.BookCollaborationInvitationRepository;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BookCollaborationInvitationService {

    static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    /**
     * Compatibility phase (#205): while the Book surfaces are still guarded by the legacy generic
     * checks, no public flow may offer AUTHOR, EDITOR or READER — accepting one would promise an
     * authority the guards do not yet enforce. #213 opens this to the assignable roles and closes the
     * legacy value once every surface is behind its minimum capability.
     */
    private static final Set<BookCollaborationRole> REQUESTABLE_ROLES = EnumSet.of(BookCollaborationRole.COLLABORATOR);

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final BookAccessService bookAccessService;
    private final BookCollaborationInvitationRepository invitationRepository;
    private final InvitationTokenService tokenService;

    public BookCollaborationInvitationService(
            BookAccessService bookAccessService,
            BookCollaborationInvitationRepository invitationRepository,
            InvitationTokenService tokenService
    ) {
        this.bookAccessService = bookAccessService;
        this.invitationRepository = invitationRepository;
        this.tokenService = tokenService;
    }

    /**
     * Creates an invitation for a book owned by the current user and returns the
     * raw token exactly once. The owner lock serializes concurrent creations for
     * the same book; the partial unique index on pending invitations remains the
     * database-level guarantee against duplicate active invitations.
     */
    @Transactional
    public BookCollaborationInvitationCreationResult create(UUID bookId, BookCollaborationInvitationRequest request) {
        Book book = bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MANAGE_COLLABORATORS);
        String recipientEmail = normalizeEmail(request.recipientEmail());
        BookCollaborationRole requestedRole = parseRole(request.requestedRole());
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = resolveExpiration(request.expiresAt(), now);

        releaseExpiredPendingInvitations(book, recipientEmail, requestedRole, now);
        if (activeDuplicateExists(book, recipientEmail, requestedRole, now)) {
            throw duplicateActiveInvitation();
        }

        InvitationToken token = tokenService.generate();
        BookCollaborationInvitation invitation = new BookCollaborationInvitation();
        invitation.setTenant(book.getTenant());
        invitation.setBook(book);
        invitation.setInviter(book.getOwner());
        invitation.setRecipientEmail(recipientEmail);
        invitation.setRequestedRole(requestedRole);
        invitation.setTokenHash(token.hashValue());
        invitation.setExpiresAt(expiresAt);

        try {
            invitationRepository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateActiveInvitation();
        }

        return new BookCollaborationInvitationCreationResult(
                BookCollaborationInvitationResponse.fromEntity(invitation, now),
                token.rawValue()
        );
    }

    @Transactional(readOnly = true)
    public BookCollaborationInvitationResponse get(UUID bookId, UUID invitationId) {
        Book book = bookAccessService.requireCapability(bookId, BookCapability.MANAGE_COLLABORATORS);
        BookCollaborationInvitation invitation = requireInvitation(book, invitationId);
        return BookCollaborationInvitationResponse.fromEntity(invitation, OffsetDateTime.now());
    }

    @Transactional
    public BookCollaborationInvitationResponse revoke(UUID bookId, UUID invitationId) {
        Book book = bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MANAGE_COLLABORATORS);
        BookCollaborationInvitation invitation = requireInvitation(book, invitationId);
        OffsetDateTime now = OffsetDateTime.now();
        if (!invitation.isUsable(now)) {
            throw new ConflictException("Only a pending invitation can be revoked.");
        }
        invitation.revoke(now);
        invitationRepository.saveAndFlush(invitation);
        return BookCollaborationInvitationResponse.fromEntity(invitation, now);
    }

    /**
     * Internal foundation for the future acceptance flow: resolves a raw token to
     * a usable invitation. Not exposed through any endpoint; performs no
     * tenant-scoped authorization because the token itself is the credential.
     *
     * <p>Only an invitation whose requested role is assignable is surfaced. A legacy COLLABORATOR
     * invitation stays auditable and revocable, but it grants no Book Role by inference, so it never
     * becomes an acceptance candidate here.
     */
    @Transactional(readOnly = true)
    public Optional<BookCollaborationInvitationResponse> lookupUsableByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        return invitationRepository.findByTokenHash(tokenService.hash(rawToken))
                .filter(invitation -> tokenService.matches(rawToken, invitation.getTokenHash()))
                .filter(invitation -> invitation.getRequestedRole().isAssignable())
                .filter(invitation -> invitation.isUsable(now))
                .map(invitation -> BookCollaborationInvitationResponse.fromEntity(invitation, now));
    }

    private BookCollaborationInvitation requireInvitation(Book book, UUID invitationId) {
        return invitationRepository.findByIdAndBook_IdAndTenant_Id(
                        invitationId,
                        book.getId(),
                        book.getTenant().getId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Book collaboration invitation not found: " + invitationId));
    }

    private void releaseExpiredPendingInvitations(
            Book book,
            String recipientEmail,
            BookCollaborationRole requestedRole,
            OffsetDateTime now
    ) {
        List<BookCollaborationInvitation> stored = findStoredPending(book, recipientEmail, requestedRole);
        boolean released = false;
        for (BookCollaborationInvitation invitation : stored) {
            if (invitation.effectiveStatus(now) == BookCollaborationInvitationStatus.EXPIRED) {
                invitation.markExpired(now);
                released = true;
            }
        }
        if (released) {
            invitationRepository.flush();
        }
    }

    private boolean activeDuplicateExists(
            Book book,
            String recipientEmail,
            BookCollaborationRole requestedRole,
            OffsetDateTime now
    ) {
        return findStoredPending(book, recipientEmail, requestedRole)
                .stream()
                .anyMatch(invitation -> invitation.isUsable(now));
    }

    private List<BookCollaborationInvitation> findStoredPending(
            Book book,
            String recipientEmail,
            BookCollaborationRole requestedRole
    ) {
        return invitationRepository.findByTenant_IdAndBook_IdAndRecipientEmailAndRequestedRoleAndStatus(
                book.getTenant().getId(),
                book.getId(),
                recipientEmail,
                requestedRole,
                BookCollaborationInvitationStatus.PENDING
        );
    }

    private String normalizeEmail(String recipientEmail) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new BadRequestException("recipientEmail must be provided");
        }
        String normalized = recipientEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("recipientEmail must be a valid email address");
        }
        return normalized;
    }

    private BookCollaborationRole parseRole(String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) {
            throw new BadRequestException("requestedRole must be provided");
        }
        BookCollaborationRole parsed;
        try {
            parsed = BookCollaborationRole.valueOf(requestedRole.trim());
        } catch (IllegalArgumentException exception) {
            throw unsupportedRole(requestedRole);
        }
        if (!REQUESTABLE_ROLES.contains(parsed)) {
            throw unsupportedRole(requestedRole);
        }
        return parsed;
    }

    private BadRequestException unsupportedRole(String requestedRole) {
        return new BadRequestException("Unsupported collaboration role: " + requestedRole.trim());
    }

    private OffsetDateTime resolveExpiration(OffsetDateTime requestedExpiresAt, OffsetDateTime now) {
        if (requestedExpiresAt == null) {
            return now.plus(DEFAULT_VALIDITY);
        }
        if (!requestedExpiresAt.isAfter(now)) {
            throw new BadRequestException("expiresAt must be in the future");
        }
        return requestedExpiresAt;
    }

    private ConflictException duplicateActiveInvitation() {
        return new ConflictException("An active collaboration invitation already exists for this book, email, and role.");
    }
}
