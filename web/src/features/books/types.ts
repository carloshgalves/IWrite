export type BookStatus = "PLANNING" | "WRITING" | "REVISING" | "FINISHED" | "ARCHIVED";
export type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

/** How the backend resolved the user's relationship to the book. */
export type BookRelationship = "OWNER" | "COLLABORATOR";

/**
 * Explicit, revocable role of a collaboration. `LEGACY_COLLABORATOR` is not a product role: it
 * preserves the surface of relationships created before roles existed and is never offered.
 */
export type BookRole = "AUTHOR" | "EDITOR" | "READER" | "LEGACY_COLLABORATOR";

/**
 * Book-scoped operations the backend derives for the current user. They exist so the UI shows only
 * the actions it may attempt; the server authorizes every request again, so a hidden control is
 * never the authorization boundary.
 */
export type BookCapability =
  | "READ_MANUSCRIPT"
  | "EDIT_AUTHORED_CONTRIBUTION"
  | "MUTATE_MANUSCRIPT_STRUCTURE"
  | "READ_CANONICAL_PLANNING"
  | "EDIT_CANONICAL_PLANNING"
  | "READ_NOTEBOOK"
  | "EDIT_NOTEBOOK"
  | "VIEW_BOOK_CONTRIBUTOR_PROGRESS"
  | "MANAGE_OWN_PERSONAL_WRITING_GOAL"
  | "EDIT_BOOK_SETTINGS"
  | "CREATE_EDITORIAL_COMMENT"
  | "CREATE_EDITORIAL_SUGGESTION"
  | "RESOLVE_EDITORIAL_SUGGESTION"
  | "READ_SCENE_VERSIONS"
  | "RESTORE_SCENE_VERSION"
  | "EXPORT_MANUSCRIPT"
  | "EXPORT_NOTEBOOK"
  | "REQUEST_SCENE_AI_ANALYSIS"
  | "READ_READER_REVIEW_RELEASE"
  | "MANAGE_COLLABORATORS"
  | "DELETE_BOOK";

export type Book = {
  id: string;
  title: string;
  subtitle: string | null;
  description: string | null;
  status: BookStatus;
  targetWordCount: number | null;
  relationship: BookRelationship;
  role: BookRole | null;
  capabilities: BookCapability[];
  contextualCapabilities: BookCapability[];
  createdAt: string;
  updatedAt: string;
};

/**
 * Shared book settings only. A daily word target and planned writing days belong to one user inside
 * the book, so they travel through the writing-goal contract instead; the backend refuses them here.
 */
export type CreateBookRequest = {
  title: string;
  subtitle?: string;
  description?: string;
  targetWordCount?: number | null;
};

export type UpdateBookRequest = {
  title?: string;
  subtitle?: string;
  description?: string;
  status?: BookStatus;
  targetWordCount?: number | null;
};
