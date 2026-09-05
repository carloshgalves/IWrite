package com.iwrite.writingprogress.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * One User's whole Personal Book Writing Goal in one Book, as read at a single instant (#206).
 *
 * <p>The target, the revision and the planned writing days travel together because they answer a
 * single question — what this goal is, and what state a save of it must be decided against. The
 * revision versions all of it, so a projection that shows the routine beside a revision read
 * separately can pair a routine with a revision the routine has already superseded, and the next
 * legitimate save is then refused over a change nobody made.
 *
 * @param dailyTargetWordCount the chosen target, or {@code null} when no target was chosen — an
 *                             intentional absence, never a target of zero
 * @param revision             the state this snapshot was read at, covering both halves
 * @param plannedWritingDays   the active routine, in week order
 * @param plannedWritingDaysEffectiveFrom the first date the active routine period covers
 */
public record PersonalBookWritingGoalSnapshot(
        Integer dailyTargetWordCount,
        int revision,
        List<DayOfWeek> plannedWritingDays,
        LocalDate plannedWritingDaysEffectiveFrom
) {
}
