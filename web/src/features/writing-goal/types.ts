import type { DayOfWeek } from "@/features/books/types";

/**
 * The signed-in user's own writing goal inside one book.
 *
 * `dailyTargetWordCount` is `null` when no target was chosen. That is an intentional absence, not a
 * target of zero, and it is never another collaborator's goal.
 */
export type PersonalBookWritingGoal = {
  dailyTargetWordCount: number | null;
  plannedWritingDays: DayOfWeek[];
  plannedWritingDaysEffectiveFrom: string;
  revision: number;
};

/**
 * Partial update: an omitted field is left alone, an explicit `null` target clears it.
 *
 * `expectedRevision` is required and names the goal state this save was decided against. Without it
 * two tabs could both save and the later one would silently discard the earlier choice; the server
 * answers `409` when the quoted revision has been superseded.
 */
export type UpdatePersonalBookWritingGoalRequest = {
  expectedRevision: number;
  dailyTargetWordCount?: number | null;
  plannedWritingDays?: DayOfWeek[];
};
