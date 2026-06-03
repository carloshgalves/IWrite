import type { BookSearchResult } from "@/features/search/types";
import { apiRequest } from "@/lib/api/client";

export function searchBook(bookId: string, query: string, limit = 30) {
  const params = new URLSearchParams({
    q: query,
    limit: String(limit),
  });

  return apiRequest<BookSearchResult[]>(`/api/books/${bookId}/search?${params.toString()}`);
}
