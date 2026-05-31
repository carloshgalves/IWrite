import type { CharacterResponse } from "@/features/characters/types";
import type { BookDashboardResponse, DashboardSceneSummaryResponse } from "@/features/dashboard/types";
import type { ItemResponse } from "@/features/items/types";
import type { LocationResponse } from "@/features/locations/types";
import type { Scene } from "@/features/scenes/types";

const now = "2026-05-14T12:00:00Z";

export const characterAda: CharacterResponse = {
  id: "character-ada",
  bookId: "book-1",
  name: "Ada",
  nickname: "A",
  age: null,
  sex: null,
  narrativeFunction: "Protagonista",
  goal: "Encontrar a chave",
  conflict: null,
  arc: null,
  physicalDescription: null,
  personality: null,
  biography: null,
  notes: null,
  createdAt: now,
  updatedAt: now,
};

export const characterBruno: CharacterResponse = {
  ...characterAda,
  id: "character-bruno",
  name: "Bruno",
  nickname: null,
  narrativeFunction: "Aliado",
};

export const locationLibrary: LocationResponse = {
  id: "location-library",
  bookId: "book-1",
  name: "Biblioteca",
  type: "Interior",
  description: "Sala antiga com arquivos.",
  historyContext: null,
  narrativeImportance: null,
  notes: null,
  createdAt: now,
  updatedAt: now,
};

export const itemKey: ItemResponse = {
  id: "item-key",
  bookId: "book-1",
  name: "Chave de prata",
  type: "Artefato",
  description: "Abre a sala proibida.",
  origin: null,
  currentOwnerCharacterId: characterAda.id,
  currentOwnerCharacter: {
    id: characterAda.id,
    name: characterAda.name,
    nickname: characterAda.nickname,
  },
  narrativeImportance: null,
  notes: null,
  createdAt: now,
  updatedAt: now,
};

export const itemMap: ItemResponse = {
  ...itemKey,
  id: "item-map",
  name: "Mapa rasgado",
  currentOwnerCharacterId: null,
  currentOwnerCharacter: null,
};

export const dashboardScene: DashboardSceneSummaryResponse = {
  sceneId: "scene-1",
  title: "A chave aparece",
  summary: "Ada encontra um sinal.",
  status: "DRAFT",
  wordCount: 1200,
  chapterId: "chapter-1",
  chapterTitle: "Capitulo 1",
  sectionId: "section-1",
  sectionTitle: "Parte 1",
  povCharacterName: "Ada",
  mainLocationName: "Biblioteca",
  participantNames: ["Ada"],
  itemNames: ["Chave de prata"],
  goal: "Encontrar a chave",
  conflict: "A porta esta trancada",
  outcome: "Ada decide investigar",
  planningNotes: "Reforcar pista visual.",
};

export const gapScene: DashboardSceneSummaryResponse = {
  ...dashboardScene,
  sceneId: "scene-gap",
  title: "Cena sem objetivo",
  status: "IDEA",
  wordCount: 0,
  povCharacterName: null,
  mainLocationName: null,
  participantNames: [],
  itemNames: [],
  goal: null,
  conflict: null,
  outcome: null,
  planningNotes: null,
};

export const dashboardWithScenes: BookDashboardResponse = {
  bookId: "book-1",
  title: "Livro de teste",
  totalWordCount: 1200,
  targetWordCount: 1000,
  dailyTargetWordCount: 500,
  remainingWordCount: 0,
  wordCountProgressPercent: 120,
  exceededTargetWordCount: 200,
  totalSections: 1,
  totalChapters: 1,
  totalScenes: 2,
  writingProgress: {
    today: {
      date: "2026-05-14",
      dailyTargetWordCount: 500,
      startWordCount: 900,
      endWordCount: 1200,
      netWordCountChange: 300,
      progressPercent: 60,
    },
    recentDays: [
      {
        date: "2026-05-14",
        dailyTargetWordCount: 500,
        startWordCount: 900,
        endWordCount: 1200,
        netWordCountChange: 300,
        progressPercent: 60,
      },
      {
        date: "2026-05-13",
        dailyTargetWordCount: 500,
        startWordCount: 1000,
        endWordCount: 900,
        netWordCountChange: -100,
        progressPercent: -20,
      },
    ],
    consistency: {
      currentStreakDays: 3,
      bestStreakDays: 12,
      writingDaysThisMonth: 9,
      recentWindowDays: 7,
      recentWritingDays: 5,
      recentWritingDaysPercent: 71.428,
    },
  },
  planningProgress: {
    plannedScenesCount: 1,
    totalScenes: 2,
    plannedScenesPercent: 50,
  },
  scenesByStatus: [
    { status: "IDEA", scenesCount: 1, wordCount: 0, scenes: [gapScene] },
    { status: "PLANNED", scenesCount: 0, wordCount: 0, scenes: [] },
    { status: "DRAFT", scenesCount: 1, wordCount: 1200, scenes: [dashboardScene] },
    { status: "WRITTEN", scenesCount: 0, wordCount: 0, scenes: [] },
    { status: "REVISED", scenesCount: 0, wordCount: 0, scenes: [] },
    { status: "FINAL", scenesCount: 0, wordCount: 0, scenes: [] },
  ],
  povStats: [
    { characterId: characterAda.id, name: characterAda.name, scenesCount: 1, wordCount: 1200 },
  ],
  narrativeGaps: {
    scenesWithoutPov: 1,
    scenesWithoutGoal: 1,
    scenesWithoutConflict: 1,
    scenesWithoutOutcome: 1,
    scenesWithoutMainLocation: 1,
    scenesWithoutParticipants: 1,
  },
  mostUsedCharacters: [
    { id: characterAda.id, name: characterAda.name, scenesCount: 1 },
  ],
  mostUsedLocations: [
    { id: locationLibrary.id, name: locationLibrary.name, scenesCount: 1 },
  ],
  mostUsedItems: [
    { id: itemKey.id, name: itemKey.name, scenesCount: 1 },
  ],
};

export const emptyDashboard: BookDashboardResponse = {
  ...dashboardWithScenes,
  totalWordCount: 0,
  targetWordCount: null,
  dailyTargetWordCount: null,
  remainingWordCount: null,
  wordCountProgressPercent: null,
  exceededTargetWordCount: null,
  totalSections: 0,
  totalChapters: 0,
  totalScenes: 0,
  planningProgress: {
    plannedScenesCount: 0,
    totalScenes: 0,
    plannedScenesPercent: 0,
  },
  writingProgress: {
    today: {
      date: "2026-05-14",
      dailyTargetWordCount: null,
      startWordCount: 0,
      endWordCount: 0,
      netWordCountChange: 0,
      progressPercent: null,
    },
    recentDays: [],
    consistency: {
      currentStreakDays: 0,
      bestStreakDays: 0,
      writingDaysThisMonth: 0,
      recentWindowDays: 7,
      recentWritingDays: 0,
      recentWritingDaysPercent: 0,
    },
  },
  scenesByStatus: dashboardWithScenes.scenesByStatus.map((status) => ({
    ...status,
    scenesCount: 0,
    wordCount: 0,
    scenes: [],
  })),
  povStats: [],
  narrativeGaps: {
    scenesWithoutPov: 0,
    scenesWithoutGoal: 0,
    scenesWithoutConflict: 0,
    scenesWithoutOutcome: 0,
    scenesWithoutMainLocation: 0,
    scenesWithoutParticipants: 0,
  },
  mostUsedCharacters: [],
  mostUsedLocations: [],
  mostUsedItems: [],
};

export const sceneForPlanning: Scene = {
  id: "scene-1",
  bookId: "book-1",
  chapterId: "chapter-1",
  title: "A chave aparece",
  summary: null,
  contentJson: "{}",
  contentText: "Texto da cena",
  status: "DRAFT",
  sortOrder: 0,
  wordCount: 3,
  goal: null,
  conflict: null,
  outcome: null,
  planningNotes: null,
  povCharacter: null,
  mainLocation: null,
  participantCharacters: [],
  items: [],
  createdAt: now,
  updatedAt: now,
};
