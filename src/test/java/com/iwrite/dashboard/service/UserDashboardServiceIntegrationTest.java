package com.iwrite.dashboard.service;

import com.iwrite.book.entity.Book;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(UserDashboardServiceIntegrationTest.FixedWritingProgressClockConfig.class)
class UserDashboardServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-24T03:30:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(FIXED_INSTANT, ZoneId.of("America/Sao_Paulo"));

    @Autowired
    private UserDashboardService dashboardService;

    @Autowired
    private BookDashboardService bookDashboardService;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUser() {
        currentUserProvider.reset();
    }

    @Test
    void currentUserDashboardAggregatesRecordedProgressAcrossAuthorizedTenantBooksOnly() {
        var firstBook = bookService.getBook(createBook("Global first book").id());
        var secondBook = bookService.getBook(createBook("Global second book").id());
        User otherUser = createUser("Other Contributor", "global.other@iwrite.local");
        addDefaultTenantMembership(otherUser);
        Book foreignTenantBook = createForeignTenantBook("Foreign tenant book");

        saveProgress(firstBook, DEFAULT_USER_ID, TODAY, 10, -2);
        saveProgress(secondBook, DEFAULT_USER_ID, TODAY, 5, 0);
        saveProgress(firstBook, DEFAULT_USER_ID, TODAY.minusDays(1), 0, 8);
        saveProgress(firstBook, DEFAULT_USER_ID, TODAY.minusDays(2), 7, 0);
        saveProgress(firstBook, DEFAULT_USER_ID, LocalDate.of(2026, 5, 1), 1, 0);
        saveProgress(firstBook, DEFAULT_USER_ID, LocalDate.of(2026, 5, 2), 1, 0);
        saveProgress(firstBook, DEFAULT_USER_ID, LocalDate.of(2026, 5, 3), 1, 0);
        saveProgress(firstBook, otherUser.getId(), TODAY, 99, 0);
        saveProgress(foreignTenantBook, DEFAULT_USER_ID, TODAY, 500, 0);
        entityManager.flush();
        entityManager.clear();

        var dashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(dashboard.period().value()).isEqualTo("7d");
        assertThat(dashboard.period().startDate()).isEqualTo(TODAY.minusDays(6));
        assertThat(dashboard.period().endDate()).isEqualTo(TODAY);
        assertThat(dashboard.summary().productiveWords()).isEqualTo(22);
        assertThat(dashboard.summary().manuscriptAdjustments()).isEqualTo(6);
        assertThat(dashboard.summary().writingDays()).isEqualTo(2);
        assertThat(dashboard.summary().booksWrittenIn()).isEqualTo(2);
        assertThat(dashboard.summary().currentGlobalWritingStreak()).isEqualTo(1);
        assertThat(dashboard.summary().bestGlobalWritingStreak()).isEqualTo(3);
        assertThat(dashboard.summary().writingDaysThisMonth()).isEqualTo(2);
        assertThat(dashboard.dailySeries()).hasSize(7);
        assertThat(dashboard.dailySeries().getLast().date()).isEqualTo(TODAY);
        assertThat(dashboard.dailySeries().getLast().productiveWords()).isEqualTo(15);
        assertThat(dashboard.bookContributions())
                .extracting(contribution -> contribution.bookId())
                .containsExactly(firstBook.getId(), secondBook.getId());
    }

    @Test
    void bookContributionsAggregateRecordedContributorsAndHideUnrecordedForeignContributorIds() {
        Book book = bookService.getBook(createBook("Contribution book").id());
        User otherUser = createUser("Team Writer", "team.writer@iwrite.local");
        addDefaultTenantMembership(otherUser);
        saveProgress(book, DEFAULT_USER_ID, TODAY, 10, -2);
        saveProgress(book, otherUser.getId(), TODAY, 7, 3);
        entityManager.flush();
        entityManager.clear();

        var allContributors = dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null);

        assertThat(allContributors.scope()).isEqualTo("ALL_CONTRIBUTORS");
        assertThat(allContributors.selectedContributor()).isNull();
        assertThat(allContributors.availableContributors())
                .extracting(contributor -> contributor.displayName())
                .containsExactly("Carlos", "Team Writer");
        assertThat(allContributors.summary().productiveWords()).isEqualTo(17);
        assertThat(allContributors.summary().manuscriptAdjustments()).isEqualTo(1);
        assertThat(allContributors.summary().contributorsCount()).isEqualTo(2);
        assertThat(allContributors.dailySeries().getLast().productiveWords()).isEqualTo(17);

        var otherContributor = dashboardService.getBookContributions(
                book.getId(),
                WritingProgressPeriod.SEVEN_DAYS,
                otherUser.getId()
        );
        assertThat(otherContributor.scope()).isEqualTo("SINGLE_CONTRIBUTOR");
        assertThat(otherContributor.selectedContributor().displayName()).isEqualTo("Team Writer");
        assertThat(otherContributor.summary().productiveWords()).isEqualTo(7);
        assertThat(otherContributor.summary().contributorsCount()).isEqualTo(1);

        assertThatThrownBy(() -> dashboardService.getBookContributions(
                book.getId(),
                WritingProgressPeriod.SEVEN_DAYS,
                UUID.randomUUID()
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void currentUserContributorFilterReturnsZeroStateWhenTheUserHasNoRecordedContribution() {
        Book book = bookService.getBook(createBook("Current user zero contribution").id());
        User otherUser = createUser("Only Contributor", "only.writer@iwrite.local");
        addDefaultTenantMembership(otherUser);
        saveProgress(book, otherUser.getId(), TODAY, 7, 0);
        entityManager.flush();
        entityManager.clear();

        var currentUser = dashboardService.getBookContributions(
                book.getId(),
                WritingProgressPeriod.SEVEN_DAYS,
                DEFAULT_USER_ID
        );

        assertThat(currentUser.scope()).isEqualTo("SINGLE_CONTRIBUTOR");
        assertThat(currentUser.selectedContributor().userId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(currentUser.selectedContributor().displayName()).isEqualTo("Carlos");
        assertThat(currentUser.summary().productiveWords()).isZero();
        assertThat(currentUser.summary().contributorsCount()).isZero();
        assertThat(currentUser.dailySeries()).hasSize(7);
        assertThat(currentUser.dailySeries())
                .allSatisfy(day -> assertThat(day.productiveWords()).isZero());
    }

    @Test
    void untouchedBookIsNotCountedAsWrittenAndKeepsItsPersonalWritingProgressEmpty() {
        Book writtenBook = bookService.getBook(createBook("Written global book").id());
        Book untouchedBook = bookService.getBook(createBook("Untouched global book").id());
        saveProgress(writtenBook, DEFAULT_USER_ID, TODAY, 12, 0);
        entityManager.flush();
        entityManager.clear();

        var userDashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);
        var untouchedBookDashboard = bookDashboardService.getDashboard(untouchedBook.getId());

        assertThat(userDashboard.summary().booksWrittenIn()).isEqualTo(1);
        assertThat(userDashboard.bookContributions())
                .extracting(contribution -> contribution.bookId())
                .containsExactly(writtenBook.getId())
                .doesNotContain(untouchedBook.getId());
        assertThat(untouchedBookDashboard.myWriting().progress().today().productiveWordCountChange()).isZero();
        assertThat(untouchedBookDashboard.myWriting().progress().consistency().currentStreakDays()).isZero();
    }

    @Test
    void adjustmentOnlyContributorIsRecordedWithoutCountingAsProductiveWriting() {
        Book book = bookService.getBook(createBook("Adjustment-only contribution book").id());
        User adjuster = createUser("Adjustment Writer", "adjustment.writer@iwrite.local");
        addDefaultTenantMembership(adjuster);
        saveProgress(book, adjuster.getId(), TODAY, 0, 11);
        entityManager.flush();
        entityManager.clear();

        var allContributors = dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null);
        var selectedAdjuster = dashboardService.getBookContributions(
                book.getId(),
                WritingProgressPeriod.SEVEN_DAYS,
                adjuster.getId()
        );

        assertThat(allContributors.availableContributors())
                .extracting(contributor -> contributor.displayName())
                .containsExactly("Adjustment Writer", "Carlos");
        assertThat(allContributors.summary().writingDays()).isZero();
        assertThat(allContributors.summary().manuscriptAdjustments()).isEqualTo(11);
        assertThat(selectedAdjuster.summary().productiveWords()).isZero();
        assertThat(selectedAdjuster.summary().writingDays()).isZero();
        assertThat(selectedAdjuster.summary().manuscriptAdjustments()).isEqualTo(11);
    }

    @Test
    void zeroProgressRowsDoNotCountAsWritingOrRecordedContribution() {
        Book book = bookService.getBook(createBook("Zero progress book").id());
        User otherUser = createUser("Zero Other", "zero.other@iwrite.local");
        addDefaultTenantMembership(otherUser);
        saveProgress(book, DEFAULT_USER_ID, TODAY, 0, 0);
        saveProgress(book, otherUser.getId(), TODAY, 0, 0);
        entityManager.flush();
        entityManager.clear();

        var userDashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);
        var allContributors = dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null);

        assertThat(userDashboard.summary().writingDays()).isZero();
        assertThat(userDashboard.summary().booksWrittenIn()).isZero();
        assertThat(userDashboard.summary().currentGlobalWritingStreak()).isZero();
        assertThat(userDashboard.bookContributions()).isEmpty();
        assertThat(allContributors.availableContributors())
                .extracting(contributor -> contributor.userId())
                .containsExactly(DEFAULT_USER_ID);
        assertThat(allContributors.summary().contributorsCount()).isZero();
        assertThat(allContributors.summary().writingDays()).isZero();
    }

    @Test
    void netZeroProductiveBookStillAppearsWhenRowsRecordActivity() {
        Book book = bookService.getBook(createBook("Net zero productive book").id());
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(1), 100, 0);
        saveProgress(book, DEFAULT_USER_ID, TODAY, -100, 0);
        entityManager.flush();
        entityManager.clear();

        var dashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(dashboard.summary().productiveWords()).isZero();
        assertThat(dashboard.summary().booksWrittenIn()).isEqualTo(1);
        assertThat(dashboard.summary().writingDays()).isEqualTo(1);
        assertThat(dashboard.bookContributions())
                .singleElement()
                .satisfies(contribution -> {
                    assertThat(contribution.bookId()).isEqualTo(book.getId());
                    assertThat(contribution.productiveWords()).isZero();
                    assertThat(contribution.manuscriptAdjustments()).isZero();
                    assertThat(contribution.writingDays()).isEqualTo(1);
                });
    }

    @Test
    void netZeroAdjustmentOnlyBookStillAppearsWithoutCountingAsProductiveWriting() {
        Book book = bookService.getBook(createBook("Net zero adjustment book").id());
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(1), 0, 20);
        saveProgress(book, DEFAULT_USER_ID, TODAY, 0, -20);
        entityManager.flush();
        entityManager.clear();

        var dashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(dashboard.summary().productiveWords()).isZero();
        assertThat(dashboard.summary().manuscriptAdjustments()).isZero();
        assertThat(dashboard.summary().booksWrittenIn()).isZero();
        assertThat(dashboard.summary().writingDays()).isZero();
        assertThat(dashboard.bookContributions())
                .singleElement()
                .satisfies(contribution -> {
                    assertThat(contribution.bookId()).isEqualTo(book.getId());
                    assertThat(contribution.productiveWords()).isZero();
                    assertThat(contribution.manuscriptAdjustments()).isZero();
                    assertThat(contribution.writingDays()).isZero();
                });
    }

    @Test
    void currentGlobalStreakContinuesThroughYesterdayWhenTodayHasNoWriting() {
        Book book = bookService.getBook(createBook("No writing today streak").id());
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(2), 4, 0);
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(1), 5, 0);
        entityManager.flush();
        entityManager.clear();

        var dashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(dashboard.summary().currentGlobalWritingStreak()).isEqualTo(2);
    }

    @Test
    void currentGlobalStreakStopsAtRealMissingDayBeforeYesterday() {
        Book book = bookService.getBook(createBook("Gap streak book").id());
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(3), 4, 0);
        saveProgress(book, DEFAULT_USER_ID, TODAY.minusDays(1), 5, 0);
        entityManager.flush();
        entityManager.clear();

        var gapDashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(gapDashboard.summary().currentGlobalWritingStreak()).isEqualTo(1);
        assertThat(gapDashboard.summary().bestGlobalWritingStreak()).isEqualTo(1);
    }

    @Test
    void contributionDashboardGroupsPersistedProgressDatesWithoutTimezoneReinterpretation() {
        Book book = bookService.getBook(createBook("Stored date contribution book").id());
        User utcWriter = createUser("UTC Writer", "utc.writer@iwrite.local", "UTC");
        User pacificWriter = createUser("Pacific Writer", "pacific.writer@iwrite.local", "America/Los_Angeles");
        addDefaultTenantMembership(utcWriter);
        addDefaultTenantMembership(pacificWriter);
        saveProgress(book, utcWriter.getId(), LocalDate.of(2026, 6, 23), 3, 0);
        saveProgress(book, pacificWriter.getId(), LocalDate.of(2026, 6, 24), 5, 0);
        utcWriter.setTimeZoneId("Asia/Tokyo");
        pacificWriter.setTimeZoneId("Pacific/Kiritimati");
        entityManager.flush();
        entityManager.clear();

        var contributions = dashboardService.getBookContributions(book.getId(), WritingProgressPeriod.SEVEN_DAYS, null);

        assertThat(contributions.dailySeries())
                .filteredOn(day -> day.date().equals(LocalDate.of(2026, 6, 23)))
                .singleElement()
                .satisfies(day -> assertThat(day.productiveWords()).isEqualTo(3));
        assertThat(contributions.dailySeries())
                .filteredOn(day -> day.date().equals(LocalDate.of(2026, 6, 24)))
                .singleElement()
                .satisfies(day -> assertThat(day.productiveWords()).isEqualTo(5));
    }

    @Test
    void globalStreaksIgnoreStoredProgressDatesAfterCurrentEffectiveWritingDate() {
        Book book = bookService.getBook(createBook("Future-relative global streak book").id());
        LocalDate newEffectiveToday = LocalDate.of(2026, 6, 23);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 21), 10, 0);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 22), 10, 0);
        saveProgress(book, DEFAULT_USER_ID, newEffectiveToday, 10, 0);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 24), 10, 0);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 25), 10, 0);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 26), 10, 0);
        saveProgress(book, DEFAULT_USER_ID, LocalDate.of(2026, 6, 27), 10, 0);

        User currentUser = entityManager.find(User.class, DEFAULT_USER_ID);
        currentUser.setTimeZoneId("America/Los_Angeles");
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));
        entityManager.flush();
        entityManager.clear();

        var dashboard = dashboardService.getCurrentUserDashboard(WritingProgressPeriod.SEVEN_DAYS);

        assertThat(dashboard.period().endDate()).isEqualTo(newEffectiveToday);
        assertThat(dashboard.summary().currentGlobalWritingStreak()).isEqualTo(3);
        assertThat(dashboard.summary().bestGlobalWritingStreak()).isEqualTo(3);
        assertThat(dashboard.summary().writingDaysThisMonth()).isEqualTo(3);
        assertThat(dashboard.dailySeries()).hasSize(7);
        assertThat(dashboard.dailySeries().getLast().date()).isEqualTo(newEffectiveToday);
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDateBetweenOrderByProgressDateAsc(
                DEFAULT_USER_ID,
                book.getId(),
                LocalDate.of(2026, 6, 24),
                LocalDate.of(2026, 6, 27)
        ))
                .extracting(DailyWritingProgress::getProgressDate)
                .containsExactly(
                        LocalDate.of(2026, 6, 24),
                        LocalDate.of(2026, 6, 25),
                        LocalDate.of(2026, 6, 26),
                        LocalDate.of(2026, 6, 27)
                );
    }

    private void saveProgress(Book book, UUID userId, LocalDate progressDate, int productiveWords, int manuscriptAdjustments) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(entityManager.getReference(Book.class, book.getId()));
        progress.setUser(entityManager.getReference(User.class, userId));
        progress.setProgressDate(progressDate);
        progress.setStartingManuscriptWordCount(0);
        progress.setEndingManuscriptWordCount(productiveWords + manuscriptAdjustments);
        progress.setProductiveWordCountChange(productiveWords);
        progress.setManuscriptAdjustmentWordCount(manuscriptAdjustments);
        progressRepository.save(progress);
    }

    private User createUser(String displayName, String email) {
        return createUser(displayName, email, "America/Sao_Paulo");
    }

    private User createUser(String displayName, String email, String timeZoneId) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId(timeZoneId);
        entityManager.persist(user);
        return user;
    }

    private void addDefaultTenantMembership(User user) {
        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
    }

    private Book createForeignTenantBook(String title) {
        Tenant tenant = new Tenant();
        tenant.setName("Foreign tenant");
        tenant.setDefaultTimeZoneId("America/Sao_Paulo");
        entityManager.persist(tenant);
        User owner = createUser("Foreign Book Owner", "service.foreign.owner@iwrite.local", "America/Sao_Paulo");
        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(owner);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);

        Book book = new Book();
        book.setTenant(tenant);
        book.setOwner(owner);
        book.setTitle(title);
        entityManager.persist(book);
        return book;
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
