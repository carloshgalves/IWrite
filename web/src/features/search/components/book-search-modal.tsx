"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { searchBook } from "@/features/search/api/book-search-api";
import type { BookSearchResult, BookSearchResultType } from "@/features/search/types";
import { queryKeys } from "@/lib/query/keys";

type BookSearchModalProps = {
  bookId: string;
  onClose: () => void;
  onSelectResult: (result: BookSearchResult) => void;
};

const MIN_QUERY_LENGTH = 2;
const SEARCH_DEBOUNCE_MS = 250;
const GROUP_LABELS: Record<BookSearchResultType, string> = {
  SCENE: "Cenas",
  NOTEBOOK_NOTE: "Caderno",
  CHARACTER: "Personagens",
  LOCATION: "Localizações",
  ITEM: "Itens",
};
const GROUP_ORDER: BookSearchResultType[] = ["SCENE", "NOTEBOOK_NOTE", "CHARACTER", "LOCATION", "ITEM"];

export function BookSearchModal({ bookId, onClose, onSelectResult }: BookSearchModalProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const trimmedDebouncedQuery = debouncedQuery.trim();
  const canSearch = trimmedDebouncedQuery.length >= MIN_QUERY_LENGTH;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedQuery(query);
    }, SEARCH_DEBOUNCE_MS);

    return () => window.clearTimeout(timeoutId);
  }, [query]);

  const searchQuery = useQuery({
    queryKey: queryKeys.bookSearch(bookId, trimmedDebouncedQuery, 30),
    queryFn: () => searchBook(bookId, trimmedDebouncedQuery, 30),
    enabled: canSearch,
  });

  const groupedResults = useMemo(() => groupResults(searchQuery.data ?? []), [searchQuery.data]);
  const hasResults = groupedResults.some((group) => group.results.length > 0);

  return (
    <div className="fixed inset-0 z-50 grid place-items-start bg-zinc-950/40 px-4 py-12" role="dialog" aria-modal="true" aria-label="Busca global do livro">
      <div className="mx-auto grid w-full max-w-3xl gap-3 rounded-md border border-zinc-200 bg-white p-4 shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-zinc-200 pb-3">
          <div>
            <h2 className="text-base font-semibold text-zinc-950">Buscar no livro</h2>
            <p className="mt-1 text-sm text-zinc-500">Encontre cenas, notas e elementos narrativos deste livro.</p>
          </div>
          <Button type="button" variant="ghost" onClick={onClose}>
            Fechar
          </Button>
        </div>

        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Termo de busca</span>
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Digite pelo menos 2 caracteres"
            className="min-h-11 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200"
          />
        </label>

        {!canSearch ? (
          <p className="rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-500">
            Digite pelo menos 2 caracteres para buscar.
          </p>
        ) : null}

        {searchQuery.isLoading ? <LoadingState label="Buscando no livro..." /> : null}
        {searchQuery.isError ? <ErrorState message="Não foi possível buscar no livro agora." /> : null}

        {canSearch && !searchQuery.isLoading && !searchQuery.isError && !hasResults ? (
          <p className="rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-500">
            Nenhum resultado encontrado.
          </p>
        ) : null}

        {hasResults ? (
          <div className="grid max-h-[min(620px,70vh)] gap-4 overflow-y-auto pr-1">
            {groupedResults.map((group) =>
              group.results.length > 0 ? (
                <section key={group.type} className="grid gap-2">
                  <h3 className="text-xs font-semibold uppercase text-zinc-500">{GROUP_LABELS[group.type]}</h3>
                  <div className="grid gap-2">
                    {group.results.map((result) => (
                      <button
                        key={`${result.type}:${result.id}`}
                        type="button"
                        className="grid gap-1 rounded-md border border-zinc-200 bg-white p-3 text-left shadow-sm transition hover:border-zinc-300 hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1"
                        onClick={() => onSelectResult(result)}
                      >
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-semibold text-zinc-950">{result.title}</span>
                          {result.metadata ? <span className="text-xs text-zinc-500">{result.metadata}</span> : null}
                        </span>
                        {result.snippet ? <span className="line-clamp-2 text-xs leading-5 text-zinc-600">{result.snippet}</span> : null}
                      </button>
                    ))}
                  </div>
                </section>
              ) : null
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function groupResults(results: BookSearchResult[]) {
  return GROUP_ORDER.map((type) => ({
    type,
    results: results.filter((result) => result.type === type),
  }));
}
