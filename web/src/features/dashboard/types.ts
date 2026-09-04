import type { SceneStatus } from "@/features/scenes/types";
import type { BookCapability, DayOfWeek } from "@/features/books/types";

export type PlanningProgressResponse = {
  plannedScenesCount: number;
  totalScenes: number;
  plannedScenesPercent: number;
};

export type StatusCountResponse = {
  status: SceneStatus;
  scenesCount: number;
  wordCount: number;
  scenes: DashboardSceneSummaryResponse[];
};

export type DashboardSceneSummaryResponse = {
  sceneId: string;
  title: string;
  summary: string | null;
  status: SceneStatus;
  wordCount: number;
  chapterId: string;
  chapterTitle: string;
  sectionId: string | null;
  sectionTitle: string | null;
  povCharacterName: string | null;
  mainLocationName: string | null;
  participantNames: string[];
  itemNames: string[];
  goal: string | null;
  conflict: string | null;
  outcome: string | null;
  planningNotes: string | null;
};

export type PovStatsResponse = {
  characterId: string;
  name: string;
  scenesCount: number;
  wordCount: number;
};

export type NarrativeGapsResponse = {
  scenesWithoutPov: number;
  scenesWithoutGoal: number;
  scenesWithoutConflict: number;
  scenesWithoutOutcome: number;
  scenesWithoutMainLocation: number;
  scenesWithoutParticipants: number;
};

export type EntityUsageResponse = {
  id: string;
  name: string;
  scenesCount: number;
};

export type DailyWritingProgressResponse = {
  date: string;
  dailyTargetWordCount: number | null;
  startingManuscriptWordCount: number;
  endingManuscriptWordCount: number;
  productiveWordCountChange: number;
  manuscriptAdjustmentWordCount: number;
  progressPercent: number | null;
};

export type WritingConsistencyResponse = {
  currentStreakDays: number;
  bestStreakDays: number;
  writingDaysThisMonth: number;
  recentWindowDays: number;
  recentWritingDays: number;
  recentWritingDaysPercent: number;
  recentPlannedWritingDays: number;
  recentSuccessfulPlannedWritingDays: number;
  recentPlannedWritingDaysPercent: number;
};

export type WritingProgressDashboardResponse = {
  today: DailyWritingProgressResponse;
  recentDays: DailyWritingProgressResponse[];
  consistency: WritingConsistencyResponse;
};

export type WritingScheduleResponse = {
  plannedWritingDays: DayOfWeek[];
  plannedWritingDaysPerWeek: number;
  restDays: DayOfWeek[];
  todayPlannedWritingDay: boolean;
  currentScheduleEffectiveFrom: string;
};

/**
 * The signed-in user's own writing in one book: the routine and daily target they chose, and the
 * progress measured against them.
 *
 * `writingGoalRevision` is the goal state this projection was read at. Every save of the daily
 * target or the routine quotes it back, so a choice made against superseded state is refused instead
 * of silently replacing whatever another tab saved in the meantime.
 */
export type BookMyWritingResponse = {
  progress: WritingProgressDashboardResponse;
  schedule: WritingScheduleResponse;
  writingGoalRevision: number;
};

export type WritingProgressPeriodResponse = {
  value: string;
  startDate: string;
  endDate: string;
};

export type UserWritingSummaryResponse = {
  productiveWords: number;
  manuscriptAdjustments: number;
  writingDays: number;
  booksWrittenIn: number;
  currentGlobalWritingStreak: number;
  bestGlobalWritingStreak: number;
  writingDaysThisMonth: number;
};

export type UserDailyWritingResponse = {
  date: string;
  productiveWords: number;
  manuscriptAdjustments: number;
};

export type UserBookContributionResponse = {
  bookId: string;
  title: string;
  productiveWords: number;
  manuscriptAdjustments: number;
  writingDays: number;
};

export type UserDashboardResponse = {
  period: WritingProgressPeriodResponse;
  summary: UserWritingSummaryResponse;
  dailySeries: UserDailyWritingResponse[];
  bookContributions: UserBookContributionResponse[];
};

export type ContributorSummaryResponse = {
  userId: string;
  displayName: string;
};

export type ContributionSummaryResponse = {
  productiveWords: number;
  manuscriptAdjustments: number;
  writingDays: number;
  contributorsCount: number;
};

export type ContributionDailyWritingResponse = {
  date: string;
  productiveWords: number;
  manuscriptAdjustments: number;
};

export type BookContributionDashboardResponse = {
  period: WritingProgressPeriodResponse;
  scope: "ALL_CONTRIBUTORS" | "SINGLE_CONTRIBUTOR";
  selectedContributor: ContributorSummaryResponse | null;
  availableContributors: ContributorSummaryResponse[];
  summary: ContributionSummaryResponse;
  dailySeries: ContributionDailyWritingResponse[];
};

/**
 * `dailyTargetWordCount` is the signed-in user's own Personal Book Writing Goal, not a book-wide
 * setting; `targetWordCount` is the shared book-wide target. `capabilities` says which controls this
 * user may attempt — the server authorizes every request again, so hiding a control is presentation,
 * never the authorization boundary.
 *
 * `myWriting` is `null` for a role that may not manage a personal writing goal at all: there is no
 * personal projection to render, not even a routine or a per-day target snapshot.
 */
export type BookDashboardResponse = {
  bookId: string;
  title: string;
  totalWordCount: number;
  targetWordCount: number | null;
  dailyTargetWordCount: number | null;
  remainingWordCount: number | null;
  wordCountProgressPercent: number | null;
  exceededTargetWordCount: number | null;
  totalSections: number;
  totalChapters: number;
  totalScenes: number;
  myWriting: BookMyWritingResponse | null;
  planningProgress: PlanningProgressResponse;
  scenesByStatus: StatusCountResponse[];
  povStats: PovStatsResponse[];
  narrativeGaps: NarrativeGapsResponse;
  mostUsedCharacters: EntityUsageResponse[];
  mostUsedLocations: EntityUsageResponse[];
  mostUsedItems: EntityUsageResponse[];
  capabilities: BookCapability[];
  contextualCapabilities: BookCapability[];
};
