"use client";

import Link from "next/link";
import { type FormEvent, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { deleteBook, updateBook } from "@/features/books/api/books-api";
import type { Book, BookStatus } from "@/features/books/types";
import { queryKeys } from "@/lib/query/keys";

const statusLabels: Record<BookStatus, string> = {
  PLANNING: "Planejamento",
  WRITING: "Escrita",
  REVISING: "Revisão",
  FINISHED: "Finalizado",
  ARCHIVED: "Arquivado",
};

const bookStatuses = Object.keys(statusLabels) as BookStatus[];

export function BookCard({ book }: { book: Book }) {
  const queryClient = useQueryClient();
  const canEditSettings = book.capabilities.includes("EDIT_BOOK_SETTINGS");
  const canDeleteBook = book.capabilities.includes("DELETE_BOOK");
  const [isEditing, setIsEditing] = useState(false);
  const [title, setTitle] = useState(book.title);
  const [subtitle, setSubtitle] = useState(book.subtitle ?? "");
  const [description, setDescription] = useState(book.description ?? "");
  const [status, setStatus] = useState<BookStatus>(book.status);

  const updateMutation = useMutation({
    mutationFn: () =>
      updateBook(book.id, {
        title: title.trim(),
        subtitle: subtitle.trim(),
        description: description.trim(),
        status,
      }),
    onSuccess: () => {
      setIsEditing(false);
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
      void queryClient.invalidateQueries({ queryKey: queryKeys.book(book.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(book.id) });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteBook(book.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
      void queryClient.removeQueries({ queryKey: queryKeys.outline(book.id) });
    },
  });

  function handleEditSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!title.trim()) {
      return;
    }

    updateMutation.mutate();
  }

  function handleCancelEdit() {
    setTitle(book.title);
    setSubtitle(book.subtitle ?? "");
    setDescription(book.description ?? "");
    setStatus(book.status);
    setIsEditing(false);
    updateMutation.reset();
  }

  function handleDelete() {
    const confirmed = window.confirm(
      `Excluir o livro "${book.title}"? Esta ação remove suas seções, capítulos e cenas.`
    );

    if (confirmed) {
      deleteMutation.mutate();
    }
  }

  if (isEditing) {
    return (
      <form
        onSubmit={handleEditSubmit}
        className="grid min-h-48 gap-3 rounded-lg border border-zinc-200 bg-white p-5 shadow-sm shadow-zinc-200/60"
      >
        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Título</span>
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-800"
          />
        </label>
        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Subtítulo</span>
          <input
            value={subtitle}
            onChange={(event) => setSubtitle(event.target.value)}
            className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-800"
          />
        </label>
        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Status</span>
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value as BookStatus)}
            className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-800"
          >
            {bookStatuses.map((bookStatus) => (
              <option key={bookStatus} value={bookStatus}>
                {statusLabels[bookStatus]}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Descrição</span>
          <textarea
            value={description}
            rows={3}
            onChange={(event) => setDescription(event.target.value)}
            className="rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-800"
          />
        </label>

        {updateMutation.isError ? (
          <FeedbackMessage variant="error">Não foi possível atualizar o livro.</FeedbackMessage>
        ) : null}

        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={updateMutation.isPending || !title.trim()}>
            {updateMutation.isPending ? "Salvando..." : "Salvar"}
          </Button>
          <Button type="button" variant="secondary" onClick={handleCancelEdit}>
            Cancelar
          </Button>
        </div>
      </form>
    );
  }

  return (
    <article className="group grid min-h-48 content-between rounded-lg border border-zinc-200 bg-white p-5 shadow-sm shadow-zinc-200/60 transition hover:-translate-y-0.5 hover:border-zinc-300 hover:shadow-md">
      <div className="grid gap-4">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h2 className="line-clamp-2 text-xl font-semibold leading-7 text-zinc-950">{book.title}</h2>
            {book.subtitle ? <p className="mt-1 line-clamp-1 text-sm text-zinc-600">{book.subtitle}</p> : null}
          </div>
          <Badge className="shrink-0">{statusLabels[book.status]}</Badge>
        </div>

        {book.description ? (
          <p className="line-clamp-3 text-sm leading-6 text-zinc-600">{book.description}</p>
        ) : (
          <p className="text-sm leading-6 text-zinc-500">Sem descrição ainda.</p>
        )}
      </div>

      <div className="mt-6 grid gap-3 border-t border-zinc-100 pt-4 text-sm">
        <Link href={`/books/${book.id}`} className="font-medium text-zinc-900 transition hover:text-zinc-600">
          Abrir workspace
        </Link>
        <div className="flex flex-wrap gap-2">
          {canEditSettings ? (
            <Button type="button" variant="secondary" size="sm" onClick={() => setIsEditing(true)}>
              Editar
            </Button>
          ) : null}
          {canDeleteBook ? (
            <Button type="button" variant="ghost" size="sm" onClick={handleDelete} disabled={deleteMutation.isPending}>
              {deleteMutation.isPending ? "Excluindo..." : "Excluir"}
            </Button>
          ) : null}
        </div>
        {deleteMutation.isError ? (
          <FeedbackMessage variant="error">Não foi possível excluir o livro.</FeedbackMessage>
        ) : null}
      </div>
    </article>
  );
}
