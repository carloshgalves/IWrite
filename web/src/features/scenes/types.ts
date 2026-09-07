export type SceneStatus = "IDEA" | "PLANNED" | "DRAFT" | "WRITTEN" | "REVISED" | "FINAL";

export type SceneCharacterSummary = {
  id: string;
  name: string;
};

export type SceneLocationSummary = {
  id: string;
  name: string;
};

export type SceneItemSummary = {
  id: string;
  name: string;
};

export type Scene = {
  id: string;
  bookId: string;
  chapterId: string;
  title: string;
  summary: string | null;
  contentJson: string | null;
  contentText: string | null;
  status: SceneStatus;
  sortOrder: number;
  wordCount: number;
  contentRevision: number;
  /**
   * Effective authority over this scene's canonical text, resolved by the backend with the same rule
   * the save applies. It is not the book-scoped eligibility: a user may hold
   * EDIT_AUTHORED_CONTRIBUTION for the book and still have no authority over this scene.
   */
  canEditContent: boolean;
  goal: string | null;
  conflict: string | null;
  outcome: string | null;
  planningNotes: string | null;
  povCharacter: SceneCharacterSummary | null;
  mainLocation: SceneLocationSummary | null;
  participantCharacters: SceneCharacterSummary[];
  items: SceneItemSummary[];
  createdAt: string;
  updatedAt: string;
};

export type UpdateSceneRequest = {
  title?: string;
  summary?: string;
  status?: SceneStatus;
  sortOrder?: number;
};

export type UpdateSceneContentRequest = {
  contentJson?: string;
  contentText?: string;
  source?: SceneVersionSource;
  expectedContentRevision: number;
  operationId: string;
};

export type RestoreSceneVersionRequest = {
  expectedContentRevision: number;
  operationId: string;
};

export type SceneVersionSource = "AUTO_SAVE" | "MANUAL_SAVE" | "RESTORE_SAFETY" | "DELETE_SAFETY";

export type SceneVersionSummary = {
  id: string;
  sceneId: string | null;
  originalSceneId: string;
  sceneTitleSnapshot: string;
  wordCount: number;
  source: SceneVersionSource;
  createdAt: string;
  contentTextPreview: string;
};

export type SceneVersionDetail = SceneVersionSummary & {
  contentJson: string | null;
  contentText: string | null;
};

export type SceneVersionPage = {
  items: SceneVersionSummary[];
  page: number;
  size: number;
  hasNext: boolean;
};
