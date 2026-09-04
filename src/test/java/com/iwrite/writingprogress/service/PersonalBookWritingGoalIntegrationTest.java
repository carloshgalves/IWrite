package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.ConflictException;
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
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
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
    private BookWritingScheduleRepository scheduleRepository;

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
    void aSaveMadeAgainstASupersededRevisionIsRefusedInsteadOfReplacingTheNewerGoal() {
        BookResponse book = createBook("Stale goal save");
        // What a second tab read before the first one saved.
        int staleRevision = personalBookWritingGoalService.getGoal(book.id()).revision();

        setPersonalDailyTarget(book.id(), 800);

        assertThatThrownBy(() -> setPersonalDailyTarget(book.id(), 1200, staleRevision))
                .isInstanceOf(ConflictException.class);
        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isEqualTo(800);
    }

    @Test
    void theRevisionCoversBothHalvesSoARoutineChangeSupersedesAStaleTargetSave() {
        BookResponse book = createBook("Whole goal revision");
        int staleRevision = personalBookWritingGoalService.getGoal(book.id()).revision();

        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        // The target half of the goal never moved, but the goal did: a save decided against the
        // routine that has since been replaced is not a save against the current goal.
        assertThatThrownBy(() -> setPersonalDailyTarget(book.id(), 1200, staleRevision))
                .isInstanceOf(ConflictException.class);
        assertThat(personalBookWritingGoalService.getGoal(book.id()).dailyTargetWordCount()).isNull();
        assertThat(personalBookWritingGoalService.getGoal(book.id()).plannedWritingDays())
                .containsExactly(DayOfWeek.MONDAY);
    }

    @Test
    void everyAcceptedSaveAdvancesTheRevisionTheNextSaveMustQuote() {
        BookResponse book = createBook("Revision advances");

        assertThat(personalBookWritingGoalService.getGoal(book.id()).revision()).isZero();

        int afterTarget = setPersonalDailyTarget(book.id(), 500).revision();
        int afterRoutine = setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.FRIDAY)).revision();
        int afterClearing = setPersonalDailyTarget(book.id(), null).revision();

        assertThat(afterTarget).isEqualTo(1);
        assertThat(afterRoutine).isEqualTo(2);
        assertThat(afterClearing).isEqualTo(3);
        assertThat(personalBookWritingGoalService.getGoal(book.id()).revision()).isEqualTo(3);
    }

    @Test
    void aRetainedGoalIsUnreachableThroughTheDashboardAfterTheRoleStopsAllowingOne() {
        BookResponse book = createBook("Demoted goal");
        UUID authorId = grantRole(book.id(), "Author", "goal-demoted@iwrite.local", BookRole.AUTHOR);

        switchTo(authorId);
        setPersonalDailyTarget(book.id(), 700);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        currentUserProvider.reset();
        changeRole(book.id(), authorId, BookRole.EDITOR);

        switchTo(authorId);
        var dashboard = dashboardService.getDashboard(book.id());
        // The whole personal projection goes, not only the top-level target: the per-day snapshots,
        // the routine and the consistency built against it are all the goal this role may not manage.
        assertThat(dashboard.dailyTargetWordCount()).isNull();
        assertThat(dashboard.myWriting()).isNull();

        // The row itself survives the demotion, exactly as revocation leaves it: it simply is not
        // reachable any more, through this surface or any other.
        currentUserProvider.reset();
        assertThat(personalBookWritingGoalService.dailyTargetWordCountFor(book.id(), authorId)).isEqualTo(700);
    }

    @Test
    void aDashboardReadByARoleWithoutTheCapabilityCreatesNoWritingSchedule() {
        BookResponse book = createBook("No lazy schedule");
        UUID editorId = grantRole(book.id(), "Editor", "dash-editor@iwrite.local", BookRole.EDITOR);
        UUID readerId = grantRole(book.id(), "Reader", "dash-reader@iwrite.local", BookRole.READER);

        for (UUID deniedUserId : List.of(editorId, readerId)) {
            switchTo(deniedUserId);
            assertThat(dashboardService.getDashboard(book.id()).myWriting()).isNull();
            // Reading is not a mutation. A denied read must not persist a default routine for a User
            // the policy says has no Personal Book Writing Goal to keep one for.
            assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(deniedUserId, book.id()))
                    .isZero();
        }
    }

    @Test
    void theDashboardCarriesTheRevisionTheNextSaveHasToQuote() {
        BookResponse book = createBook("Dashboard revision");

        // The dashboard is the only place the client reads its own goal from, so it is the only place
        // the revision can come from. A save quoting what the dashboard showed must be accepted.
        int readRevision = dashboardService.getDashboard(book.id()).myWriting().writingGoalRevision();
        assertThat(readRevision).isZero();
        assertThat(setPersonalDailyTarget(book.id(), 500, readRevision).dailyTargetWordCount()).isEqualTo(500);

        assertThat(dashboardService.getDashboard(book.id()).myWriting().writingGoalRevision()).isEqualTo(1);
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

    private void changeRole(UUID bookId, UUID userId, BookRole role) {
        BookCollaborator collaborator = collaboratorRepository
                .findByBook_IdAndTenant_IdAndUser_Id(bookId, DEFAULT_TENANT_ID, userId)
                .orElseThrow();
        collaborator.setRole(role);
        collaboratorRepository.saveAndFlush(collaborator);
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
