import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookWorkspace } from "@/features/workspace/components/book-workspace";
import type { Book } from "@/features/books/types";
import type { BookOutline } from "@/features/outline/types";
import { queryKeys } from "@/lib/query/keys";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

/**
 * Authority freshness for the mutation surfaces.
 *
 * The workspace may only offer a structure mutation while the projection it is reading is the one
 * the server currently confirms. A cached capability that survived a failed refetch, or a control
 * that outlived a revocation, is authority the server no longer stated.
 */

const outline: BookOutline = {
  id: "book-1",
  title: "Livro de teste",
  status: "WRITING",
  wordCount: 1200,
  sections: [
    {
      id: "section-1",
      title: "Parte 1",
      type: "PART",
      sortOrder: 0,
      wordCount: 1200,
      chapters: [
        {
          id: "chapter-1",
          title: "Capitulo 1",
          summary: null,
          sortOrder: 0,
          wordCount: 1200,
          scenes: [
            {
              id: sceneForPlanning.id,
              title: sceneForPlanning.title,
              status: sceneForPlanning.status,
              sortOrder: sceneForPlanning.sortOrder,
              wordCount: sceneForPlanning.wordCount,
              povCharacterId: null,
              povCharacterName: null,
              planningGaps: ["POV", "Objetivo", "Conflito", "Resultado"],
            },
          ],
        },
      ],
    },
  ],
};

const ownedBook: Book = {
  id: "book-1",
  title: "Livro de teste",
  subtitle: null,
  description: null,
  status: "WRITING",
  targetWordCount: null,
  relationship: "OWNER",
  role: null,
  capabilities: ["READ_MANUSCRIPT", "MUTATE_MANUSCRIPT_STRUCTURE"],
  contextualCapabilities: ["EDIT_AUTHORED_CONTRIBUTION"],
  createdAt: "2026-05-14T12:00:00Z",
  updatedAt: "2026-05-14T12:00:00Z",
};

/** The same user after the structure capability was revoked: reading stays, mutating does not. */
const revokedBook: Book = {
  ...ownedBook,
  relationship: "COLLABORATOR",
  role: "EDITOR",
  capabilities: ["READ_MANUSCRIPT"],
  contextualCapabilities: [],
};

const mocks = vi.hoisted(() => ({
  getOutline: vi.fn(),
  getBook: vi.fn(),
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  deleteScene: vi.fn(),
  createSection: vi.fn(),
  updateSection: vi.fn(),
  deleteSection: vi.fn(),
  createChapter: vi.fn(),
  updateChapter: vi.fn(),
  deleteChapter: vi.fn(),
  createScene: vi.fn(),
  deleteOutlineScene: vi.fn(),
  routerReplace: vi.fn(),
  searchParams: new URLSearchParams(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    replace: mocks.routerReplace,
  }),
  useSearchParams: () => mocks.searchParams,
}));

vi.mock("@/features/books/api/books-api", () => ({
  getBook: mocks.getBook,
}));

vi.mock("@/features/outline/api/outline-api", async () => {
  const actual = await vi.importActual<typeof import("@/features/outline/api/outline-api")>(
    "@/features/outline/api/outline-api"
  );

  return {
    ...actual,
    getOutline: mocks.getOutline,
    createSection: mocks.createSection,
    updateSection: mocks.updateSection,
    deleteSection: mocks.deleteSection,
    createChapter: mocks.createChapter,
    updateChapter: mocks.updateChapter,
    deleteChapter: mocks.deleteChapter,
    createScene: mocks.createScene,
    deleteScene: mocks.deleteOutlineScene,
  };
});

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  deleteScene: mocks.deleteScene,
}));

vi.mock("@/features/scenes/editor/tiptap-editor", () => ({
  TiptapEditor: ({ initialContentText }: { initialContentText?: string | null }) => (
    <textarea aria-label="Editor de conteúdo" readOnly value={initialContentText ?? ""} />
  ),
}));

function expectNoStructureDispatch() {
  expect(mocks.createSection).not.toHaveBeenCalled();
  expect(mocks.updateSection).not.toHaveBeenCalled();
  expect(mocks.deleteSection).not.toHaveBeenCalled();
  expect(mocks.createChapter).not.toHaveBeenCalled();
  expect(mocks.updateChapter).not.toHaveBeenCalled();
  expect(mocks.deleteChapter).not.toHaveBeenCalled();
  expect(mocks.createScene).not.toHaveBeenCalled();
  expect(mocks.deleteOutlineScene).not.toHaveBeenCalled();
  expect(mocks.updateScene).not.toHaveBeenCalled();
}

describe("BookWorkspace com autoridade de estrutura desatualizada", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getBook.mockResolvedValue(ownedBook);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
  });

  test("um refetch que falha nao deixa a autoridade em cache no comando", async () => {
    const { queryClient } = renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByRole("textbox", { name: "Nova seção" })).toBeInTheDocument();

    mocks.getBook.mockRejectedValue(new Error("network down"));
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.book("book-1") });
    });

    // The banner and the controls have to tell the same story: while the projection is not the one
    // the server currently confirms, the previous answer is not authority to act on.
    expect(await screen.findByRole("alert")).toHaveTextContent(/n.o foi poss.vel carregar suas permiss.es/i);
    expect(screen.queryByRole("textbox", { name: "Nova seção" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Excluir" })).not.toBeInTheDocument();
    expect(screen.getByText(/permiss.es deste livro n.o puderam ser carregadas/i)).toBeInTheDocument();
    expectNoStructureDispatch();
  });

  // Control: a projection that is currently successful still mutates, so the assertions above are
  // about the freshness of the answer and not about a surface that stopped working.
  test("a projecao atualmente bem-sucedida continua despachando a mutation", async () => {
    mocks.createSection.mockResolvedValue({ id: "section-2" });
    const { queryClient } = renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByRole("textbox", { name: "Nova seção" });
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.book("book-1") });
    });

    const input = await screen.findByRole("textbox", { name: "Nova seção" });
    fireEvent.change(input, { target: { value: "Parte 2" } });
    fireEvent.click(screen.getByRole("button", { name: "Criar" }));

    await waitFor(() => expect(mocks.createSection).toHaveBeenCalledWith("book-1", { title: "Parte 2", type: "PART" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("uma revogacao fecha a edicao inline aberta do outline e nao despacha", async () => {
    const { queryClient } = renderWithClient(<BookWorkspace bookId="book-1" />);

    // The section and its chapter both offer "Editar"; the section's is the first one.
    const [editSection] = await screen.findAllByRole("button", { name: "Editar" });
    fireEvent.click(editSection);
    fireEvent.change(await screen.findByLabelText("Nome da seção"), { target: { value: "Parte renomeada" } });

    mocks.getBook.mockResolvedValue(revokedBook);
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.book("book-1") });
    });

    // Editing state opened under the old authority is mutable state, so losing the capability has to
    // end it instead of leaving a Salvar that dispatches a request the server will refuse.
    await waitFor(() => expect(screen.queryByLabelText("Nome da seção")).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Salvar" })).not.toBeInTheDocument();
    expect(screen.getByText(/Somente leitura: voc. n.o altera a estrutura deste livro/)).toBeInTheDocument();
    expectNoStructureDispatch();
  });

  test("uma revogacao fecha a transicao pendente do kanban e nao despacha", async () => {
    const { queryClient } = renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByRole("textbox", { name: "Nova seção" });
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));

    fireEvent.change(await screen.findByLabelText(`Status de ${sceneForPlanning.title}`), {
      target: { value: "WRITTEN" },
    });
    expect(screen.getByRole("dialog", { name: "Avancar com lacunas?" })).toBeInTheDocument();

    mocks.getBook.mockResolvedValue(revokedBook);
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.book("book-1") });
    });

    // A modal that survives the revocation keeps a confirm button that still applies the optimistic
    // move and dispatches the write.
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Avancar com lacunas?" })).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Mover mesmo assim" })).not.toBeInTheDocument();
    expectNoStructureDispatch();
  });

  test("sem a capability o kanban nao oferece nenhum controle de mutacao da cena", async () => {
    mocks.getBook.mockResolvedValue(revokedBook);
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText(/Somente leitura: voc. n.o altera a estrutura deste livro/);
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));

    // Scoped to presentation on purpose: this proves the card leaves no reachable route to a status
    // write, which is what a regression here would remove. It is not evidence about the guard inside
    // applyStatusChange -- a disabled select never delivers a change event, so driving one from here
    // would pass with or without that guard. That guard is covered by the revocation tests above,
    // which reach the dispatch through a control opened while the authority still held.
    expect(await screen.findByLabelText(`Status de ${sceneForPlanning.title}`)).toBeDisabled();
    expect(screen.queryByLabelText(`Mover cena ${sceneForPlanning.title}`)).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expectNoStructureDispatch();
  });
});
