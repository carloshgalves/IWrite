package com.iwrite.writingprogress.service;

/**
 * One User's Personal Book Writing Goal in one Book, as read at a single instant (#206).
 *
 * <p>The target and the revision travel together because a projection that shows both is answering a
 * single question — what this goal is, and what state a save of it must be decided against. Carrying
 * them as one value keeps a caller from assembling them out of two reads, which is where they could
 * come from different states of the goal.
 *
 * @param dailyTargetWordCount the chosen target, or {@code null} when no target was chosen — an
 *                             intentional absence, never a target of zero
 * @param revision             the state this snapshot was read at
 */
public record PersonalBookWritingGoalSnapshot(Integer dailyTargetWordCount, int revision) {

    /** The goal of a User who never saved one in this Book: no target, at the unsaved revision. */
    static PersonalBookWritingGoalSnapshot unsaved() {
        return new PersonalBookWritingGoalSnapshot(null, PersonalBookWritingGoalService.UNSAVED_GOAL_REVISION);
    }
}
