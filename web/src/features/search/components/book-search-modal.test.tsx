import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookSearchModal } from "@/features/search/components/book-search-modal";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  searchBook: vi.fn(),
  onClose: vi.fn(),
  onSelectResult: vi.fn(),
}));

vi.mock("@/features/search/api/book-search-api", () => ({
  searchBook: mocks.searchBook,
}));

describe("BookSearchModal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchBook.mockResolvedValue([]);
  });

  test("aguarda termo minimo e debounce antes de buscar", async () => {
    renderWithClient(<BookSearchModal bookId="book-1" onClose={mocks.onClose} onSelectResult={mocks.onSelectResult} />);

    const input = screen.getByLabelText("Termo de busca");
    fireEvent.change(input, { target: { value: "a" } });

    await waitFor(() => {
      expect(mocks.searchBook).not.toHaveBeenCalled();
    });

    fireEvent.change(input, { target: { value: "ada" } });

    await waitFor(() => {
      expect(mocks.searchBook).toHaveBeenCalledWith("book-1", "ada", 30);
    });
  });

  test("renderiza resultados agrupados e seleciona resultado", async () => {
    mocks.searchBook.mockResolvedValue([
      {
        id: "scene-1",
        type: "SCENE",
        title: "Cena encontrada",
        snippet: "Trecho encontrado na cena.",
        metadata: "Parte - Capitulo",
      },
      {
        id: "note-1",
        type: "NOTEBOOK_NOTE",
        title: "Nota encontrada",
        snippet: "Trecho encontrado na nota.",
        metadata: "OPEN",
      },
    ]);
    renderWithClient(<BookSearchModal bookId="book-1" onClose={mocks.onClose} onSelectResult={mocks.onSelectResult} />);

    fireEvent.change(screen.getByLabelText("Termo de busca"), { target: { value: "encontrada" } });

    expect(await screen.findByText("Cenas")).toBeInTheDocument();
    expect(screen.getByText("Caderno")).toBeInTheDocument();
    const sceneButton = screen.getByRole("button", { name: /Cena encontrada/ });
    expect(within(sceneButton).getByText("Trecho encontrado na cena.")).toBeInTheDocument();

    fireEvent.click(sceneButton);

    expect(mocks.onSelectResult).toHaveBeenCalledWith(expect.objectContaining({ id: "scene-1", type: "SCENE" }));
  });

  test("renderiza estados vazio e erro", async () => {
    const { rerender } = renderWithClient(
      <BookSearchModal bookId="book-1" onClose={mocks.onClose} onSelectResult={mocks.onSelectResult} />
    );

    fireEvent.change(screen.getByLabelText("Termo de busca"), { target: { value: "nada" } });
    expect(await screen.findByText("Nenhum resultado encontrado.")).toBeInTheDocument();

    mocks.searchBook.mockRejectedValue(new Error("falhou"));
    rerender(<BookSearchModal bookId="book-2" onClose={mocks.onClose} onSelectResult={mocks.onSelectResult} />);
    fireEvent.change(screen.getByLabelText("Termo de busca"), { target: { value: "erro" } });

    expect(await screen.findByText("Não foi possível buscar no livro agora.")).toBeInTheDocument();
  });
});
