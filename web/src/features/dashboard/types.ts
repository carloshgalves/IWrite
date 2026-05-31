import type { SceneStatus } from "@/features/scenes/types";

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
  startWordCount: number;
  endWordCount: number;
  netWordCountChange: number;
  progressPercent: number | null;
};

export type WritingConsistencyResponse = {
  currentStreakDays: number;
  bestStreakDays: number;
  writingDaysThisMonth: number;
  recentWindowDays: number;
  recentWritingDays: number;
  recentWritingDaysPercent: number;
};

export type WritingProgressDashboardResponse = {
  today: DailyWritingProgressResponse;
  recentDays: DailyWritingProgressResponse[];
  consistency: WritingConsistencyResponse;
};

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
  writingProgress: WritingProgressDashboardResponse;
  planningProgress: PlanningProgressResponse;
  scenesByStatus: StatusCountResponse[];
  povStats: PovStatsResponse[];
  narrativeGaps: NarrativeGapsResponse;
  mostUsedCharacters: EntityUsageResponse[];
  mostUsedLocations: EntityUsageResponse[];
  mostUsedItems: EntityUsageResponse[];
};
