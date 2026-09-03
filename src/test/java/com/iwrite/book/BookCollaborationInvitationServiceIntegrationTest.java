package com.iwrite.book;

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
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(BookCollaborationInvitationServiceIntegrationTest.CurrentUserTestConfiguration.class)
class BookCollaborationInvitationServiceIntegrationTest extends PostgresIntegrationTest {

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

    /**
     * A raw invitation token is a credential: only its hash may reach persistence, and no column of the
     * row may carry the raw value. The canary is a synthetic token this test chose, so the assertion
     * cannot pass by recomputing whatever the implementation happened to store.
     *
     * <p>Public creation is closed in this phase (#205), so the invariant is proven on a persisted row.
     * Token generation and shape stay covered by {@code InvitationTokenServiceTest}.
     */
    @Test
    void aPersistedInvitationStoresOnlyTheTokenHash() throws Exception {
        BookResponse book = createBook("Invitation token privacy");
        String canaryToken = randomRawToken();
        BookCollaborationInvitation persisted = persistPendingInvitationWithRole(
                book, "canary@example.com", BookCollaborationRole.COLLABORATOR, canaryToken
        );

        String storedHash = storedTokenHash(persisted.getId());
        assertThat(storedHash).isEqualTo(sha256Hex(canaryToken));
        assertThat(wholeRowAsText(persisted.getId())).doesNotContain(canaryToken);

        BookCollaborationInvitationResponse response = invitationService.get(book.id(), persisted.getId());
        assertThat(response.bookId()).isEqualTo(book.id());
        assertThat(response.inviterUserId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(response.recipientEmail()).isEqualTo("canary@example.com");
        assertThat(response.status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);
        assertThat(response.toString()).doesNotContain(canaryToken).doesNotContain(storedHash);
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

    /**
     * The consolidated contract of #205: a legacy COLLABORATOR invitation is preserved state, never new
     * state. No assignable role may be requested in this phase either, so creation is closed
     * altogether — issuing a raw token for an invitation that {@code lookupUsableByRawToken} can never
     * surface would report success for an access that can never come to exist. #213 reopens creation
     * once every surface is behind its minimum capability and grants are role-aware.
     */
    @Test
    void createNeverProducesANewLegacyInvitation() {
        BookResponse book = createBook("Invitation legacy creation closed");

        assertThatThrownBy(() -> invitationService.create(
                book.id(),
                new BookCollaborationInvitationRequest("new-legacy@example.com", "COLLABORATOR", null)
        )).isInstanceOf(BadRequestException.class);

        assertThat(countInvitations(book.id())).isZero();
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
    void onlyTheBookOwnerCanCreateGetAndRevokeInvitations() throws Exception {
        BookResponse book = createBook("Invitation authorization");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "Invited Collaborator", "c2-collab@iwrite.local");
        UUID unrelatedId = createMember(DEFAULT_TENANT_ID, "Unrelated Member", "c2-unrelated@iwrite.local");
        collaboratorService.grantInternal(book.id(), collaboratorId, DEFAULT_USER_ID);
        ForeignIdentity foreign = createForeignIdentity();

        UUID invitationId = persistLegacyInvitation(book, "target@example.com").id();

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
    void invitationIdsDoNotBypassTenantOrBookAuthorization() throws Exception {
        BookResponse book = createBook("Invitation tenant isolation");
        UUID invitationId = persistLegacyInvitation(book, "isolated@example.com").id();

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
    void expiredInvitationBecomesUnusableWithoutRowMutation() throws Exception {
        BookResponse book = createBook("Invitation expiration");
        PersistedInvitation result = persistLegacyInvitation(book, "expiring@example.com");
        UUID invitationId = result.id();

        forceExpiration(invitationId);

        BookCollaborationInvitationResponse reloaded = invitationService.get(book.id(), invitationId);
        assertThat(reloaded.status()).isEqualTo(BookCollaborationInvitationStatus.EXPIRED);
        assertThat(storedStatus(invitationId)).isEqualTo("PENDING");
        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();
        assertThatThrownBy(() -> invitationService.revoke(book.id(), invitationId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revocationMakesInvitationUnusableAndIsTerminal() throws Exception {
        BookResponse book = createBook("Invitation revocation");
        PersistedInvitation result = persistLegacyInvitation(book, "revoked@example.com");
        UUID invitationId = result.id();

        BookCollaborationInvitationResponse revoked = invitationService.revoke(book.id(), invitationId);
        assertThat(revoked.status()).isEqualTo(BookCollaborationInvitationStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();

        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();
        assertThatThrownBy(() -> invitationService.revoke(book.id(), invitationId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void terminalInvitationsCannotReturnToPendingOrBeReused() throws Exception {
        BookResponse book = createBook("Invitation terminal lifecycle");
        OffsetDateTime now = OffsetDateTime.now();

        UUID revokedId = persistLegacyInvitation(book, "terminal-revoked@example.com").id();
        invitationService.revoke(book.id(), revokedId);
        BookCollaborationInvitation revoked = invitationRepository.findById(revokedId).orElseThrow();
        assertThatThrownBy(() -> revoked.markAccepted(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> revoked.revoke(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> revoked.markExpired(now)).isInstanceOf(IllegalStateException.class);

        PersistedInvitation acceptedResult = persistLegacyInvitation(book, "terminal-accepted@example.com");
        BookCollaborationInvitation accepted = invitationRepository.findById(acceptedResult.id()).orElseThrow();
        accepted.markAccepted(now);
        invitationRepository.saveAndFlush(accepted);

        assertThat(invitationService.lookupUsableByRawToken(acceptedResult.rawToken())).isEmpty();
        assertThatThrownBy(() -> accepted.revoke(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accepted.markAccepted(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitationService.revoke(book.id(), accepted.getId()))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * The guarantee against two usable invitations for the same (Workspace, Book, recipient, role) is
     * the partial unique index over PENDING rows, not the service. Public creation is closed in this
     * phase (#205), so the invariant is proven against the database directly — it is what a reopened
     * creation in #213 will still rely on. The rejected insert comes last: it poisons the transaction.
     */
    @Test
    void aSecondPendingInvitationForTheSameRecipientAndRoleIsRejectedByTheDatabase() throws Exception {
        BookResponse book = createBook("Invitation duplicates");
        persistLegacyInvitation(book, "duplicate@example.com");

        assertThat(storedStatus(persistLegacyInvitation(book, "different@example.com").id())).isEqualTo("PENDING");
        assertThat(storedStatus(persistInvitation(book, "duplicate@example.com", BookCollaborationRole.AUTHOR).id()))
                .isEqualTo("PENDING");

        assertThatThrownBy(() -> persistLegacyInvitation(book, "duplicate@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Revocation is what releases the pending slot: the row leaves PENDING, so the partial unique index
     * admits a replacement for the same recipient and role while the revoked row stays in the audit
     * trail.
     */
    @Test
    void revokingAnInvitationReleasesThePendingSlotForTheSameRecipientAndRole() throws Exception {
        BookResponse book = createBook("Invitation replacement");
        PersistedInvitation revoked = persistLegacyInvitation(book, "replace-revoked@example.com");

        invitationService.revoke(book.id(), revoked.id());

        PersistedInvitation replacement = persistLegacyInvitation(book, "replace-revoked@example.com");
        assertThat(storedStatus(replacement.id())).isEqualTo("PENDING");
        assertThat(storedStatus(revoked.id())).isEqualTo("REVOKED");
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
    void legacyCollaboratorInvitationStaysAuditableAndRevocableButNeverAGrantCandidate() throws Exception {
        BookResponse book = createBook("Invitation legacy grant guard");
        PersistedInvitation result = persistLegacyInvitation(book, "legacy@example.com");
        UUID invitationId = result.id();

        BookCollaborationInvitationResponse queried = invitationService.get(book.id(), invitationId);
        assertThat(queried.requestedRole()).isEqualTo(BookCollaborationRole.COLLABORATOR);
        assertThat(queried.status()).isEqualTo(BookCollaborationInvitationStatus.PENDING);

        assertThat(invitationService.lookupUsableByRawToken(result.rawToken())).isEmpty();

        BookCollaborationInvitationResponse revoked = invitationService.revoke(book.id(), invitationId);
        assertThat(revoked.status()).isEqualTo(BookCollaborationInvitationStatus.REVOKED);
    }

    /** A legacy COLLABORATOR invitation persisted directly, as rows created before #205 exist today. */
    private PersistedInvitation persistLegacyInvitation(BookResponse book, String recipientEmail) throws Exception {
        return persistInvitation(book, recipientEmail, BookCollaborationRole.COLLABORATOR);
    }

    private PersistedInvitation persistInvitation(
            BookResponse book,
            String recipientEmail,
            BookCollaborationRole role
    ) throws Exception {
        String rawToken = randomRawToken();
        return new PersistedInvitation(
                persistPendingInvitationWithRole(book, recipientEmail, role, rawToken).getId(),
                rawToken
        );
    }

    /** A token of the same shape the generator produces, chosen by the test so it stays a known canary. */
    private static String randomRawToken() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record PersistedInvitation(UUID id, String rawToken) {
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

    private long countInvitations(UUID bookId) {
        Number count = (Number) entityManager.createNativeQuery(
                        "select count(*) from book_collaboration_invitations where book_id = :bookId"
                )
                .setParameter("bookId", bookId)
                .getSingleResult();
        return count.longValue();
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
