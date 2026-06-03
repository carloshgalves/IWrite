export type BookSearchResultType = "SCENE" | "NOTEBOOK_NOTE" | "CHARACTER" | "LOCATION" | "ITEM";

export type BookSearchResult = {
  id: string;
  type: BookSearchResultType;
  title: string;
  snippet: string | null;
  metadata: string | null;
};
