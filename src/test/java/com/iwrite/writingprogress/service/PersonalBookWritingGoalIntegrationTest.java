package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.dashboard.service.BookDashboardService;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalUpdateRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Separation of shared Book settings from the Personal Book Writing Goal (#206).
 *
 * <p>Proves that the Book Owner controls what every collaborator shares, that each User owns only
 * their own daily target and planned writing days, that Editors and Readers reach neither their own
 * goal nor anyone else's, and that the absence of a target stays an absence rather than a zero.
 */
@Import(PersonalBookWritingGoalIntegrationTest.CurrentUserTestConfiguration.class)
class PersonalBookWritingGoalIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookService books;

    @Autowired
    private BookDashboardService dashboardService;

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
    void everyCollaboratorKeepsTheirOwnDailyTargetAndRoutine() {
        BookResponse book = createBook("Personal goals");
        UUID authorId = grantRole(book.id(), "Author", "goal-author@iwrite.local", BookRole.AUTHOR);

        setPersonalDailyTarget(book.id(), 800);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY));

        switchTo(authorId);
        setPersonalDailyTarget(book.id(), 150);

        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isEqualTo(150);
        // The Author's routine is untouched by the Owner's: both are User + Book scoped.
        assertThat(personalBookWritingGoalService.getGoal(book.id()).plannedWritingDays())
                .containsExactly(DayOfWeek.values());

        currentUserProvider.reset();
        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isEqualTo(800);
        assertThat(personalBookWritingGoalService.getGoal(book.id()).plannedWritingDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY);
    }

    @Test
    void anAbsentTargetStaysAbsentAndIsNeverReadAsZero() {
        BookResponse book = createBook("No target chosen");

        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isNull();
        assertThat(dashboardService.getDashboard(book.id()).dailyTargetWordCount()).isNull();
        assertThat(dashboardService.getDashboard(book.id()).myWriting().progress().today().dailyTargetWordCount())
                .isNull();
        assertThat(dashboardService.getDashboard(book.id()).myWriting().progress().today().progressPercent())
                .isNull();

        setPersonalDailyTarget(book.id(), 400);
        assertThat(dashboardService.getDashboard(book.id()).dailyTargetWordCount()).isEqualTo(400);

        // Clearing restores the intentional absence instead of recording a target of zero.
        assertThat(setPersonalDailyTarget(book.id(), null).dailyTargetWordCount()).isNull();
        assertThat(dashboardService.getDashboard(book.id()).dailyTargetWordCount()).isNull();
    }

    @Test
    void editorsAndReadersReachNoPersonalWritingGoalAtAll() {
        BookResponse book = createBook("Roles and goals");
        UUID editorId = grantRole(book.id(), "Editor", "goal-editor@iwrite.local", BookRole.EDITOR);
        UUID readerId = grantRole(book.id(), "Reader", "goal-reader@iwrite.local", BookRole.READER);
        setPersonalDailyTarget(book.id(), 900);

        switchTo(editorId);
        assertNotFound(() -> personalBookWritingGoalService.getGoal(book.id()));
        assertNotFound(() -> setPersonalDailyTarget(book.id(), 10));
        assertNotFound(() -> setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY)));
        // Reading the Book never leaks the Owner's target through the dashboard either.
        assertThat(dashboardService.getDashboard(book.id()).dailyTargetWordCount()).isNull();

        switchTo(readerId);
        assertNotFound(() -> personalBookWritingGoalService.getGoal(book.id()));
        assertNotFound(() -> setPersonalDailyTarget(book.id(), 10));

        currentUserProvider.reset();
        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isEqualTo(900);
    }

    @Test
    void sharedBookSettingsStayOwnerOnlyWithTheLegacyCompatibilitySurfacePreserved() {
        BookResponse book = createBook("Shared settings");
        UUID authorId = grantRole(book.id(), "Author", "settings-author@iwrite.local", BookRole.AUTHOR);
        UUID editorId = grantRole(book.id(), "Editor", "settings-editor@iwrite.local", BookRole.EDITOR);
        UUID readerId = grantRole(book.id(), "Reader", "settings-reader@iwrite.local", BookRole.READER);
        UUID legacyId = grantRole(book.id(), "Legacy", "settings-legacy@iwrite.local", BookRole.LEGACY_COLLABORATOR);

        BookResponse updated = books.update(book.id(), bookSettings("Owner title", BookStatus.WRITING, 120000));
        assertThat(updated.title()).isEqualTo("Owner title");
        assertThat(updated.status()).isEqualTo(BookStatus.WRITING);
        assertThat(updated.targetWordCount()).isEqualTo(120000);

        for (UUID deniedUserId : List.of(authorId, editorId, readerId)) {
            switchTo(deniedUserId);
            assertNotFound(() -> books.update(book.id(), bookSettings("Hijacked", BookStatus.ARCHIVED, 1)));
        }

        // LEGACY_COLLABORATOR is not a fourth product role: it keeps exactly the surface these
        // relationships already had before roles existed.
        switchTo(legacyId);
        assertThat(books.update(book.id(), bookSettings("Legacy title", null, null)).title()).isEqualTo("Legacy title");

        currentUserProvider.reset();
        assertThat(books.findById(book.id()).title()).isEqualTo("Legacy title");
        assertThat(books.findById(book.id()).targetWordCount()).isEqualTo(120000);
    }

    @Test
    void goalDenialsAreIndistinguishableFromAnInaccessibleBook() {
        BookResponse book = createBook("Non-enumerable goal");
        UUID strangerId = createMember(DEFAULT_TENANT_ID, "Stranger", "goal-stranger@iwrite.local");
        ForeignIdentity foreign = createForeignIdentity();

        assertNotFound(() -> personalBookWritingGoalService.getGoal(UUID.randomUUID()));

        switchTo(strangerId);
        assertNotFound(() -> personalBookWritingGoalService.getGoal(book.id()));
        assertNotFound(() -> setPersonalDailyTarget(book.id(), 10));

        switchTo(foreign.userId(), foreign.tenantId());
        assertNotFound(() -> personalBookWritingGoalService.getGoal(book.id()));
        assertNotFound(() -> setPersonalDailyTarget(book.id(), 10));
    }

    @Test
    void revokingAccessEndsTheGoalSurfaceWithoutErasingRecordedHistory() {
        BookResponse book = createBook("Revoked goal");
        UUID authorId = grantRole(book.id(), "Author", "goal-revoked@iwrite.local", BookRole.AUTHOR);

        switchTo(authorId);
        setPersonalDailyTarget(book.id(), 250);

        currentUserProvider.reset();
        collaboratorRepository.delete(collaboratorRepository
                .findByBook_IdAndTenant_IdAndUser_Id(book.id(), DEFAULT_TENANT_ID, authorId)
                .orElseThrow());
        collaboratorRepository.flush();

        switchTo(authorId);
        assertNotFound(() -> personalBookWritingGoalService.getGoal(book.id()));
        // The record itself survives revocation; it simply is not reachable any more.
        assertThat(personalBookWritingGoalService.dailyTargetWordCountFor(book.id(), authorId)).isEqualTo(250);
    }

    private BookUpdateRequest bookSettings(String title, BookStatus status, Integer targetWordCount) {
        BookUpdateRequest request = new BookUpdateRequest();
        request.setTitle(title);
        if (status != null) {
            request.setStatus(status);
        }
        if (targetWordCount != null) {
            request.setTargetWordCount(targetWordCount);
        }
        return request;
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
        tenant.setName("Foreign goal workspace");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "goal-foreign@iwrite.local");
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
