import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookWorkspace } from "@/features/workspace/components/book-workspace";
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

const mocks = vi.hoisted(() => ({
  getOutline: vi.fn(),
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  deleteScene: vi.fn(),
  searchBook: vi.fn(),
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
  CharactersPanel: ({ openCharacterIntent }: { openCharacterIntent?: { id: string; requestId: number } | null }) => (
    <div>
      <h1>Personagens</h1>
      {openCharacterIntent ? <p>Personagem selecionado {openCharacterIntent.id}</p> : null}
    </div>
  ),
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

vi.mock("@/features/search/api/book-search-api", () => ({
  searchBook: mocks.searchBook,
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

describe("BookWorkspace focus mode", () => {
  let fullscreenElement: Element | null;
  let requestFullscreen: ReturnType<typeof vi.fn>;
  let exitFullscreen: ReturnType<typeof vi.fn>;

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
    mocks.getScene.mockResolvedValue(sceneForPlanning);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.searchBook.mockResolvedValue([]);
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

describe("BookWorkspace initial scene selection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.searchParams = new URLSearchParams();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.searchBook.mockResolvedValue([]);
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

  test("abre resultado de busca de personagem na aba correta", async () => {
    mocks.searchBook.mockResolvedValue([
      {
        id: "character-ada",
        type: "CHARACTER",
        title: "Ada",
        snippet: "Ada aparece na busca.",
        metadata: "Personagem",
      },
    ]);
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    const dialog = screen.getByRole("dialog", { name: "Busca global do livro" });
    fireEvent.change(within(dialog).getByLabelText("Termo de busca"), { target: { value: "Ada" } });

    fireEvent.click(await within(dialog).findByRole("button", { name: /Ada/ }));

    expect(await screen.findByRole("heading", { name: "Personagens" })).toBeInTheDocument();
    expect(screen.getByText("Personagem selecionado character-ada")).toBeInTheDocument();
    expect(mocks.routerReplace).toHaveBeenCalledWith("/books/book-1", { scroll: false });
  });

  test("resultado de busca de cena abre editor sem reaplicar pedido antigo de planejamento", async () => {
    mocks.getOutline.mockResolvedValue(outlineWithAlternateScene);
    mocks.getScene.mockImplementation((sceneId: string) =>
      Promise.resolve(sceneId === alternateSceneForPlanning.id ? alternateSceneForPlanning : sceneForPlanning)
    );
    mocks.searchBook.mockResolvedValue([
      {
        id: alternateSceneForPlanning.id,
        type: "SCENE",
        title: alternateSceneForPlanning.title,
        snippet: "Cena encontrada pela busca.",
        metadata: "Parte 1 - Capitulo 1",
      },
    ]);
    renderWithClient(<BookWorkspace bookId="book-1" />);

    await screen.findByText("Livro");
    fireEvent.click(screen.getByRole("button", { name: "Kanban" }));
    expect(await screen.findByRole("heading", { name: "Kanban" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(`Status de ${sceneForPlanning.title}`), { target: { value: "PLANNED" } });
    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Recolher planejamento da cena" }));

    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    const dialog = screen.getByRole("dialog", { name: "Busca global do livro" });
    fireEvent.change(within(dialog).getByLabelText("Termo de busca"), { target: { value: "alternativa" } });
    fireEvent.click(await within(dialog).findByRole("button", { name: /Cena alternativa/ }));

    expect(await screen.findByRole("heading", { name: alternateSceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expandir planejamento da cena" })).toHaveAttribute("aria-expanded", "false");
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

function selectScene() {
  const sceneRow = screen.getByText(sceneForPlanning.title).closest("button");
  expect(sceneRow).not.toBeNull();
  fireEvent.click(sceneRow as HTMLButtonElement);
}
