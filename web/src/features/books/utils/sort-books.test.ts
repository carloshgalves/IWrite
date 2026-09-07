import { describe, expect, test } from "vitest";
import { sortBooksForLibrary } from "@/features/books/utils/sort-books";
import type { Book } from "@/features/books/types";

function book(overrides: Partial<Book> & Pick<Book, "id" | "status">): Book {
  return {
    id: overrides.id,
    title: overrides.id,
    subtitle: null,
    description: null,
    status: overrides.status,
    targetWordCount: null,
    relationship: "OWNER",
    role: null,
    capabilities: [],
    contextualCapabilities: [],
    createdAt: overrides.createdAt ?? "2026-01-01T00:00:00.000Z",
    updatedAt: overrides.updatedAt ?? "2026-01-01T00:00:00.000Z",
  };
}

describe("sortBooksForLibrary", () => {
  test("prioriza livros ativos antes de finalizados e arquivados", () => {
    const sorted = sortBooksForLibrary([
      book({ id: "finished", status: "FINISHED" }),
      book({ id: "planning", status: "PLANNING" }),
      book({ id: "archived", status: "ARCHIVED" }),
      book({ id: "writing", status: "WRITING" }),
      book({ id: "revising", status: "REVISING" }),
    ]);

    expect(sorted.map((item) => item.id)).toEqual(["writing", "revising", "planning", "finished", "archived"]);
  });

  test("ordena por updatedAt mais recente dentro do mesmo status", () => {
    const sorted = sortBooksForLibrary([
      book({ id: "old", status: "WRITING", updatedAt: "2026-01-01T00:00:00.000Z" }),
      book({ id: "new", status: "WRITING", updatedAt: "2026-02-01T00:00:00.000Z" }),
    ]);

    expect(sorted.map((item) => item.id)).toEqual(["new", "old"]);
  });

  test("usa createdAt quando updatedAt nao esta disponivel", () => {
    const books = [
      { id: "old", status: "REVISING" as const, createdAt: "2026-01-01T00:00:00.000Z" },
      { id: "new", status: "REVISING" as const, createdAt: "2026-02-01T00:00:00.000Z" },
    ];

    expect(sortBooksForLibrary(books).map((item) => item.id)).toEqual(["new", "old"]);
  });

  test("mantem ordem original dentro do grupo sem datas validas", () => {
    const books = [
      { id: "first", status: "PLANNING" as const },
      { id: "second", status: "PLANNING" as const },
    ];

    expect(sortBooksForLibrary(books).map((item) => item.id)).toEqual(["first", "second"]);
  });
});
