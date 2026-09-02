package com.iwrite.book;

import com.iwrite.book.dto.BookCollaborationInvitationCreationResult;
import com.iwrite.book.dto.BookCollaborationInvitationRequest;
import com.iwrite.book.dto.BookCollaborationInvitationResponse;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborationInvitation;
import com.iwrite.book.entity.BookCollaborationInvitationStatus;
import com.iwrite.book.entity.BookCollaborationRole;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaborationInvitationRepository;
import com.iwrite.book.service.BookCollaborationInvitationService;
import com.iwrite.book.service.BookCollaboratorService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(BookCollaborationInvitationServiceIntegrationTest.CurrentUserTestConfiguration.class)
class BookCollaborationInvitationServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Pattern URL_SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    @Autowired
    private BookCollaborationInvitationService invitationService;

    @Autowired
    private BookCollaborationInvitationRepository invitationRepository;

    @Autowired
    private BookCollaboratorService collaboratorService;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void createReturnsRawTokenOnceAndPersistsOnlyItsHash() throws Exception {
        BookResponse book = createBook("Invitation happy path");
        OffsetDateTime before = OffsetDateTime.now();

        BookCollaborationInvitationCreationResult result = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("  Writer@Example.COM  ", "COLLABORATOR", null)
        );

        BookCollaborationInvitationResponse invitation = result.invitation();
        assertThat(invitation.bookId()).isEqualTo(book.id());
        assertThat(invitation.inviterUserId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(invitation.recipientEmail()).isEqualTo("writer@example.com");
        assertThat(invitation.requestedRole()).isEqualTo(BookCollaborationRole.COLLABORATOR);
        assertThat(invitation.status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);
        assertThat(invitation.acceptedAt()).isNull();
        assertThat(invitation.revokedAt()).isNull();
        assertThat(invitation.createdAt()).isNotNull();
        assertThat(invitation.updatedAt()).isNotNull();
        assertThat(invitation.expiresAt()).isAfter(before.plusDays(6));
        assertThat(invitation.expiresAt()).isBefore(before.plusDays(8));

        String rawToken = result.rawToken();
        assertThat(URL_SAFE_TOKEN.matcher(rawToken).matches()).isTrue();

        String storedHash = storedTokenHash(invitation.id());
        assertThat(storedHash).isEqualTo(sha256Hex(rawToken));

        String rowText = wholeRowAsText(invitation.id());
        assertThat(rowText.contains(rawToken)).isFalse();

        assertThat(result.toString().contains(rawToken)).isFalse();
        assertThat(invitation.toString().contains(rawToken)).isFalse();
        assertThat(invitation.toString().contains(storedHash)).isFalse();
    }

    @Test
    void createRejectsInvalidEmails() {
        BookResponse book = createBook("Invitation invalid email");

        for (String email : new String[]{null, "", "   ", "not-an-email", "missing@dot", "two words@example.com", "@example.com"}) {
            assertThatThrownBy(() -> invitationService.create(
                    book.id(),
                    new BookCollaborationInvitationRequest(email, "COLLABORATOR", null)
            )).isInstanceOf(BadRequestException.class);
        }
    }

    /**
     * Compatibility phase (#205): the assignable Book Roles exist in the persisted catalog, but no
     * public flow may request one while the Book surfaces are still guarded by the legacy checks.
     * Offering AUTHOR, EDITOR or READER here would promise an authority nothing enforces yet.
     */
    @Test
    void createRejectsUnsupportedRolesAndDoesNotYetOfferTheAssignableBookRoles() {
        BookResponse book = createBook("Invitation invalid role");

        for (String role : new String[]{
                null, "", "OWNER", "REVIEWER", "collaborator ",
                "AUTHOR", "EDITOR", "READER", "LEGACY_COLLABORATOR"
        }) {
            assertThatThrownBy(() -> invitationService.create(
                    book.id(),
                    new BookCollaborationInvitationRequest("writer@example.com", role, null)
            )).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void invitationRolesMapToTheClosedBookRoleCatalog() {
        assertThat(BookCollaborationRole.AUTHOR.grantedBookRole()).contains(BookRole.AUTHOR);
        assertThat(BookCollaborationRole.EDITOR.grantedBookRole()).contains(BookRole.EDITOR);
        assertThat(BookCollaborationRole.READER.grantedBookRole()).contains(BookRole.READER);
        assertThat(BookCollaborationRole.AUTHOR.isAssignable()).isTrue();
        assertThat(BookCollaborationRole.EDITOR.isAssignable()).isTrue();
        assertThat(BookCollaborationRole.READER.isAssignable()).isTrue();

        // A legacy invitation grants no Book Role by inference: there is no conversion to hand a
        // future acceptance flow, so it can never become an assignable grant.
        assertThat(BookCollaborationRole.COLLABORATOR.grantedBookRole()).isEmpty();
        assertThat(BookCollaborationRole.COLLABORATOR.isAssignable()).isFalse();
    }

    @Test
    void createHonorsCustomExpirationAndRejectsPastExpiration() {
        BookResponse book = createBook("Invitation custom expiration");
        OffsetDateTime customExpiresAt = OffsetDateTime.now().plusDays(1);

        BookCollaborationInvitationCreationResult result = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("custom@example.com", "COLLABORATOR", customExpiresAt)
        );
        assertThat(result.invitation().expiresAt()).isEqualTo(customExpiresAt);

        assertThatThrownBy(() -> invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("past@example.com", "COLLABORATOR", OffsetDateTime.now().minusMinutes(1))
        )).isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyTheBookOwnerCanCreateGetAndRevokeInvitations() {
        BookResponse book = createBook("Invitation authorization");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "Invited Collaborator", "c2-collab@iwrite.local");
        UUID unrelatedId = createMember(DEFAULT_TENANT_ID, "Unrelated Member", "c2-unrelated@iwrite.local");
        collaboratorService.grantInternal(book.id(), collaboratorId, DEFAULT_USER_ID);
        ForeignIdentity foreign = createForeignIdentity();

        UUID invitationId = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("target@example.com", "COLLABORATOR", null)
        ).invitation().id();

        for (UUID deniedUserId : new UUID[]{collaboratorId, unrelatedId}) {
            currentUserProvider.switchTo(deniedUserId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
            assertDeniedAsBookNotFound(book.id(), invitationId);
        }

        currentUserProvider.switchTo(foreign.userId(), foreign.tenantId(), ZoneId.of("UTC"));
        assertDeniedAsBookNotFound(book.id(), invitationId);

        currentUserProvider.reset();
        assertThat(invitationService.get(book.id(), invitationId).id()).isEqualTo(invitationId);
    }

    @Test
    void invitationIdsDoNotBypassTenantOrBookAuthorization() {
        BookResponse book = createBook("Invitation tenant isolation");
        UUID invitationId = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("isolated@example.com", "COLLABORATOR", null)
        ).invitation().id();

        ForeignIdentity foreign = createForeignIdentity();
        currentUserProvider.switchTo(foreign.userId(), foreign.tenantId(), ZoneId.of("UTC"));
        BookResponse foreignBook = createBook("Foreign own book");

        assertThatThrownBy(() -> invitationService.get(foreignBook.id(), invitationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("invitation not found");
        assertThatThrownBy(() -> invitationService.revoke(foreignBook.id(), invitationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("invitation not found");

        BookResponse otherBook;
        currentUserProvider.reset();
        otherBook = createBook("Other book same tenant");
        assertThatThrownBy(() -> invitationService.get(otherBook.id(), invitationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("invitation not found");
    }

    @Test
    void expiredInvitationBecomesUnusableWithoutRowMutation() {
        BookResponse book = createBook("Invitation expiration");
        BookCollaborationInvitationCreationResult result = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("expiring@example.com", "COLLABORATOR", null)
        );
        UUID invitationId = result.invitation().id();

        forceExpiration(invitationId);

        BookCollaborationInvitationResponse reloaded = invitationService.get(book.id(), invitationId);
        assertThat(reloaded.status()).isEqualTo(BookCollaborationInvitationStatus.EXPIRED);
        assertThat(storedStatus(invitationId)).isEqualTo("PENDING");
        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();
        assertThatThrownBy(() -> invitationService.revoke(book.id(), invitationId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revocationMakesInvitationUnusableAndIsTerminal() {
        BookResponse book = createBook("Invitation revocation");
        BookCollaborationInvitationCreationResult result = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("revoked@example.com", "COLLABORATOR", null)
        );
        UUID invitationId = result.invitation().id();

        BookCollaborationInvitationResponse revoked = invitationService.revoke(book.id(), invitationId);
        assertThat(revoked.status()).isEqualTo(BookCollaborationInvitationStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();

        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();
        assertThatThrownBy(() -> invitationService.revoke(book.id(), invitationId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void terminalInvitationsCannotReturnToPendingOrBeReused() {
        BookResponse book = createBook("Invitation terminal lifecycle");
        OffsetDateTime now = OffsetDateTime.now();

        UUID revokedId = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("terminal-revoked@example.com", "COLLABORATOR", null)
        ).invitation().id();
        invitationService.revoke(book.id(), revokedId);
        BookCollaborationInvitation revoked = invitationRepository.findById(revokedId).orElseThrow();
        assertThatThrownBy(() -> revoked.markAccepted(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> revoked.revoke(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> revoked.markExpired(now)).isInstanceOf(IllegalStateException.class);

        BookCollaborationInvitationCreationResult acceptedResult = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("terminal-accepted@example.com", "COLLABORATOR", null)
        );
        BookCollaborationInvitation accepted = invitationRepository.findById(acceptedResult.invitation().id()).orElseThrow();
        accepted.markAccepted(now);
        invitationRepository.saveAndFlush(accepted);

        assertThat(invitationService.lookupUsableByRawToken(acceptedResult.rawToken())).isEmpty();
        assertThatThrownBy(() -> accepted.revoke(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accepted.markAccepted(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitationService.revoke(book.id(), accepted.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicateActiveInvitationIsRejected() {
        BookResponse book = createBook("Invitation duplicates");
        invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("duplicate@example.com", "COLLABORATOR", null)
        );

        assertThatThrownBy(() -> invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest(" Duplicate@Example.com ", "COLLABORATOR", null)
        )).isInstanceOf(ConflictException.class);

        assertThat(invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("different@example.com", "COLLABORATOR", null)
        ).invitation().status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);
    }

    @Test
    void newInvitationIsAllowedAfterRevocationOrExpiration() {
        BookResponse book = createBook("Invitation replacement");

        UUID revokedId = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("replace-revoked@example.com", "COLLABORATOR", null)
        ).invitation().id();
        invitationService.revoke(book.id(), revokedId);
        assertThat(invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("replace-revoked@example.com", "COLLABORATOR", null)
        ).invitation().status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);

        UUID expiredId = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("replace-expired@example.com", "COLLABORATOR", null)
        ).invitation().id();
        forceExpiration(expiredId);
        assertThat(invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("replace-expired@example.com", "COLLABORATOR", null)
        ).invitation().status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);
        assertThat(storedStatus(expiredId)).isEqualTo("EXPIRED");
    }

    @Test
    void usableAssignableInvitationCanBeLookedUpByRawTokenOnly() throws Exception {
        BookResponse book = createBook("Invitation token lookup");
        String rawToken = "L".repeat(43);
        BookCollaborationInvitation persisted = persistPendingInvitationWithRole(
                book, "assignable-lookup@example.com", BookCollaborationRole.AUTHOR, rawToken
        );

        assertThat(invitationService.lookupUsableByRawToken(rawToken))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.id()).isEqualTo(persisted.getId());
                    assertThat(found.status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);
                });

        assertThat(invitationService.lookupUsableByRawToken("A".repeat(43))).isEmpty();
        assertThat(invitationService.lookupUsableByRawToken(null)).isEmpty();
        assertThat(invitationService.lookupUsableByRawToken("  ")).isEmpty();
    }

    /**
     * A legacy COLLABORATOR invitation stays part of the audit trail and can still be revoked, but it
     * grants no assignable Book Role, so the acceptance-facing token lookup never surfaces it even
     * while it is pending and unexpired. This keeps #147 from being handed a direct conversion into
     * the broad legacy read/edit/export/AI surface.
     */
    @Test
    void legacyCollaboratorInvitationStaysAuditableAndRevocableButNeverAGrantCandidate() {
        BookResponse book = createBook("Invitation legacy grant guard");
        BookCollaborationInvitationCreationResult result = invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("legacy@example.com", "COLLABORATOR", null)
        );
        UUID invitationId = result.invitation().id();

        BookCollaborationInvitationResponse queried = invitationService.get(book.id(), invitationId);
        assertThat(queried.requestedRole()).isEqualTo(BookCollaborationRole.COLLABORATOR);
        assertThat(queried.status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);

        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();

        BookCollaborationInvitationResponse revoked = invitationService.revoke(book.id(), invitationId);
        assertThat(revoked.status()).isEqualTo(BookCollaborationInvitationStatus.REVOKED);
    }

    private BookCollaborationInvitation persistPendingInvitationWithRole(
            BookResponse book,
            String recipientEmail,
            BookCollaborationRole role,
            String rawToken
    ) throws Exception {
        BookCollaborationInvitation invitation = new BookCollaborationInvitation();
        invitation.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        invitation.setBook(entityManager.getReference(Book.class, book.id()));
        invitation.setInviter(entityManager.getReference(User.class, DEFAULT_USER_ID));
        invitation.setRecipientEmail(recipientEmail);
        invitation.setRequestedRole(role);
        invitation.setTokenHash(sha256Hex(rawToken));
        invitation.setExpiresAt(OffsetDateTime.now().plusDays(7));
        invitationRepository.saveAndFlush(invitation);
        entityManager.clear();
        return invitation;
    }

    private void assertDeniedAsBookNotFound(UUID bookId, UUID invitationId) {
        assertThatThrownBy(() -> invitationService.create(
                bookId,
                new BookCollaborationInvitationRequest("denied@example.com", "COLLABORATOR", null)
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
        assertThatThrownBy(() -> invitationService.get(bookId, invitationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
        assertThatThrownBy(() -> invitationService.revoke(bookId, invitationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
    }

    private void forceExpiration(UUID invitationId) {
        entityManager.createNativeQuery(
                        "update book_collaboration_invitations set expires_at = :past where id = :id"
                )
                .setParameter("past", OffsetDateTime.now().minusHours(1))
                .setParameter("id", invitationId)
                .executeUpdate();
        entityManager.clear();
    }

    private String storedTokenHash(UUID invitationId) {
        return (String) entityManager.createNativeQuery(
                        "select token_hash from book_collaboration_invitations where id = :id"
                )
                .setParameter("id", invitationId)
                .getSingleResult();
    }

    private String storedStatus(UUID invitationId) {
        return (String) entityManager.createNativeQuery(
                        "select status from book_collaboration_invitations where id = :id"
                )
                .setParameter("id", invitationId)
                .getSingleResult();
    }

    private String wholeRowAsText(UUID invitationId) {
        return (String) entityManager.createNativeQuery(
                        "select cast(invitation as text) from book_collaboration_invitations invitation where id = :id"
                )
                .setParameter("id", invitationId)
                .getSingleResult();
    }

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private UUID createMember(UUID tenantId, String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, tenantId));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    private ForeignIdentity createForeignIdentity() {
        Tenant tenant = new Tenant();
        tenant.setName("Foreign C2");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "c2-foreign-" + UUID.randomUUID() + "@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private record ForeignIdentity(UUID userId, UUID tenantId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }
}
