import { apiRequest } from "@/lib/api/client";
import type { Book, CreateBookRequest, UpdateBookRequest } from "@/features/books/types";

export function listBooks() {
  return apiRequest<Book[]>("/api/books");
}

/**
 * Reads one book with the effective access the backend derived for the current user. Surfaces that
 * present book-scoped actions read the capabilities from here instead of inferring them from a role.
 */
export function getBook(bookId: string) {
  return apiRequest<Book>(`/api/books/${bookId}`);
}

export function createBook(request: CreateBookRequest) {
  return apiRequest<Book>("/api/books", {
    method: "POST",
    body: request,
  });
}

export function updateBook(bookId: string, request: UpdateBookRequest) {
  return apiRequest<Book>(`/api/books/${bookId}`, {
    method: "PATCH",
    body: request,
  });
}

export function deleteBook(bookId: string) {
  return apiRequest<void>(`/api/books/${bookId}`, {
    method: "DELETE",
  });
}
