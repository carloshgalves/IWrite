import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import LibraryPage from "@/app/library/page";
import { BooksList } from "@/features/books/components/books-list";
import type { Book } from "@/features/books/types";

const api = vi.hoisted(() => ({
  listBooks: vi.fn(),
  createBook: vi.fn(),
  updateBook: vi.fn(),
  deleteBook: vi.fn(),
}));

vi.mock("@/features/books/api/books-api", () => api);

const ownerBook: Book = {
  id: "book-owner",
  title: "Livro principal",
  subtitle: "Primeiro rascunho",
  description: "Uma aventura",
  status: "WRITING",
  targetWordCount: 80_000,
  dailyTargetWordCount: 500,
  plannedWritingDays: ["MONDAY"],
  relationship: "OWNER",
  role: null,
  capabilities: [
    "READ_MANUSCRIPT",
    "MUTATE_MANUSCRIPT_STRUCTURE",
    "EDIT_BOOK_SETTINGS",
    "MANAGE_COLLABORATORS",
    "DELETE_BOOK",
  ],
  contextualCapabilities: ["EDIT_AUTHORED_CONTRIBUTION"],
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
};

const collaboratorBook: Book = {
  ...ownerBook,
  id: "book-collaborator",
  title: "Livro compartilhado",
  subtitle: null,
  description: null,
  status: "PLANNING",
  relationship: "COLLABORATOR",
  role: "LEGACY_COLLABORATOR",
  capabilities: ["READ_MANUSCRIPT", "EDIT_AUTHORED_CONTRIBUTION", "EDIT_BOOK_SETTINGS"],
  contextualCapabilities: [],
  updatedAt: "2026-01-03T00:00:00Z",
};

function renderWithClient(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
}

describe("books workflow", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    api.listBooks.mockResolvedValue([ownerBook, collaboratorBook]);
    api.createBook.mockResolvedValue(ownerBook);
    api.updateBook.mockResolvedValue(ownerBook);
    api.deleteBook.mockResolvedValue(undefined);
  });

  test("renders the library and creates, updates, cancels, and deletes a book", async () => {
    renderWithClient(<LibraryPage />);

    let ownerCard = (await screen.findByText(ownerBook.title)).closest("article")!;
    const collaboratorCard = screen.getByText(collaboratorBook.title).closest("article")!;
    expect(within(collaboratorCard).getByText("Sem descrição ainda.")).toBeInTheDocument();
    expect(within(collaboratorCard).queryByRole("button", { name: "Excluir" })).not.toBeInTheDocument();

    fireEvent.click(within(ownerCard).getByRole("button", { name: "Editar" }));
    let editForm = screen.getByRole("button", { name: "Salvar" }).closest("form")!;
    const titleInput = within(editForm).getByRole("textbox", { name: /^t.tulo$/i });
    fireEvent.change(titleInput, { target: { value: "  Livro revisado  " } });
    fireEvent.change(within(editForm).getByRole("textbox", { name: /^subt.tulo$/i }), {
      target: { value: "  Nova edição  " },
    });
    fireEvent.change(within(editForm).getByRole("textbox", { name: /descri/i }), {
      target: { value: "  Outra aventura  " },
    });
    fireEvent.change(within(editForm).getByRole("combobox", { name: "Status" }), {
      target: { value: "REVISING" },
    });
    fireEvent.click(within(editForm).getByRole("button", { name: "Salvar" }));

    await waitFor(() =>
      expect(api.updateBook).toHaveBeenCalledWith(ownerBook.id, {
        title: "Livro revisado",
        subtitle: "Nova edição",
        description: "Outra aventura",
        status: "REVISING",
      }),
    );

    ownerCard = (await screen.findByText(ownerBook.title)).closest("article")!;
    fireEvent.click(within(ownerCard).getByRole("button", { name: "Editar" }));
    editForm = screen.getByRole("button", { name: "Salvar" }).closest("form")!;
    fireEvent.change(within(editForm).getByRole("textbox", { name: /^t.tulo$/i }), { target: { value: "Temporário" } });
    fireEvent.click(within(editForm).getByRole("button", { name: "Cancelar" }));
    ownerCard = screen.getByText(ownerBook.title).closest("article")!;
    expect(within(ownerCard).getByText(ownerBook.title)).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText(/cidade de vidro/i), { target: { value: "  Livro novo  " } });
    fireEvent.change(screen.getByPlaceholderText("Opcional"), { target: { value: "  Subtítulo  " } });
    fireEvent.change(screen.getByPlaceholderText(/frase sobre/i), { target: { value: "  Descrição  " } });
    fireEvent.click(screen.getByRole("button", { name: "Criar livro" }));

    await waitFor(() =>
      expect(api.createBook).toHaveBeenCalledWith({
        title: "Livro novo",
        subtitle: "Subtítulo",
        description: "Descrição",
      }, expect.any(Object)),
    );
    expect(await screen.findByText("Livro criado com sucesso.")).toBeInTheDocument();

    ownerCard = screen.getByText(ownerBook.title).closest("article")!;
    fireEvent.click(within(ownerCard).getByRole("button", { name: "Excluir" }));
    await waitFor(() => expect(api.deleteBook).toHaveBeenCalledWith(ownerBook.id));
  });

  test("projects controls from the backend capabilities instead of an owner flag", async () => {
    const readOnlyBook: Book = {
      ...collaboratorBook,
      id: "book-read-only",
      title: "Livro somente leitura",
      role: "EDITOR",
      capabilities: ["READ_MANUSCRIPT", "READ_CANONICAL_PLANNING"],
      contextualCapabilities: [],
    };
    api.listBooks.mockResolvedValue([readOnlyBook]);

    renderWithClient(<LibraryPage />);

    const card = (await screen.findByText(readOnlyBook.title)).closest("article")!;
    expect(within(card).queryByRole("button", { name: "Editar" })).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Excluir" })).not.toBeInTheDocument();
    expect(within(card).getByRole("link", { name: "Abrir workspace" })).toBeInTheDocument();
  });

  test("shows loading, empty, and error library states", async () => {
    let resolveBooks: ((books: Book[]) => void) | undefined;
    api.listBooks.mockReturnValueOnce(new Promise<Book[]>((resolve) => { resolveBooks = resolve; }));
    const loading = renderWithClient(<BooksList />);
    expect(screen.getByText("Carregando livros...")).toBeInTheDocument();
    resolveBooks?.([]);
    expect(await screen.findByText(/biblioteca ainda est/)).toBeInTheDocument();
    loading.unmount();

    api.listBooks.mockRejectedValueOnce(new Error("offline"));
    renderWithClient(<BooksList />);
    expect(await screen.findByText(/carregar a biblioteca/)).toBeInTheDocument();
    expect(screen.getByText(/Tente novamente em instantes/)).toBeInTheDocument();
  });
});
