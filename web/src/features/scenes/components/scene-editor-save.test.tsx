import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";
import { queryKeys } from "@/lib/query/keys";
import type { Scene } from "@/features/scenes/types";
import type { QueryClient } from "@tanstack/react-query";

const mocks = vi.hoisted(() => ({
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  restoreSceneVersion: vi.fn(),
  deleteScene: vi.fn(),
  analyzeScene: vi.fn(),
  randomUUID: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  restoreSceneVersion: mocks.restoreSceneVersion,
  deleteScene: mocks.deleteScene,
}));

vi.mock("@/features/scenes/api/analyze-scene", () => ({
  analyzeScene: mocks.analyzeScene,
}));

vi.mock("@/features/scenes/components/scene-content-editor", () => ({
  SceneContentEditor: ({
    contentKey,
    sourceSceneId,
    contentText,
    onContentChange,
  }: {
    contentKey: string;
    sourceSceneId: string;
    contentText: string;
    onContentChange: (sceneId: string, contentJson: string, contentText: string) => void;
  }) => (
    <div>
      <p data-testid="editor-content" data-content-key={contentKey}>{contentText}</p>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc\"}", "Novo texto")}>
        Alterar conteudo
      </button>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc-2\"}", "Texto mais novo")}>
        Alterar novamente
      </button>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc-d\"}", "Texto local D")}>
        Alterar para D
      </button>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc\",\"content\":[]}", "")}>
        Apagar conteudo
      </button>
      <button
        type="button"
        onClick={() =>
          onContentChange(sourceSceneId, sceneForPlanning.contentJson ?? "", sceneForPlanning.contentText ?? "")
        }
      >
        Desfazer para A
      </button>
    </div>
  ),
}));

vi.mock("@/features/scenes/components/scene-version-history-panel", () => ({
  SceneVersionHistoryPanel: () => <div />,
}));

const analysisResult = {
  summary: "Resumo da análise",
  tone: "Tenso",
  pacing: "Crescente",
  strengths: ["Conflito claro"],
  issues: ["Transição rápida"],
  suggestions: ["Expandir a reação"],
};

describe("SceneEditor content save contract", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID.mockReset().mockReturnValue("operation-id");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.analyzeScene.mockResolvedValue(analysisResult);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("autosave sends expectedContentRevision operationId and AUTO_SAVE", async () => {
    mocks.randomUUID.mockReturnValueOnce("operation-autosave");
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        source: "AUTO_SAVE",
        expectedContentRevision: 3,
        operationId: "operation-autosave",
      });
    });
  });

  test("manual save sends expectedContentRevision operationId and MANUAL_SAVE", async () => {
    mocks.randomUUID.mockReturnValueOnce("operation-manual");
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: sceneForPlanning.contentJson,
        contentText: sceneForPlanning.contentText,
        source: "MANUAL_SAVE",
        expectedContentRevision: 3,
        operationId: "operation-manual",
      });
    });
  });

  test("cancels queued autosave when a conflicting newer revision becomes pending", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    vi.useFakeTimers();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Conteúdo remoto C",
        contentRevision: 2,
      });
      await vi.advanceTimersByTimeAsync(2400);
    });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByText(/versão salva mais recente/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.queryByText("Erro ao salvar")).not.toBeInTheDocument();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("blocks autosave while outdated and resumes after accepting the pending revision", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent.mockResolvedValueOnce({
      ...sceneForPlanning,
      contentJson: "{\"type\":\"doc-d\"}",
      contentText: "Texto local D",
      contentRevision: 3,
    });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    vi.useFakeTimers();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Conteúdo remoto C",
        contentRevision: 2,
      });
      await vi.advanceTimersByTimeAsync(0);
    });
    fireEvent.click(screen.getByRole("button", { name: "Alterar para D" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2400);
    });
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Desfazer para A" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Conteúdo remoto C");

    fireEvent.click(screen.getByRole("button", { name: "Alterar para D" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, expect.objectContaining({
        contentJson: "{\"type\":\"doc-d\"}",
        contentText: "Texto local D",
        source: "AUTO_SAVE",
        expectedContentRevision: 2,
      }));
    });
  });

  test.each(["", "   "])("treats saved content text %p as empty for AI analysis", async (savedContentText) => {
    mocks.getScene.mockResolvedValueOnce({
      ...sceneForPlanning,
      contentJson: "{\"type\":\"doc\",\"content\":[]}",
      contentText: savedContentText,
      contentRevision: 1,
      wordCount: 0,
    });
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    const analyzeButton = screen.getByRole("button", { name: "Analisar com IA" });

    expect(analyzeButton).toBeDisabled();
    expect(screen.getByText("Escreva algum conteúdo na cena antes de solicitar uma análise.")).toBeInTheDocument();
    fireEvent.submit(analyzeButton.closest("form") as HTMLFormElement);
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("keeps local deletion dirty until saved and re-enables analysis after non-empty content is saved", async () => {
    mocks.updateSceneContent
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\",\"content\":[]}",
        contentText: "",
        contentRevision: 4,
        wordCount: 0,
      })
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 5,
      });
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));

    fireEvent.click(screen.getByRole("button", { name: "Apagar conteudo" }));
    expect(screen.getByText("Salve as alterações antes de analisar a versão mais recente.")).toBeInTheDocument();
    expect(screen.queryByText("Escreva algum conteúdo na cena antes de solicitar uma análise.")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Salvar conteúdo/ }));
    expect(await screen.findByText("Escreva algum conteúdo na cena antes de solicitar uma análise.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: /Salvar conteúdo/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("metadata update preserves dirty visible content until that content is saved", async () => {
    const metadataUpdate = createDeferred<typeof sceneForPlanning>();
    const contentSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateScene.mockReturnValueOnce(metadataUpdate.promise);
    mocks.updateSceneContent.mockReturnValueOnce(contentSave.promise);
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto da cena");
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    vi.useFakeTimers();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText("Digitando...")).toBeInTheDocument();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Título"), { target: { value: "Título atualizado" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar cena" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(mocks.updateScene).toHaveBeenCalledTimes(1);

    await act(async () => {
      metadataUpdate.resolve({
        ...sceneForPlanning,
        title: "Título atualizado",
        contentRevision: 3,
      });
      await metadataUpdate.promise;
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByRole("heading", { name: "Título atualizado" })).toBeInTheDocument();

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText("Digitando...")).toBeInTheDocument();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    vi.useRealTimers();
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        source: "MANUAL_SAVE",
        expectedContentRevision: 3,
        operationId: expect.any(String),
      });
    });
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    await act(async () => {
      contentSave.resolve({
        ...sceneForPlanning,
        title: "Título atualizado",
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 4,
      });
      await contentSave.promise;
    });

    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByText("Salvo")).toBeInTheDocument();
  });

  test("delayed older revision cannot roll back content after a successful save", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
      })
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 3,
      });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Resposta atrasada",
        contentRevision: 1,
      });
    });
    await screen.findByRole("heading", { name: "Resposta atrasada" });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar novamente" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        source: "MANUAL_SAVE",
        expectedContentRevision: 2,
        operationId: expect.any(String),
      });
    });
  });

  test("same revision updates metadata without rehydrating content", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
      })
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 3,
      });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => expect(screen.getByText("Salvo")).toBeInTheDocument());
    const contentKey = screen.getByTestId("editor-content").getAttribute("data-content-key");
    expect(contentKey).not.toBeNull();

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Metadados da mesma revisão",
        contentRevision: 2,
      });
    });
    await screen.findByRole("heading", { name: "Metadados da mesma revisão" });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByTestId("editor-content")).toHaveAttribute("data-content-key", contentKey as string);

    fireEvent.click(screen.getByRole("button", { name: "Alterar novamente" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, expect.objectContaining({
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        expectedContentRevision: 2,
      }));
    });
  });

  test("newer revision hydrates clean content and advances the editor key", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
      })
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-d\"}",
        contentText: "Texto local D",
        contentRevision: 4,
      });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    const contentKey = screen.getByTestId("editor-content").getAttribute("data-content-key");
    expect(contentKey).not.toBeNull();

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Revisão remota mais nova",
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 3,
      });
    });
    await screen.findByRole("heading", { name: "Revisão remota mais nova" });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto mais novo");
    expect(screen.getByTestId("editor-content")).not.toHaveAttribute("data-content-key", contentKey as string);
    expect(screen.getByText("Salvo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar para D" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, expect.objectContaining({
        contentJson: "{\"type\":\"doc-d\"}",
        contentText: "Texto local D",
        expectedContentRevision: 3,
      }));
    });
  });

  test("accepts a pending newer revision when local edits return to the saved baseline", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    vi.useFakeTimers();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 2,
      });
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText(/versão salva mais recente/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Desfazer para A" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    vi.useRealTimers();

    await waitFor(() => expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto mais novo"));
    expect(screen.getByText("Salvo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("accepts a newer revision matching dirty local content without remounting or autosaving", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent.mockResolvedValueOnce({
      ...sceneForPlanning,
      contentJson: "{\"type\":\"doc-2\"}",
      contentText: "Texto mais novo",
      contentRevision: 3,
    });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    const contentKey = screen.getByTestId("editor-content").getAttribute("data-content-key");
    vi.useFakeTimers();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
      });
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByTestId("editor-content")).toHaveAttribute("data-content-key", contentKey as string);
    expect(screen.getByText("Salvo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1200);
    });
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar novamente" }));
    fireEvent.click(screen.getByRole("button", { name: /Salvar conteúdo/ }));
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, expect.objectContaining({
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        expectedContentRevision: 2,
      }));
    });
  });

  test("recovers from a failed save when the pending remote snapshot matches visible content", async () => {
    const contentSave = createDeferred<Scene>();
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent.mockReturnValueOnce(contentSave.promise);
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: /Salvar conteúdo/ }));
    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));

    await act(async () => {
      queryClient.setQueryData<Scene>(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
        wordCount: 2,
      });
    });

    await act(async () => {
      contentSave.reject(new Error("save failed"));
      await contentSave.promise.catch(() => undefined);
    });

    await waitFor(() => expect(screen.getByText("Salvo")).toBeInTheDocument());
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
    expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
  });

  test("clears analysis when a matching newer revision is accepted", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    fireEvent.change(screen.getByLabelText(/Foco/), { target: { value: "ritmo" } });
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentRevision: 2,
      });
    });

    await waitFor(() => expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument());
    expect(screen.getByLabelText(/Foco/)).toHaveValue("ritmo");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });

  test("keeps the highest pending remote revision after a later older response", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: /Expandir análise/ }));
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-3\"}",
        contentText: "Conteúdo remoto da revisão 3",
        contentRevision: 3,
      });
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Conteúdo remoto atrasado",
        contentRevision: 2,
      });
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText(/versão salva mais recente/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Desfazer para A" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(screen.getByTestId("editor-content")).toHaveTextContent("Conteúdo remoto da revisão 3");
    });
    expect(screen.queryByText("Conteúdo remoto atrasado")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });

  test("writes an accepted highest pending snapshot back to the scene cache", async () => {
    const sceneTwo: Scene = {
      ...sceneForPlanning,
      id: "scene-2",
      title: "Outra cena",
      contentText: "Conteúdo da outra cena",
      contentRevision: 1,
    };
    const stalledRefetch = createDeferred<Scene>();
    mocks.getScene
      .mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1, wordCount: 3 })
      .mockResolvedValueOnce(sceneTwo)
      .mockReturnValueOnce(stalledRefetch.promise);
    const { queryClient, rerender } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    await act(async () => {
      queryClient.setQueryData<Scene>(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-3\"}",
        contentText: "Conteúdo remoto da revisão 3",
        contentRevision: 3,
        wordCount: 33,
        updatedAt: "2026-06-27T12:03:00Z",
      });
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      queryClient.setQueryData<Scene>(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Metadados mais recentes",
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Conteúdo remoto atrasado",
        contentRevision: 2,
        wordCount: 22,
        updatedAt: "2026-06-27T12:02:00Z",
      });
      await vi.advanceTimersByTimeAsync(0);
    });

    fireEvent.click(screen.getByRole("button", { name: "Desfazer para A" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    vi.useRealTimers();

    const cachedScene = queryClient.getQueryData<Scene>(queryKeys.scene(sceneForPlanning.id));
    expect(cachedScene).toEqual(expect.objectContaining({
      title: "Metadados mais recentes",
      contentJson: "{\"type\":\"doc-3\"}",
      contentText: "Conteúdo remoto da revisão 3",
      contentRevision: 3,
      wordCount: 33,
      updatedAt: "2026-06-27T12:03:00Z",
    }));

    rerender(
      <SceneEditor bookId="book-1" sceneId={sceneTwo.id} canMutateStructure onSceneDeleted={vi.fn()} />
    );
    await screen.findByRole("heading", { name: sceneTwo.title });
    rerender(
      <SceneEditor bookId="book-1" sceneId={sceneForPlanning.id} canMutateStructure onSceneDeleted={vi.fn()} />
    );

    await waitFor(() => {
      expect(screen.getByTestId("editor-content")).toHaveTextContent("Conteúdo remoto da revisão 3");
    });
    expect(screen.getByText("33 palavras")).toBeInTheDocument();
    expect(queryClient.getQueryData<Scene>(queryKeys.scene(sceneForPlanning.id))?.contentRevision).toBe(3);
  });

  test("newer revision preserves dirty local content and accepted revision", async () => {
    mocks.getScene.mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 1 });
    mocks.updateSceneContent
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 2,
      })
      .mockRejectedValueOnce(new Error("revision conflict"));
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    const contentKey = screen.getByTestId("editor-content").getAttribute("data-content-key");
    expect(contentKey).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Alterar para D" }));

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Revisão remota durante edição",
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 3,
      });
    });
    await screen.findByRole("heading", { name: "Revisão remota durante edição" });

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto local D");
    expect(screen.getByTestId("editor-content")).toHaveAttribute("data-content-key", contentKey as string);
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, expect.objectContaining({
        contentJson: "{\"type\":\"doc-d\"}",
        contentText: "Texto local D",
        expectedContentRevision: 2,
      }));
    });
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
  });

  test("same-scene refresh cannot disrupt a pending save or a newer local edit", async () => {
    const firstSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateSceneContent
      .mockReturnValueOnce(firstSave.promise)
      .mockResolvedValueOnce({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        contentRevision: 5,
      });
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));

    await act(async () => {
      queryClient.setQueryData(queryKeys.scene(sceneForPlanning.id), {
        ...sceneForPlanning,
        title: "Cena atualizada externamente",
        contentRevision: 99,
      });
    });
    await screen.findByRole("heading", { name: "Cena atualizada externamente" });
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar novamente" }));
    await act(async () => {
      firstSave.resolve({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 4,
      });
      await firstSave.promise;
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByText(/versão salva mais recente/)).toBeInTheDocument();
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto mais novo");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));
    vi.useRealTimers();
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        source: "MANUAL_SAVE",
        expectedContentRevision: 4,
        operationId: expect.any(String),
      });
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
  });

  test("successful response updates revision refs for later saves", async () => {
    mocks.randomUUID
      .mockReturnValueOnce("operation-first")
      .mockReturnValueOnce("operation-second");
    mocks.updateSceneContent
      .mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 4 })
      .mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 5 });
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenLastCalledWith(
        sceneForPlanning.id,
        expect.objectContaining({
          expectedContentRevision: 4,
          operationId: "operation-second",
        })
      );
    });
  });

  test("failed autosave uses one operationId and is not retried by the editor", async () => {
    mocks.randomUUID.mockReturnValueOnce("operation-autosave");
    mocks.updateSceneContent.mockRejectedValueOnce(new Error("network down"));
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });
    expect(mocks.randomUUID).toHaveBeenCalledTimes(1);
    expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, expect.objectContaining({
      operationId: "operation-autosave",
    }));
  });

  test("failed content save disables AI analysis", async () => {
    mocks.updateSceneContent.mockRejectedValueOnce(new Error("network down"));
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));

    expect(
      await screen.findByText("O conteúdo mais recente não foi salvo. Salve novamente antes de analisar.")
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("AI analysis follows dirty saving and saved content transitions", async () => {
    const inFlightSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateSceneContent.mockReturnValueOnce(inFlightSave.promise);
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText("Salve as alterações antes de analisar a versão mais recente.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));

    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("O conteúdo está sendo salvo. Aguarde para analisar.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    await act(async () => {
      inFlightSave.resolve({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 4,
      });
      await inFlightSave.promise;
    });

    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    expect(screen.getByText("A IA analisa a última versão salva.")).toBeInTheDocument();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
    expect(mocks.updateScene).not.toHaveBeenCalled();
  });

  test("editing aborts an in-flight analysis and ignores its response", async () => {
    const pendingAnalysis = createDeferred<typeof analysisResult>();
    mocks.analyzeScene.mockReturnValueOnce(pendingAnalysis.promise);
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
    const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    expect(signal.aborted).toBe(true);
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    await act(async () => pendingAnalysis.resolve(analysisResult));
    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  test("editing after a successful analysis clears the result without saving immediately", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  test("AI analysis does not save metadata or content and does not schedule autosave", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));

    expect(await screen.findByText("Resumo da análise")).toBeInTheDocument();
    expect(mocks.analyzeScene).toHaveBeenCalledWith(sceneForPlanning.id, {}, expect.any(AbortSignal));

    vi.useFakeTimers();
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(mocks.updateScene).not.toHaveBeenCalled();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });
});

describe("SceneEditor pending autosave versus the scene's projected authority", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID.mockReset().mockReturnValue("operation-id");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.analyzeScene.mockResolvedValue(analysisResult);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("um autosave agendado nao dispara depois que a autoridade sobre o conteudo desaparece", async () => {
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    // The server stops projecting authority over this scene's text while the timer is still pending.
    // The revision does not move, so nothing else in the editor reacts to the refetch.
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3, canEditContent: false });
    await refetchScene(queryClient);

    // The surface itself already gave the answer up: the save control is gone.
    expect(screen.queryByRole("button", { name: /Salvar conte.do/ })).not.toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    // Scheduling checked the authority; firing must check it again, or the browser dispatches a
    // mutation under an authority the server has already withdrawn.
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("um autosave agendado nao dispara depois que o refetch da cena falha", async () => {
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    // A failed refetch keeps the cached scene in TanStack Query, so the authority it carries is no
    // longer a currently confirmed projection: it is unknown, not granted.
    mocks.getScene.mockRejectedValue(new Error("network down"));
    await refetchScene(queryClient);

    // The editor is already showing the failure rather than the writable scene.
    expect(screen.getByText(/N.o foi poss.vel carregar a cena/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Salvar conte.do/ })).not.toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("um autosave que dispara junto com a revogacao revalida antes de despachar", async () => {
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    // Deliberately not flushing the query notification: the withdrawal and the autosave land in the
    // same tick, so React has not re-rendered and nothing has had the chance to cancel the timer.
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3, canEditContent: false });
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: queryKeys.scene(sceneForPlanning.id) }).catch(() => undefined);
    });
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    // The dispatch point has to ask the projection again; the render it was scheduled from still
    // says the user may write.
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  // Control: with the authority still projected, the very same edit and the very same refetch still
  // reach the server. Without it, an editor that simply stopped autosaving would look identical to
  // the two assertions above.
  test("com a autoridade preservada o mesmo autosave continua sendo enviado", async () => {
    mocks.randomUUID.mockReturnValueOnce("operation-autosave");
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3, canEditContent: true });
    await refetchScene(queryClient);

    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        source: "AUTO_SAVE",
        expectedContentRevision: 3,
        operationId: "operation-autosave",
      });
    });
  });
});

/**
 * Refetches the scene and lets the editor actually observe the answer. TanStack Query delivers query
 * updates through a zero-delay timeout, so under fake timers the new projection would otherwise only
 * reach React in the same tick that fires the autosave — the editor would never have had the chance
 * to react to the authority change before dispatching.
 */
async function refetchScene(queryClient: QueryClient) {
  await act(async () => {
    await queryClient.refetchQueries({ queryKey: queryKeys.scene(sceneForPlanning.id) }).catch(() => undefined);
  });
  await act(async () => {
    vi.advanceTimersByTime(0);
  });
}

function renderEditor() {
  return renderWithClient(
    <SceneEditor
      bookId="book-1"
      sceneId={sceneForPlanning.id}
      canMutateStructure
      onSceneDeleted={vi.fn()}
    />
  );
}

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

describe("SceneEditor delayed save response versus the newest projected authority", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID.mockReset().mockReturnValue("operation-id");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.analyzeScene.mockResolvedValue(analysisResult);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("uma resposta de save iniciada sob a concessao antiga nao ressuscita a autoridade retirada", async () => {
    const deferredSave = createDeferred<Scene>();
    mocks.updateSceneContent.mockImplementation(() => deferredSave.promise);
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    // The save leaves under an authority the server was still projecting.
    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });

    // While it is in flight, a newer projection withdraws the authority over this scene's text.
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3, canEditContent: false });
    await refetchSceneWithRealTimers(queryClient);
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: /Salvar conte.do/ })).not.toBeInTheDocument();
    });

    // Only now does the older response land, still carrying the grant it was decided with.
    await act(async () => {
      deferredSave.resolve({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
      await deferredSave.promise;
    });

    // Content and authority do not share a clock: reconciling the saved text must not put back an
    // action the newest projection has already invalidated.
    expect(queryClient.getQueryData<Scene>(queryKeys.scene(sceneForPlanning.id))?.canEditContent).toBe(false);
    expect(screen.getByText("Somente leitura")).toBeInTheDocument();
    // Both labels of the same control, so a save still in flight cannot satisfy this by being named
    // "Salvando..." at the moment the assertion runs.
    expect(screen.queryByRole("button", { name: /Salvar conte.do|Salvando/ })).not.toBeInTheDocument();
  });

  test("uma resposta de save atrasada nao transforma a falha de refetch em concessao", async () => {
    const deferredSave = createDeferred<Scene>();
    mocks.updateSceneContent.mockImplementation(() => deferredSave.promise);
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });

    // A failed refetch leaves the authority unknown, not granted.
    mocks.getScene.mockRejectedValue(new Error("network down"));
    await refetchSceneWithRealTimers(queryClient);
    await waitFor(() => {
      expect(screen.getByText(/N.o foi poss.vel carregar a cena/)).toBeInTheDocument();
    });

    await act(async () => {
      deferredSave.resolve({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
      await deferredSave.promise;
    });

    // Writing the response into the cache would turn the failure back into a success carrying the
    // old grant, so the editor would reopen on an authority nothing has confirmed since.
    expect(queryClient.getQueryState<Scene>(queryKeys.scene(sceneForPlanning.id))?.status).toBe("error");
    expect(screen.getByText(/N.o foi poss.vel carregar a cena/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Salvar conte.do|Salvando/ })).not.toBeInTheDocument();
  });

  // Control: with the authority still projected, the very same delayed response still reconciles
  // content and revision. Without it, a surface that simply stopped applying save responses would
  // satisfy the two assertions above just as well.
  test("com a autoridade preservada a mesma resposta atrasada reconcilia conteudo e revision", async () => {
    const deferredSave = createDeferred<Scene>();
    mocks.updateSceneContent.mockImplementationOnce(() => deferredSave.promise);
    const { queryClient } = renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });

    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3, canEditContent: true });
    await refetchSceneWithRealTimers(queryClient);

    await act(async () => {
      deferredSave.resolve({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
      await deferredSave.promise;
    });

    expect(await screen.findByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();
    expect(queryClient.getQueryData<Scene>(queryKeys.scene(sceneForPlanning.id))?.contentRevision).toBe(4);
    expect(queryClient.getQueryData<Scene>(queryKeys.scene(sceneForPlanning.id))?.canEditContent).toBe(true);

    // The accepted revision moved with the response, so the next save is sent against it.
    mocks.randomUUID.mockReturnValueOnce("operation-after-delayed-response");
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Texto mais novo", contentRevision: 5 });
    fireEvent.click(screen.getByRole("button", { name: "Alterar novamente" }));
    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenLastCalledWith(sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc-2\"}",
        contentText: "Texto mais novo",
        source: "MANUAL_SAVE",
        expectedContentRevision: 4,
        operationId: "operation-after-delayed-response",
      });
    });
  });
});

/**
 * Refetches the scene under real timers and lets the editor observe the answer, so the newer
 * projection is already in place when the older save response lands.
 */
async function refetchSceneWithRealTimers(queryClient: QueryClient) {
  await act(async () => {
    await queryClient.refetchQueries({ queryKey: queryKeys.scene(sceneForPlanning.id) }).catch(() => undefined);
  });
}
