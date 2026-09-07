package com.iwrite.dashboard.dto;

/**
 * The authenticated User's own writing inside one Book: the routine and daily target they chose, and
 * the progress measured against them. Projected only for a role that may manage a Personal Book
 * Writing Goal at all.
 *
 * @param writingGoalRevision the goal state this projection was read at, which a save of the daily
 *                            target or the routine quotes back so a choice made against superseded
 *                            state is refused instead of silently replacing the newer one
 */
public record BookMyWritingResponse(
        WritingProgressDashboardResponse progress,
        WritingScheduleResponse schedule,
        int writingGoalRevision
) {
}
