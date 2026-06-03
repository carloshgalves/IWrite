"use client";

import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { TextAreaField, TextField } from "@/components/ui/text-field";
import {
  useCreateNotebookCategory,
  useCreateNotebookNote,
  useDeleteNotebookCategory,
  useDeleteNotebookNote,
  useNotebookCategories,
  useNotebookNotes,
  useUpdateNotebookCategory,
  useUpdateNotebookNote,
} from "@/features/notebook/api/notebook-hooks";
import type {
  NotebookCategory,
  NotebookNote,
  NotebookNoteRequest,
  NotebookNoteStatus,
} from "@/features/notebook/types";
import { ApiError } from "@/lib/api/client";

type NotebookPanelProps = {
  bookId: string;
  openNoteIntent?: NotebookNoteOpenIntent | null;
};

type DetailMode = "empty" | "create" | "edit";
type CategoryFilter = "all" | "uncategorized" | string;
type StatusFilter = "all" | NotebookNoteStatus;

type NoteFormState = {
  title: string;
  content: string;
  categoryId: string;
  status: NotebookNoteStatus;
};

export type NotebookNoteOpenIntent = {
  id: string;
  requestId: number;
};

const emptyForm: NoteFormState = {
  title: "",
  content: "",
  categoryId: "",
  status: "OPEN",
};

const dateFormatter = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

export function NotebookPanel({ bookId, openNoteIntent = null }: NotebookPanelProps) {
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>("all");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [searchTerm, setSearchTerm] = useState("");
  const [isCategoryManagerOpen, setIsCategoryManagerOpen] = useState(false);
  const selectedCategoryId = categoryFilter === "all" || categoryFilter === "uncategorized" ? null : categoryFilter;
  const categoriesQuery = useNotebookCategories(bookId);
  const notesQuery = useNotebookNotes(bookId, selectedCategoryId);
  const createCategoryMutation = useCreateNotebookCategory(bookId);
  const updateCategoryMutation = useUpdateNotebookCategory(bookId);
  const deleteCategoryMutation = useDeleteNotebookCategory(bookId);
  const createMutation = useCreateNotebookNote(bookId);
  const updateMutation = useUpdateNotebookNote(bookId);
  const deleteMutation = useDeleteNotebookNote(bookId);
  const [selectedNote, setSelectedNote] = useState<NotebookNote | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("empty");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const consumedOpenRequestIdRef = useRef<number | null>(null);
  const orderedCategories = useMemo(() => orderNotebookCategories(categoriesQuery.data ?? []), [categoriesQuery.data]);

  const notes = useMemo(() => {
    const values = notesQuery.data ?? [];
    const filteredValues = values.filter((note) => noteMatchesFilters(note, categoryFilter, statusFilter, searchTerm));

    if (!selectedNote || !noteMatchesFilters(selectedNote, categoryFilter, statusFilter, searchTerm)) {
      return filteredValues;
    }

    if (filteredValues.some((note) => note.id === selectedNote.id)) {
      return filteredValues;
    }

    return [selectedNote, ...filteredValues];
  }, [notesQuery.data, categoryFilter, statusFilter, searchTerm, selectedNote]);

  const activeMutation = detailMode === "edit" ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getNotebookErrorMessage(activeMutation.error), [activeMutation.error]);
  const deleteErrorMessage = useMemo(() => getNotebookErrorMessage(deleteMutation.error), [deleteMutation.error]);
  const categoryErrorMessage = useMemo(
    () =>
      getNotebookErrorMessage(
        createCategoryMutation.error ?? updateCategoryMutation.error ?? deleteCategoryMutation.error
      ),
    [createCategoryMutation.error, deleteCategoryMutation.error, updateCategoryMutation.error]
  );

  useEffect(() => {
    if (!openNoteIntent || consumedOpenRequestIdRef.current === openNoteIntent.requestId) {
      return;
    }

    if (categoryFilter !== "all" || statusFilter !== "all" || searchTerm) {
      setCategoryFilter("all");
      setStatusFilter("all");
      setSearchTerm("");
      return;
    }

    const note = notesQuery.data?.find((value) => value.id === openNoteIntent.id);
    if (!note) {
      return;
    }

    consumedOpenRequestIdRef.current = openNoteIntent.requestId;
    startEdit(note);
  }, [categoryFilter, notesQuery.data, openNoteIntent, searchTerm, statusFilter]);

  function resetMutations() {
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
    createCategoryMutation.reset();
    updateCategoryMutation.reset();
    deleteCategoryMutation.reset();
  }

  function startCreate() {
    setSelectedNote(null);
    setDetailMode("create");
    setSuccessMessage(null);
    resetMutations();
  }

  function startEdit(note: NotebookNote) {
    setSelectedNote(note);
    setDetailMode("edit");
    setSuccessMessage(null);
    resetMutations();
  }

  function clearDetail() {
    setSelectedNote(null);
    setDetailMode("empty");
    setSuccessMessage(null);
    resetMutations();
  }

  function handleSubmit(payload: NotebookNoteRequest) {
    setSuccessMessage(null);

    if (detailMode === "edit" && selectedNote) {
      updateMutation.mutate(
        { noteId: selectedNote.id, payload },
        {
          onSuccess: (note) => {
            setSelectedNote(note);
            setSuccessMessage("Nota atualizada com sucesso.");
          },
        }
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (note) => {
        setSearchTerm("");
        setStatusFilter(note.status);
        setCategoryFilter(note.categoryId ?? "uncategorized");
        setSelectedNote(note);
        setDetailMode("edit");
        setSuccessMessage("Nota criada com sucesso.");
      },
    });
  }

  function handleDeleteNote(note: NotebookNote) {
    const confirmed = window.confirm(`Excluir a nota "${note.title}"? Esta ação não pode ser desfeita.`);
    if (!confirmed) {
      return;
    }

    setSuccessMessage(null);
    resetMutations();

    deleteMutation.mutate(note.id, {
      onSuccess: () => {
        if (selectedNote?.id === note.id) {
          setSelectedNote(null);
          setDetailMode("empty");
        }
        setSuccessMessage("Nota excluída com sucesso.");
      },
    });
  }

  function handleCreateCategory(name: string) {
    createCategoryMutation.mutate(
      { name },
      {
        onSuccess: () => {
          setSuccessMessage("Categoria criada com sucesso.");
        },
      }
    );
  }

  function handleRenameCategory(category: NotebookCategory, name: string) {
    updateCategoryMutation.mutate(
      { categoryId: category.id, payload: { name } },
      {
        onSuccess: () => {
          setSuccessMessage("Categoria atualizada com sucesso.");
        },
      }
    );
  }

  function handleDeleteCategory(category: NotebookCategory) {
    const confirmed = window.confirm(
      `Excluir a categoria "${category.name}"? As notas desta categoria continuarão no caderno sem categoria.`
    );
    if (!confirmed) {
      return;
    }

    deleteCategoryMutation.mutate(category.id, {
      onSuccess: () => {
        if (categoryFilter === category.id) {
          setCategoryFilter("uncategorized");
        }
        setSelectedNote((current) =>
          current?.categoryId === category.id ? { ...current, categoryId: null, category: null } : current
        );
        setSuccessMessage("Categoria excluída com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-7xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-zinc-950">Caderno</h1>
            <p className="mt-1 text-sm text-zinc-500">Guarde notas gerais deste livro.</p>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Nova nota
          </Button>
        </header>

        {categoriesQuery.isLoading || notesQuery.isLoading ? <LoadingState label="Carregando caderno..." /> : null}

        {categoriesQuery.isError ? (
          <ErrorState message="Não foi possível carregar as categorias do caderno. Verifique o backend e tente novamente." />
        ) : null}

        {notesQuery.isError ? (
          <ErrorState message="Não foi possível carregar as notas do caderno. Verifique o backend e tente novamente." />
        ) : null}

        {deleteErrorMessage ? <ErrorState message={deleteErrorMessage} /> : null}
        {categoryErrorMessage ? <ErrorState message={categoryErrorMessage} /> : null}
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

        {categoriesQuery.data ? (
          <div className="grid min-h-[calc(100vh-180px)] gap-4 lg:grid-cols-[390px_minmax(0,1fr)]">
            <aside className="grid min-h-0 min-w-0 content-start gap-3" aria-label="Navegação do caderno">
              <NotebookNavigation
                categories={orderedCategories}
                categoryFilter={categoryFilter}
                statusFilter={statusFilter}
                searchTerm={searchTerm}
                onSearchTermChange={setSearchTerm}
                onCategoryFilterChange={(filter) => {
                  setCategoryFilter(filter);
                  clearDetail();
                }}
                onStatusFilterChange={(filter) => {
                  setStatusFilter(filter);
                  clearDetail();
                }}
                onOpenCategoryManager={() => setIsCategoryManagerOpen(true)}
              />

              <NotesList
                notes={notes}
                selectedNoteId={detailMode === "edit" ? selectedNote?.id ?? null : null}
                deletePendingNoteId={deleteMutation.isPending ? deleteMutation.variables ?? null : null}
                onEditNote={startEdit}
                onDeleteNote={handleDeleteNote}
              />
            </aside>

            <main className="min-h-0 min-w-0" aria-label="Editor do caderno">
              {detailMode === "empty" ? (
                <EmptyState title="Selecione uma nota para visualizar ou editar." className="h-full" />
              ) : (
                <NotebookNoteForm
                  note={detailMode === "edit" ? selectedNote : null}
                  categories={orderedCategories}
                  isPending={activeMutation.isPending}
                  deletePending={deleteMutation.isPending && deleteMutation.variables === selectedNote?.id}
                  errorMessage={errorMessage}
                  onCancelEdit={clearDetail}
                  onDeleteNote={selectedNote ? () => handleDeleteNote(selectedNote) : undefined}
                  onSubmit={handleSubmit}
                />
              )}
            </main>

            {isCategoryManagerOpen ? (
              <CategoryManagerPanel
                categories={orderedCategories}
                createPending={createCategoryMutation.isPending}
                updatePendingCategoryId={
                  updateCategoryMutation.isPending ? updateCategoryMutation.variables?.categoryId ?? null : null
                }
                deletePendingCategoryId={deleteCategoryMutation.isPending ? deleteCategoryMutation.variables ?? null : null}
                onClose={() => setIsCategoryManagerOpen(false)}
                onCreateCategory={handleCreateCategory}
                onRenameCategory={handleRenameCategory}
                onDeleteCategory={handleDeleteCategory}
              />
            ) : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}

function NotebookNavigation({
  categories,
  categoryFilter,
  statusFilter,
  searchTerm,
  onSearchTermChange,
  onCategoryFilterChange,
  onStatusFilterChange,
  onOpenCategoryManager,
}: {
  categories: NotebookCategory[];
  categoryFilter: CategoryFilter;
  statusFilter: StatusFilter;
  searchTerm: string;
  onSearchTermChange: (value: string) => void;
  onCategoryFilterChange: (filter: CategoryFilter) => void;
  onStatusFilterChange: (filter: StatusFilter) => void;
  onOpenCategoryManager: () => void;
}) {
  return (
    <div className="grid gap-3 rounded-md border border-zinc-200 bg-white p-3 shadow-sm">
      <TextField
        label="Buscar notas"
        value={searchTerm}
        onChange={(event) => onSearchTermChange(event.target.value)}
        placeholder="Título ou conteúdo"
      />

      <div className="grid gap-2">
        <h2 className="text-sm font-semibold text-zinc-950">Status</h2>
        <div className="flex flex-wrap gap-2" aria-label="Filtros de status">
          <FilterButton isActive={statusFilter === "all"} onClick={() => onStatusFilterChange("all")}>
            Todas
          </FilterButton>
          <FilterButton isActive={statusFilter === "OPEN"} onClick={() => onStatusFilterChange("OPEN")}>
            Abertas
          </FilterButton>
          <FilterButton isActive={statusFilter === "RESOLVED"} onClick={() => onStatusFilterChange("RESOLVED")}>
            Resolvidas
          </FilterButton>
        </div>
      </div>

      <div className="grid gap-2">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-zinc-950">Categorias</h2>
          <Button type="button" size="sm" variant="ghost" onClick={onOpenCategoryManager}>
            Gerenciar categorias
          </Button>
        </div>
        <div className="flex flex-wrap gap-2" aria-label="Filtros de categoria">
          <FilterButton isActive={categoryFilter === "all"} onClick={() => onCategoryFilterChange("all")}>
            Todas
          </FilterButton>
          <FilterButton
            isActive={categoryFilter === "uncategorized"}
            onClick={() => onCategoryFilterChange("uncategorized")}
          >
            Sem categoria
          </FilterButton>
          {categories.map((category) => (
            <FilterButton
              key={category.id}
              isActive={categoryFilter === category.id}
              onClick={() => onCategoryFilterChange(category.id)}
            >
              {category.name}
            </FilterButton>
          ))}
        </div>
      </div>
    </div>
  );
}

function CategoryManagerPanel({
  categories,
  createPending,
  updatePendingCategoryId,
  deletePendingCategoryId,
  onClose,
  onCreateCategory,
  onRenameCategory,
  onDeleteCategory,
}: {
  categories: NotebookCategory[];
  createPending: boolean;
  updatePendingCategoryId: string | null;
  deletePendingCategoryId: string | null;
  onClose: () => void;
  onCreateCategory: (name: string) => void;
  onRenameCategory: (category: NotebookCategory, name: string) => void;
  onDeleteCategory: (category: NotebookCategory) => void;
}) {
  const [newCategoryName, setNewCategoryName] = useState("");
  const [editingCategoryId, setEditingCategoryId] = useState<string | null>(null);
  const [editingCategoryName, setEditingCategoryName] = useState("");
  const [validationMessage, setValidationMessage] = useState<string | null>(null);

  function handleCreateCategory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = newCategoryName.trim();
    if (!name) {
      setValidationMessage("Informe o nome da categoria.");
      return;
    }

    setValidationMessage(null);
    onCreateCategory(name);
    setNewCategoryName("");
  }

  function startRename(category: NotebookCategory) {
    setValidationMessage(null);
    setEditingCategoryId(category.id);
    setEditingCategoryName(category.name);
  }

  function cancelRename() {
    setValidationMessage(null);
    setEditingCategoryId(null);
    setEditingCategoryName("");
  }

  function submitRename(category: NotebookCategory) {
    const name = editingCategoryName.trim();
    if (!name) {
      setValidationMessage("Informe o nome da categoria.");
      return;
    }

    setValidationMessage(null);
    onRenameCategory(category, name);
    cancelRename();
  }

  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-zinc-950/30 p-4" role="dialog" aria-modal="true">
      <div className="grid max-h-[min(720px,90vh)] w-full max-w-xl gap-4 overflow-y-auto rounded-md border border-zinc-200 bg-white p-4 shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-zinc-200 pb-3">
          <div>
            <h2 className="text-base font-semibold text-zinc-950">Gerenciar categorias</h2>
            <p className="mt-1 text-sm text-zinc-500">Crie, renomeie ou exclua categorias deste livro.</p>
          </div>
          <Button type="button" variant="ghost" onClick={onClose}>
            Fechar
          </Button>
        </div>

        <form onSubmit={handleCreateCategory} className="grid gap-2">
          <label className="grid gap-1 text-sm">
            <span className="font-medium text-zinc-700">Nova categoria</span>
            <input
              value={newCategoryName}
              onChange={(event) => {
                setValidationMessage(null);
                setNewCategoryName(event.target.value);
              }}
              disabled={createPending}
              className="min-h-9 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
            />
          </label>
          <Button type="submit" size="sm" disabled={createPending}>
            {createPending ? "Criando..." : "Criar categoria"}
          </Button>
        </form>

        {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}

        <div className="grid gap-2">
          {categories.map((category) => {
            const isEditing = editingCategoryId === category.id;
            const isPending = updatePendingCategoryId === category.id || deletePendingCategoryId === category.id;

            return (
              <div
                key={category.id}
                className="grid gap-2 rounded-md border border-zinc-200 bg-zinc-50/70 p-2"
                role="group"
                aria-label={`Categoria ${category.name}`}
              >
                {isEditing ? (
                  <label className="grid gap-1 text-sm">
                    <span className="font-medium text-zinc-700">Renomear categoria</span>
                    <input
                      value={editingCategoryName}
                      onChange={(event) => {
                        setValidationMessage(null);
                        setEditingCategoryName(event.target.value);
                      }}
                      disabled={isPending}
                      className="min-h-9 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
                    />
                  </label>
                ) : (
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="truncate text-sm font-medium text-zinc-800">{category.name}</p>
                    {category.isDefault ? <Badge variant="outline">Padrão</Badge> : null}
                  </div>
                )}

                {category.isDefault ? null : (
                  <div className="flex flex-wrap gap-1">
                    {isEditing ? (
                      <>
                        <Button
                          type="button"
                          size="sm"
                          variant="secondary"
                          disabled={isPending}
                          onClick={() => submitRename(category)}
                        >
                          Salvar
                        </Button>
                        <Button type="button" size="sm" variant="ghost" disabled={isPending} onClick={cancelRename}>
                          Cancelar
                        </Button>
                      </>
                    ) : (
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        disabled={isPending}
                        onClick={() => startRename(category)}
                      >
                        Renomear
                      </Button>
                    )}
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      className="text-red-700 hover:bg-red-50"
                      disabled={isPending}
                      onClick={() => onDeleteCategory(category)}
                    >
                      {deletePendingCategoryId === category.id ? "..." : "Excluir"}
                    </Button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function FilterButton({
  isActive,
  children,
  onClick,
}: {
  isActive: boolean;
  children: string;
  onClick: () => void;
}) {
  return (
    <Button type="button" size="sm" variant={isActive ? "primary" : "secondary"} onClick={onClick}>
      {children}
    </Button>
  );
}

function NotesList({
  notes,
  selectedNoteId,
  deletePendingNoteId,
  onEditNote,
  onDeleteNote,
}: {
  notes: NotebookNote[];
  selectedNoteId: string | null;
  deletePendingNoteId: string | null;
  onEditNote: (note: NotebookNote) => void;
  onDeleteNote: (note: NotebookNote) => void;
}) {
  if (notes.length === 0) {
    return <EmptyState title="Nenhuma nota no caderno" description="Crie a primeira nota geral deste livro." />;
  }

  return (
    <section className="grid min-h-0 content-start gap-2 overflow-y-auto pr-1" aria-label="Notas do caderno">
      {notes.map((note) => (
        <article
          key={note.id}
          className={`grid min-h-[136px] content-start gap-2 rounded-md border bg-white p-3 shadow-sm transition ${
            selectedNoteId === note.id ? "border-zinc-900 ring-2 ring-zinc-200" : "border-zinc-200 hover:border-zinc-300"
          }`}
        >
          <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
            <button type="button" className="min-w-0 text-left" onClick={() => onEditNote(note)}>
              <h3 className="truncate text-sm font-semibold text-zinc-950">{note.title}</h3>
              <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
                <Badge variant={note.status === "RESOLVED" ? "neutral" : "outline"}>{statusLabel(note.status)}</Badge>
                {note.category ? <Badge variant="outline">{note.category.name}</Badge> : null}
                {note.updatedAt ? <span>Atualizada em {formatDate(note.updatedAt)}</span> : null}
              </div>
            </button>

            <div className="flex shrink-0 items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs"
                onClick={() => onEditNote(note)}
              >
                Editar
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs text-red-700 hover:bg-red-50"
                disabled={deletePendingNoteId === note.id}
                onClick={() => onDeleteNote(note)}
              >
                {deletePendingNoteId === note.id ? "..." : "Excluir"}
              </Button>
            </div>
          </div>

          <p className="line-clamp-3 min-h-15 text-xs leading-5 text-zinc-600">
            {note.content?.trim() ? note.content : <span className="text-zinc-400">Sem conteúdo.</span>}
          </p>
        </article>
      ))}
    </section>
  );
}

function NotebookNoteForm({
  note,
  categories,
  isPending,
  deletePending,
  errorMessage,
  onCancelEdit,
  onDeleteNote,
  onSubmit,
}: {
  note: NotebookNote | null;
  categories: NotebookCategory[];
  isPending: boolean;
  deletePending: boolean;
  errorMessage: string | null;
  onCancelEdit: () => void;
  onDeleteNote?: () => void;
  onSubmit: (payload: NotebookNoteRequest) => void;
}) {
  const [form, setForm] = useState<NoteFormState>(emptyForm);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const isEditing = Boolean(note);

  useEffect(() => {
    setForm(note ? toFormState(note) : emptyForm);
    setValidationMessage(null);
  }, [note]);

  function updateField(field: keyof NoteFormState, value: string) {
    setValidationMessage(null);
    setForm((current) => ({ ...current, [field]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!form.title.trim()) {
      setValidationMessage("Informe o título da nota.");
      return;
    }

    onSubmit({
      title: form.title.trim(),
      content: nullableText(form.content),
      categoryId: form.categoryId || null,
      status: form.status,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid h-full content-start gap-4 rounded-md border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 pb-4">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">{isEditing ? "Editar nota" : "Nova nota"}</h2>
          <p className="text-sm text-zinc-500">Registre ideias, perguntas, pesquisas e referências gerais.</p>
        </div>

        <div className="flex flex-wrap gap-2">
          {isEditing && onDeleteNote ? (
            <Button
              type="button"
              variant="ghost"
              className="text-red-700 hover:bg-red-50"
              disabled={deletePending}
              onClick={onDeleteNote}
            >
              {deletePending ? "Excluindo..." : "Excluir"}
            </Button>
          ) : null}
          <Button type="button" variant="ghost" onClick={onCancelEdit}>
            Cancelar
          </Button>
        </div>
      </div>

      <TextField
        label="Título"
        value={form.title}
        onChange={(event) => updateField("title", event.target.value)}
        disabled={isPending}
        placeholder="Título da nota"
      />

      <div className="grid gap-3 md:grid-cols-2">
        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Categoria</span>
          <select
            value={form.categoryId}
            onChange={(event) => updateField("categoryId", event.target.value)}
            disabled={isPending}
            className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
          >
            <option value="">Sem categoria</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </label>

        <label className="grid gap-1 text-sm">
          <span className="font-medium text-zinc-700">Status</span>
          <select
            value={form.status}
            onChange={(event) => updateField("status", event.target.value)}
            disabled={isPending}
            className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
          >
            <option value="OPEN">Aberta</option>
            <option value="RESOLVED">Resolvida</option>
          </select>
        </label>
      </div>

      <TextAreaField
        label="Conteúdo"
        value={form.content}
        rows={14}
        onChange={(event) => updateField("content", event.target.value)}
        disabled={isPending}
        className="resize-y"
      />

      {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}

      <div className="border-t border-zinc-200 pt-4">
        <Button type="submit" disabled={isPending}>
          {isPending ? "Salvando..." : isEditing ? "Salvar nota" : "Criar nota"}
        </Button>
      </div>
    </form>
  );
}

function nullableText(value: string) {
  return value.trim() ? value.trim() : null;
}

function toFormState(note: NotebookNote): NoteFormState {
  return {
    title: note.title,
    content: note.content ?? "",
    categoryId: note.categoryId ?? "",
    status: note.status,
  };
}

function noteMatchesFilters(
  note: NotebookNote,
  categoryFilter: CategoryFilter,
  statusFilter: StatusFilter,
  searchTerm: string
) {
  if (categoryFilter === "uncategorized" && note.categoryId) {
    return false;
  }

  if (categoryFilter !== "all" && categoryFilter !== "uncategorized" && note.categoryId !== categoryFilter) {
    return false;
  }

  if (statusFilter !== "all" && note.status !== statusFilter) {
    return false;
  }

  const normalizedSearch = searchTerm.trim().toLocaleLowerCase("pt-BR");
  if (!normalizedSearch) {
    return true;
  }

  return (
    note.title.toLocaleLowerCase("pt-BR").includes(normalizedSearch) ||
    (note.content ?? "").toLocaleLowerCase("pt-BR").includes(normalizedSearch)
  );
}

function orderNotebookCategories(categories: NotebookCategory[]) {
  return [...categories].sort((first, second) => {
    const firstOutro = isOutro(first);
    const secondOutro = isOutro(second);
    if (firstOutro !== secondOutro) {
      return firstOutro ? 1 : -1;
    }

    if (first.isDefault !== second.isDefault) {
      return first.isDefault ? -1 : 1;
    }

    if (first.sortOrder !== second.sortOrder) {
      return first.sortOrder - second.sortOrder;
    }

    return first.name.localeCompare(second.name, "pt-BR");
  });
}

function isOutro(category: NotebookCategory) {
  return category.name.trim().toLocaleLowerCase("pt-BR") === "outro";
}

function statusLabel(status: NotebookNoteStatus) {
  return status === "RESOLVED" ? "Resolvida" : "Aberta";
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }

  return dateFormatter.format(date);
}

function getNotebookErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar a nota agora.";
  }

  return "Não foi possível salvar a nota. Verifique a API e tente novamente.";
}
