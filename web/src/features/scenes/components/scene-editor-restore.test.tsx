import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import type { SceneVersionRestoreMode } from "@/features/scenes/components/scene-version-history-panel";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

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
    sourceSceneId,
    contentText,
    onContentChange,
  }: {
    sourceSceneId: string;
    contentText: string;
    onContentChange: (sceneId: string, contentJson: string, contentText: string) => void;
  }) => (
    <div>
      <p data-testid="editor-content">{contentText}</p>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc\"}", "Novo texto")}>
        Alterar conteudo
      </button>
    </div>
  ),
}));

vi.mock("@/features/scenes/components/scene-version-history-panel", () => ({
  SceneVersionHistoryPanel: ({
    onRestoreVersion,
    contentSaveInFlight,
  }: {
    contentSaveInFlight: boolean;
    onRestoreVersion: (versionId: string, mode: SceneVersionRestoreMode) => Promise<void>;
  }) => (
    <div>
      <button type="button" onClick={() => void onRestoreVersion("version-1", "RESTORE")}>
        Restore clean
      </button>
      <button type="button" onClick={() => void onRestoreVersion("version-1", "SAVE_AND_RESTORE")}>
        Save and restore
      </button>
      <button type="button" disabled={contentSaveInFlight} onClick={() => void onRestoreVersion("version-1", "DISCARD_AND_RESTORE")}>
        Discard and restore
      </button>
    </div>
  ),
}));

describe("SceneEditor restore orchestration", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID
      .mockReturnValueOnce("save-operation")
      .mockReturnValueOnce("restore-operation")
      .mockReturnValue("later-operation");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue({ ...sceneForPlanning, contentText: "Texto restaurado", contentRevision: 8 });
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.analyzeScene.mockResolvedValue({
      summary: "Resumo da análise",
      tone: "Tenso",
      pacing: "Crescente",
      strengths: ["Conflito claro"],
      issues: ["Transição rápida"],
      suggestions: ["Expandir a reação"],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("discard and restore is allowed when only a queued autosave exists and cancels it", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    openHistory();
    fireEvent.click(screen.getByRole("button", { name: "Discard and restore" }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(mocks.restoreSceneVersion).toHaveBeenCalledWith(sceneForPlanning.id, "version-1", {
      expectedContentRevision: 3,
      operationId: "save-operation",
    });
    expect(mocks.randomUUID).toHaveBeenCalledTimes(1);

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("discard and restore is disabled while a content save is in flight", async () => {
    const inFlightSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateSceneContent.mockReturnValueOnce(inFlightSave.promise);

    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();
    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));

    openHistory();
    const discardButton = screen.getByRole("button", { name: "Discard and restore" });
    expect(discardButton).toBeDisabled();
    fireEvent.click(discardButton);
    expect(mocks.restoreSceneVersion).not.toHaveBeenCalled();
    await act(async () => {
      inFlightSave.resolve({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
      await inFlightSave.promise;
    });
  });

  test("save and restore stays available during an in-flight save and waits safely", async () => {
    const inFlightSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateSceneContent
      .mockReturnValueOnce(inFlightSave.promise)
      .mockResolvedValueOnce({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });

    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();
    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));

    openHistory();
    fireEvent.click(screen.getByRole("button", { name: "Save and restore" }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(mocks.restoreSceneVersion).not.toHaveBeenCalled();

    inFlightSave.resolve({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenNthCalledWith(2, sceneForPlanning.id, expect.objectContaining({
        source: "MANUAL_SAVE",
        expectedContentRevision: 4,
        operationId: "restore-operation",
      }));
      expect(mocks.restoreSceneVersion).toHaveBeenCalledWith(sceneForPlanning.id, "version-1", {
        expectedContentRevision: 4,
        operationId: expect.any(String),
      });
    });
  });
});

function openHistory() {
  fireEvent.click(screen.getByRole("button", { name: /Hist/ }));
}

function renderEditor() {
  renderWithClient(
    <SceneEditor
      bookId="book-1"
      sceneId={sceneForPlanning.id}
      canMutateStructure
      canEditContent
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
