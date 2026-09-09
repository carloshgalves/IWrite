import { fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { BookWorkspace } from "@/features/workspace/components/book-workspace";
import type { Book } from "@/features/books/types";
import type { BookOutline } from "@/features/outline/types";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

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

/** The workspace reads the effective access from the book projection, so every test states one. */
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

/** An editor reads the manuscript and changes nothing in it. */
const editorBook: Book = {
  ...ownedBook,
  relationship: "COLLABORATOR",
  role: "EDITOR",
  capabilities: ["READ_MANUSCRIPT"],
  contextualCapabilities: [],
};

/**
 * An author is eligible for EDIT_AUTHORED_CONTRIBUTION at book scope and still holds no authority
 * over a given scene until #184. Book-scoped eligibility is not permission to edit.
 */
const authorBook: Book = {
  ...ownedBook,
  relationship: "COLLABORATOR",
  role: "AUTHOR",
  capabilities: ["READ_MANUSCRIPT"],
  contextualCapabilities: ["EDIT_AUTHORED_CONTRIBUTION"],
};

const mocks = vi.hoisted(() => ({
  getOutline: vi.fn(),
  getBook: vi.fn(),
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  deleteScene: vi.fn(),
  routerReplace: vi.fn(),
  searchParams: new URLSearchParams(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    replace: mocks.routerReplace,
  }),
  useSearchParams: () => mocks.searchParams,
}));

vi.mock("@/features/characters/components/characters-panel", () => ({
  CharactersPanel: () => <h1>Personagens</h1>,
}));

vi.mock("@/features/locations/components/locations-panel", () => ({
  LocationsPanel: () => <h1>Localizações</h1>,
}));

vi.mock("@/features/items/components/items-panel", () => ({
  ItemsPanel: () => <h1>Itens</h1>,
}));

vi.mock("@/features/notebook/components/notebook-panel", () => ({
  NotebookPanel: () => <h1>Caderno</h1>,
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacters: () => ({
    isLoading: false,
    isError: false,
    data: [],
  }),
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocations: () => ({
    isLoading: false,
    isError: false,
    data: [],
  }),
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItems: () => ({
    isLoading: false,
    isError: false,
    data: [],
  }),
}));

vi.mock("@/features/dashboard/components/book-dashboard", () => ({
  BookDashboard: ({
    onOpenSceneInEditor,
    onOpenWorkspaceTab,
  }: {
    onOpenSceneInEditor?: (sceneId: string) => void;
    onOpenWorkspaceTab?: (tab: "characters" | "locations" | "items") => void;
  }) => (
    <div>
      <button type="button" onClick={() => onOpenSceneInEditor?.("scene-1")}>
        Abrir cena do dashboard
      </button>
      <button type="button" onClick={() => onOpenWorkspaceTab?.("characters")}>
        Ver em Personagens
      </button>
      <button type="button" onClick={() => onOpenWorkspaceTab?.("locations")}>
        Ver em Localizações
      </button>
      <button type="button" onClick={() => onOpenWorkspaceTab?.("items")}>
        Ver em Itens
      </button>
    </div>
  ),
}));

vi.mock("@/features/books/api/books-api", () => ({
  getBook: mocks.getBook,
}));

vi.mock("@/features/outline/api/outline-api", async () => {
  const actual = await vi.importActual<typeof import("@/features/outline/api/outline-api")>("@/features/outline/api/outline-api");

  return {
    ...actual,
    getOutline: mocks.getOutline,
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

const alternateSceneForPlanning = {
  ...sceneForPlanning,
  id: "scene-alternativa",
  title: "Cena alternativa",
};

const outlineWithAlternateScene: BookOutline = {
  ...outline,
  sections: outline.sections.map((section) => ({
    ...section,
    chapters: section.chapters.map((chapter) => ({
      ...chapter,
      scenes: [
        ...chapter.scenes,
        {
          id: alternateSceneForPlanning.id,
          title: alternateSceneForPlanning.title,
          status: alternateSceneForPlanning.status,
          sortOrder: sceneForPlanning.sortOrder + 1,
          wordCount: alternateSceneForPlanning.wordCount,
          povCharacterId: null,
          povCharacterName: null,
          planningGaps: ["POV", "Objetivo", "Conflito", "Resultado"],
        },
      ],
    })),
  })),
};

/**
 * Undoes exactly what {@link Object.defineProperty} did: reinstalls the captured descriptor, or
 * deletes the property when it did not exist beforehand. Scoped to this file's own `afterEach`
 * rather than the global setup - moving it there would only turn a leak local to this describe
 * block into one shared by the whole suite.
 */
function restoreProperty(target: object, property: PropertyKey, original: PropertyDescriptor | undefined) {
  if (original) {
    Object.defineProperty(target, property, original);
  } else {
    delete (target as Record<PropertyKey, unknown>)[property];
  }
}

describe("BookWorkspace focus mode", () => {
  let fullscreenElement: Element | null;
  let requestFullscreen: ReturnType<typeof vi.fn>;
  let exitFullscreen: ReturnType<typeof vi.fn>;

  // None of the three exist on jsdom's document by default (verified: all three descriptors are
  // undefined here), so "restore" means delete, not reinstall a prior getter. Captured once, before
  // any test in this block runs, so the shape holds even if a future jsdom version does define one
  // of them.
  const originalFullscreenElement = Object.getOwnPropertyDescriptor(document, "fullscreenElement");
  const originalRequestFullscreen = Object.getOwnPropertyDescriptor(document.documentElement, "requestFullscreen");
  const originalExitFullscreen = Object.getOwnPropertyDescriptor(document, "exitFullscreen");

  afterEach(() => {
    restoreProperty(document, "fullscreenElement", originalFullscreenElement);
    restoreProperty(document.documentElement, "requestFullscreen", originalRequestFullscreen);
    restoreProperty(document, "exitFullscreen", originalExitFullscreen);
  });

  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    fullscreenElement = null;
    requestFullscreen = vi.fn(() => {
      fullscreenElement = document.documentElement;
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    exitFullscreen = vi.fn(() => {
      fullscreenElement = null;
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    Object.defineProperty(document, "fullscreenElement", {
      configurable: true,
      get: () => fullscreenElement,
    });
    Object.defineProperty(document.documentElement, "requestFullscreen", {
      configurable: true,
      value: requestFullscreen,
    });
    Object.defineProperty(document, "exitFullscreen", {
      configurable: true,
      value: exitFullscreen,
    });
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getBook.mockResolvedValue(ownedBook);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  test("oculta o outline no foco e restaura mantendo a cena selecionada", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();

    const sceneRow = screen.getByText(sceneForPlanning.title).closest("button");
    expect(sceneRow).not.toBeNull();
    fireEvent.click(sceneRow as HTMLButtonElement);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getAllByText(`${sceneForPlanning.wordCount} palavras`).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Salvo").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Modo foco" }));

    await waitFor(() => {
      expect(screen.queryByText("Livro")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getAllByText(`${sceneForPlanning.wordCount} palavras`).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Salvo").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sair do foco" }));

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Modo foco" })).toBeInTheDocument();
  });

  test("restaura foco via localStorage apenas depois que uma cena e selecionada", async () => {
    window.localStorage.setItem("iwrite.focusMode.enabled", "true");
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();

    selectScene();

    await waitFor(() => {
      expect(screen.queryByText("Livro")).not.toBeInTheDocument();
    });
    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sair do foco" })).toBeInTheDocument();
  });

  test("nao restaura foco sem cena selecionada", async () => {
    window.localStorage.setItem("iwrite.focusMode.enabled", "true");
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sair do foco" })).not.toBeInTheDocument();
  });

  test("salva a preferencia ao entrar e sair do foco", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    selectScene();
    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Modo foco" }));
    expect(window.localStorage.getItem("iwrite.focusMode.enabled")).toBe("true");

    fireEvent.click(screen.getByRole("button", { name: "Sair do foco" }));
    expect(window.localStorage.getItem("iwrite.focusMode.enabled")).toBe("false");
    expect(await screen.findByText("Livro")).toBeInTheDocument();
  });

  test("Ctrl+Shift+F alterna foco e Esc sai do foco", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    selectScene();
    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "F", ctrlKey: true, shiftKey: true });
    await waitFor(() => {
      expect(screen.queryByText("Livro")).not.toBeInTheDocument();
    });

    fireEvent.keyDown(window, { key: "Escape" });
    expect(await screen.findByText("Livro")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "F", ctrlKey: true, shiftKey: true });
    await waitFor(() => {
      expect(screen.queryByText("Livro")).not.toBeInTheDocument();
    });

    fireEvent.keyDown(window, { key: "F", ctrlKey: true, shiftKey: true });
    expect(await screen.findByText("Livro")).toBeInTheDocument();
  });

  test("mostra tela cheia em foco e sai da tela cheia ao sair do foco", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    selectScene();
    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Modo foco" }));
    expect(await screen.findByRole("button", { name: "Tela cheia" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Tela cheia" }));
    expect(requestFullscreen).toHaveBeenCalled();
    expect(await screen.findByRole("button", { name: "Sair da tela cheia" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sair do foco" }));
    expect(exitFullscreen).toHaveBeenCalled();
    expect(await screen.findByText("Livro")).toBeInTheDocument();
  });
});

// Regression guard for the fullscreen mocks above: this block declares no fixtures of its own, so
// if the previous describe's afterEach failed to clean up, these globals would still carry its
// mocks here.
describe("global fullscreen properties after BookWorkspace focus mode", () => {
  test("document and documentElement are back to their pre-suite shape", () => {
    expect(Object.getOwnPropertyDescriptor(document, "fullscreenElement")).toBeUndefined();
    expect(Object.getOwnPropertyDescriptor(document.documentElement, "requestFullscreen")).toBeUndefined();
    expect(Object.getOwnPropertyDescriptor(document, "exitFullscreen")).toBeUndefined();
  });
});

describe("BookWorkspace initial scene selection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getBook.mockResolvedValue(ownedBook);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  test("carrega a cena inicial sem clicar no outline", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(mocks.getScene).toHaveBeenCalledWith(sceneForPlanning.id);
    expect(mocks.routerReplace).not.toHaveBeenCalled();
  });

  test("atualiza a URL ao selecionar uma cena", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    selectScene();

    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${sceneForPlanning.id}`, { scroll: false });
  });

  test("remove sceneId da URL ao excluir a cena selecionada", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithClient(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Excluir cena" }));

    await waitFor(() => {
      expect(mocks.routerReplace).toHaveBeenCalledWith("/books/book-1", { scroll: false });
    });
  });

  test("atualiza a cena selecionada quando sceneId muda na URL", async () => {
    mocks.getOutline.mockResolvedValue(outlineWithAlternateScene);
    mocks.searchParams = new URLSearchParams(`sceneId=${sceneForPlanning.id}`);
    mocks.getScene.mockImplementation((sceneId: string) =>
      Promise.resolve(sceneId === alternateSceneForPlanning.id ? alternateSceneForPlanning : sceneForPlanning)
    );
    const { rerender } = renderWithClient(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();

    mocks.searchParams = new URLSearchParams(`sceneId=${alternateSceneForPlanning.id}`);
    rerender(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByRole("heading", { name: alternateSceneForPlanning.title })).toBeInTheDocument();
    expect(mocks.getScene).toHaveBeenCalledWith(alternateSceneForPlanning.id);
  });

  test("nao remove sceneId valido da URL quando a selecao anterior era de outro livro", async () => {
    const stalePreviousBookScene = {
      ...sceneForPlanning,
      id: "scene-do-livro-anterior",
      title: "Cena do livro anterior",
    };
    mocks.searchParams = new URLSearchParams(`sceneId=${sceneForPlanning.id}`);
    mocks.getScene.mockImplementation((sceneId: string) =>
      Promise.resolve(sceneId === stalePreviousBookScene.id ? stalePreviousBookScene : sceneForPlanning)
    );

    renderWithClient(<BookWorkspace bookId="book-1" initialSceneId={stalePreviousBookScene.id} />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(mocks.getScene).toHaveBeenCalledWith(sceneForPlanning.id);
    expect(mocks.routerReplace).not.toHaveBeenCalledWith("/books/book-1", { scroll: false });
  });

  test("limpa sceneId invalido da URL depois que o outline carrega", async () => {
    mocks.searchParams = new URLSearchParams("sceneId=scene-inexistente");
    renderWithClient(<BookWorkspace bookId="book-1" initialSceneId="scene-inexistente" />);

    await waitFor(() => {
      expect(mocks.routerReplace).toHaveBeenCalledWith("/books/book-1", { scroll: false });
    });
    expect(await screen.findByText("Selecione uma cena")).toBeInTheDocument();
  });

  test("limpa novamente o mesmo sceneId invalido apos URL sem sceneId e ainda aceita sceneId valido", async () => {
    mocks.searchParams = new URLSearchParams("sceneId=scene-inexistente");
    const { rerender } = renderWithClient(<BookWorkspace bookId="book-1" initialSceneId="scene-inexistente" />);

    await waitFor(() => {
      expect(mocks.routerReplace).toHaveBeenCalledTimes(1);
    });
    expect(mocks.routerReplace).toHaveBeenLastCalledWith("/books/book-1", { scroll: false });

    mocks.searchParams = new URLSearchParams();
    rerender(<BookWorkspace bookId="book-1" initialSceneId="scene-inexistente" />);

    await screen.findByText("Selecione uma cena");

    mocks.searchParams = new URLSearchParams("sceneId=scene-inexistente");
    rerender(<BookWorkspace bookId="book-1" initialSceneId="scene-inexistente" />);

    await waitFor(() => {
      expect(mocks.routerReplace).toHaveBeenCalledTimes(2);
    });
    expect(mocks.routerReplace).toHaveBeenLastCalledWith("/books/book-1", { scroll: false });

    mocks.searchParams = new URLSearchParams(`sceneId=${sceneForPlanning.id}`);
    rerender(<BookWorkspace bookId="book-1" initialSceneId="scene-inexistente" />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(mocks.getScene).toHaveBeenCalledWith(sceneForPlanning.id);
  });

  test("nao seleciona sceneId que nao pertence ao outline do livro", async () => {
    mocks.searchParams = new URLSearchParams("sceneId=scene-de-outro-livro");
    renderWithClient(<BookWorkspace bookId="book-1" initialSceneId="scene-de-outro-livro" />);

    await waitFor(() => {
      expect(mocks.routerReplace).toHaveBeenCalledWith("/books/book-1", { scroll: false });
    });
    expect(screen.queryByRole("heading", { name: sceneForPlanning.title })).not.toBeInTheDocument();
  });

  test("limpa a selecao quando sceneId e removido da URL", async () => {
    mocks.searchParams = new URLSearchParams(`sceneId=${sceneForPlanning.id}`);
    const { rerender } = renderWithClient(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();

    mocks.searchParams = new URLSearchParams();
    rerender(<BookWorkspace bookId="book-1" initialSceneId={sceneForPlanning.id} />);

    expect(await screen.findByText("Selecione uma cena")).toBeInTheDocument();
  });

  test("mantem a selecao vazia quando nao ha cena inicial", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Selecione uma cena")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: sceneForPlanning.title })).not.toBeInTheDocument();
    expect(mocks.getScene).not.toHaveBeenCalled();
  });

  test("abre cena do dashboard no editor e atualiza a URL", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: /Vis.o geral/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Abrir cena do dashboard" }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByText("Livro")).toBeInTheDocument();
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${sceneForPlanning.id}`, { scroll: false });
  });

  test("abre cena do storyboard no editor e atualiza a URL", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Storyboard" }));
    expect(await screen.findByRole("heading", { name: "Storyboard" })).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: `Abrir cena ${sceneForPlanning.title}` })[0]);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByText("Livro")).toBeInTheDocument();
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${sceneForPlanning.id}`, { scroll: false });
  });

  test("abre cena do kanban no editor e atualiza a URL", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: `Abrir cena ${sceneForPlanning.title}` }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByText("Livro")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expandir planejamento da cena" })).toHaveAttribute("aria-expanded", "false");
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${sceneForPlanning.id}`, { scroll: false });
  });

  test("abre planejamento a partir do kanban bloqueado e atualiza a URL", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(`Status de ${sceneForPlanning.title}`), { target: { value: "PLANNED" } });
    expect(screen.getByRole("dialog", { name: "Planejamento incompleto" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByText("Livro")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Recolher planejamento da cena" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByLabelText("Objetivo")).toBeInTheDocument();
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${sceneForPlanning.id}`, { scroll: false });
  });

  test("nao reaplica pedido antigo de planejamento ao selecionar outra cena normalmente", async () => {
    mocks.getOutline.mockResolvedValue(outlineWithAlternateScene);
    mocks.getScene.mockImplementation((sceneId: string) =>
      Promise.resolve(sceneId === alternateSceneForPlanning.id ? alternateSceneForPlanning : sceneForPlanning)
    );
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(`Status de ${sceneForPlanning.title}`), { target: { value: "PLANNED" } });
    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Recolher planejamento da cena" }));
    expect(screen.getByRole("button", { name: "Expandir planejamento da cena" })).toHaveAttribute("aria-expanded", "false");

    const alternateSceneRow = screen.getByText(alternateSceneForPlanning.title).closest("button");
    expect(alternateSceneRow).not.toBeNull();
    fireEvent.click(alternateSceneRow as HTMLButtonElement);

    expect(await screen.findByRole("heading", { name: alternateSceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expandir planejamento da cena" })).toHaveAttribute("aria-expanded", "false");
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${alternateSceneForPlanning.id}`, {
      scroll: false,
    });
  });

  test("novo pedido do kanban ainda abre planejamento para outra cena", async () => {
    mocks.getOutline.mockResolvedValue(outlineWithAlternateScene);
    mocks.getScene.mockImplementation((sceneId: string) =>
      Promise.resolve(sceneId === alternateSceneForPlanning.id ? alternateSceneForPlanning : sceneForPlanning)
    );
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(`Status de ${sceneForPlanning.title}`), { target: { value: "PLANNED" } });
    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Recolher planejamento da cena" }));

    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(`Status de ${alternateSceneForPlanning.title}`), { target: { value: "PLANNED" } });
    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(await screen.findByRole("heading", { name: alternateSceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Recolher planejamento da cena" })).toHaveAttribute("aria-expanded", "true");
    expect(mocks.routerReplace).toHaveBeenCalledWith(`/books/book-1?sceneId=${alternateSceneForPlanning.id}`, {
      scroll: false,
    });
  });

  test("abre abas de entidades a partir do dashboard", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: /Vis.o geral/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Ver em Personagens" }));
    expect(await screen.findByRole("heading", { name: "Personagens" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Vis.o geral/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Ver em Localizações" }));
    expect(await screen.findByRole("heading", { name: "Localizações" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Vis.o geral/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Ver em Itens" }));
    expect(await screen.findByRole("heading", { name: "Itens" })).toBeInTheDocument();
  });

  test("mostra a aba Caderno e abre o painel de notas", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Caderno" }));

    expect(await screen.findByRole("heading", { name: "Caderno" })).toBeInTheDocument();
  });
});

describe("BookWorkspace sem capabilities de estrutura e conteudo", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getBook.mockResolvedValue(editorBook);
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, canEditContent: false });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  test("o outline vira leitura sem acoes de estrutura", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    expect(screen.getByText(sceneForPlanning.title)).toBeInTheDocument();

    expect(screen.getByText(/Somente leitura: voc. n.o altera a estrutura deste livro/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Nova seção" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Novo cap.tulo/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Nova cena/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Excluir" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reordenar se..o/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reordenar cena/ })).not.toBeInTheDocument();
  });

  test("o editor da cena abre somente leitura e sem acoes proibidas", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    selectScene();

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getAllByText("Somente leitura").length).toBeGreaterThan(0);
    expect(screen.getByText(/n.o autoriza voc. a alterar o conte.do desta cena/)).toBeInTheDocument();

    expect(screen.queryByRole("button", { name: /Salvar conte.do/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Salvar cena" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Excluir cena" })).not.toBeInTheDocument();

    // The metadata still shows; it is the writing that is withheld, not the reading.
    expect(screen.getByLabelText("Título")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("Status")).toBeDisabled();
  });
});

function selectScene() {
  const sceneRow = screen.getByText(sceneForPlanning.title).closest("button");
  expect(sceneRow).not.toBeNull();
  fireEvent.click(sceneRow as HTMLButtonElement);
}

describe("BookWorkspace com elegibilidade de conteudo sem autoridade sobre a cena", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getBook.mockResolvedValue(authorBook);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  test("a cena que o servidor projeta como nao editavel abre somente leitura", async () => {
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, canEditContent: false });

    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    selectScene();

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    // The browser must not read the book-scoped eligibility as permission and offer a save the
    // backend always refuses.
    expect(screen.queryByRole("button", { name: /Salvar conte.do/ })).not.toBeInTheDocument();
    expect(screen.getByText(/n.o autoriza voc. a alterar o conte.do desta cena/)).toBeInTheDocument();
  });

  // Control: the decision follows the server for the very same role, so it is the projection being
  // read and not the role being guessed at.
  test("a mesma cena projetada como editavel abre para escrita", async () => {
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, canEditContent: true });

    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    selectScene();

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();
  });
});

describe("BookWorkspace quando as capabilities nao carregam", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
  });

  test("uma falha de getBook aparece como erro com nova tentativa, nao como negacao de acesso", async () => {
    mocks.getBook.mockRejectedValue(new Error("network down"));

    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();

    // A failure is not an answer about what this user may do: saying "you do not change this book"
    // would report a transport problem as an authorization decision.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/n.o foi poss.vel carregar suas permiss.es/i);
    expect(screen.getByRole("button", { name: /Tentar novamente/ })).toBeInTheDocument();
    expect(screen.queryByText(/Somente leitura: voc. n.o altera a estrutura deste livro/)).not.toBeInTheDocument();
    // Still conservative while it is unknown: no structure control is offered.
    expect(screen.queryByRole("textbox", { name: "Nova seção" })).not.toBeInTheDocument();
  });

  test("a nova tentativa recarrega as capabilities e devolve os controles", async () => {
    mocks.getBook.mockRejectedValueOnce(new Error("network down")).mockResolvedValue(ownedBook);

    renderWithClient(<BookWorkspace bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: /Tentar novamente/ }));

    expect(await screen.findByRole("textbox", { name: "Nova seção" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  // Control: while the answer is merely on its way, the surface stays quiet and conservative instead
  // of announcing a failure that has not happened.
  test("enquanto as capabilities carregam nao ha erro nem controles de estrutura", async () => {
    mocks.getBook.mockImplementation(() => new Promise(() => undefined));

    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Nova seção" })).not.toBeInTheDocument();
  });
});
