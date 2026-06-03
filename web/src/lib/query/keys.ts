export const queryKeys = {
  books: ["books"] as const,
  book: (bookId: string) => ["books", bookId] as const,
  characters: (bookId: string) => ["books", bookId, "characters"] as const,
  character: (characterId: string) => ["characters", characterId] as const,
  locations: (bookId: string) => ["books", bookId, "locations"] as const,
  location: (locationId: string) => ["locations", locationId] as const,
  items: (bookId: string) => ["books", bookId, "items"] as const,
  item: (itemId: string) => ["items", itemId] as const,
  outline: (bookId: string) => ["books", bookId, "outline"] as const,
  bookDashboard: (bookId: string) => ["books", bookId, "dashboard"] as const,
  bookSearch: (bookId: string, query: string, limit: number) => ["books", bookId, "search", query, limit] as const,
  scene: (sceneId: string) => ["scenes", sceneId] as const,
};
