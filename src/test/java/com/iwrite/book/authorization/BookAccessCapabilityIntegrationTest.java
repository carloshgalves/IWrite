package com.iwrite.book.authorization;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.service.BookAccessService;
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

import java.time.ZoneId;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that relationship, Book Role and effective capabilities are derived by the backend from the
 * authenticated identity, the persisted membership and the Book itself, and that the capability guards
 * keep the same non-enumerable public semantics for every kind of denial.
 */
@Import(BookAccessCapabilityIntegrationTest.CurrentUserTestConfiguration.class)
class BookAccessCapabilityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookAccessService bookAccessService;

    @Autowired
    private BookCollaboratorRepository collaboratorRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void ownershipIsResolvedAsARelationshipAndNotAsABookRole() {
        BookResponse book = createBook("Owner context");

        BookAccessContext context = bookAccessService.resolveAccessContext(book.id());

        assertThat(context.bookId()).isEqualTo(book.id());
        assertThat(context.tenantId()).isEqualTo(DEFAULT_TENANT_ID);
        assertThat(context.userId()).isEqualTo(SwitchableCurrentUserProvider.DEFAULT_USER_ID);
        assertThat(context.relationship()).isEqualTo(BookRelationship.OWNER);
        assertThat(context.role()).isNull();
        assertThat(context.capabilities()).contains(
                BookCapability.MANAGE_COLLABORATORS,
                BookCapability.DELETE_BOOK,
                BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
        );
        assertThat(context.contextualCapabilities()).contains(BookCapability.EDIT_AUTHORED_CONTRIBUTION);
        assertThat(context.isGranted(BookCapability.EDIT_AUTHORED_CONTRIBUTION)).isFalse();
        assertThat(context.isEligible(BookCapability.EDIT_AUTHORED_CONTRIBUTION)).isTrue();
    }

    @Test
    void aCollaboratorRoleIsDerivedFromPersistedMembershipRatherThanFromTheRequest() {
        BookResponse book = createBook("Derived roles");
        UUID authorId = grantRole(book.id(), "Author", "capability-author@iwrite.local", BookRole.AUTHOR);
        UUID editorId = grantRole(book.id(), "Editor", "capability-editor@iwrite.local", BookRole.EDITOR);
        UUID readerId = grantRole(book.id(), "Reader", "capability-reader@iwrite.local", BookRole.READER);
        UUID legacyId = grantRole(book.id(), "Legacy", "capability-legacy@iwrite.local", BookRole.LEGACY_COLLABORATOR);

        assertRole(book.id(), authorId, BookRole.AUTHOR);
        assertRole(book.id(), editorId, BookRole.EDITOR);
        assertRole(book.id(), readerId, BookRole.READER);
        assertRole(book.id(), legacyId, BookRole.LEGACY_COLLABORATOR);

        switchTo(readerId);
        BookAccessContext readerContext = bookAccessService.resolveAccessContext(book.id());
        assertThat(readerContext.capabilities()).isEmpty();
        assertThat(readerContext.contextualCapabilities())
                .containsExactlyInAnyOrder(
                        BookCapability.READ_READER_REVIEW_RELEASE,
                        BookCapability.CREATE_EDITORIAL_COMMENT
                );

        switchTo(legacyId);
        BookAccessContext legacyContext = bookAccessService.resolveAccessContext(book.id());
        assertThat(legacyContext.capabilities()).contains(
                BookCapability.READ_MANUSCRIPT,
                BookCapability.EDIT_AUTHORED_CONTRIBUTION,
                BookCapability.EXPORT_MANUSCRIPT
        );
        assertThat(legacyContext.capabilities()).doesNotContain(
                BookCapability.MANAGE_COLLABORATORS,
                BookCapability.DELETE_BOOK,
                BookCapability.CREATE_EDITORIAL_COMMENT
        );
    }

    @Test
    void capabilityGuardsGrantOnlyTheMinimumCapabilityAndKeepDenialsNonEnumerable() {
        BookResponse book = createBook("Guarded book");
        UUID collaboratorId = grantRole(book.id(), "Legacy", "guard-legacy@iwrite.local", BookRole.LEGACY_COLLABORATOR);
        UUID strangerId = createMember(DEFAULT_TENANT_ID, "Stranger", "guard-stranger@iwrite.local");
        ForeignIdentity foreign = createForeignIdentity();

        Book owned = bookAccessService.requireCapability(book.id(), BookCapability.MANAGE_COLLABORATORS);
        assertThat(owned.getId()).isEqualTo(book.id());
        assertThat(bookAccessService.requireCapabilityForUpdate(book.id(), BookCapability.DELETE_BOOK).getId())
                .isEqualTo(book.id());

        // A capability the Book Owner is only eligible for is not authorized by the Book-scoped guard.
        assertNotFound(() -> bookAccessService.requireCapability(book.id(), BookCapability.EDIT_AUTHORED_CONTRIBUTION));
        assertThat(bookAccessService.requireCapabilityEligibility(book.id(), BookCapability.EDIT_AUTHORED_CONTRIBUTION)
                .relationship()).isEqualTo(BookRelationship.OWNER);
        assertNotFound(() -> bookAccessService.requireCapabilityEligibility(book.id(), BookCapability.READ_READER_REVIEW_RELEASE));

        // Nonexistent Book, another Workspace and a session without membership are indistinguishable.
        assertNotFound(() -> bookAccessService.requireCapability(UUID.randomUUID(), BookCapability.READ_MANUSCRIPT));
        switchTo(foreign.userId(), foreign.tenantId());
        assertNotFound(() -> bookAccessService.requireCapability(book.id(), BookCapability.READ_MANUSCRIPT));
        assertNotFound(() -> bookAccessService.resolveAccessContext(book.id()));
        // An identity without Workspace Membership fails before any Book is read, so the denial cannot
        // depend on the Book existing; it still reaches the caller as the same public not-found result.
        currentUserProvider.switchTo(UUID.randomUUID(), DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        assertThatThrownBy(() -> bookAccessService.requireCapability(book.id(), BookCapability.READ_MANUSCRIPT))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(book.id().toString());

        // Same-Workspace user without a Book Role sees the same public answer as a stranger.
        switchTo(strangerId);
        assertNotFound(() -> bookAccessService.requireCapability(book.id(), BookCapability.READ_MANUSCRIPT));

        // A collaborator is denied administration without learning anything about the Book.
        switchTo(collaboratorId);
        assertThat(bookAccessService.requireCapability(book.id(), BookCapability.READ_MANUSCRIPT).getId())
                .isEqualTo(book.id());
        assertNotFound(() -> bookAccessService.requireCapability(book.id(), BookCapability.MANAGE_COLLABORATORS));
        assertNotFound(() -> bookAccessService.requireCapabilityForUpdate(book.id(), BookCapability.DELETE_BOOK));
    }

    @Test
    void revokedAccessStopsResolvingCapabilitiesImmediately() {
        BookResponse book = createBook("Revoked book");
        UUID collaboratorId = grantRole(book.id(), "Revoked", "guard-revoked@iwrite.local", BookRole.LEGACY_COLLABORATOR);

        switchTo(collaboratorId);
        assertThat(bookAccessService.resolveAccessContext(book.id()).role()).isEqualTo(BookRole.LEGACY_COLLABORATOR);

        currentUserProvider.reset();
        collaboratorRepository.delete(requireCollaboration(book.id(), collaboratorId));
        collaboratorRepository.flush();

        switchTo(collaboratorId);
        assertNotFound(() -> bookAccessService.resolveAccessContext(book.id()));
        assertNotFound(() -> bookAccessService.requireCapability(book.id(), BookCapability.READ_MANUSCRIPT));
    }

    private void assertRole(UUID bookId, UUID userId, BookRole expectedRole) {
        assertThat(requireCollaboration(bookId, userId).getRole()).isEqualTo(expectedRole);
    }

    private BookCollaborator requireCollaboration(UUID bookId, UUID userId) {
        return collaboratorRepository.findByBook_IdAndTenant_IdAndUser_Id(bookId, DEFAULT_TENANT_ID, userId)
                .orElseThrow();
    }

    private UUID grantRole(UUID bookId, String displayName, String email, BookRole role) {
        UUID userId = createMember(DEFAULT_TENANT_ID, displayName, email);
        BookCollaborator collaborator = new BookCollaborator();
        collaborator.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        collaborator.setBook(entityManager.getReference(Book.class, bookId));
        collaborator.setUser(entityManager.getReference(User.class, userId));
        collaborator.setCreatedBy(entityManager.getReference(User.class, SwitchableCurrentUserProvider.DEFAULT_USER_ID));
        collaborator.setRole(role);
        collaboratorRepository.saveAndFlush(collaborator);
        return userId;
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
        tenant.setName("Foreign capability workspace");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "guard-foreign@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private void switchTo(UUID userId) {
        switchTo(userId, DEFAULT_TENANT_ID);
    }

    private void switchTo(UUID userId, UUID tenantId) {
        currentUserProvider.switchTo(userId, tenantId, ZoneId.of("UTC"));
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
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
