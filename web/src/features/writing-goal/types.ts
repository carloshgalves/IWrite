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
};

/** Partial update: an omitted field is left alone, an explicit `null` target clears it. */
export type UpdatePersonalBookWritingGoalRequest = {
  dailyTargetWordCount?: number | null;
  plannedWritingDays?: DayOfWeek[];
};
