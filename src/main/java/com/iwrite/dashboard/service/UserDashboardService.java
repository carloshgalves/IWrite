package com.iwrite.dashboard.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.dashboard.dto.BookContributionDashboardResponse;
import com.iwrite.dashboard.dto.ContributionDailyWritingResponse;
import com.iwrite.dashboard.dto.ContributionOriginResponse;
import com.iwrite.dashboard.dto.ContributionSummaryResponse;
import com.iwrite.dashboard.dto.ContributorSummaryResponse;
import com.iwrite.dashboard.dto.UserBookContributionResponse;
import com.iwrite.dashboard.dto.UserDailyWritingResponse;
import com.iwrite.dashboard.dto.UserDashboardResponse;
import com.iwrite.dashboard.dto.UserWritingSummaryResponse;
import com.iwrite.dashboard.dto.WritingProgressPeriodResponse;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.entity.User;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.WritingDayResolver;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserDashboardService {

    private static final String ALL_CONTRIBUTORS_SCOPE = "ALL_CONTRIBUTORS";
    private static final String SINGLE_CONTRIBUTOR_SCOPE = "SINGLE_CONTRIBUTOR";

    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final DailyWritingProgressRepository progressRepository;
    private final BookWordCountEventRepository eventRepository;
    private final WritingDayResolver writingDayResolver;
    private final BookAccessService bookAccessService;
    private final UserRepository userRepository;

    public UserDashboardService(
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService,
            DailyWritingProgressRepository progressRepository,
            BookWordCountEventRepository eventRepository,
            WritingDayResolver writingDayResolver,
            BookAccessService bookAccessService,
            UserRepository userRepository
    ) {
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
        this.progressRepository = progressRepository;
        this.eventRepository = eventRepository;
        this.writingDayResolver = writingDayResolver;
        this.bookAccessService = bookAccessService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserDashboardResponse getCurrentUserDashboard(WritingProgressPeriod progressPeriod) {
        UUID currentUserId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        LocalDate today = writingDayResolver.currentWritingDate();
        LocalDate startDate = progressPeriod.startDateInclusive(today);
        List<DailyWritingProgress> progressRows = progressRepository.findCurrentUserTenantProgressBetween(
                currentUserId,
                tenantId,
                startDate,
                today
        );
        Set<LocalDate> positiveDates = new HashSet<>(
                progressRepository.findPositiveProgressDatesForUserTenantThroughDate(currentUserId, tenantId, today)
        );

        return new UserDashboardResponse(
                period(progressPeriod, startDate, today),
                userSummary(progressRows, positiveDates, today),
                userDailySeries(startDate, today, progressRows),
                userBookContributions(progressRows)
        );
    }

    @Transactional(readOnly = true)
    public BookContributionDashboardResponse getBookContributions(
            UUID bookId,
            WritingProgressPeriod progressPeriod,
            UUID contributorId
    ) {
        Book book = bookAccessService.requireCapability(bookId, BookCapability.VIEW_BOOK_CONTRIBUTOR_PROGRESS);
        LocalDate today = writingDayResolver.currentWritingDate();
        LocalDate startDate = progressPeriod.startDateInclusive(today);
        List<User> contributors = userRepository.findBookContributorCandidates(book.getId(), BookRole.READER);
        Map<UUID, User> contributorsById = contributors.stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<ContributorSummaryResponse> contributorSummaries = contributors.stream()
                .map(this::toContributorSummary)
                .toList();

        if (contributorId == null) {
            List<DailyWritingProgress> progressRows = progressRepository.findBookProgressBetweenWithUsers(
                    book.getId(),
                    startDate,
                    today
            );
            List<ContributionOriginResponse> origins = contributionOrigins(
                    eventRepository.findBookContributionEventsBetween(book.getId(), startDate, today)
            );
            return new BookContributionDashboardResponse(
                    period(progressPeriod, startDate, today),
                    ALL_CONTRIBUTORS_SCOPE,
                    null,
                    contributorSummaries,
                    contributionSummary(progressRows, origins),
                    contributionDailySeries(startDate, today, progressRows),
                    origins
            );
        }

        User selectedContributor = contributorsById.get(contributorId);
        if (selectedContributor == null) {
            throw new ResourceNotFoundException("Recorded contributor not found for book: " + book.getId());
        }

        List<DailyWritingProgress> progressRows = progressRepository.findBookContributorProgressBetweenWithUsers(
                book.getId(),
                contributorId,
                startDate,
                today
        );
        List<ContributionOriginResponse> origins = contributionOrigins(
                eventRepository.findBookContributorEventsBetween(book.getId(), contributorId, startDate, today)
        );
        return new BookContributionDashboardResponse(
                period(progressPeriod, startDate, today),
                SINGLE_CONTRIBUTOR_SCOPE,
                toContributorSummary(selectedContributor),
                contributorSummaries,
                contributionSummary(progressRows, origins),
                contributionDailySeries(startDate, today, progressRows),
                origins
        );
    }

    private WritingProgressPeriodResponse period(WritingProgressPeriod progressPeriod, LocalDate startDate, LocalDate today) {
        return new WritingProgressPeriodResponse(progressPeriod.requestValue(), startDate, today);
    }

    private UserWritingSummaryResponse userSummary(
            List<DailyWritingProgress> progressRows,
            Set<LocalDate> positiveDates,
            LocalDate today
    ) {
        long productiveWords = progressRows.stream().mapToLong(DailyWritingProgress::getProductiveWordCountChange).sum();
        long manuscriptAdjustments = progressRows.stream().mapToLong(DailyWritingProgress::getManuscriptAdjustmentWordCount).sum();
        long writingDays = progressRows.stream()
                .filter(progress -> progress.getProductiveWordCountChange() > 0)
                .map(DailyWritingProgress::getProgressDate)
                .distinct()
                .count();
        long booksWrittenIn = progressRows.stream()
                .filter(progress -> progress.getProductiveWordCountChange() > 0)
                .map(progress -> progress.getBook().getId())
                .distinct()
                .count();
        long writingDaysThisMonth = positiveDates.stream()
                .filter(date -> !date.isBefore(today.withDayOfMonth(1)) && !date.isAfter(today))
                .count();

        return new UserWritingSummaryResponse(
                productiveWords,
                manuscriptAdjustments,
                writingDays,
                booksWrittenIn,
                currentStreak(positiveDates, today),
                bestStreak(positiveDates),
                writingDaysThisMonth
        );
    }

    private List<UserDailyWritingResponse> userDailySeries(
            LocalDate startDate,
            LocalDate endDate,
            List<DailyWritingProgress> progressRows
    ) {
        Map<LocalDate, ProgressTotals> totalsByDate = new HashMap<>();
        for (DailyWritingProgress progress : progressRows) {
            totalsByDate
                    .computeIfAbsent(progress.getProgressDate(), ignored -> new ProgressTotals())
                    .add(progress);
        }

        return startDate.datesUntil(endDate.plusDays(1))
                .map(date -> {
                    ProgressTotals totals = totalsByDate.getOrDefault(date, ProgressTotals.EMPTY);
                    return new UserDailyWritingResponse(date, totals.productiveWords, totals.manuscriptAdjustments);
                })
                .toList();
    }

    private List<UserBookContributionResponse> userBookContributions(List<DailyWritingProgress> progressRows) {
        Map<UUID, BookTotals> totalsByBook = new HashMap<>();
        for (DailyWritingProgress progress : progressRows) {
            totalsByBook
                    .computeIfAbsent(progress.getBook().getId(), ignored -> new BookTotals(progress.getBook()))
                    .add(progress);
        }

        return totalsByBook.values()
                .stream()
                .filter(BookTotals::hasRecordedContribution)
                .sorted(Comparator
                        .comparing((BookTotals totals) -> totals.book.getTitle())
                        .thenComparing(totals -> totals.book.getId()))
                .map(totals -> new UserBookContributionResponse(
                        totals.book.getId(),
                        totals.book.getTitle(),
                        totals.productiveWords,
                        totals.manuscriptAdjustments,
                        totals.positiveDates.size()
                ))
                .toList();
    }

    private ContributionSummaryResponse contributionSummary(
            List<DailyWritingProgress> progressRows,
            List<ContributionOriginResponse> origins
    ) {
        long productiveWords = progressRows.stream().mapToLong(DailyWritingProgress::getProductiveWordCountChange).sum();
        long manuscriptAdjustments = progressRows.stream().mapToLong(DailyWritingProgress::getManuscriptAdjustmentWordCount).sum();
        long writingDays = progressRows.stream()
                .filter(progress -> progress.getProductiveWordCountChange() > 0)
                .map(DailyWritingProgress::getProgressDate)
                .distinct()
                .count();
        long contributorsCount = progressRows.stream()
                .filter(UserDashboardService::hasRecordedContribution)
                .map(progress -> progress.getUser().getId())
                .distinct()
                .count();

        long distinctScenes = origins.stream().map(ContributionOriginResponse::sceneId).distinct().count();
        long distinctChapters = origins.stream()
                .map(ContributionOriginResponse::chapterId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        return new ContributionSummaryResponse(
                productiveWords,
                manuscriptAdjustments,
                writingDays,
                contributorsCount,
                distinctScenes,
                distinctChapters
        );
    }

    private List<ContributionOriginResponse> contributionOrigins(List<BookWordCountEvent> events) {
        Map<ContributionOriginKey, ContributionOriginTotals> totalsByOrigin = new LinkedHashMap<>();
        for (BookWordCountEvent event : events) {
            if (event.getOriginalSceneId() == null) {
                continue;
            }
            ContributionOriginKey key = new ContributionOriginKey(
                    event.getOriginalSceneId(),
                    event.getOriginalChapterId()
            );
            totalsByOrigin.computeIfAbsent(key, ignored -> new ContributionOriginTotals(key)).add(event);
        }

        Comparator<String> nullableText = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<ContributionOriginResponse> byOrigin = Comparator
                .comparing(ContributionOriginResponse::chapterTitle, nullableText)
                .thenComparing(ContributionOriginResponse::sceneTitle, nullableText)
                .thenComparing(ContributionOriginResponse::chapterId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ContributionOriginResponse::sceneId);
        return totalsByOrigin.values().stream()
                .map(ContributionOriginTotals::response)
                .sorted(byOrigin)
                .toList();
    }

    private List<ContributionDailyWritingResponse> contributionDailySeries(
            LocalDate startDate,
            LocalDate endDate,
            List<DailyWritingProgress> progressRows
    ) {
        Map<LocalDate, ProgressTotals> totalsByDate = new HashMap<>();
        for (DailyWritingProgress progress : progressRows) {
            totalsByDate
                    .computeIfAbsent(progress.getProgressDate(), ignored -> new ProgressTotals())
                    .add(progress);
        }

        return startDate.datesUntil(endDate.plusDays(1))
                .map(date -> {
                    ProgressTotals totals = totalsByDate.getOrDefault(date, ProgressTotals.EMPTY);
                    return new ContributionDailyWritingResponse(date, totals.productiveWords, totals.manuscriptAdjustments);
                })
                .toList();
    }

    private long currentStreak(Set<LocalDate> positiveDates, LocalDate today) {
        LocalDate cursor = positiveDates.contains(today) ? today : today.minusDays(1);
        long streak = 0;
        while (positiveDates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private long bestStreak(Set<LocalDate> positiveDates) {
        long best = 0;
        long current = 0;
        LocalDate previousDate = null;
        for (LocalDate date : positiveDates.stream().sorted().toList()) {
            current = previousDate != null && date.equals(previousDate.plusDays(1)) ? current + 1 : 1;
            best = Math.max(best, current);
            previousDate = date;
        }
        return best;
    }

    private ContributorSummaryResponse toContributorSummary(User user) {
        return new ContributorSummaryResponse(user.getId(), user.getDisplayName());
    }

    private static boolean hasRecordedContribution(DailyWritingProgress progress) {
        return progress.getProductiveWordCountChange() != 0 || progress.getManuscriptAdjustmentWordCount() != 0;
    }

    private static class ProgressTotals {
        private static final ProgressTotals EMPTY = new ProgressTotals();

        private long productiveWords;
        private long manuscriptAdjustments;

        void add(DailyWritingProgress progress) {
            productiveWords += progress.getProductiveWordCountChange();
            manuscriptAdjustments += progress.getManuscriptAdjustmentWordCount();
        }
    }

    private static class BookTotals {
        private final Book book;
        private final Set<LocalDate> positiveDates = new HashSet<>();
        private boolean recordedContribution;
        private long productiveWords;
        private long manuscriptAdjustments;

        BookTotals(Book book) {
            this.book = book;
        }

        void add(DailyWritingProgress progress) {
            productiveWords += progress.getProductiveWordCountChange();
            manuscriptAdjustments += progress.getManuscriptAdjustmentWordCount();
            if (UserDashboardService.hasRecordedContribution(progress)) {
                recordedContribution = true;
            }
            if (progress.getProductiveWordCountChange() > 0) {
                positiveDates.add(progress.getProgressDate());
            }
        }

        boolean hasRecordedContribution() {
            return recordedContribution;
        }
    }

    private record ContributionOriginKey(UUID sceneId, UUID chapterId) {
    }

    private static class ContributionOriginTotals {
        private final ContributionOriginKey key;
        private final Set<LocalDate> productiveDates = new HashSet<>();
        private String sceneTitle;
        private String chapterTitle;
        private long productiveWords;
        private long manuscriptAdjustments;

        ContributionOriginTotals(ContributionOriginKey key) {
            this.key = key;
        }

        void add(BookWordCountEvent event) {
            sceneTitle = event.getSceneTitleSnapshot();
            chapterTitle = event.getChapterTitleSnapshot();
            productiveWords += event.getProductiveWordDelta();
            manuscriptAdjustments += event.getManuscriptWordDelta() - event.getProductiveWordDelta();
            if (event.getProductiveWordDelta() > 0) {
                productiveDates.add(event.getProgressDate());
            }
        }

        ContributionOriginResponse response() {
            return new ContributionOriginResponse(
                    key.sceneId(),
                    sceneTitle,
                    key.chapterId(),
                    chapterTitle,
                    productiveWords,
                    manuscriptAdjustments,
                    productiveDates.size()
            );
        }
    }
}
