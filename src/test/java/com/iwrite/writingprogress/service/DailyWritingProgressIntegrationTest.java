package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.dashboard.service.BookDashboardService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

class DailyWritingProgressIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 30);

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private BookDashboardService dashboardService;

    @Autowired
    private BookWritingScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void sceneContentUpdateCreatesTodayProgressFromBeforeAndAfterTotals() {
        var book = createBook("content progress");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");

        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(5)));

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartingManuscriptWordCount()).isZero();
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(5);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
    }

    @Test
    void multipleSavesOnSameDayUpdateOneProgressRow() {
        var book = createBook("multiple saves");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");

        SceneResponse firstSave = sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(2)));
        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(5), null, firstSave.contentRevision()));

        assertThat(progressRepository.countByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)).isEqualTo(1);
        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartingManuscriptWordCount()).isZero();
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(5);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
    }

    @Test
    void sceneDeleteUpdatesManuscriptAdjustmentWithoutReducingProductiveProgress() {
        var book = createBook("delete progress");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, wordText(4));

        sceneService.delete(scene.id());

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartingManuscriptWordCount()).isZero();
        assertThat(progress.getEndingManuscriptWordCount()).isZero();
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(4);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(-4);
        assertThat(dashboardService.getDashboard(book.id()).myWriting().progress().consistency().writingDaysThisMonth()).isEqualTo(1);
    }

    @Test
    void sceneCreationAfterPriorManuscriptAdjustmentAddsOnlyCreatedWordsToProductiveProgress() {
        var book = createBook("create after adjustment");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Existing Scene", SceneStatus.DRAFT, 0, wordText(100));
        DailyWritingProgress adjustedProgress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        adjustedProgress.setProductiveWordCountChange(0);
        adjustedProgress.setManuscriptAdjustmentWordCount(100);
        adjustedProgress.setEndingManuscriptWordCount(100);
        progressRepository.save(adjustedProgress);

        createScene(chapter, "New Scene", SceneStatus.DRAFT, 1, wordText(5));
        entityManager.flush();
        entityManager.clear();

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartingManuscriptWordCount()).isZero();
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(105);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(100);
    }

    @Test
    void dashboardReturnsSafeEmptyWritingProgressWithoutProgressRows() {
        var book = createBook("empty writing progress");

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.myWriting().progress().today().date()).isEqualTo(TODAY);
        assertThat(dashboard.myWriting().progress().today().dailyTargetWordCount()).isNull();
        assertThat(dashboard.myWriting().progress().today().startingManuscriptWordCount()).isZero();
        assertThat(dashboard.myWriting().progress().today().endingManuscriptWordCount()).isZero();
        assertThat(dashboard.myWriting().progress().today().productiveWordCountChange()).isZero();
        assertThat(dashboard.myWriting().progress().today().manuscriptAdjustmentWordCount()).isZero();
        assertThat(dashboard.myWriting().progress().today().progressPercent()).isNull();
        assertThat(dashboard.myWriting().progress().recentDays()).isEmpty();
    }

    @Test
    void dashboardReturnsTodayAndRecentDaysNewestFirst() {
        var book = createBook("recent writing progress");
        setPersonalDailyTarget(book.id(), 6);
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");
        LocalDate today = TODAY;

        saveProgress(bookService.getBook(book.id()), today.minusDays(2), 3, 7);
        saveProgress(bookService.getBook(book.id()), today.minusDays(1), 7, 9);
        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(3)));

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.myWriting().progress().today().date()).isEqualTo(today);
        assertThat(dashboard.myWriting().progress().today().dailyTargetWordCount()).isEqualTo(6);
        assertThat(dashboard.myWriting().progress().today().productiveWordCountChange()).isEqualTo(3);
        assertThat(dashboard.myWriting().progress().today().manuscriptAdjustmentWordCount()).isZero();
        assertThat(dashboard.myWriting().progress().today().progressPercent()).isEqualTo(50.0);
        assertThat(dashboard.myWriting().progress().recentDays())
                .extracting(day -> day.date())
                .containsExactly(today, today.minusDays(1), today.minusDays(2));
    }

    @Test
    void dashboardExposesProductiveAndAdjustmentValuesSeparately() {
        var book = createBook("adjustment dashboard");
        setPersonalDailyTarget(book.id(), 5);
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, TODAY, 100, 112, 2, 10);

        var today = dashboardService.getDashboard(book.id()).myWriting().progress().today();

        assertThat(today.startingManuscriptWordCount()).isEqualTo(100);
        assertThat(today.endingManuscriptWordCount()).isEqualTo(112);
        assertThat(today.productiveWordCountChange()).isEqualTo(2);
        assertThat(today.manuscriptAdjustmentWordCount()).isEqualTo(10);
        assertThat(today.progressPercent()).isEqualTo(40.0);
    }

    @Test
    void todayAdjustmentWithoutProductiveWritingDoesNotBreakCurrentStreak() {
        var book = createBook("adjustment keeps streak");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(1), 100, 104, 4, 0);
        saveProgress(persistedBook, today, 104, 99, 0, -5);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(1);
        assertThat(consistency.writingDaysThisMonth()).isEqualTo(1);
        assertThat(consistency.recentWritingDays()).isEqualTo(1);
        assertThat(consistency.recentSuccessfulPlannedWritingDays()).isEqualTo(1);
    }

    @Test
    void dashboardTotalWordCountComesFromLiveScenesNotDailyRollupEndingTotal() {
        var book = createBook("live total dashboard");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Scene", SceneStatus.DRAFT, 0, wordText(3));
        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), TODAY)
                .orElseThrow();
        progress.setEndingManuscriptWordCount(999);
        progress.setProductiveWordCountChange(1);
        progress.setManuscriptAdjustmentWordCount(998);
        progressRepository.save(progress);

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.totalWordCount()).isEqualTo(3);
        assertThat(dashboard.myWriting().progress().today().endingManuscriptWordCount()).isEqualTo(999);
    }

    @Test
    void dashboardCurrentStreakIncludesTodayWhenTodayIsPositive() {
        var book = createBook("current streak includes today");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(2), 0, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 7);
        saveProgress(persistedBook, today, 7, 9);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(3);
        assertThat(consistency.bestStreakDays()).isEqualTo(3);
    }

    @Test
    void dashboardCurrentStreakFallsBackToYesterdayWhenTodayIsMissing() {
        LocalDate today = TODAY;

        var missingTodayBook = createBook("current streak missing today");
        Book persistedMissingTodayBook = bookService.getBook(missingTodayBook.id());
        saveProgress(persistedMissingTodayBook, today.minusDays(2), 0, 1);
        saveProgress(persistedMissingTodayBook, today.minusDays(1), 1, 3);

        assertThat(dashboardService.getDashboard(missingTodayBook.id()).myWriting().progress().consistency().currentStreakDays())
                .isEqualTo(2);
    }

    @Test
    void dashboardCurrentStreakFallsBackToYesterdayWhenTodayHasNoProductiveWriting() {
        LocalDate today = TODAY;
        var zeroTodayBook = createBook("current streak zero today");
        Book persistedZeroTodayBook = bookService.getBook(zeroTodayBook.id());
        saveProgress(persistedZeroTodayBook, today.minusDays(2), 0, 1);
        saveProgress(persistedZeroTodayBook, today.minusDays(1), 1, 3);
        saveProgress(persistedZeroTodayBook, today, 3, 3);

        assertThat(dashboardService.getDashboard(zeroTodayBook.id()).myWriting().progress().consistency().currentStreakDays())
                .isEqualTo(2);
    }

    @Test
    void dashboardCurrentStreakIsZeroWhenNeitherTodayNorYesterdayIsPositive() {
        var book = createBook("current streak zero");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(3), 0, 2);
        saveProgress(persistedBook, today.minusDays(1), 2, 2);
        saveProgress(persistedBook, today, 2, 1);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.currentStreakDays()).isZero();
        assertThat(consistency.bestStreakDays()).isEqualTo(1);
    }

    @Test
    void dashboardMissingDateBreaksCurrentAndBestStreak() {
        var book = createBook("missing date breaks streak");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(4), 0, 1);
        saveProgress(persistedBook, today.minusDays(3), 1, 2);
        saveProgress(persistedBook, today.minusDays(1), 2, 3);
        saveProgress(persistedBook, today, 3, 4);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(2);
        assertThat(consistency.bestStreakDays()).isEqualTo(2);
    }

    @Test
    void dashboardZeroOrNegativeDayBreaksStreak() {
        var book = createBook("zero negative breaks streak");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(4), 0, 2);
        saveProgress(persistedBook, today.minusDays(3), 2, 2);
        saveProgress(persistedBook, today.minusDays(2), 2, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 3);
        saveProgress(persistedBook, today, 3, 5);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(1);
        assertThat(consistency.bestStreakDays()).isEqualTo(1);
    }

    @Test
    void dashboardBestStreakUsesFullHistoryNotSelectedChartPeriod() {
        var book = createBook("best streak full history");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(20), 0, 1);
        saveProgress(persistedBook, today.minusDays(19), 1, 2);
        saveProgress(persistedBook, today.minusDays(18), 2, 3);
        saveProgress(persistedBook, today.minusDays(17), 3, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 5);
        saveProgress(persistedBook, today, 5, 6);

        var dashboard = dashboardService.getDashboard(book.id(), WritingProgressPeriod.SEVEN_DAYS);
        var consistency = dashboard.myWriting().progress().consistency();

        assertThat(dashboard.myWriting().progress().recentDays())
                .extracting(day -> day.date())
                .doesNotContain(today.minusDays(20), today.minusDays(19), today.minusDays(18), today.minusDays(17));
        assertThat(consistency.currentStreakDays()).isEqualTo(2);
        assertThat(consistency.bestStreakDays()).isEqualTo(4);
    }

    @Test
    void dashboardMonthAndRecentConsistencyCountOnlyPositiveRows() {
        var book = createBook("month and recent consistency");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);

        saveProgress(persistedBook, firstDayOfMonth.minusDays(1), 0, 10);
        saveProgress(persistedBook, firstDayOfMonth, 10, 12);
        saveProgress(persistedBook, today.minusDays(8), 12, 14);
        saveProgress(persistedBook, today.minusDays(6), 14, 17);
        saveProgress(persistedBook, today.minusDays(5), 17, 17);
        saveProgress(persistedBook, today.minusDays(4), 17, 16);
        saveProgress(persistedBook, today, 16, 20);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.writingDaysThisMonth()).isEqualTo(4);
        assertThat(consistency.recentWindowDays()).isEqualTo(7);
        assertThat(consistency.recentWritingDays()).isEqualTo(2);
        assertThat(consistency.recentWritingDaysPercent()).isEqualTo((2 * 100.0) / 7);
        assertThat(consistency.recentPlannedWritingDays()).isEqualTo(7);
        assertThat(consistency.recentSuccessfulPlannedWritingDays()).isEqualTo(2);
        assertThat(consistency.recentPlannedWritingDaysPercent()).isEqualTo((2 * 100.0) / 7);
    }

    @Test
    void futureScheduleChangeDoesNotReinterpretCurrentHistoricalStreak() {
        var book = createBook("future schedule does not rewrite today");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());
        saveProgress(persistedBook, today.minusDays(1), 0, 1);
        saveProgress(persistedBook, today, 1, 2);

        setPersonalPlannedWritingDays(
                book.id(),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        );

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.myWriting().progress().consistency().currentStreakDays()).isEqualTo(2);
        assertThat(dashboard.myWriting().schedule().plannedWritingDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(dashboard.myWriting().schedule().todayPlannedWritingDay()).isTrue();
        assertThat(dashboard.myWriting().schedule().currentScheduleEffectiveFrom()).isEqualTo(today.plusDays(1));
    }

    @Test
    void restDayWritingIsRecordedButDoesNotIncrementPlannedStreak() {
        var book = createBook("rest day bonus");
        writeOnWeekdaysSinceTheBookStarted(book.id());
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());
        saveProgress(persistedBook, today.minusDays(1), 0, 2);
        saveProgress(persistedBook, today, 2, 5);

        var dashboard = dashboardService.getDashboard(book.id());
        var consistency = dashboard.myWriting().progress().consistency();

        assertThat(dashboard.myWriting().schedule().todayPlannedWritingDay()).isFalse();
        assertThat(dashboard.myWriting().progress().today().productiveWordCountChange()).isEqualTo(3);
        assertThat(consistency.currentStreakDays()).isEqualTo(1);
        assertThat(consistency.writingDaysThisMonth()).isEqualTo(2);
    }

    @Test
    void recentPlannedConsistencyUsesPlannedDaysAsDenominator() {
        var book = createBook("planned denominator");
        writeOnWeekdaysSinceTheBookStarted(book.id());
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());
        saveProgress(persistedBook, today.minusDays(5), 0, 1);
        saveProgress(persistedBook, today, 1, 3);

        var consistency = dashboardService.getDashboard(book.id()).myWriting().progress().consistency();

        assertThat(consistency.recentWindowDays()).isEqualTo(7);
        assertThat(consistency.recentWritingDays()).isEqualTo(2);
        assertThat(consistency.recentPlannedWritingDays()).isEqualTo(5);
        assertThat(consistency.recentSuccessfulPlannedWritingDays()).isEqualTo(1);
        assertThat(consistency.recentPlannedWritingDaysPercent()).isEqualTo(20.0);
    }

    private void saveProgress(Book book, LocalDate progressDate, int startingManuscriptWordCount, int endingManuscriptWordCount) {
        saveProgress(
                book,
                progressDate,
                startingManuscriptWordCount,
                endingManuscriptWordCount,
                endingManuscriptWordCount - startingManuscriptWordCount,
                0
        );
    }

    private void saveProgress(
            Book book,
            LocalDate progressDate,
            int startingManuscriptWordCount,
            int endingManuscriptWordCount,
            int productiveWordCountChange,
            int manuscriptAdjustmentWordCount
    ) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setUser(entityManager.getReference(User.class, DEFAULT_USER_ID));
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(personalBookWritingGoalService.dailyTargetWordCountFor(book.getId(), DEFAULT_USER_ID));
        progress.setStartingManuscriptWordCount(startingManuscriptWordCount);
        progress.setEndingManuscriptWordCount(endingManuscriptWordCount);
        progress.setProductiveWordCountChange(productiveWordCountChange);
        progress.setManuscriptAdjustmentWordCount(manuscriptAdjustmentWordCount);
        progressRepository.save(progress);
    }

    /**
     * Puts the User's routine on weekdays for the Book's whole history. Changing a routine through the
     * goal only takes effect tomorrow, on purpose, so a test about rest days that already happened has
     * to seed the schedule period itself instead of asking the product to rewrite the past.
     */
    private void writeOnWeekdaysSinceTheBookStarted(UUID bookId) {
        var activeSchedule = scheduleRepository
                .findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, bookId)
                .orElseThrow();
        activeSchedule.setPlannedDays(EnumSet.of(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
        ));
        scheduleRepository.saveAndFlush(activeSchedule);
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
