"use client";

import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { deleteScene, getScene, restoreSceneVersion, updateScene, updateSceneContent } from "@/features/scenes/api/scenes-api";
import {
  SceneAiAnalysisPanel,
  type SceneContentSyncState,
} from "@/features/scenes/components/scene-ai-analysis-panel";
import { SceneContentEditor } from "@/features/scenes/components/scene-content-editor";
import { SceneEditorHeader, type ContentSaveStatus } from "@/features/scenes/components/scene-editor-header";
import { SceneEmptyState } from "@/features/scenes/components/scene-empty-state";
import { SceneMetadataForm } from "@/features/scenes/components/scene-metadata-form";
import { ScenePlanningPanel } from "@/features/scenes/components/scene-planning-panel";
import { SceneVersionHistoryPanel, type SceneVersionRestoreMode } from "@/features/scenes/components/scene-version-history-panel";
import { applySceneMutationResponse, updateSceneProjection } from "@/features/scenes/cache/apply-scene-mutation-response";
import type { Scene, SceneStatus, SceneVersionSource } from "@/features/scenes/types";
import { trackEvent } from "@/lib/analytics/analytics";
import { queryKeys } from "@/lib/query/keys";

const SCENE_STATUSES: SceneStatus[] = ["IDEA", "PLANNED", "DRAFT", "WRITTEN", "REVISED", "FINAL"];
const METADATA_FORM_ID = "scene-metadata-form";
const PLANNING_FORM_ID = "scene-planning-form";
const CONTENT_AUTOSAVE_DELAY_MS = 1200;
const PLANNING_PANEL_STORAGE_KEY = "iwrite.scenePlanningPanelOpen";

type SceneEditorProps = {
  bookId: string;
  sceneId: string | null;
  /**
   * MUTATE_MANUSCRIPT_STRUCTURE for this book. Scene metadata and deletion are structure mutations,
   * so without it the header and the metadata form are read-only.
   */
  canMutateStructure: boolean;
  isFocusMode?: boolean;
  isFullscreenAvailable?: boolean;
  isFullscreenActive?: boolean;
  onEnterFocusMode?: () => void;
  onExitFocusMode?: () => void;
  onToggleFullscreen?: () => void;
  onSceneDeleted: () => void;
  planningPanelOpenIntent?: PlanningPanelOpenIntent | null;
};

type SaveContentVariables = {
  targetSceneId: string;
  contentJson: string;
  contentText: string;
  source: SceneVersionSource;
  expectedContentRevision: number;
  operationId: string;
};

type RestoreVersionVariables = {
  targetSceneId: string;
  versionId: string;
  expectedContentRevision: number;
  operationId: string;
};

type PersistedContentSnapshot = {
  sceneId: string;
  contentJson: string;
  contentText: string;
  contentRevision: number;
  wordCount: number;
  updatedAt: string;
  sourceScene: Scene;
};

export type PlanningPanelOpenIntent = {
  sceneId: string;
  requestId: number;
};

export function SceneEditor({
  bookId,
  sceneId,
  canMutateStructure,
  isFocusMode = false,
  isFullscreenAvailable = false,
  isFullscreenActive = false,
  onEnterFocusMode,
  onExitFocusMode,
  onToggleFullscreen,
  onSceneDeleted,
  planningPanelOpenIntent = null,
}: SceneEditorProps) {
  const queryClient = useQueryClient();
  const activeSceneIdRef = useRef<string | null>(sceneId);
  const loadedSceneIdRef = useRef<string | null>(null);
  const autosaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const autosaveGenerationRef = useRef(0);
  const contentSavePendingRef = useRef(false);
  const contentSavePromiseRef = useRef<Promise<Scene | null> | null>(null);
  const currentContentJsonRef = useRef("");
  const currentContentTextRef = useRef("");
  const lastSavedContentJsonRef = useRef("");
  const lastSavedContentTextRef = useRef("");
  const contentRevisionRef = useRef(0);
  const pendingRemoteContentRef = useRef<PersistedContentSnapshot | null>(null);
  const consumedPlanningOpenRequestIdRef = useRef<number | null>(null);
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [status, setStatus] = useState<SceneStatus>("IDEA");
  const [contentJson, setContentJson] = useState("");
  const [contentText, setContentText] = useState("");
  const [lastSavedContentJson, setLastSavedContentJson] = useState("");
  const [lastSavedContentText, setLastSavedContentText] = useState("");
  const [acceptedContentRevision, setAcceptedContentRevision] = useState<number | null>(null);
  const [pendingRemoteContentRevision, setPendingRemoteContentRevision] = useState<number | null>(null);
  const [loadedSceneId, setLoadedSceneId] = useState<string | null>(null);
  const [isPlanningPanelOpen, setIsPlanningPanelOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [editorContentVersion, setEditorContentVersion] = useState(0);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const [restoreWorkflowPending, setRestoreWorkflowPending] = useState(false);

  const sceneQuery = useQuery({
    queryKey: sceneId ? queryKeys.scene(sceneId) : ["scenes", "empty"],
    queryFn: () => getScene(sceneId as string),
    enabled: Boolean(sceneId),
  });

  /**
   * The effective authority over this scene's canonical text, as the backend resolved it with the same
   * rule its save applies. Deriving it here from book-scoped eligibility would duplicate the server's
   * resource-scoped decision in the browser and offer an editor for a save that is always refused.
   *
   * Only a currently successful projection answers: a failed refetch keeps the previous `data` in the
   * cache, and that stale grant is no longer something the server has confirmed. Read-only until the
   * loaded scene is this scene: a control that turns out to be unauthorized is worse than one that
   * appears a moment late, and the server authorizes every request again anyway.
   */
  const projectedScene = sceneQuery.isSuccess ? sceneQuery.data : undefined;
  const canEditContent = projectedScene?.id === sceneId && projectedScene.canEditContent === true;

  /**
   * Content, revision and authority do not share a clock, so every writer of this cache applies the
   * same rule: a mutation response reconciles the text it just wrote and may narrow the authority,
   * never widen one a newer projection has withdrawn.
   */
  const applyMutationResponse = useCallback(
    (savedScene: Scene) => applySceneMutationResponse(queryClient, savedScene),
    [queryClient]
  );

  const metadataMutation = useMutation({
    mutationFn: () =>
      updateScene(sceneId as string, {
        title: title.trim(),
        summary: summary.trim(),
        status,
      }),
    onSuccess: (scene) => {
      applyMutationResponse(scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });
  const resetMetadataMutation = metadataMutation.reset;

  const contentMutation = useMutation({
    mutationFn: ({ targetSceneId, contentJson, contentText, source, expectedContentRevision, operationId }: SaveContentVariables) =>
      updateSceneContent(targetSceneId, {
        contentText,
        contentJson,
        source,
        expectedContentRevision,
        operationId,
      }),
  });
  const resetContentMutation = contentMutation.reset;

  const restoreMutation = useMutation({
    mutationFn: ({ targetSceneId, versionId, expectedContentRevision, operationId }: RestoreVersionVariables) =>
      restoreSceneVersion(targetSceneId, versionId, {
        expectedContentRevision,
        operationId,
      }),
  });
  const resetRestoreMutation = restoreMutation.reset;

  const deleteMutation = useMutation({
    mutationFn: () => deleteScene(sceneId as string),
    onSuccess: () => {
      if (sceneId) {
        queryClient.removeQueries({ queryKey: queryKeys.scene(sceneId) });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
      onSceneDeleted();
    },
  });

  const clearPendingAutosave = useCallback(() => {
    if (autosaveTimerRef.current) {
      clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = null;
    }
  }, []);

  const cancelQueuedAutosaves = useCallback(() => {
    autosaveGenerationRef.current += 1;
    clearPendingAutosave();
  }, [clearPendingAutosave]);

  /**
   * The authority as currently projected, read from the same query the surface reads. A save dispatched
   * from a timer must ask again here instead of trusting the closure that scheduled it or the last
   * render: the withdrawal can land after the timer was armed and before React re-renders.
   */
  const hasCurrentContentAuthority = useCallback(
    (targetSceneId: string) => {
      const queryState = queryClient.getQueryState<Scene>(queryKeys.scene(targetSceneId));
      return queryState?.status === "success" && queryState.data?.id === targetSceneId && queryState.data.canEditContent === true;
    },
    [queryClient]
  );

  const hasPendingNewerRemoteContent = useCallback((targetSceneId: string) => {
    const pendingSnapshot = pendingRemoteContentRef.current;
    return Boolean(
      pendingSnapshot &&
      pendingSnapshot.sceneId === targetSceneId &&
      pendingSnapshot.contentRevision > contentRevisionRef.current
    );
  }, []);

  const acceptContentRevision = useCallback((contentRevision: number) => {
    if (loadedSceneIdRef.current === activeSceneIdRef.current && contentRevision < contentRevisionRef.current) {
      return false;
    }

    contentRevisionRef.current = contentRevision;
    setAcceptedContentRevision(contentRevision);
    return true;
  }, []);

  const clearPendingRemoteContent = useCallback(() => {
    pendingRemoteContentRef.current = null;
    setPendingRemoteContentRevision(null);
  }, []);

  const reconcilePendingRemoteContent = useCallback(() => {
    const pendingSnapshot = pendingRemoteContentRef.current;
    if (!pendingSnapshot || pendingSnapshot.sceneId !== activeSceneIdRef.current) {
      return false;
    }

    if (pendingSnapshot.contentRevision <= contentRevisionRef.current) {
      clearPendingRemoteContent();
      return false;
    }

    if (contentSavePendingRef.current || contentSavePromiseRef.current !== null) {
      setPendingRemoteContentRevision(pendingSnapshot.contentRevision);
      return false;
    }

    const pendingMatchesVisibleContent =
      pendingSnapshot.contentJson === currentContentJsonRef.current &&
      pendingSnapshot.contentText === currentContentTextRef.current;
    const localContentIsClean =
      currentContentJsonRef.current === lastSavedContentJsonRef.current &&
      currentContentTextRef.current === lastSavedContentTextRef.current;

    if (!pendingMatchesVisibleContent && !localContentIsClean) {
      setPendingRemoteContentRevision(pendingSnapshot.contentRevision);
      return false;
    }

    cancelQueuedAutosaves();
    if (pendingMatchesVisibleContent) {
      lastSavedContentJsonRef.current = pendingSnapshot.contentJson;
      lastSavedContentTextRef.current = pendingSnapshot.contentText;
      setLastSavedContentJson(pendingSnapshot.contentJson);
      setLastSavedContentText(pendingSnapshot.contentText);
    } else {
      currentContentJsonRef.current = pendingSnapshot.contentJson;
      currentContentTextRef.current = pendingSnapshot.contentText;
      lastSavedContentJsonRef.current = pendingSnapshot.contentJson;
      lastSavedContentTextRef.current = pendingSnapshot.contentText;
      setContentJson(pendingSnapshot.contentJson);
      setContentText(pendingSnapshot.contentText);
      setLastSavedContentJson(pendingSnapshot.contentJson);
      setLastSavedContentText(pendingSnapshot.contentText);
      setEditorContentVersion((version) => version + 1);
    }

    acceptContentRevision(pendingSnapshot.contentRevision);
    // Reconciling text is not a permission refresh, so this write obeys the same rule every other
    // writer of the shared cache does: while the projection is not currently successful it is
    // refused, instead of returning the query to success on a grant nothing has confirmed since.
    // The revision the surface accepted above is kept either way, so refusing the write costs the
    // ordering nothing and the next successful fetch carries the same text back.
    updateSceneProjection(queryClient, pendingSnapshot.sceneId, (cachedScene) => {
      const sceneToUpdate = cachedScene ?? pendingSnapshot.sourceScene;
      if (sceneToUpdate.id !== pendingSnapshot.sceneId || sceneToUpdate.contentRevision > pendingSnapshot.contentRevision) {
        return sceneToUpdate;
      }

      return {
        ...sceneToUpdate,
        contentJson: pendingSnapshot.contentJson,
        contentText: pendingSnapshot.contentText,
        contentRevision: pendingSnapshot.contentRevision,
        wordCount: pendingSnapshot.wordCount,
        updatedAt: pendingSnapshot.updatedAt,
      };
    });
    resetContentMutation();
    clearPendingRemoteContent();
    return true;
  }, [acceptContentRevision, cancelQueuedAutosaves, clearPendingRemoteContent, queryClient, resetContentMutation]);

  useEffect(() => {
    contentSavePendingRef.current = contentMutation.isPending;
    if (!contentMutation.isPending) {
      reconcilePendingRemoteContent();
    }
  }, [contentMutation.isPending, reconcilePendingRemoteContent]);

  useEffect(() => {
    const storedValue = window.localStorage.getItem(PLANNING_PANEL_STORAGE_KEY);
    if (storedValue === "true") {
      setIsPlanningPanelOpen(true);
    }
    if (storedValue === "false") {
      setIsPlanningPanelOpen(false);
    }
  }, []);

  useEffect(() => {
    if (!sceneId || !planningPanelOpenIntent) {
      return;
    }

    if (planningPanelOpenIntent.sceneId !== sceneId) {
      return;
    }

    if (consumedPlanningOpenRequestIdRef.current === planningPanelOpenIntent.requestId) {
      return;
    }

    consumedPlanningOpenRequestIdRef.current = planningPanelOpenIntent.requestId;
    setIsPlanningPanelOpen(true);
  }, [planningPanelOpenIntent, sceneId]);

  useEffect(() => clearPendingAutosave, [clearPendingAutosave]);

  /**
   * Losing the projected authority invalidates whatever was scheduled under it. A timer armed while
   * the user could still write would otherwise outlive the permission and dispatch the save anyway.
   */
  useEffect(() => {
    if (!canEditContent) {
      cancelQueuedAutosaves();
    }
  }, [canEditContent, cancelQueuedAutosaves]);

  useEffect(() => {
    cancelQueuedAutosaves();
    activeSceneIdRef.current = sceneId;
    loadedSceneIdRef.current = null;
    contentSavePromiseRef.current = null;
    currentContentJsonRef.current = "";
    currentContentTextRef.current = "";
    lastSavedContentJsonRef.current = "";
    lastSavedContentTextRef.current = "";
    contentRevisionRef.current = 0;
    pendingRemoteContentRef.current = null;
    resetMetadataMutation();
    resetContentMutation();
    resetRestoreMutation();
    setRestoreError(null);
    setRestoreWorkflowPending(false);
    setTitle("");
    setSummary("");
    setStatus("IDEA");
    setContentJson("");
    setContentText("");
    setLastSavedContentJson("");
    setLastSavedContentText("");
    setAcceptedContentRevision(null);
    setPendingRemoteContentRevision(null);
    setLoadedSceneId(null);
    setIsHistoryOpen(false);
    setEditorContentVersion((version) => version + 1);
  }, [cancelQueuedAutosaves, resetContentMutation, resetMetadataMutation, resetRestoreMutation, sceneId]);

  useEffect(() => {
    const queriedScene = sceneQuery.data;
    if (!queriedScene || queriedScene.id !== sceneId) {
      return;
    }

    setTitle(queriedScene.title);
    setSummary(queriedScene.summary ?? "");
    setStatus(queriedScene.status);

    const incomingSnapshot: PersistedContentSnapshot = {
      sceneId: queriedScene.id,
      contentJson: queriedScene.contentJson ?? "",
      contentText: queriedScene.contentText ?? "",
      contentRevision: queriedScene.contentRevision,
      wordCount: queriedScene.wordCount,
      updatedAt: queriedScene.updatedAt,
      sourceScene: queriedScene,
    };
    const isInitialContentHydration = loadedSceneIdRef.current !== queriedScene.id;

    if (!isInitialContentHydration) {
      if (incomingSnapshot.contentRevision <= contentRevisionRef.current) {
        return;
      }

      const highestObservedSnapshot = pendingRemoteContentRef.current;
      const snapshotToTrack =
        !highestObservedSnapshot || incomingSnapshot.contentRevision > highestObservedSnapshot.contentRevision
          ? incomingSnapshot
          : highestObservedSnapshot;
      pendingRemoteContentRef.current = snapshotToTrack;
      setPendingRemoteContentRevision(snapshotToTrack.contentRevision);
      cancelQueuedAutosaves();
      reconcilePendingRemoteContent();
      return;
    }

    clearPendingRemoteContent();
    setContentJson(incomingSnapshot.contentJson);
    setContentText(incomingSnapshot.contentText);
    setLastSavedContentJson(incomingSnapshot.contentJson);
    setLastSavedContentText(incomingSnapshot.contentText);
    currentContentJsonRef.current = incomingSnapshot.contentJson;
    currentContentTextRef.current = incomingSnapshot.contentText;
    lastSavedContentJsonRef.current = incomingSnapshot.contentJson;
    lastSavedContentTextRef.current = incomingSnapshot.contentText;
    acceptContentRevision(incomingSnapshot.contentRevision);
    loadedSceneIdRef.current = queriedScene.id;
    setLoadedSceneId(queriedScene.id);
  }, [acceptContentRevision, cancelQueuedAutosaves, clearPendingRemoteContent, reconcilePendingRemoteContent, sceneId, sceneQuery.data]);

  function handleMetadataSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sceneId || !canMutateStructure || !title.trim()) {
      return;
    }

    metadataMutation.mutate();
  }

  async function saveSceneContent(
    targetSceneId: string,
    nextContentJson: string,
    nextContentText: string,
    source: SceneVersionSource
  ): Promise<Scene | null> {
    if (!targetSceneId || targetSceneId !== activeSceneIdRef.current) {
      return null;
    }

    const savePromise = contentMutation.mutateAsync({
        targetSceneId,
        contentJson: nextContentJson,
        contentText: nextContentText,
        source,
        expectedContentRevision: contentRevisionRef.current,
        operationId: crypto.randomUUID(),
      });
    contentSavePromiseRef.current = savePromise;

    try {
      const savedScene = await savePromise;
      trackEvent({ name: "scene_saved", data: { source: source === "AUTO_SAVE" ? "AUTO_SAVE" : "MANUAL_SAVE" } });
      applyMutationResponse(savedScene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });

      if (activeSceneIdRef.current !== targetSceneId || savedScene.id !== targetSceneId) {
        return savedScene;
      }

      const savedContentJson = savedScene.contentJson ?? "";
      const savedContentText = savedScene.contentText ?? "";
      if (!acceptContentRevision(savedScene.contentRevision)) {
        return savedScene;
      }
      const currentContentMatchesSavedRequest =
        currentContentJsonRef.current === nextContentJson && currentContentTextRef.current === nextContentText;

      if (currentContentMatchesSavedRequest) {
        currentContentJsonRef.current = savedContentJson;
        currentContentTextRef.current = savedContentText;
        setContentJson(savedContentJson);
        setContentText(savedContentText);
      }

      lastSavedContentJsonRef.current = savedContentJson;
      lastSavedContentTextRef.current = savedContentText;
      setLastSavedContentJson(savedContentJson);
      setLastSavedContentText(savedContentText);
      return savedScene;
    } catch {
      return null;
      // A mutation mantém o estado de erro para a UI; o conteúdo local fica preservado.
    } finally {
      if (contentSavePromiseRef.current === savePromise) {
        contentSavePromiseRef.current = null;
      }
      reconcilePendingRemoteContent();
    }
  }

  function handleSaveContent(targetSceneId: string) {
    if (!canEditContent || !hasCurrentContentAuthority(targetSceneId)) {
      return;
    }

    cancelQueuedAutosaves();
    void saveSceneContent(targetSceneId, currentContentJsonRef.current, currentContentTextRef.current, "MANUAL_SAVE");
  }

  function scheduleAutosave(targetSceneId: string, nextContentJson: string, nextContentText: string) {
    if (
      !canEditContent ||
      !targetSceneId ||
      targetSceneId !== activeSceneIdRef.current ||
      loadedSceneIdRef.current !== targetSceneId ||
      hasPendingNewerRemoteContent(targetSceneId)
    ) {
      return;
    }

    clearPendingAutosave();

    if (nextContentJson === lastSavedContentJsonRef.current && nextContentText === lastSavedContentTextRef.current) {
      return;
    }

    const scheduledGeneration = autosaveGenerationRef.current;
    autosaveTimerRef.current = setTimeout(() => {
      autosaveTimerRef.current = null;

      if (scheduledGeneration !== autosaveGenerationRef.current) {
        return;
      }

      // The authority is proved again here, not read from the closure that scheduled the save.
      if (!hasCurrentContentAuthority(targetSceneId)) {
        return;
      }

      if (targetSceneId !== activeSceneIdRef.current || loadedSceneIdRef.current !== targetSceneId) {
        return;
      }

      if (hasPendingNewerRemoteContent(targetSceneId)) {
        return;
      }

      if (nextContentJson === lastSavedContentJsonRef.current && nextContentText === lastSavedContentTextRef.current) {
        return;
      }

      if (contentSavePendingRef.current) {
        scheduleAutosave(targetSceneId, nextContentJson, nextContentText);
        return;
      }

      void saveSceneContent(targetSceneId, nextContentJson, nextContentText, "AUTO_SAVE");
    }, CONTENT_AUTOSAVE_DELAY_MS);
  }

  async function awaitInFlightContentSave() {
    const savePromise = contentSavePromiseRef.current;
    if (!savePromise) {
      return true;
    }

    const savedScene = await savePromise;
    return savedScene !== null;
  }

  async function handleRestoreVersion(versionId: string, mode: SceneVersionRestoreMode) {
    const targetSceneId = activeSceneIdRef.current;
    if (!targetSceneId) {
      return;
    }

    setRestoreError(null);

    if (mode === "DISCARD_AND_RESTORE" && contentSavePromiseRef.current) {
      setRestoreError("Ha um salvamento em andamento. Aguarde a conclusao antes de descartar alteracoes locais.");
      return;
    }

    setRestoreWorkflowPending(true);
    cancelQueuedAutosaves();

    try {
      const inFlightSaveSucceeded = await awaitInFlightContentSave();
      if (!inFlightSaveSucceeded || activeSceneIdRef.current !== targetSceneId) {
        setRestoreError("Nao foi possivel confirmar o conteudo atual. Recarregue a cena e tente novamente.");
        return;
      }

      let expectedContentRevision = contentRevisionRef.current;
      if (mode === "SAVE_AND_RESTORE") {
        const savedScene = await saveSceneContent(
          targetSceneId,
          currentContentJsonRef.current,
          currentContentTextRef.current,
          "MANUAL_SAVE"
        );
        if (!savedScene || activeSceneIdRef.current !== targetSceneId) {
          setRestoreError("Nao foi possivel salvar as alteracoes locais antes de restaurar.");
          return;
        }
        expectedContentRevision = savedScene.contentRevision;
      }

      const restoredScene = await restoreMutation.mutateAsync({
        targetSceneId,
        versionId,
        expectedContentRevision,
        operationId: crypto.randomUUID(),
      });
      handleVersionRestored(restoredScene);
    } catch {
      setRestoreError("Nao foi possivel restaurar. Recarregue a cena e tente novamente.");
    } finally {
      setRestoreWorkflowPending(false);
    }
  }

  function handleVersionRestored(restoredScene: Scene) {
    if (restoredScene.id !== activeSceneIdRef.current) {
      return;
    }

    const restoredContentJson = restoredScene.contentJson ?? "";
    const restoredContentText = restoredScene.contentText ?? "";
    if (!acceptContentRevision(restoredScene.contentRevision)) {
      return;
    }
    clearPendingRemoteContent();
    currentContentJsonRef.current = restoredContentJson;
    currentContentTextRef.current = restoredContentText;
    lastSavedContentJsonRef.current = restoredContentJson;
    lastSavedContentTextRef.current = restoredContentText;
    setContentJson(restoredContentJson);
    setContentText(restoredContentText);
    setLastSavedContentJson(restoredContentJson);
    setLastSavedContentText(restoredContentText);
    setEditorContentVersion((version) => version + 1);
    setIsHistoryOpen(false);
    setRestoreError(null);
    applyMutationResponse(restoredScene);
    void queryClient.invalidateQueries({ queryKey: queryKeys.sceneVersions(restoredScene.id) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.bookDashboard(bookId) });
  }

  function handleDeleteScene(sceneTitle: string) {
    if (!sceneId || !canMutateStructure) {
      return;
    }

    const confirmed = window.confirm(`Excluir a cena "${sceneTitle}"? Esta ação não pode ser desfeita nesta etapa.`);
    if (confirmed) {
      deleteMutation.mutate();
    }
  }

  function handlePlanningPanelOpenChange(isOpen: boolean) {
    setIsPlanningPanelOpen(isOpen);
    window.localStorage.setItem(PLANNING_PANEL_STORAGE_KEY, String(isOpen));
  }

  if (!sceneId) {
    return (
      <SceneEmptyState
        title="Selecione uma cena"
        description="Selecione uma cena no esboço ou crie uma nova cena para começar a escrever."
      />
    );
  }

  if (sceneQuery.isLoading) {
    return (
      <section className="p-6">
        <LoadingState label="Carregando cena..." />
      </section>
    );
  }

  if (sceneQuery.isError) {
    return (
      <section className="p-6">
        <ErrorState message="Não foi possível carregar a cena. Verifique se o backend está rodando e tente novamente." />
      </section>
    );
  }

  const scene = sceneQuery.data?.id === sceneId ? sceneQuery.data : null;

  if (!scene) {
    return (
      <section className="p-6">
        <LoadingState label="Carregando cena..." />
      </section>
    );
  }

  const activeContentMutationSceneId = contentMutation.variables?.targetSceneId;
  const hasUnsavedContent = contentJson !== lastSavedContentJson || contentText !== lastSavedContentText;
  const hasAcceptedSavedContent = lastSavedContentText.trim().length > 0;
  const contentSyncState: SceneContentSyncState =
    loadedSceneId !== scene.id || acceptedContentRevision === null
      ? "loading"
      : contentMutation.isPending && activeContentMutationSceneId === scene.id
        ? "saving"
        : contentMutation.isError && activeContentMutationSceneId === scene.id
          ? "error"
          : pendingRemoteContentRevision !== null
            ? "outdated"
            : hasUnsavedContent
              ? "dirty"
              : hasAcceptedSavedContent
                ? "saved"
                : "empty";

  if (contentSyncState === "loading" || acceptedContentRevision === null) {
    return (
      <section className="p-6">
        <LoadingState label="Preparando editor..." />
      </section>
    );
  }

  const contentSaveStatus: ContentSaveStatus =
    contentSyncState === "dirty" || contentSyncState === "outdated"
      ? "editing"
      : contentSyncState === "empty"
        ? "saved"
        : contentSyncState;

  return (
    <section className={`h-full overflow-y-auto bg-zinc-100/70 ${isFocusMode ? "p-2 md:p-4 lg:p-6" : "p-4 md:p-6 lg:p-8"}`}>
      <Card
        className={`mx-auto grid min-h-full overflow-hidden border-zinc-200 bg-white ${
          isFocusMode
            ? "max-w-6xl grid-rows-[auto_auto_minmax(0,1fr)] shadow-sm shadow-zinc-200/70"
            : "max-w-7xl grid-rows-[auto_auto_auto_auto_minmax(0,1fr)] shadow-xl shadow-zinc-200/70"
        }`}
      >
        <SceneEditorHeader
          scene={scene}
          metadataFormId={METADATA_FORM_ID}
          title={title}
          canMutateStructure={canMutateStructure}
          canEditContent={canEditContent}
          contentSaveStatus={contentSaveStatus}
          metadataPending={metadataMutation.isPending}
          contentPending={contentMutation.isPending}
          deletePending={deleteMutation.isPending}
          deleteError={deleteMutation.isError}
          isFocusMode={isFocusMode}
          isFullscreenAvailable={isFullscreenAvailable}
          isFullscreenActive={isFullscreenActive}
          onEnterFocusMode={onEnterFocusMode}
          onExitFocusMode={onExitFocusMode}
          onToggleFullscreen={onToggleFullscreen}
          onSaveContent={() => handleSaveContent(scene.id)}
          onOpenHistory={() => {
            setRestoreError(null);
            setIsHistoryOpen(true);
          }}
          onDeleteScene={handleDeleteScene}
        />

        <SceneMetadataForm
          formId={METADATA_FORM_ID}
          readOnly={!canMutateStructure}
          title={title}
          summary={summary}
          status={status}
          statuses={SCENE_STATUSES}
          isSuccess={metadataMutation.isSuccess}
          isError={metadataMutation.isError}
          onSubmit={handleMetadataSubmit}
          onTitleChange={(nextTitle) => {
            metadataMutation.reset();
            setTitle(nextTitle);
          }}
          onSummaryChange={(nextSummary) => {
            metadataMutation.reset();
            setSummary(nextSummary);
          }}
          onStatusChange={(nextStatus) => {
            metadataMutation.reset();
            setStatus(nextStatus);
          }}
        />

        <section className={`border-b border-zinc-200 bg-white px-4 py-3 md:px-7 ${isFocusMode ? "hidden" : ""}`}>
          <div className="rounded-lg border border-zinc-200 bg-zinc-50/70">
            <div className="flex flex-wrap items-center gap-3 px-3 py-3">
              <button
                type="button"
                aria-expanded={isPlanningPanelOpen}
                aria-label={isPlanningPanelOpen ? "Recolher planejamento da cena" : "Expandir planejamento da cena"}
                onClick={() => handlePlanningPanelOpenChange(!isPlanningPanelOpen)}
                className="flex min-w-0 flex-1 items-center gap-3 rounded-md text-left transition hover:text-zinc-950 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
              >
                <span
                  aria-hidden="true"
                  className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-zinc-200 bg-white text-zinc-600 transition ${
                    isPlanningPanelOpen ? "rotate-90" : ""
                  }`}
                >
                  <span className="h-2.5 w-2.5 rotate-45 border-r-2 border-t-2 border-current" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-zinc-950">Planejamento da cena</span>
                  <span className="mt-0.5 block truncate text-xs text-zinc-500">{getPlanningSummary(scene)}</span>
                </span>
              </button>

              {isPlanningPanelOpen ? (
                <Button type="submit" form={PLANNING_FORM_ID} size="sm">
                  Salvar planejamento
                </Button>
              ) : null}
            </div>

            {isPlanningPanelOpen ? (
              <div className="border-t border-zinc-200 bg-zinc-50/60">
                <ScenePlanningPanel formId={PLANNING_FORM_ID} bookId={bookId} scene={scene} />
              </div>
            ) : null}
          </div>
        </section>

        <div className={isFocusMode ? "hidden" : ""}>
          <SceneAiAnalysisPanel
            sceneId={scene.id}
            contentRevision={acceptedContentRevision}
            contentSyncState={contentSyncState}
          />
        </div>

        <div className="min-h-0 bg-white lg:h-full">
          <SceneContentEditor
            contentKey={`${scene.id}:${editorContentVersion}`}
            sourceSceneId={scene.id}
            contentJson={contentJson}
            contentText={contentText}
            readOnly={!canEditContent}
            wordCount={scene.wordCount}
            isSuccess={contentMutation.isSuccess}
            isError={contentMutation.isError}
            saveStatus={contentSaveStatus}
            isFocusMode={isFocusMode}
            onContentChange={(sourceSceneId, nextContentJson, nextContentText) => {
              if (sourceSceneId !== activeSceneIdRef.current) {
                return;
              }

              if (!contentMutation.isPending) {
                contentMutation.reset();
              }
              currentContentJsonRef.current = nextContentJson;
              currentContentTextRef.current = nextContentText;
              setContentJson(nextContentJson);
              setContentText(nextContentText);
              scheduleAutosave(sourceSceneId, nextContentJson, nextContentText);
              reconcilePendingRemoteContent();
            }}
          />
        </div>
      </Card>
      {isHistoryOpen ? (
        <SceneVersionHistoryPanel
          sceneId={scene.id}
          hasUnsavedContent={hasUnsavedContent}
          contentSaveInFlight={contentMutation.isPending && activeContentMutationSceneId === scene.id}
          restoreDisabled={restoreWorkflowPending}
          restorePending={restoreWorkflowPending}
          restoreError={restoreError}
          onClose={() => setIsHistoryOpen(false)}
          onRestoreVersion={handleRestoreVersion}
        />
      ) : null}
    </section>
  );
}

function getPlanningSummary(scene: Scene) {
  const summaryParts = [
    scene.povCharacter ? `POV: ${scene.povCharacter.name}` : null,
    scene.mainLocation ? `Local: ${scene.mainLocation.name}` : null,
    scene.participantCharacters.length > 0
      ? `${scene.participantCharacters.length} ${scene.participantCharacters.length === 1 ? "participante" : "participantes"}`
      : null,
    scene.items.length > 0 ? `${scene.items.length} ${scene.items.length === 1 ? "item" : "itens"}` : null,
  ].filter(Boolean);

  return summaryParts.length > 0 ? summaryParts.join(" · ") : "Nenhum planejamento preenchido.";
}
