package com.iwrite.writingprogress.repository;

import com.iwrite.writingprogress.entity.PersonalBookWritingGoal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PersonalBookWritingGoalRepository extends JpaRepository<PersonalBookWritingGoal, UUID> {

    Optional<PersonalBookWritingGoal> findByUser_IdAndBook_Id(UUID userId, UUID bookId);

    /**
     * Serializes concurrent writes of the same User's goal in the same Book, so two tabs saving at
     * once cannot interleave into a target neither of them chose.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select goal
            from PersonalBookWritingGoal goal
            where goal.user.id = :userId
              and goal.book.id = :bookId
            """)
    Optional<PersonalBookWritingGoal> findByUserIdAndBookIdForUpdate(
            @Param("userId") UUID userId,
            @Param("bookId") UUID bookId
    );

    /**
     * Reads a User's whole goal in one statement: the chosen target, the revision that versions it, and
     * the active routine that is the other half of that same revision.
     *
     * <p>One statement because the revision has to describe everything returned beside it. Under
     * {@code READ COMMITTED} each statement takes its own snapshot, so assembling these from several
     * reads lets a save committing between them pair a revision with state from either side of it —
     * an old target under a new revision, which a later save would be allowed to overwrite, or a new
     * routine under an old revision, which would refuse a legitimate save over a change nobody made.
     * One statement makes both impossible without locking anything, so a goal read never blocks a
     * writer.
     *
     * <p>Native because it spans three tables that no entity association joins, and because the outer
     * shape has to survive a User who has no goal row at all: the key row on the left keeps the result
     * present, with a null target at the unsaved revision, instead of returning nothing. The days are
     * aggregated rather than joined out so one goal stays one row.
     *
     * <p>The routine is missing only for a User whose Book-scoped routine has never been materialized;
     * callers create it and read again, so the pairing still comes from a single statement.
     */
    @Query(value = """
            select goal.daily_target_word_count as dailyTargetWordCount,
                   coalesce(goal.revision, 0) as revision,
                   schedule.effective_from as plannedWritingDaysEffectiveFrom,
                   string_agg(day.day_of_week, ',') as plannedWritingDays
            from (select cast(:userId as uuid) as user_id, cast(:bookId as uuid) as book_id) key
            left join book_personal_writing_goals goal
                   on goal.user_id = key.user_id
                  and goal.book_id = key.book_id
            left join book_writing_schedules schedule
                   on schedule.user_id = key.user_id
                  and schedule.book_id = key.book_id
                  and schedule.effective_to is null
            left join book_writing_schedule_days day
                   on day.schedule_id = schedule.id
            group by goal.daily_target_word_count, goal.revision, schedule.effective_from
            """, nativeQuery = true)
    GoalSnapshotRow readGoalSnapshot(@Param("userId") UUID userId, @Param("bookId") UUID bookId);

    /** The single-statement projection of {@link #readGoalSnapshot}. */
    interface GoalSnapshotRow {

        Integer getDailyTargetWordCount();

        int getRevision();

        LocalDate getPlannedWritingDaysEffectiveFrom();

        /** Comma-separated weekday names, or {@code null} when no active routine exists yet. */
        String getPlannedWritingDays();
    }
}
