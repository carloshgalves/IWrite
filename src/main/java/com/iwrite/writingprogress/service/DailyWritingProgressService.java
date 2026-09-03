package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.dashboard.dto.WritingConsistencyResponse;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class DailyWritingProgressService {

    private static final int WRITING_DAY_THRESHOLD = 0;
    private final BookService bookService;
    private final DailyWritingProgressRepository progressRepository;
    private final WritingScheduleService writingScheduleService;
    private final PersonalBookWritingGoalService personalBookWritingGoalService;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final UserRepository userRepository;
    private final WritingDayResolver writingDayResolver;

    public DailyWritingProgressService(
            BookService bookService,
            DailyWritingProgressRepository progressRepository,
            WritingScheduleService writingScheduleService,
            PersonalBookWritingGoalService personalBookWritingGoalService,
            CurrentUserMembershipService currentUserMembershipService,
            UserRepository userRepository,
            WritingDayResolver writingDayResolver
    ) {
        this.bookService = bookService;
        this.progressRepository = progressRepository;
        this.writingScheduleService = writingScheduleService;
        this.personalBookWritingGoalService = personalBookWritingGoalService;
        this.currentUserMembershipService = currentUserMembershipService;
        this.userRepository = userRepository;
        this.writingDayResolver = writingDayResolver;
    }

    @Transactional
    public void recordWordCountChange(UUID bookId, int totalBefore, int totalAfter) {
        if (totalBefore == totalAfter) {
            return;
        }

        Book book = bookService.getBook(bookId);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        LocalDate progressDate = today();

        DailyWritingProgress progress = progressRepository.findByUser_IdAndBookIdAndProgressDate(userId, bookId, progressDate)
                .orElseGet(() -> createProgress(book, userId, progressDate, totalBefore));

        progress.setEndingManuscriptWordCount(totalAfter);
        progress.setProductiveWordCountChange(totalAfter - progress.getStartingManuscriptWordCount());
        progressRepository.save(progress);
    }

    @Transactional(readOnly = true)
    public DailyWritingProgress getTodayProgressOrEmpty(UUID bookId, int currentTotalWordCount) {
        Book book = bookService.getBook(bookId);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        LocalDate progressDate = today();
        return progressRepository.findByUser_IdAndBookIdAndProgressDate(userId, bookId, progressDate)
                .orElseGet(() -> emptyTodayProgress(book, userId, progressDate, currentTotalWordCount));
    }

    @Transactional(readOnly = true)
    public List<DailyWritingProgress> getRecentProgress(UUID bookId) {
        return getRecentProgress(bookId, WritingProgressPeriod.DEFAULT);
    }

    @Transactional(readOnly = true)
    public List<DailyWritingProgress> getRecentProgress(UUID bookId, WritingProgressPeriod period) {
        bookService.getBook(bookId);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        LocalDate endDate = today();
        LocalDate startDate = period.startDateInclusive(endDate);
        return progressRepository.findByUser_IdAndBookIdAndProgressDateBetweenOrderByProgressDateDesc(userId, bookId, startDate, endDate);
    }

    @Transactional
    public WritingConsistencyResponse getWritingConsistency(UUID bookId) {
        return getWritingConsistency(bookId, WritingProgressPeriod.DEFAULT);
    }

    @Transactional
    public WritingConsistencyResponse getWritingConsistency(UUID bookId, WritingProgressPeriod period) {
        Book book = bookService.getBook(bookId);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        LocalDate today = today();
        LocalDate earliestScheduleDate = writingScheduleService.getEarliestEffectiveFrom(book);
        LocalDate calculationStartDate = progressRepository.findFirstByUser_IdAndBookIdOrderByProgressDateAsc(userId, bookId)
                .map(DailyWritingProgress::getProgressDate)
                .filter(progressDate -> progressDate.isBefore(earliestScheduleDate))
                .orElse(earliestScheduleDate);
        LocalDate recentStartDate = period.startDateInclusive(today);
        List<DailyWritingProgress> progressHistory = progressRepository
                .findByUser_IdAndBookIdAndProgressDateBetweenOrderByProgressDateAsc(userId, bookId, calculationStartDate, today);
        Map<LocalDate, DailyWritingProgress> progressByDate = progressHistory.stream()
                .collect(Collectors.toMap(DailyWritingProgress::getProgressDate, Function.identity()));
        List<BookWritingSchedule> fullScheduleHistory =
                writingScheduleService.getSchedulesForRange(book, calculationStartDate, today);
        List<BookWritingSchedule> recentScheduleHistory =
                writingScheduleService.getSchedulesForRange(book, recentStartDate, today);

        int recentWritingDays = countPositiveProgressBetween(userId, bookId, recentStartDate, today);
        int recentWindowDays = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(recentStartDate, today) + 1);
        int recentPlannedWritingDays = countPlannedDays(recentStartDate, today, recentScheduleHistory);
        int recentSuccessfulPlannedWritingDays = countSuccessfulPlannedDays(recentStartDate, today, progressByDate, recentScheduleHistory);

        return new WritingConsistencyResponse(
                currentStreakDays(calculationStartDate, today, progressByDate, fullScheduleHistory),
                bestStreakDays(calculationStartDate, today, progressByDate, fullScheduleHistory),
                countPositiveProgressBetween(userId, bookId, today.withDayOfMonth(1), today),
                recentWindowDays,
                recentWritingDays,
                recentWindowDays == 0 ? 0.0 : (recentWritingDays * 100.0) / recentWindowDays,
                recentPlannedWritingDays,
                recentSuccessfulPlannedWritingDays,
                recentPlannedWritingDays == 0 ? 0.0 : (recentSuccessfulPlannedWritingDays * 100.0) / recentPlannedWritingDays
        );
    }

    private int currentStreakDays(
            LocalDate earliestScheduleDate,
            LocalDate today,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int streakDays = 0;
        LocalDate progressDate = today;

        while (!progressDate.isBefore(earliestScheduleDate)) {
            if (!writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                progressDate = progressDate.minusDays(1);
                continue;
            }

            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (progressDate.equals(today) && !isProductiveWritingDay(progress)) {
                progressDate = progressDate.minusDays(1);
                continue;
            }
            if (isProductiveWritingDay(progress)) {
                streakDays++;
                progressDate = progressDate.minusDays(1);
                continue;
            }

            break;
        }

        return streakDays;
    }

    private int bestStreakDays(
            LocalDate earliestScheduleDate,
            LocalDate today,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int bestStreakDays = 0;
        int currentStreakDays = 0;
        LocalDate progressDate = earliestScheduleDate;

        while (!progressDate.isAfter(today)) {
            if (!writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                progressDate = progressDate.plusDays(1);
                continue;
            }

            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (isProductiveWritingDay(progress)) {
                currentStreakDays++;
                bestStreakDays = Math.max(bestStreakDays, currentStreakDays);
            } else {
                currentStreakDays = 0;
            }

            progressDate = progressDate.plusDays(1);
        }

        return bestStreakDays;
    }

    private int countPlannedDays(
            LocalDate startDate,
            LocalDate endDate,
            List<BookWritingSchedule> schedules
    ) {
        int plannedDays = 0;
        LocalDate progressDate = startDate;
        while (!progressDate.isAfter(endDate)) {
            if (writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                plannedDays++;
            }
            progressDate = progressDate.plusDays(1);
        }
        return plannedDays;
    }

    private int countSuccessfulPlannedDays(
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int successfulPlannedDays = 0;
        LocalDate progressDate = startDate;
        while (!progressDate.isAfter(endDate)) {
            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (writingScheduleService.isPlannedWritingDay(progressDate, schedules)
                    && isProductiveWritingDay(progress)) {
                successfulPlannedDays++;
            }
            progressDate = progressDate.plusDays(1);
        }
        return successfulPlannedDays;
    }

    private int countPositiveProgressBetween(UUID userId, UUID bookId, LocalDate startDate, LocalDate endDate) {
        return Math.toIntExact(progressRepository.countByUser_IdAndBookIdAndProgressDateBetweenAndProductiveWordCountChangeGreaterThan(
                userId,
                bookId,
                startDate,
                endDate,
                WRITING_DAY_THRESHOLD
        ));
    }

    private boolean isProductiveWritingDay(DailyWritingProgress progress) {
        return progress != null && progress.getProductiveWordCountChange() > WRITING_DAY_THRESHOLD;
    }

    /**
     * Snapshots the target of the User the progress belongs to, never a Book-wide one. A day with no
     * chosen target keeps {@code null}, which stays an intentional absence rather than a target of zero.
     */
    private DailyWritingProgress createProgress(Book book, UUID userId, LocalDate progressDate, int totalBefore) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setUser(userRepository.getReferenceById(userId));
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(personalBookWritingGoalService.dailyTargetWordCountFor(book.getId(), userId));
        progress.setStartingManuscriptWordCount(totalBefore);
        progress.setEndingManuscriptWordCount(totalBefore);
        progress.setProductiveWordCountChange(0);
        progress.setManuscriptAdjustmentWordCount(0);
        return progress;
    }

    private DailyWritingProgress emptyTodayProgress(Book book, UUID userId, LocalDate progressDate, int currentTotalWordCount) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setUser(userRepository.getReferenceById(userId));
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(personalBookWritingGoalService.dailyTargetWordCountFor(book.getId(), userId));
        progress.setStartingManuscriptWordCount(currentTotalWordCount);
        progress.setEndingManuscriptWordCount(currentTotalWordCount);
        progress.setProductiveWordCountChange(0);
        progress.setManuscriptAdjustmentWordCount(0);
        return progress;
    }

    public LocalDate today() {
        return writingDayResolver.currentWritingDate();
    }
}
