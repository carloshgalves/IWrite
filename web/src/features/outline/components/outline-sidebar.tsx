"use client";

import { type FormEvent, useEffect, useState } from "react";
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  type DragEndEvent,
  type DragStartEvent,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { LoadingState } from "@/components/ui/feedback";
import {
  createChapter,
  createScene,
  createSection,
  deleteChapter,
  deleteScene,
  deleteSection,
  getOutline,
  updateChapter,
  updateSection,
} from "@/features/outline/api/outline-api";
import {
  useReorderChaptersMutation,
  useReorderScenesMutation,
  useReorderSectionsMutation,
} from "@/features/outline/api/outline-reorder-mutations";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { SectionDragPreview } from "@/features/outline/components/outline-drag-overlay";
import { SectionItem } from "@/features/outline/components/section-item";
import type { OutlineChapter, OutlineSection, SectionType } from "@/features/outline/types";
import { getReorderedIds } from "@/features/outline/utils/reorder";
import { queryKeys } from "@/lib/query/keys";

type OutlineSidebarProps = {
  bookId: string;
  selectedSceneId: string | null;
  /**
   * Whether the backend granted MUTATE_MANUSCRIPT_STRUCTURE for this book. Without it the outline is
   * a reading surface: creating, renaming, reordering and deleting are not offered at all. Hiding
   * them is presentation only — every one of those requests is authorized again on the server.
   */
  canMutateStructure: boolean;
  /** The effective access could not be loaded, so the missing controls are unknown, not denied. */
  capabilitiesUnavailable?: boolean;
  onSelectScene: (sceneId: string | null) => void;
};

const sectionTypes: SectionType[] = ["PART", "PROLOGUE", "INTERLUDE", "EPILOGUE", "OTHER"];

export function OutlineSidebar({
  bookId,
  selectedSceneId,
  canMutateStructure,
  capabilitiesUnavailable = false,
  onSelectScene,
}: OutlineSidebarProps) {
  const queryClient = useQueryClient();
  const [successMessage, setSuccessMessage] = useState("");
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [sectionTitle, setSectionTitle] = useState("");
  const [sectionType, setSectionType] = useState<SectionType>("PART");
  const [editingChapterId, setEditingChapterId] = useState<string | null>(null);
  const [chapterTitle, setChapterTitle] = useState("");
  const [chapterSummary, setChapterSummary] = useState("");
  const [collapsedSectionIds, setCollapsedSectionIds] = useState<Set<string>>(() => new Set());
  const [collapsedChapterIds, setCollapsedChapterIds] = useState<Set<string>>(() => new Set());
  const [activeSectionId, setActiveSectionId] = useState<string | null>(null);

  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });
  const reorderSectionsMutation = useReorderSectionsMutation(bookId);
  const reorderChaptersMutation = useReorderChaptersMutation(bookId);
  const reorderScenesMutation = useReorderScenesMutation(bookId);
  const sectionSensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  // Losing the capability has to end the mutable state opened under it. An inline edit or an active
  // drag left behind is a control the workspace already decided not to offer, still holding a Salvar
  // that would dispatch a request the server now refuses.
  useEffect(() => {
    if (canMutateStructure) {
      return;
    }

    setEditingSectionId(null);
    setEditingChapterId(null);
    setActiveSectionId(null);
  }, [canMutateStructure]);

  useEffect(() => {
    const outline = outlineQuery.data;
    if (!outline || !selectedSceneId) {
      return;
    }

    const selectedSection = outline.sections.find((section) =>
      section.chapters.some((chapter) => chapter.scenes.some((scene) => scene.id === selectedSceneId))
    );
    const selectedChapter = selectedSection?.chapters.find((chapter) =>
      chapter.scenes.some((scene) => scene.id === selectedSceneId)
    );

    if (selectedSection) {
      setCollapsedSectionIds((current) => {
        const next = new Set(current);
        next.delete(selectedSection.id);
        return next;
      });
    }
    if (selectedChapter) {
      setCollapsedChapterIds((current) => {
        const next = new Set(current);
        next.delete(selectedChapter.id);
        return next;
      });
    }
  }, [outlineQuery.data, selectedSceneId]);

  function invalidateOutline() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
  }

  function toggleSection(sectionId: string) {
    setCollapsedSectionIds((current) => toggleSetId(current, sectionId));
  }

  function toggleChapter(chapterId: string) {
    setCollapsedChapterIds((current) => toggleSetId(current, chapterId));
  }

  const sectionMutation = useMutation({
    mutationFn: (title: string) => createSection(bookId, { title, type: "PART" }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção criada com sucesso.");
      invalidateOutline();
    },
  });

  const updateSectionMutation = useMutation({
    mutationFn: ({ sectionId, title, type }: { sectionId: string; title: string; type: SectionType }) =>
      updateSection(sectionId, { title, type }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção atualizada com sucesso.");
      setEditingSectionId(null);
      invalidateOutline();
    },
  });

  const deleteSectionMutation = useMutation({
    mutationFn: (sectionId: string) => deleteSection(sectionId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção excluída com sucesso.");
      invalidateOutline();
    },
  });

  const chapterMutation = useMutation({
    mutationFn: ({ sectionId, title }: { sectionId: string; title: string }) => createChapter(sectionId, { title }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo criado com sucesso.");
      invalidateOutline();
    },
  });

  const updateChapterMutation = useMutation({
    mutationFn: ({ chapterId, title, summary }: { chapterId: string; title: string; summary: string }) =>
      updateChapter(chapterId, { title, summary }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo atualizado com sucesso.");
      setEditingChapterId(null);
      invalidateOutline();
    },
  });

  const deleteChapterMutation = useMutation({
    mutationFn: (chapterId: string) => deleteChapter(chapterId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo excluído com sucesso.");
      invalidateOutline();
    },
  });

  const sceneMutation = useMutation({
    mutationFn: ({ chapterId, title }: { chapterId: string; title: string }) =>
      createScene(chapterId, { title, status: "IDEA", contentText: "", contentJson: "" }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: (scene) => {
      setSuccessMessage("Cena criada com sucesso.");
      invalidateOutline();
      onSelectScene(scene.id);
    },
  });

  const deleteSceneMutation = useMutation({
    mutationFn: (sceneId: string) => deleteScene(sceneId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: (_data, sceneId) => {
      setSuccessMessage("Cena excluída com sucesso.");
      if (selectedSceneId === sceneId) {
        onSelectScene(null);
        queryClient.removeQueries({ queryKey: queryKeys.scene(sceneId) });
      }
      invalidateOutline();
    },
  });

  function handleSectionDragEnd(event: DragEndEvent) {
    setActiveSectionId(null);

    const outline = outlineQuery.data;
    if (!outline) {
      return;
    }

    const orderedIds = getReorderedIds(outline.sections, String(event.active.id), event.over ? String(event.over.id) : null);
    if (!orderedIds) {
      return;
    }

    handleReorderSections(orderedIds);
  }

  function handleSectionDragStart(event: DragStartEvent) {
    setActiveSectionId(String(event.active.id));
  }

  function handleSectionDragCancel() {
    setActiveSectionId(null);
  }

  function handleReorderSections(orderedIds: string[]) {
    // The last check before the request leaves: hiding and disabling controls is presentation, so a
    // dispatch reached by a stale control, a pending drag or a race must still refuse itself.
    if (!canMutateStructure) {
      return;
    }

    setSuccessMessage("");
    reorderSectionsMutation.mutate(
      { orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das seções atualizada."),
      }
    );
  }

  function handleReorderChapters(section: OutlineSection, orderedIds: string[]) {
    if (!canMutateStructure) {
      return;
    }

    setSuccessMessage("");
    reorderChaptersMutation.mutate(
      { sectionId: section.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem dos capítulos atualizada."),
      }
    );
  }

  function handleReorderScenes(chapter: OutlineChapter, orderedIds: string[]) {
    if (!canMutateStructure) {
      return;
    }

    setSuccessMessage("");
    reorderScenesMutation.mutate(
      { chapterId: chapter.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das cenas atualizada."),
      }
    );
  }

  function handleCreateSection(title: string) {
    if (!canMutateStructure) {
      return;
    }

    sectionMutation.mutate(title);
  }

  function handleCreateChapter(sectionId: string, title: string) {
    if (!canMutateStructure) {
      return;
    }

    chapterMutation.mutate({ sectionId, title });
  }

  function handleCreateScene(chapterId: string, title: string) {
    if (!canMutateStructure) {
      return;
    }

    sceneMutation.mutate({ chapterId, title });
  }

  function startEditingSection(section: OutlineSection) {
    setEditingSectionId(section.id);
    setSectionTitle(section.title);
    setSectionType(section.type);
  }

  function handleSectionSubmit(event: FormEvent<HTMLFormElement>, sectionId: string) {
    event.preventDefault();
    if (!canMutateStructure || !sectionTitle.trim()) {
      return;
    }
    updateSectionMutation.mutate({ sectionId, title: sectionTitle.trim(), type: sectionType });
  }

  function handleDeleteSection(section: OutlineSection) {
    if (!canMutateStructure) {
      return;
    }

    const confirmed = window.confirm(
      `Excluir a seção "${section.title}"? Esta ação pode remover capítulos e cenas desta seção.`
    );
    if (!confirmed) {
      return;
    }

    if (selectedSceneId && section.chapters.some((chapter) => chapter.scenes.some((scene) => scene.id === selectedSceneId))) {
      onSelectScene(null);
    }
    deleteSectionMutation.mutate(section.id);
  }

  function startEditingChapter(chapter: OutlineChapter) {
    setEditingChapterId(chapter.id);
    setChapterTitle(chapter.title);
    setChapterSummary(chapter.summary ?? "");
  }

  function handleChapterSubmit(event: FormEvent<HTMLFormElement>, chapterId: string) {
    event.preventDefault();
    if (!canMutateStructure || !chapterTitle.trim()) {
      return;
    }
    updateChapterMutation.mutate({ chapterId, title: chapterTitle.trim(), summary: chapterSummary.trim() });
  }

  function handleDeleteChapter(chapter: OutlineChapter) {
    if (!canMutateStructure) {
      return;
    }

    const confirmed = window.confirm(`Excluir o capítulo "${chapter.title}"? Esta ação pode remover cenas deste capítulo.`);
    if (!confirmed) {
      return;
    }

    if (selectedSceneId && chapter.scenes.some((scene) => scene.id === selectedSceneId)) {
      onSelectScene(null);
    }
    deleteChapterMutation.mutate(chapter.id);
  }

  function handleDeleteScene(sceneId: string, sceneTitle: string) {
    if (!canMutateStructure) {
      return;
    }

    const confirmed = window.confirm(`Excluir a cena "${sceneTitle}"? Esta ação não pode ser desfeita nesta etapa.`);
    if (confirmed) {
      deleteSceneMutation.mutate(sceneId);
    }
  }

  if (outlineQuery.isLoading) {
    return <LoadingState label="Carregando outline..." />;
  }

  if (outlineQuery.isError) {
    return (
      <aside className="flex h-full min-h-0 flex-col bg-white p-3">
        <EmptyState
          size="sm"
          title="Não foi possível carregar o outline"
          description="Verifique se o backend está rodando e tente abrir o livro novamente."
        />
        <FeedbackMessage variant="error" className="mt-3">
          Erro ao carregar outline.
        </FeedbackMessage>
      </aside>
    );
  }

  const outline = outlineQuery.data;

  if (!outline) {
    return (
      <aside className="flex h-full min-h-0 flex-col bg-white p-3">
        <EmptyState size="sm" title="Outline indisponível" description="Não recebemos dados deste livro." />
      </aside>
    );
  }

  const actionError =
    sectionMutation.isError ||
    updateSectionMutation.isError ||
    deleteSectionMutation.isError ||
    chapterMutation.isError ||
    updateChapterMutation.isError ||
    deleteChapterMutation.isError ||
    sceneMutation.isError ||
    deleteSceneMutation.isError ||
    reorderSectionsMutation.isError ||
    reorderChaptersMutation.isError ||
    reorderScenesMutation.isError;
  const activeSection = activeSectionId ? outline.sections.find((section) => section.id === activeSectionId) : null;

  return (
    <aside className="flex h-full min-h-0 flex-col bg-white">
      <div className="border-b border-zinc-200 bg-white px-4 py-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-medium uppercase text-zinc-500">Livro</p>
            <h1 className="mt-1 truncate text-lg font-semibold text-zinc-950">{outline.title}</h1>
          </div>
          <Badge className="shrink-0">{outline.wordCount} palavras</Badge>
        </div>
      </div>

      <div className="grid gap-2 border-b border-zinc-200 bg-white px-4 py-3">
        {canMutateStructure ? (
          <InlineCreateForm
            ariaLabel="Nova seção"
            placeholder="Nova seção"
            buttonLabel="Criar"
            disabled={sectionMutation.isPending}
            onCreate={handleCreateSection}
          />
        ) : capabilitiesUnavailable ? (
          <p className="text-xs text-zinc-500">Suas permissões deste livro não puderam ser carregadas.</p>
        ) : (
          <p className="text-xs text-zinc-500">Somente leitura: você não altera a estrutura deste livro.</p>
        )}
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto bg-zinc-50/70 px-3 py-4">
        {outline.sections.length === 0 ? (
          <EmptyState
            size="sm"
            title="Nenhuma seção ainda"
            description={
              canMutateStructure
                ? "Crie uma seção para começar a organizar o esboço do livro."
                : "Este livro ainda não tem seções para ler."
            }
          />
        ) : (
          <DndContext
            sensors={sectionSensors}
            collisionDetection={closestCenter}
            onDragStart={handleSectionDragStart}
            onDragEnd={handleSectionDragEnd}
            onDragCancel={handleSectionDragCancel}
          >
            <SortableContext items={outline.sections.map((section) => section.id)} strategy={verticalListSortingStrategy}>
              <div className="grid gap-4">
                {outline.sections.map((section) => (
                  <SectionItem
                    key={section.id}
                    section={section}
                    canMutateStructure={canMutateStructure}
                    isCollapsed={collapsedSectionIds.has(section.id)}
                    collapsedChapterIds={collapsedChapterIds}
                    sectionTypes={sectionTypes}
                    selectedSceneId={selectedSceneId}
                    editingSectionId={editingSectionId}
                    sectionTitle={sectionTitle}
                    sectionType={sectionType}
                    editingChapterId={editingChapterId}
                    chapterTitle={chapterTitle}
                    chapterSummary={chapterSummary}
                    updateSectionPending={updateSectionMutation.isPending}
                    deleteSectionPending={deleteSectionMutation.isPending}
                    createChapterPending={chapterMutation.isPending}
                    updateChapterPending={updateChapterMutation.isPending}
                    deleteChapterPending={deleteChapterMutation.isPending}
                    createScenePending={sceneMutation.isPending}
                    deleteScenePending={deleteSceneMutation.isPending}
                    reorderSectionPending={reorderSectionsMutation.isPending}
                    reorderChapterPending={reorderChaptersMutation.isPending}
                    reorderScenePending={reorderScenesMutation.isPending}
                    onSectionTitleChange={setSectionTitle}
                    onSectionTypeChange={setSectionType}
                    onStartEditSection={startEditingSection}
                    onCancelEditSection={() => setEditingSectionId(null)}
                    onSubmitSection={handleSectionSubmit}
                    onDeleteSection={handleDeleteSection}
                    onToggleSection={toggleSection}
                    onToggleChapter={toggleChapter}
                    onCreateChapter={handleCreateChapter}
                    onChapterTitleChange={setChapterTitle}
                    onChapterSummaryChange={setChapterSummary}
                    onStartEditChapter={startEditingChapter}
                    onCancelEditChapter={() => setEditingChapterId(null)}
                    onSubmitChapter={handleChapterSubmit}
                    onDeleteChapter={handleDeleteChapter}
                    onReorderChapters={handleReorderChapters}
                    onCreateScene={handleCreateScene}
                    onSelectScene={(sceneId) => onSelectScene(sceneId)}
                    onDeleteScene={handleDeleteScene}
                    onReorderScenes={handleReorderScenes}
                  />
                ))}
              </div>
            </SortableContext>
            <DragOverlay>{activeSection ? <SectionDragPreview section={activeSection} /> : null}</DragOverlay>
          </DndContext>
        )}
      </div>

      {actionError ? (
        <div className="border-t border-zinc-200 p-3">
          <FeedbackMessage variant="error">
            Não foi possível concluir a ação agora. Verifique a API e tente novamente.
          </FeedbackMessage>
        </div>
      ) : null}
    </aside>
  );
}

function toggleSetId(current: Set<string>, id: string) {
  const next = new Set(current);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  return next;
}
