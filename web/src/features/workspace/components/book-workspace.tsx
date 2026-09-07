"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { getBook } from "@/features/books/api/books-api";
import { CharactersPanel } from "@/features/characters/components/characters-panel";
import type { DashboardWorkspaceTab } from "@/features/dashboard/components/dashboard-detail-modal";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { ExportManuscriptButton } from "@/features/export/components/export-manuscript-button";
import { ItemsPanel } from "@/features/items/components/items-panel";
import { SceneKanbanPanel } from "@/features/kanban/components/scene-kanban-panel";
import { LocationsPanel } from "@/features/locations/components/locations-panel";
import { NotebookPanel } from "@/features/notebook/components/notebook-panel";
import { getOutline } from "@/features/outline/api/outline-api";
import { OutlineSidebar } from "@/features/outline/components/outline-sidebar";
import type { BookOutline } from "@/features/outline/types";
import { SceneEditor, type PlanningPanelOpenIntent } from "@/features/scenes/components/scene-editor";
import { SceneStoryboardPanel } from "@/features/storyboard/components/scene-storyboard-panel";
import { queryKeys } from "@/lib/query/keys";

type WorkspaceMode =
  | "overview"
  | "storyboard"
  | "kanban"
  | "scenes"
  | "characters"
  | "locations"
  | "items"
  | "notebook";
const FOCUS_MODE_STORAGE_KEY = "iwrite.focusMode.enabled";

type BookWorkspaceProps = {
  bookId: string;
  initialSceneId?: string;
};

function readStoredFocusMode() {
  if (typeof window === "undefined") {
    return false;
  }

  try {
    return window.localStorage.getItem(FOCUS_MODE_STORAGE_KEY) === "true";
  } catch {
    return false;
  }
}

function writeStoredFocusMode(isEnabled: boolean) {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.localStorage.setItem(FOCUS_MODE_STORAGE_KEY, String(isEnabled));
  } catch {
    // localStorage can be blocked; focus mode still works for the current session.
  }
}

function isKeyboardEventFromInteractiveTarget(target: EventTarget | null, allowContentEditable: boolean) {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  const tagName = target.tagName.toLowerCase();
  const isFormControl = tagName === "input" || tagName === "textarea" || tagName === "select";
  const isButtonLike = tagName === "button" || tagName === "a";
  const isInModal = Boolean(target.closest('[role="dialog"], [aria-modal="true"]'));

  return isFormControl || isButtonLike || isInModal || (!allowContentEditable && target.isContentEditable);
}

function outlineHasScene(outline: BookOutline, sceneId: string) {
  return outline.sections.some((section) =>
    section.chapters.some((chapter) => chapter.scenes.some((scene) => scene.id === sceneId))
  );
}

export function BookWorkspace({ bookId, initialSceneId }: BookWorkspaceProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const hasObservedInitialSearchParamsRef = useRef(false);
  const lastClearedInvalidSceneRef = useRef<string | null>(null);
  const [selectedSceneId, setSelectedSceneId] = useState<string | null>(initialSceneId ?? null);
  const [mode, setMode] = useState<WorkspaceMode>("scenes");
  const [isFocusMode, setIsFocusMode] = useState(false);
  const [isFullscreenAvailable, setIsFullscreenAvailable] = useState(false);
  const [isFullscreenActive, setIsFullscreenActive] = useState(false);
  const [planningPanelOpenIntent, setPlanningPanelOpenIntent] = useState<PlanningPanelOpenIntent | null>(null);
  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });
  const bookQuery = useQuery({
    queryKey: queryKeys.book(bookId),
    queryFn: () => getBook(bookId),
  });

  const outline = outlineQuery.data;
  // Effective access as the backend derived it. Until it arrives, the workspace presents the
  // read-only surface: a control that turns out to be unauthorized is worse than one that appears a
  // moment late, and the server authorizes every request again either way.
  const capabilities = bookQuery.data?.capabilities;
  const contextualCapabilities = bookQuery.data?.contextualCapabilities;
  const canMutateStructure = Boolean(capabilities?.includes("MUTATE_MANUSCRIPT_STRUCTURE"));
  // EDIT_AUTHORED_CONTRIBUTION is contextual for the roles that hold it at all: book scope makes a
  // user eligible to attempt a content save, and the backend still decides it per scene. Offering the
  // editor to an eligible user is the contract; hiding it would make the browser guess the predicate.
  const canEditSceneContent = Boolean(
    capabilities?.includes("EDIT_AUTHORED_CONTRIBUTION") || contextualCapabilities?.includes("EDIT_AUTHORED_CONTRIBUTION")
  );
  const isScenesFocusMode = mode === "scenes" && isFocusMode;

  const exitNativeFullscreen = useCallback(() => {
    if (typeof document === "undefined" || !document.fullscreenElement || !document.exitFullscreen) {
      return;
    }

    void document.exitFullscreen().catch(() => undefined);
  }, []);

  const handleEnterFocusMode = useCallback(() => {
    if (mode !== "scenes" || !selectedSceneId) {
      return;
    }

    setIsFocusMode(true);
    writeStoredFocusMode(true);
  }, [mode, selectedSceneId]);

  const handleExitFocusMode = useCallback(() => {
    setIsFocusMode(false);
    writeStoredFocusMode(false);
    exitNativeFullscreen();
  }, [exitNativeFullscreen]);

  const handleToggleFocusMode = useCallback(() => {
    if (mode !== "scenes" || !selectedSceneId) {
      return;
    }

    if (isFocusMode) {
      handleExitFocusMode();
      return;
    }

    handleEnterFocusMode();
  }, [handleEnterFocusMode, handleExitFocusMode, isFocusMode, mode, selectedSceneId]);

  const handleToggleFullscreen = useCallback(() => {
    if (typeof document === "undefined") {
      return;
    }

    if (document.fullscreenElement) {
      exitNativeFullscreen();
      return;
    }

    const root = document.documentElement;
    if (!root.requestFullscreen) {
      return;
    }

    void root.requestFullscreen().catch(() => undefined);
  }, [exitNativeFullscreen]);

  function handleModeChange(nextMode: WorkspaceMode) {
    setMode(nextMode);
    if (nextMode !== "scenes") {
      handleExitFocusMode();
    }
  }

  const handleSelectScene = useCallback(
    (sceneId: string | null) => {
      setSelectedSceneId(sceneId);

      const href = sceneId ? `/books/${bookId}?sceneId=${encodeURIComponent(sceneId)}` : `/books/${bookId}`;
      router.replace(href, { scroll: false });
    },
    [bookId, router]
  );

  const handleOpenSceneInEditor = useCallback(
    (sceneId: string) => {
      setMode("scenes");
      handleSelectScene(sceneId);
    },
    [handleSelectScene]
  );

  const handleOpenScenePlanning = useCallback(
    (sceneId: string) => {
      setMode("scenes");
      setPlanningPanelOpenIntent((intent) => ({
        sceneId,
        requestId: (intent?.requestId ?? 0) + 1,
      }));
      handleSelectScene(sceneId);
    },
    [handleSelectScene]
  );

  const handleOpenWorkspaceTab = useCallback(
    (tab: DashboardWorkspaceTab) => {
      setMode(tab);
      handleExitFocusMode();
    },
    [handleExitFocusMode]
  );

  function handleSceneDeleted() {
    handleSelectScene(null);
    handleExitFocusMode();
  }

  useEffect(() => {
    if (!hasObservedInitialSearchParamsRef.current) {
      hasObservedInitialSearchParamsRef.current = true;
      return;
    }

    const nextSceneId = searchParams.get("sceneId");
    setSelectedSceneId(nextSceneId && nextSceneId.trim() ? nextSceneId : null);
  }, [searchParams]);

  useEffect(() => {
    if (!outline) {
      return;
    }

    const sceneIdFromUrl = searchParams.get("sceneId");
    const currentUrlSceneId = sceneIdFromUrl && sceneIdFromUrl.trim() ? sceneIdFromUrl : null;

    if (!currentUrlSceneId) {
      lastClearedInvalidSceneRef.current = null;
      return;
    }

    if (outlineHasScene(outline, currentUrlSceneId)) {
      lastClearedInvalidSceneRef.current = null;
      setSelectedSceneId((previousSceneId) =>
        previousSceneId === currentUrlSceneId ? previousSceneId : currentUrlSceneId
      );
      return;
    }

    setSelectedSceneId(null);
    const invalidSceneKey = `${bookId}:${currentUrlSceneId}`;
    if (lastClearedInvalidSceneRef.current === invalidSceneKey) {
      return;
    }

    lastClearedInvalidSceneRef.current = invalidSceneKey;
    router.replace(`/books/${bookId}`, { scroll: false });
  }, [bookId, outline, router, searchParams]);

  useEffect(() => {
    if (mode === "scenes" && selectedSceneId && readStoredFocusMode()) {
      setIsFocusMode(true);
    }
  }, [mode, selectedSceneId]);

  useEffect(() => {
    if (typeof document === "undefined") {
      return;
    }

    setIsFullscreenAvailable(
      typeof document.documentElement.requestFullscreen === "function" && typeof document.exitFullscreen === "function"
    );

    function handleFullscreenChange() {
      setIsFullscreenActive(Boolean(document.fullscreenElement));
    }

    handleFullscreenChange();
    document.addEventListener("fullscreenchange", handleFullscreenChange);
    return () => document.removeEventListener("fullscreenchange", handleFullscreenChange);
  }, []);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      const isFocusShortcut =
        event.ctrlKey && event.shiftKey && !event.altKey && !event.metaKey && event.key.toLowerCase() === "f";

      if (isFocusShortcut) {
        if (isKeyboardEventFromInteractiveTarget(event.target, true)) {
          return;
        }
        event.preventDefault();
        handleToggleFocusMode();
        return;
      }

      if (event.key === "Escape" && isScenesFocusMode && !isKeyboardEventFromInteractiveTarget(event.target, false)) {
        handleExitFocusMode();
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleExitFocusMode, handleToggleFocusMode, isScenesFocusMode]);

  return (
    <main className={`grid h-screen overflow-hidden bg-zinc-50 text-zinc-950 ${isScenesFocusMode ? "grid-rows-[1fr]" : "grid-rows-[64px_1fr]"}`}>
      {isScenesFocusMode ? null : (
      <header className="flex min-w-0 items-center justify-between gap-4 border-b border-zinc-200 bg-white px-4 shadow-sm shadow-zinc-200/60 md:px-6">
        <div className="flex min-w-0 items-center gap-4">
          <Link
            href="/library"
            className="inline-flex min-h-9 shrink-0 items-center justify-center rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 hover:text-zinc-950"
          >
            Voltar
          </Link>

          <div className="min-w-0">
            <p className="text-xs font-medium uppercase text-zinc-500">Workspace</p>
            <h1 className="truncate text-base font-semibold text-zinc-950 md:text-lg">
              {outline?.title ?? "Carregando livro..."}
            </h1>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <div className="flex rounded-md border border-zinc-200 bg-zinc-50 p-1">
            <Button
              type="button"
              size="sm"
              variant={mode === "overview" ? "primary" : "ghost"}
              onClick={() => handleModeChange("overview")}
            >
              Visão geral
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "storyboard" ? "primary" : "ghost"}
              onClick={() => handleModeChange("storyboard")}
            >
              Storyboard
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "kanban" ? "primary" : "ghost"}
              onClick={() => handleModeChange("kanban")}
            >
              Kanban
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "scenes" ? "primary" : "ghost"}
              onClick={() => handleModeChange("scenes")}
            >
              Cenas
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "characters" ? "primary" : "ghost"}
              onClick={() => handleModeChange("characters")}
            >
              Personagens
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "locations" ? "primary" : "ghost"}
              onClick={() => handleModeChange("locations")}
            >
              Localizações
            </Button>
            <Button type="button" size="sm" variant={mode === "items" ? "primary" : "ghost"} onClick={() => handleModeChange("items")}>
              Itens
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "notebook" ? "primary" : "ghost"}
              onClick={() => handleModeChange("notebook")}
            >
              Caderno
            </Button>
          </div>
          {typeof outline?.wordCount === "number" ? <Badge>{outline.wordCount} palavras</Badge> : null}
          <ExportManuscriptButton bookId={bookId} />
        </div>
      </header>
      )}

      <div className={`grid min-h-0 grid-cols-1 overflow-hidden ${isScenesFocusMode ? "" : "md:grid-cols-[340px_minmax(0,1fr)]"}`}>
        {mode === "scenes" && !isScenesFocusMode ? (
          <div className="min-h-0 overflow-hidden border-r border-zinc-200 bg-white">
            <OutlineSidebar
              bookId={bookId}
              selectedSceneId={selectedSceneId}
              canMutateStructure={canMutateStructure}
              onSelectScene={handleSelectScene}
            />
          </div>
        ) : null}

        <div className={`min-h-0 overflow-hidden bg-zinc-100/70 ${mode !== "scenes" || isScenesFocusMode ? "md:col-span-2" : ""}`}>
          {mode === "overview" ? (
            <BookDashboard
              bookId={bookId}
              onOpenSceneInEditor={handleOpenSceneInEditor}
              onOpenWorkspaceTab={handleOpenWorkspaceTab}
            />
          ) : mode === "storyboard" ? (
            <SceneStoryboardPanel
              outline={outline}
              isLoading={outlineQuery.isLoading}
              isError={outlineQuery.isError}
              onOpenSceneInEditor={handleOpenSceneInEditor}
            />
          ) : mode === "kanban" ? (
            <SceneKanbanPanel
              bookId={bookId}
              outline={outline}
              canMutateStructure={canMutateStructure}
              isLoading={outlineQuery.isLoading}
              isError={outlineQuery.isError}
              onOpenSceneInEditor={handleOpenSceneInEditor}
              onOpenScenePlanning={handleOpenScenePlanning}
            />
          ) : mode === "scenes" ? (
            <SceneEditor
              bookId={bookId}
              sceneId={selectedSceneId}
              canMutateStructure={canMutateStructure}
              canEditContent={canEditSceneContent}
              isFocusMode={isScenesFocusMode}
              isFullscreenAvailable={isFullscreenAvailable}
              isFullscreenActive={isFullscreenActive}
              onEnterFocusMode={handleEnterFocusMode}
              onExitFocusMode={handleExitFocusMode}
              onToggleFullscreen={handleToggleFullscreen}
              onSceneDeleted={handleSceneDeleted}
              planningPanelOpenIntent={planningPanelOpenIntent}
            />
          ) : mode === "characters" ? (
            <CharactersPanel bookId={bookId} />
          ) : mode === "locations" ? (
            <LocationsPanel bookId={bookId} />
          ) : mode === "items" ? (
            <ItemsPanel bookId={bookId} />
          ) : (
            <NotebookPanel bookId={bookId} />
          )}
        </div>
      </div>
    </main>
  );
}
