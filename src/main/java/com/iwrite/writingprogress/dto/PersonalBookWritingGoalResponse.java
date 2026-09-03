package com.iwrite.writingprogress.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * The authenticated User's own Personal Book Writing Goal in one Book (#206).
 *
 * <p>It never carries another collaborator's goal, and {@code dailyTargetWordCount} is {@code null}
 * when no target was chosen — an intentional absence, not zero.
 *
 * @param plannedWritingDaysEffectiveFrom the first date covered by the active schedule period, so a
 *                                        consumer can tell a routine change that already applies from
 *                                        one that starts later
 */
public record PersonalBookWritingGoalResponse(
        Integer dailyTargetWordCount,
        List<DayOfWeek> plannedWritingDays,
        LocalDate plannedWritingDaysEffectiveFrom
) {
}
