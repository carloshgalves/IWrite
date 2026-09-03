package com.iwrite.writingprogress.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalResponse;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalUpdateRequest;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.entity.PersonalBookWritingGoal;
import com.iwrite.writingprogress.repository.PersonalBookWritingGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The Personal Book Writing Goal of the authenticated User in one Book (#206).
 *
 * <p>Every public operation is guarded by {@link BookCapability#MANAGE_OWN_PERSONAL_WRITING_GOAL} and
 * always resolves the goal of the caller. There is no parameter for whose goal to read or write, so
 * an Editor or Reader cannot reach another collaborator's target, and no request can widen the scope
 * of the operation by naming a different User.
 *
 * <p>The daily target and the planned writing days are the two halves of the same personal goal, so
 * one request updates both; the days keep living in the period-versioned schedule that past progress
 * is evaluated against.
 */
@Service
public class PersonalBookWritingGoalService {

    private final PersonalBookWritingGoalRepository goalRepository;
    private final BookAccessService bookAccessService;
    private final WritingScheduleService writingScheduleService;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final UserRepository userRepository;

    public PersonalBookWritingGoalService(
            PersonalBookWritingGoalRepository goalRepository,
            BookAccessService bookAccessService,
            WritingScheduleService writingScheduleService,
            CurrentUserMembershipService currentUserMembershipService,
            UserRepository userRepository
    ) {
        this.goalRepository = goalRepository;
        this.bookAccessService = bookAccessService;
        this.writingScheduleService = writingScheduleService;
        this.currentUserMembershipService = currentUserMembershipService;
        this.userRepository = userRepository;
    }

    @Transactional
    public PersonalBookWritingGoalResponse getGoal(UUID bookId) {
        Book book = bookAccessService.requireCapability(bookId, BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return response(
                writingScheduleService.getOrCreateActiveScheduleForCurrentUser(book),
                dailyTargetWordCountFor(bookId, userId)
        );
    }

    @Transactional
    public PersonalBookWritingGoalResponse updateGoal(UUID bookId, PersonalBookWritingGoalUpdateRequest request) {
        Book book = bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();

        Integer dailyTargetWordCount = request.isDailyTargetWordCountPresent()
                ? applyDailyTarget(book, userId, request.dailyTargetWordCount())
                : dailyTargetWordCountFor(bookId, userId);

        if (request.isPlannedWritingDaysPresent()) {
            // The routine change already resolved the operation's writing date and left an active
            // schedule behind, so the projection reads it instead of resolving "now" a second time and
            // risking a different local date for the same request.
            writingScheduleService.changeSchedule(book, request.plannedWritingDays());
            return response(writingScheduleService.getActiveSchedule(bookId), dailyTargetWordCount);
        }

        return response(writingScheduleService.getOrCreateActiveScheduleForCurrentUser(book), dailyTargetWordCount);
    }

    /**
     * The caller's own current daily target, or {@code null} when no target was chosen.
     *
     * <p>Unguarded on purpose: it exists for flows that have already proven the User's Book access and
     * only need to snapshot the target that was in effect for them. It never reads another User's goal
     * because the User is the one the calling flow already resolved from the session.
     */
    @Transactional(readOnly = true)
    public Integer dailyTargetWordCountFor(UUID bookId, UUID userId) {
        return goalRepository.findByUser_IdAndBook_Id(userId, bookId)
                .map(PersonalBookWritingGoal::getDailyTargetWordCount)
                .orElse(null);
    }

    private Integer applyDailyTarget(Book book, UUID userId, Integer dailyTargetWordCount) {
        PersonalBookWritingGoal goal = goalRepository.findByUserIdAndBookIdForUpdate(userId, book.getId())
                .orElse(null);

        if (goal == null) {
            if (dailyTargetWordCount == null) {
                return null;
            }
            goal = new PersonalBookWritingGoal();
            goal.setUser(userRepository.getReferenceById(userId));
            goal.setBook(book);
        }

        goal.setDailyTargetWordCount(dailyTargetWordCount);
        goalRepository.save(goal);
        return dailyTargetWordCount;
    }

    private PersonalBookWritingGoalResponse response(BookWritingSchedule activeSchedule, Integer dailyTargetWordCount) {
        return new PersonalBookWritingGoalResponse(
                dailyTargetWordCount,
                writingScheduleService.orderedDays(activeSchedule.getPlannedDays()),
                activeSchedule.getEffectiveFrom()
        );
    }
}
