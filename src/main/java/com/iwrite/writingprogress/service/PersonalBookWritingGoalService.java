package com.iwrite.writingprogress.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
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

    /**
     * The revision a User who never saved a goal in this Book reads. A row created by the V36 backfill
     * starts here too: it carries the target that was already effective, not a choice made through
     * this contract.
     */
    static final int UNSAVED_GOAL_REVISION = 0;

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
        PersonalBookWritingGoal goal = goalRepository.findByUser_IdAndBook_Id(userId, bookId).orElse(null);
        return response(
                writingScheduleService.getOrCreateActiveScheduleForCurrentUser(book),
                goal == null ? null : goal.getDailyTargetWordCount(),
                revisionOf(goal)
        );
    }

    @Transactional
    public PersonalBookWritingGoalResponse updateGoal(UUID bookId, PersonalBookWritingGoalUpdateRequest request) {
        Book book = bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL);
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();

        PersonalBookWritingGoal goal = applyGoal(book, userId, request);

        if (request.isPlannedWritingDaysPresent()) {
            // The routine change already resolved the operation's writing date and left an active
            // schedule behind, so the projection reads it instead of resolving "now" a second time and
            // risking a different local date for the same request.
            writingScheduleService.changeSchedule(book, request.plannedWritingDays());
            return response(
                    writingScheduleService.getActiveSchedule(bookId),
                    goal.getDailyTargetWordCount(),
                    goal.getRevision()
            );
        }

        return response(
                writingScheduleService.getOrCreateActiveScheduleForCurrentUser(book),
                goal.getDailyTargetWordCount(),
                goal.getRevision()
        );
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

    /**
     * The caller's own goal read once: the target they chose, if any, together with the revision that
     * target was read at.
     *
     * <p>One read on purpose. A projection that shows both must not assemble them from two statements:
     * under {@code READ COMMITTED} each statement takes its own snapshot, so a save committing between
     * them would pair a target read before it with the revision it produced. A later save quoting that
     * revision would then be accepted against a state its caller never saw, which is the lost update
     * the revision exists to refuse.
     *
     * <p>Unguarded for the same reason as {@link #dailyTargetWordCountFor}: it serves flows that have
     * already established whose goal they are projecting.
     */
    @Transactional(readOnly = true)
    public PersonalBookWritingGoalSnapshot snapshotFor(UUID bookId, UUID userId) {
        return goalRepository.findByUser_IdAndBook_Id(userId, bookId)
                .map(goal -> new PersonalBookWritingGoalSnapshot(goal.getDailyTargetWordCount(), goal.getRevision()))
                .orElseGet(PersonalBookWritingGoalSnapshot::unsaved);
    }

    /**
     * Rejects a save decided against superseded state, then applies it and advances the revision of
     * the whole goal.
     *
     * <p>The comparison and the advance both happen under the Book row lock the guard already took,
     * so no other save of this goal can read the revision between them: two tabs holding the same
     * revision produce exactly one success and one conflict, never two successes with the second
     * silently discarding the first.
     *
     * <p>The row is materialized even when only the routine changed and even when the target is
     * cleared: the revision is the goal's, not the target's, and it needs somewhere to live for the
     * next save to be checked against. A row whose target is {@code null} still means no target was
     * chosen.
     */
    private PersonalBookWritingGoal applyGoal(Book book, UUID userId, PersonalBookWritingGoalUpdateRequest request) {
        PersonalBookWritingGoal goal = goalRepository.findByUserIdAndBookIdForUpdate(userId, book.getId())
                .orElse(null);

        if (request.expectedRevision() == null) {
            // Bean validation already refuses this over HTTP; the service refuses it too so the
            // guarantee belongs to the operation rather than to one of its callers.
            throw new BadRequestException("expectedRevision is required");
        }

        if (!request.isGoalChangeNamed()) {
            // Refused for the same reason and in the same place. A request that names no half of the
            // goal has nothing to apply, and applying it anyway would advance the revision and make the
            // next real edit conflict with a save that changed nothing.
            throw new BadRequestException("a save must change dailyTargetWordCount or plannedWritingDays");
        }

        if (request.expectedRevision() != revisionOf(goal)) {
            // Deliberately says nothing about the current state: the caller reloads its own goal
            // through the guarded read rather than learning anything from the refusal.
            throw new ConflictException(
                    "Personal writing goal was changed by a newer save; reload it and try again"
            );
        }

        if (goal == null) {
            goal = new PersonalBookWritingGoal();
            goal.setUser(userRepository.getReferenceById(userId));
            goal.setBook(book);
        }

        if (request.isDailyTargetWordCountPresent()) {
            goal.setDailyTargetWordCount(request.dailyTargetWordCount());
        }

        goal.setRevision(revisionOf(goal) + 1);
        return goalRepository.save(goal);
    }

    private int revisionOf(PersonalBookWritingGoal goal) {
        return goal == null ? UNSAVED_GOAL_REVISION : goal.getRevision();
    }

    private PersonalBookWritingGoalResponse response(
            BookWritingSchedule activeSchedule,
            Integer dailyTargetWordCount,
            int revision
    ) {
        return new PersonalBookWritingGoalResponse(
                dailyTargetWordCount,
                writingScheduleService.orderedDays(activeSchedule.getPlannedDays()),
                activeSchedule.getEffectiveFrom(),
                revision
        );
    }
}
